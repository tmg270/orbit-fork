// SPDX-License-Identifier: MPL-2.0
// Fork patch. UNVERIFIED: the client id, endpoints and scope come from
// github.com/tmg270/orbit-supergrok-oauth and have not been confirmed against a live
// SuperGrok account or by xAI. Using another product's OAuth client may breach xAI terms.
package com.orbit.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SuperGrok device-code (RFC 8628) sign-in for the xAI provider. Tokens live only in
 * {@link SecureStore} (Android Keystore). Refresh is single-flight: callers serialize on a lock
 * and re-read the stored session first, so a rotated refresh token is never reused.
 */
final class SuperGrokAuth {
    static final String CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828";
    static final String DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code";
    static final String TOKEN_URL = "https://auth.x.ai/oauth2/token";
    /**
     * RFC 7009 revocation. Listed as "revocation_endpoint" in
     * https://auth.x.ai/.well-known/openid-configuration (fetched 2026-10-16).
     */
    static final String REVOCATION_URL = "https://auth.x.ai/oauth2/revoke";
    static final String SCOPE = "openid profile email offline_access grok-cli:access api:access";
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final long REFRESH_SKEW_MS = 120_000L;
    private static final int DEFAULT_EXPIRES_IN = 1800;

    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object REFRESH_LOCK = new Object();
    private static volatile long activeAttempt;
    private static final Object POLL_WAKE = new Object();
    private static final String TAG = "OrbitSuperGrok";

    private SuperGrokAuth() {}

    /** Whether a failed reply asked the user to sign in to xAI, and they still are not. */
    static boolean needsSignIn(Context context, String failure) {
        if (failure == null || SecureStore.hasXaiCredential(context)) return false;
        String f = failure.toLowerCase(java.util.Locale.US);
        return f.contains("sign in with supergrok") || f.contains("sign in again");
    }

    static final class DeviceCode {
        final String deviceCode, userCode, verificationUri, verificationUriComplete;
        final long expiresAtMs;
        final int intervalSeconds;
        final long attempt;
        DeviceCode(String dc, String uc, String uri, String complete, long exp, int interval, long attempt) {
            deviceCode = dc; userCode = uc; verificationUri = uri; verificationUriComplete = complete;
            expiresAtMs = exp; intervalSeconds = interval; this.attempt = attempt;
        }
        /** The page to open: the prefilled one when it is a trusted x.ai https URL. */
        String browserUrl() {
            return trusted(verificationUriComplete) ? verificationUriComplete : verificationUri;
        }
    }

    interface StartCallback { void onCode(DeviceCode code); void onError(String message); }
    interface DoneCallback { void onSignedIn(); void onError(String message); }

    static final class AuthException extends Exception {
        final String code;
        AuthException(String message, String code) { super(message); this.code = code; }
    }

    // ---- sign-in -------------------------------------------------------------------------------

    static void start(StartCallback cb) {
        EXEC.execute(() -> {
            try {
                Response r = post(DEVICE_CODE_URL, "client_id=" + enc(CLIENT_ID) + "&scope=" + enc(SCOPE));
                JSONObject o = r.json;
                if (!r.ok() || o.has("error")) throw fail(r, "Could not start SuperGrok sign-in");
                String dc = o.optString("device_code", ""), uc = o.optString("user_code", "");
                String uri = o.optString("verification_uri", "");
                if (dc.isEmpty() || uc.isEmpty() || !trusted(uri)) {
                    throw new AuthException("xAI returned an incomplete or untrusted sign-in response.", "bad_response");
                }
                long attempt = System.nanoTime();
                activeAttempt = attempt;
                DeviceCode code = new DeviceCode(dc, uc, uri, o.optString("verification_uri_complete", ""),
                        System.currentTimeMillis() + o.optInt("expires_in", 900) * 1000L,
                        Math.max(1, o.optInt("interval", 5)), attempt);
                MAIN.post(() -> cb.onCode(code));
            } catch (Exception e) {
                String m = message(e);
                MAIN.post(() -> cb.onError(m));
            }
        });
    }

