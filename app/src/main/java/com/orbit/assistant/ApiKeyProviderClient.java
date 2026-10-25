package com.orbit.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * xAI Chat Completions transport, authenticated with a saved xAI API key or the SuperGrok
 * OAuth access token.
 */
final class ApiKeyProviderClient {
    private static final String XAI_CHAT = "https://api.x.ai/v1/chat/completions";
    private static final String XAI_RESPONSES = "https://api.x.ai/v1/responses";
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();

    private ApiKeyProviderClient() {}

    static void send(Context c, String provider, String key, AiRequest request,
                     AssistantClient.Callback callback) {
        send(c, provider, key, request, callback, null);
    }

    /**
     * Fork patch: {@code refreshOn401}, when non-null, is asked once (on this client's worker
     * thread) for a fresh credential after an HTTP 401, and the request is retried once with it.
     * Used only for the SuperGrok OAuth token, never for a saved API key.
     */
    static void send(Context c, String provider, String key, AiRequest request,
                     AssistantClient.Callback callback, Supplier<String> refreshOn401) {
        EXEC.execute(() -> doSend(c, provider, key, request, callback, refreshOn401));
    }

    static void complete(Context c, String provider, String key, AiSelection selection,
                         String instructions, String prompt, AssistantClient.PlanCallback callback) {
        complete(c, provider, key, selection, instructions, prompt, callback, null);
    }

static void complete(Context c, String provider, String key, AiSelection selection,
                     String instructions, String prompt, AssistantClient.PlanCallback callback,
                     Supplier<String> refreshOn401) {
        complete(c, provider, key, selection, instructions, prompt, callback, refreshOn401, false);
    }

    static void complete(Context c, String provider, String key, AiSelection selection,
                         String instructions, String prompt, AssistantClient.PlanCallback callback,
                         Supplier<String> refreshOn401, boolean webSearch) {
        EXEC.execute(() -> doComplete(c, provider, key, selection, instructions, prompt, callback,
                refreshOn401, webSearch));
    }