    /** Polls until approval, denial, expiry or {@link #cancel()}. Saves the session on success. */
    static void awaitApproval(Context context, DeviceCode code, DoneCallback cb) {
        Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            int interval = code.intervalSeconds;
            int transientFailures = 0;
            try {
                while (System.currentTimeMillis() < code.expiresAtMs && activeAttempt == code.attempt) {
                    // Sleeps the poll interval, but wakes early when the user returns to Orbit.
                    synchronized (POLL_WAKE) { POLL_WAKE.wait(interval * 1000L); }
                    if (activeAttempt != code.attempt) return;
                    Response r;
                    try {
                        r = post(TOKEN_URL, "grant_type=" + enc(DEVICE_GRANT) + "&client_id="
                                + enc(CLIENT_ID) + "&device_code=" + enc(code.deviceCode));
                    } catch (IOException e) {
                        // The phone is in the browser: Doze, a network switch or background
                        // restrictions can fail one poll. Keep waiting instead of giving up.
                        transientFailures++;
                        Log.w(TAG, "device poll network error #" + transientFailures + ": " + e.getClass().getSimpleName());
                        continue;
                    }
                    String error = r.json.optString("error", "");
                    Log.i(TAG, "device poll http_" + r.code + (error.isEmpty() ? "" : " " + error));
                    if (r.ok() && error.isEmpty()) {
                        store(app, r.json, "");
                        Prefs.get(app).edit().putString(Prefs.PROVIDER, Prefs.PROVIDER_XAI).apply();
                        activeAttempt = 0;
                        MAIN.post(cb::onSignedIn);
                        return;
                    }
                    if ("authorization_pending".equals(error)) continue;
                    if ("slow_down".equals(error)) { interval += 5; continue; }
                    // Server hiccup or a non-JSON gateway page (5xx, 429, HTML): retry, do not abort.
                    if (error.isEmpty() && (r.code >= 500 || r.code == 429 || r.code == 408)) {
                        transientFailures++;
                        continue;
                    }
                    throw fail(r, "SuperGrok sign-in failed");
                }
                if (activeAttempt == code.attempt) {
                    MAIN.post(() -> cb.onError("The SuperGrok code expired. Start sign-in again."));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.w(TAG, "device poll stopped: " + e.getClass().getSimpleName());
                String m = message(e);
                MAIN.post(() -> cb.onError(m));
            }
        });
    }

    /** Called when Orbit returns to the foreground: check the token endpoint now, not after the interval. */
    static void pollNow() {
        synchronized (POLL_WAKE) { POLL_WAKE.notifyAll(); }
    }

    static void cancel() { activeAttempt = 0; }

    /**
     * Local sign-out always happens first and synchronously. Revocation at xAI is best-effort on a
     * background thread: its result is ignored, so an offline phone still signs out.
     */
    static void signOut(Context context) {
        cancel();
        SecureStore.SuperGrokSession session = SecureStore.loadSuperGrokSession(context);
        SecureStore.clearSuperGrokSession(context);
        if (session == null) return;
        final String refresh = session.refreshToken;
        final String access = session.accessToken;
        EXEC.execute(() -> {
            // Revoking the refresh token ends the grant; the access token is revoked too in case
            // the server does not cascade. Failures are deliberately ignored.
            if (!refresh.isEmpty()) revokeQuietly(refresh, "refresh_token");
            if (!access.isEmpty()) revokeQuietly(access, "access_token");
        });
    }

    private static void revokeQuietly(String token, String hint) {
        try {
            post(REVOCATION_URL, "token=" + enc(token) + "&token_type_hint=" + enc(hint)
                    + "&client_id=" + enc(CLIENT_ID));
        } catch (Exception ignored) {
            // Best effort only.
        }
    }

    // ---- tokens --------------------------------------------------------------------------------

    /**
     * A usable access token, refreshing first when it is near expiry or {@code forceRefresh}.
     * Blocking network call: never run on the main thread. Returns "" when signed out; throws
     * {@link AuthException} on failure (and clears the session on invalid_grant).
     */
    static String validAccessToken(Context context, boolean forceRefresh) throws IOException, AuthException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("network on main thread");
        Context app = context.getApplicationContext();
        SecureStore.SuperGrokSession seen = SecureStore.loadSuperGrokSession(app);
        if (seen == null) return "";
        if (!forceRefresh && !nearExpiry(seen)) return seen.accessToken;
        synchronized (REFRESH_LOCK) {
            SecureStore.SuperGrokSession now = SecureStore.loadSuperGrokSession(app);
            if (now == null) return "";
            // Another caller refreshed while this one waited: use its result, do not reuse the old refresh token.
            if (!now.refreshToken.equals(seen.refreshToken) || !now.accessToken.equals(seen.accessToken)) {
                if (!nearExpiry(now)) return now.accessToken;
            } else if (!forceRefresh && !nearExpiry(now)) {
                return now.accessToken;
            }
            if (now.refreshToken.isEmpty()) {
                SecureStore.clearSuperGrokSession(app);
                throw new AuthException("SuperGrok session expired. Sign in again.", "no_refresh_token");
            }
            Response r = post(TOKEN_URL, "grant_type=refresh_token&client_id=" + enc(CLIENT_ID)
                    + "&refresh_token=" + enc(now.refreshToken));
            String error = r.json.optString("error", "");
            if ("invalid_grant".equals(error)) {
                SecureStore.clearSuperGrokSession(app);
                throw new AuthException("SuperGrok session ended. Sign in again.", error);
            }
            if (!r.ok() || !error.isEmpty()) throw fail(r, "Could not renew the SuperGrok session");
            return store(app, r.json, now.refreshToken);
        }
    }

    private static boolean nearExpiry(SecureStore.SuperGrokSession s) {
        return s.expiresAtMs <= System.currentTimeMillis() + REFRESH_SKEW_MS;
    }

    private static String store(Context app, JSONObject o, String previousRefresh) throws AuthException {
        String access = o.optString("access_token", "");
        if (access.isEmpty()) throw new AuthException("xAI returned no access token.", "bad_response");
        String refresh = o.optString("refresh_token", "");
        if (refresh.isEmpty()) refresh = previousRefresh;
        long expiresAt = System.currentTimeMillis() + o.optInt("expires_in", DEFAULT_EXPIRES_IN) * 1000L;
        if (!SecureStore.saveSuperGrokSession(app, access, refresh, expiresAt)) {
            throw new AuthException("Signed in, but GrokLink could not save the session in Android Keystore.", "store_failed");
        }
        return access;
    }

    // ---- http ----------------------------------------------------------------------------------

    private static final class Response {
        final int code; final JSONObject json;
        Response(int code, JSONObject json) { this.code = code; this.json = json; }
        boolean ok() { return code >= 200 && code < 300; }
    }

    private static Response post(String url, String form) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "application/json");
            try (OutputStream os = conn.getOutputStream()) { os.write(form.getBytes(StandardCharsets.UTF_8)); }
            int code = conn.getResponseCode();
            String body = read(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            JSONObject json;
            try { json = body.isEmpty() ? new JSONObject() : new JSONObject(body); }
            catch (JSONException e) { json = new JSONObject(); }
            return new Response(code, json);
        } finally {
            conn.disconnect();
        }
    }

    private static AuthException fail(Response r, String prefix) {
        if (r.code == 403) {
            return new AuthException("xAI refused this account (403). This SuperGrok tier may not allow API access; use an API key instead.", "forbidden");
        }
        String error = r.json.optString("error", "http_" + r.code);
        if ("access_denied".equals(error)) return new AuthException("Sign-in was declined.", error);
        if ("expired_token".equals(error)) return new AuthException("The SuperGrok code expired. Start sign-in again.", error);
        // error_description is server text; keep it out of logs, show only a short prefix + code.
        return new AuthException(prefix + " (" + error + ").", error);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096]; int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static boolean trusted(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        try {
            URL u = new URL(uri);
            String host = u.getHost().toLowerCase(java.util.Locale.US);
            return "https".equals(u.getProtocol()) && (host.equals("x.ai") || host.endsWith(".x.ai")
                    || host.equals("grok.com") || host.endsWith(".grok.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String message(Exception e) {
        if (e instanceof AuthException) return e.getMessage();
        return "Could not reach xAI. Check the connection and try again.";
    }
}