    private static void doComplete(Context c, String provider, String key, AiSelection selection,
                                   String instructions, String prompt,
                                   AssistantClient.PlanCallback callback,
                                   Supplier<String> refreshOn401, boolean webSearch) {
        if (webSearch) {
            doCompleteWithSearch(c, provider, key, selection, instructions, prompt, callback,
                    refreshOn401, true, true);
            return;
        }
        {
            HttpURLConnection conn = null;
            try {
                JSONObject body = ProviderRequestMapper.simple(provider, selection, instructions, prompt);
                conn = open(XAI_CHAT, key, false);
                write(conn, body);
                int code = conn.getResponseCode();
                String raw = read(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
                if (code == 401 && refreshOn401 != null) {
                    String fresh = freshCredential(refreshOn401);
                    if (!fresh.isEmpty()) {
                        conn.disconnect();
                        conn = null;
                        doComplete(c, provider, fresh, selection, instructions, prompt, callback, null, false);
                        return;
                    }
                }
                if (code < 200 || code >= 300) {
                    callback.onError(error(c, provider, code, raw, selection.model));
                    return;
                }
                String text = xaiText(new JSONObject(raw));
                if (text.trim().isEmpty()) callback.onError(name(provider) + " returned no text.");
                else callback.onText(text, name(provider) + " · " + selection.modelName());
            } catch (Exception e) {
                callback.onError("Could not reach " + name(provider) + ". Check the connection and try again.");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    /** Asks for a refreshed credential; "" when none could be obtained. Never throws. */
    private static String freshCredential(Supplier<String> refreshOn401) {
        try {
            String fresh = refreshOn401.get();
            return fresh == null ? "" : fresh;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void doSend(Context c, String provider, String key, AiRequest request,
                               AssistantClient.Callback callback, Supplier<String> refreshOn401) {
        doSend(c, provider, key, request, callback, refreshOn401, true, true, true);
    }

    private static void doSend(Context c, String provider, String key, AiRequest request,
                               AssistantClient.Callback callback, Supplier<String> refreshOn401,
                               boolean responses, boolean webSearch, boolean json) {
        HttpURLConnection conn = null;
        boolean xai = true;
        try {
            AiModelSpec spec = OrbitModelCatalog.spec(provider, request.selection.model);
            if (spec == null || !spec.selectable()) {
                callback.onError(OrbitModelCatalog.unavailableMessage(request.selection.model));
                return;
            }
            JSONObject chat = ProviderRequestMapper.xai(c, request, true);
            JSONObject body = responses
                    ? ProviderRequestMapper.responsesFrom(chat, webSearch, json)
                    : chat;
            String url = responses ? XAI_RESPONSES : XAI_CHAT;
            long started = android.os.SystemClock.elapsedRealtime();
            SessionTrace.api("request model=" + chat.optString("model")
                    + " effort=" + chat.optString("reasoning_effort", "")
                    + " web_search=" + (responses && webSearch)
                    + " json=" + (!responses || json)
                    + " endpoint=" + (responses ? "responses" : "chat"));
            conn = open(url, key, true);
            write(conn, body);
            int code = conn.getResponseCode();
            if (code == 401 && refreshOn401 != null) {
                String fresh = freshCredential(refreshOn401);
                if (!fresh.isEmpty()) {
                    conn.disconnect();
                    conn = null;
                    doSend(c, provider, fresh, request, callback, null, responses, webSearch, json);
                    return;
                }
            }
            if (code < 200 || code >= 300) {
                String raw = read(conn.getErrorStream());
                long ms = android.os.SystemClock.elapsedRealtime() - started;
                SessionTrace.api("http " + code + " in " + ms + " ms " + clipTrace(raw));
                if (responses && json && ProviderRequestMapper.jsonFormatRefused(code, raw)) {
                    conn.disconnect();
                    conn = null;
                    doSend(c, provider, key, request, callback, refreshOn401, true, webSearch, false);
                    return;
                }
                if (responses && webSearch && ProviderRequestMapper.webSearchRefused(code, raw)) {
                    conn.disconnect();
                    conn = null;
                    doSend(c, provider, key, request, callback, refreshOn401, true, false, json);
                    return;
                }
                if (responses && (code == 404 || code == 410)
                        && !modelUnavailable(provider, code, raw, request.selection.model)) {
                    conn.disconnect();
                    conn = null;
                    doSend(c, provider, key, request, callback, refreshOn401, false, false, true);
                    return;
                }
                String friendly = error(c, provider, code, raw, request.selection.model);
                if (modelUnavailable(provider, code, raw, request.selection.model)) {
                    ModelAvailability.markUnavailable(c, provider, request.selection.model);
                }
                callback.onError(friendly);
                return;
            }
            ModelAvailability.markAvailable(c, provider, request.selection.model);
            if (request.thinkingUpdates) callback.onThinking(ThinkingUpdate.modelReasoning(request.selection.model));
            String text = readXaiStream(conn.getInputStream(), callback, true);
            SessionTrace.api("response " + (android.os.SystemClock.elapsedRealtime() - started)
                    + " ms chars=" + text.length());
            if (text.trim().isEmpty()) {
                callback.onError(name(provider) + " connected but returned no assistant text. Try again.");
                return;
            }
            ResponseDetails details = ResponseDetails.sentWith(request.selection);
            AssistantReply reply = xai
                    // xAI answers in Orbit's {"text","actions"} envelope (fork patch).
                    ? DeviceActionContract.parseEnvelope(text, false)
                    : new AssistantReply(text.replace("—", "-"), new ArrayList<>());
            if (xai) reply = SetlistPlaylist.attach(request.prompt, reply);
            if (xai && reply.text.isEmpty() && reply.actions.isEmpty()) {
                callback.onError(name(provider) + " connected but returned no assistant text. Try again.");
                return;
            }
            callback.onSuccess(reply.withDetails(details));
        } catch (StreamError e) {
            callback.onError(error(c, provider, e.code, e.getMessage(), request.selection.model));
        } catch (Exception e) {
            callback.onError("Could not reach " + name(provider) + ". Check the connection and try again.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String key, boolean stream) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(240_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setRequestProperty("User-Agent", GrokLinkHttp.userAgent());
        return conn;
    }

    private static void write(HttpURLConnection conn, JSONObject body) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
    }


    static String readXaiStream(InputStream input, AssistantClient.Callback callback) throws Exception {
        return readXaiStream(input, callback, false);
    }

    /**
     * With {@code envelope}, the stream is Orbit's JSON envelope: only the decoded "text" field is
     * shown while streaming (never raw JSON), and the full raw body is returned for parsing.
     */
    static String readXaiStream(InputStream input, AssistantClient.Callback callback,
                                boolean envelope) throws Exception {
        StringBuilder full = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        long lastReasoningUi = 0;
        String lastVisible = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JSONObject event;
                try { event = new JSONObject(data); } catch (Exception ignored) { continue; }
                String type = event.optString("type", "");
                if (!type.isEmpty()) {
                    if ("response.output_text.delta".equals(type)) {
                        full.append(event.optString("delta", ""));
                        if (envelope) {
                            String visible = DeviceActionContract.removeEmDashes(
                                    DeviceActionContract.extractPartialText(full.toString()));
                            if (!visible.isEmpty() && !visible.equals(lastVisible)) {
                                lastVisible = visible;
                                callback.onDelta(visible);
                            }
                        } else {
                            callback.onDelta(full.toString().replace("—", "-"));
                        }
                    } else if (type.contains("web_search") || type.contains("browse")) {
                        SessionTrace.note("grok tool " + type);
                    } else if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
                        noteCitations(event.optJSONObject("response"));
                    } else if ("error".equals(type) || "response.failed".equals(type)) {
                        JSONObject response = event.optJSONObject("response");
                        JSONObject failure = response == null ? event.optJSONObject("error") : response.optJSONObject("error");
                        String message = failure == null ? event.optString("message", "Grok request failed")
                                : failure.optString("message", "Grok request failed");
                        throw new StreamError(400, message);
                    }
                    continue;
                }
                JSONArray choices = event.optJSONArray("choices");
                JSONObject choice = choices == null ? null : choices.optJSONObject(0);
                JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
                // GrokLink: xAI streams readable reasoning as delta.reasoning_content before the
                // answer (docs.x.ai streaming guide). Shown live as a dimmed thinking line only
                // while no answer text has arrived; never stored or sent back.
                if (delta != null && full.length() == 0 && delta.has("reasoning_content")
                        && !delta.isNull("reasoning_content")) {
                    reasoning.append(delta.optString("reasoning_content", ""));
                    long now = System.currentTimeMillis();
                    if (now - lastReasoningUi > 350) {
                        lastReasoningUi = now;
                        ThinkingUpdate u = ThinkingUpdate.providerSummary(reasoningTail(reasoning));
                        if (u != null) callback.onThinking(u);
                    }
                }
                if (delta != null && delta.has("content") && !delta.isNull("content")) {
                    full.append(delta.optString("content", ""));
                    if (envelope) {
                        String visible = DeviceActionContract.removeEmDashes(
                                DeviceActionContract.extractPartialText(full.toString()));
                        if (!visible.isEmpty() && !visible.equals(lastVisible)) {
                            lastVisible = visible;
                            callback.onDelta(visible);
                        }
                    } else {
                        callback.onDelta(full.toString().replace("—", "-"));
                    }
                }
            }
        }
        return full.toString();
    }

    /** A failure reported inside an HTTP 200 stream. The message is bounded. */
    static final class StreamError extends Exception {
        final int code;

        StreamError(int code, String message) {
            super(message == null ? "" : message.length() > 300 ? message.substring(0, 300) : message);
            this.code = code;
        }
    }


    /** Whether an error means this account cannot reach this model right now. */
    static boolean modelUnavailable(String provider, int code, String raw, String model) {
        return code == 404 || OrbitModelCatalog.looksUnavailable(model, raw);
    }

    /** {@link #error(String, int, String, String)}, saying how OpenRouter was connected. */
    static String error(Context c, String provider, int code, String raw, String model) {
        if ((code == 401 || code == 403) && c != null && !SecureStore.hasXaiKey(c)
                && SecureStore.hasSuperGrokSession(c)) {
            return code == 403
                    ? "xAI refused this SuperGrok account (403). Your plan may not include API access; "
                    + "an xAI API key works instead."
                    : "xAI did not accept the SuperGrok sign-in. Sign in again in AI Providers.";
        }
        return error(provider, code, raw, model);
    }

    static String error(String provider, int code, String raw, String model) {
        String lower = raw == null ? "" : raw.toLowerCase(java.util.Locale.US);
        if (code == 401 || code == 403) return "The saved " + name(provider)
                + " API key is invalid, revoked, or lacks access. Update it in AI Providers.";
        if (code == 404 || OrbitModelCatalog.looksUnavailable(model, raw))
            return OrbitModelCatalog.unavailableMessage(model);
        if (code == 413 || lower.contains("context") && (lower.contains("long") || lower.contains("limit")))
            return "This request is too large for " + modelLabel(model) + ". Remove some context or continue in a new chat.";
        if (code == 429) return name(provider) + " rate limit reached. Wait a moment and try again.";
        if (code == 529 || lower.contains("overloaded")) return name(provider) + " is temporarily overloaded. Try again shortly.";
        if (code >= 500) return name(provider) + " had a server problem. Try again shortly.";
        if (lower.contains("image") || lower.contains("unsupported"))
            return "The selected model does not support something attached to this request.";
        return name(provider) + " rejected this request. Check the selected model and try again.";
    }


    private static String modelLabel(String model) {
        String name = OrbitModelCatalog.displayName(model);
        return name.isEmpty() ? (model == null || model.isEmpty() ? "This model" : model) : name;
    }


    private static String xaiText(JSONObject root) {
        JSONArray choices = root.optJSONArray("choices");
        JSONObject choice = choices == null ? null : choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        return message == null ? "" : message.optString("content", "");
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && out.length() < 200_000) out.append(line);
        }
        return out.toString();
    }

    static String name(String provider) {
        return "xAI";
    }

    private static String clipTrace(String raw) {
        String text = SessionTrace.redact(raw == null ? "" : raw).replace('\n', ' ');
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    private static void noteCitations(JSONObject response) {
        if (response == null) return;
        JSONArray citations = response.optJSONArray("citations");
        if (citations == null || citations.length() == 0) return;
        StringBuilder b = new StringBuilder("citations");
        for (int i = 0; i < citations.length() && i < 8; i++) {
            b.append(' ').append(citations.optString(i, ""));
        }
        SessionTrace.api(b.toString());
    }

    /**
     * Car and other plain completions that should be allowed to browse. Same model, Responses API,
     * falling back to chat completions if this account refuses the tool.
     */
    private static void doCompleteWithSearch(Context c, String provider, String key, AiSelection selection,
                                             String instructions, String prompt,
                                             AssistantClient.PlanCallback callback,
                                             Supplier<String> refreshOn401,
                                             boolean webSearch, boolean json) {
        HttpURLConnection conn = null;
        try {
            JSONObject chat = ProviderRequestMapper.simple(provider, selection, instructions, prompt);
            chat.put("stream", true);
            JSONObject body = ProviderRequestMapper.responsesFrom(chat, webSearch, json);
            long started = android.os.SystemClock.elapsedRealtime();
            SessionTrace.api("request model=" + chat.optString("model")
                    + " web_search=" + webSearch + " endpoint=responses");
            conn = open(XAI_RESPONSES, key, true);
            write(conn, body);
            int code = conn.getResponseCode();
            if (code == 401 && refreshOn401 != null) {
                String fresh = freshCredential(refreshOn401);
                if (!fresh.isEmpty()) {
                    conn.disconnect();
                    doCompleteWithSearch(c, provider, fresh, selection, instructions, prompt, callback,
                            null, webSearch, json);
                    return;
                }
            }
            if (code < 200 || code >= 300) {
                String raw = read(conn.getErrorStream());
                SessionTrace.api("http " + code + " in "
                        + (android.os.SystemClock.elapsedRealtime() - started) + " ms " + clipTrace(raw));
                if (json && ProviderRequestMapper.jsonFormatRefused(code, raw)) {
                    conn.disconnect();
                    doCompleteWithSearch(c, provider, key, selection, instructions, prompt, callback,
                            refreshOn401, webSearch, false);
                    return;
                }
                if (webSearch && ProviderRequestMapper.webSearchRefused(code, raw)) {
                    conn.disconnect();
                    doCompleteWithSearch(c, provider, key, selection, instructions, prompt, callback,
                            refreshOn401, false, json);
                    return;
                }
                if (code == 404 || code == 410) {
                    conn.disconnect();
                    doComplete(c, provider, key, selection, instructions, prompt, callback, refreshOn401, false);
                    return;
                }
                callback.onError(error(c, provider, code, raw, selection.model));
                return;
            }
            String text = readXaiStream(conn.getInputStream(), new AssistantClient.Callback() {
                @Override public void onSuccess(AssistantReply reply) {}
                @Override public void onError(String message) {}
            }, false);
            SessionTrace.api("response " + (android.os.SystemClock.elapsedRealtime() - started)
                    + " ms chars=" + text.length());
            if (text.trim().isEmpty()) callback.onError(name(provider) + " returned no text.");
            else callback.onText(text, name(provider) + " · " + selection.modelName());
        } catch (Exception e) {
            callback.onError("Could not reach " + name(provider) + ". Check the connection and try again.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** The last ~110 characters of the reasoning so far, starting at a word boundary. */
    static String reasoningTail(CharSequence reasoning) {
        String t = reasoning.toString().replace('\n', ' ').trim();
        if (t.length() <= 110) return t;
        t = t.substring(t.length() - 110);
        int sp = t.indexOf(' ');
        return "…" + (sp >= 0 ? t.substring(sp + 1) : t);
    }
}
