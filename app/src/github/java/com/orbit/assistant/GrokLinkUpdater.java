package com.orbit.assistant;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds a newer GrokLink debug build and hands it to Android's package installer.
 *
 * <p>The feed is the public {@code groklink} release on {@code tmg270/orbit-fork}: one small JSON
 * file with a stable address, and the APK it names. No token is built into the app and none is
 * sent. The download is checked against the published checksum, package name, version and the
 * certificate of the copy already installed. Android still draws the Install button.
 */
public final class GrokLinkUpdater {
    static final String OWNER_REPO = "tmg270/orbit-fork";
    static final String TAG = "groklink";
    static final String MANIFEST_NAME = "groklink-update.json";
    public static final String NEED_PERMISSION = "need-unknown-apps";
    static final String WORK_NAME = "groklink-update-check";
    private static final String LEGACY_WORK = "orbit-stable-update-check";
    static final String KEY_JSON = "groklink_update_json";
    static final String KEY_CHECKED = "groklink_update_checked_at";
    static final String KEY_SEEN = "groklink_update_seen_code";
    private static final long CHECK_INTERVAL_MS = 3L * 60L * 60L * 1000L;
    private static final int NOTIFICATION_ID = 51001;
    private static final int MANIFEST_LIMIT = 65536;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);
    /** The check the screen is waiting on. Replaced if a newer request arrives mid-flight. */
    private static volatile CheckCallback pendingCheck;

    private GrokLinkUpdater() {}

    public interface CheckCallback {
        /** {@code published} is the remote build when one was read. {@code error} is set on failure. */
        void onChecked(GrokLinkUpdateManifest published, boolean newer, String error);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onConfirm();
        void onError(String message);
    }

    /** Stable public address of the latest published debug build. No credentials. */
    public static String manifestUrl() {
        return "https://github.com/" + OWNER_REPO + "/releases/download/" + TAG + "/" + MANIFEST_NAME;
    }

    /** Where a verified APK is written. The same directory FileProvider already exposes. */
    public static File updateDirectory(Context context) {
        File dir = new File(context.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public static void schedule(Context context) {
        if (context == null) return;
        try {
            WorkManager wm = WorkManager.getInstance(context);
            wm.cancelUniqueWork(LEGACY_WORK);
            if (!OrbitDistribution.selfUpdates()) {
                wm.cancelUniqueWork(WORK_NAME);
                return;
            }
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    GrokLinkUpdateWorker.class, 12, TimeUnit.HOURS)
                    .setConstraints(new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build())
                    .build();
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Exception ignored) {
            // WorkManager is not ready in some tests. The in-app check still works.
        }
    }

    /**
     * Launch-time check. Uses the last result when it is still fresh, and does not open a
     * connection from unit tests.
     */
    public static void refreshSoon(Context context, CheckCallback callback) {
        if (context == null || !OrbitDistribution.selfUpdates()) {
            deliver(callback, null, false, null);
            return;
        }
        GrokLinkUpdateManifest cached = readCache(context);
        boolean fresh = cached != null && !stale(context);
        if (underTest() || fresh) {
            deliver(callback, cached, cached != null && cached.newerThan(BuildConfig.VERSION_CODE), null);
            return;
        }
        checkNow(context, callback);
    }

    public static void checkNow(Context context, CheckCallback callback) {
        if (context == null || !OrbitDistribution.selfUpdates()) {
            deliver(callback, null, false, null);
            return;
        }
        if (underTest()) {
            GrokLinkUpdateManifest cached = readCache(context);
            deliver(callback, cached,
                    cached != null && cached.newerThan(BuildConfig.VERSION_CODE), null);
            return;
        }
        pendingCheck = callback;
        if (!CHECKING.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            try {
                GrokLinkUpdateManifest manifest = fetchAndStore(app);
                boolean available = manifest != null && manifest.newerThan(BuildConfig.VERSION_CODE);
                deliver(pendingCheck, manifest, available, null);
            } catch (GrokLinkUpdateException e) {
                deliver(pendingCheck, null, false, e.getMessage());
            } catch (Exception e) {
                deliver(pendingCheck, null, false,
                        "Couldn't reach GitHub. Check the connection and try again.");
            } finally {
                CHECKING.set(false);
            }
        }, "groklink-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    public static long seenCode(Context context) {
        if (context == null) return 0L;
        return Prefs.get(context).getLong(KEY_SEEN, 0L);
    }

    public static void rememberOffer(Context context, long versionCode) {
        if (context == null || versionCode <= 0L) return;
        Prefs.get(context).edit().putLong(KEY_SEEN, versionCode).apply();
    }

    public static boolean canRequestPackageInstalls(Context context) {
        return context != null && OrbitEdition.canRequestPackageInstalls(context);
    }

    public static void openUnknownSourcesSettings(Activity activity) {
        if (activity == null || !OrbitDistribution.selfUpdates()) return;
        OrbitEdition.openUnknownSourcesSettings(activity);
    }

    public static void downloadAndInstall(Activity activity, GrokLinkUpdateManifest manifest,
                                           DownloadCallback callback) {
        if (activity == null || manifest == null || !OrbitDistribution.selfUpdates()) {
            deliverError(callback, "This build can't install updates.");
            return;
        }
        if (!canRequestPackageInstalls(activity)) {
            deliverError(callback, NEED_PERMISSION);
            return;
        }
        if (!acceptsApkUrl(manifest.apkUrl, manifest.apkName)) {
            deliverError(callback, "The published build isn't from the GrokLink release.");
            return;
        }
        Context app = activity.getApplicationContext();
        Thread thread = new Thread(() -> {
            try {
                File apk = obtain(app, manifest, callback);
                String problem = verifyApk(app, apk, manifest);
                if (problem != null) {
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    deliverError(callback, problem);
                    return;
                }
                handToInstaller(app, apk);
                onMain(() -> { if (callback != null) callback.onConfirm(); });
            } catch (GrokLinkUpdateException e) {
                deliverError(callback, e.getMessage());
            } catch (SecurityException e) {
                deliverError(callback, NEED_PERMISSION);
            } catch (Exception e) {
                deliverError(callback, "The download didn't finish. Try again.");
            }
        }, "groklink-update-download");
        thread.setDaemon(true);
        thread.start();
    }

    /** Downloads the manifest, checks it, and remembers it. Used by the background check too. */
    static GrokLinkUpdateManifest fetchAndStore(Context context) throws IOException {
        byte[] body = httpGet(manifestUrl(), MANIFEST_LIMIT);
        String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        GrokLinkUpdateManifest manifest = GrokLinkUpdateManifest.parse(json, context.getPackageName());
        if (!acceptsApkUrl(manifest.apkUrl, manifest.apkName)) {
            throw new GrokLinkUpdateException("The published build isn't from the GrokLink release.", false);
        }
        Prefs.get(context).edit()
                .putString(KEY_JSON, manifest.toJson())
                .putLong(KEY_CHECKED, System.currentTimeMillis())
                .apply();
        return manifest;
    }

    static void notifyIfWanted(Context context, GrokLinkUpdateManifest manifest) {
        if (context == null || manifest == null) return;
        if (!manifest.newerThan(BuildConfig.VERSION_CODE)) return;
        if (manifest.alreadyOffered(seenCode(context))) return;
        if (!Prefs.updateNotifications(context)) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return;
        OrbitNotificationChannels.ensure(context, OrbitNotificationChannels.UPDATES);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = nm.getNotificationChannel(OrbitNotificationChannels.UPDATES);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
        }
        Intent open = new Intent(context, UpdateActivity.class)
                .putExtra(UpdateActivity.EXTRA_START_DOWNLOAD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = manifest.versionName + " (" + manifest.versionCode + ") is ready to install.";
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, OrbitNotificationChannels.UPDATES)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("A newer GrokLink build is ready")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS);
        nm.notify(NOTIFICATION_ID, builder.build());
        rememberOffer(context, manifest.versionCode);
    }

    /**
     * The APK address has to be this fork's {@code groklink} release and the file named in the
     * manifest. A description that points anywhere else is ignored.
     */
    static boolean acceptsApkUrl(String url, String apkName) {
        if (!GrokLinkUpdateManifest.safeApkName(apkName) || url == null) return false;
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) return false;
        if (uri.getQuery() != null || uri.getFragment() != null) return false;
        if (!"github.com".equalsIgnoreCase(uri.getHost())) return false;
        String path = uri.getEncodedPath() != null ? uri.getPath() : null;
        String expected = "/" + OWNER_REPO + "/releases/download/" + TAG + "/" + apkName;
        return expected.equals(path);
    }

    static String verifyApk(Context context, File apk, GrokLinkUpdateManifest manifest) {
        if (apk == null || !apk.isFile() || manifest == null) {
            return "The download wasn't there to check.";
        }
        try {
            if (!manifest.sha256.equals(sha256Hex(apk))) {
                return "The download didn't match the published checksum.";
            }
        } catch (IOException e) {
            return "The download couldn't be checked.";
        }
        if (manifest.sizeBytes > 0 && apk.length() != manifest.sizeBytes) {
            return "The download was the wrong size.";
        }
        PackageManager pm = context.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || archive.packageName == null) {
            return "That file isn't an installable GrokLink build.";
        }
        if (archive.applicationInfo != null) {
            archive.applicationInfo.sourceDir = apk.getAbsolutePath();
            archive.applicationInfo.publicSourceDir = apk.getAbsolutePath();
        }
        if (!context.getPackageName().equals(archive.packageName)) {
            return "That file is a different app.";
        }
        if (archive.getLongVersionCode() != manifest.versionCode) {
            return "That file is a different version than the one just published.";
        }
        try {
            PackageInfo installed = pm.getPackageInfo(
                    context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (!sameSigners(archive, installed)) {
                return "That build is signed differently from the GrokLink already on this phone.";
            }
        } catch (PackageManager.NameNotFoundException e) {
            return "GrokLink couldn't read its own signature.";
        }
        return null;
    }

    static String sha256Hex(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable");
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) digest.update(buf, 0, n);
        }
        return hex(digest.digest());
    }

    private static File obtain(Context context, GrokLinkUpdateManifest manifest, DownloadCallback callback)
            throws IOException {
        File dir = updateDirectory(context);
        File dest = new File(dir, manifest.apkName);
        String dirPath = dir.getCanonicalPath();
        String destPath = dest.getCanonicalPath();
        if (!destPath.startsWith(dirPath + File.separator)) {
            throw new GrokLinkUpdateException("The published file name isn't usable.", false);
        }
        String already = verifyApk(context, dest, manifest);
        if (already == null) {
            onMain(() -> { if (callback != null) callback.onProgress(100); });
            return dest;
        }
        File part = new File(dir, "download.part");
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        long expected = manifest.sizeBytes;
        try {
            httpGetToFile(manifest.apkUrl, part,
                    expected > 0 ? expected : GrokLinkUpdateManifest.MAX_BYTES, callback);
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (!part.renameTo(dest)) {
                try (InputStream in = new FileInputStream(part);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        return dest;
    }

    private static void handToInstaller(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo old : installer.getMySessions()) {
            try {
                installer.abandonSession(old.getSessionId());
            } catch (Exception ignored) {
                // A leftover session must not block the one we are about to ask for.
            }
        }
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            params.setAppPackageName(context.getPackageName());
        }
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            try (OutputStream out = session.openWrite("groklink.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent status = new Intent(context, GrokLinkInstallActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getActivity(context, sessionId, status, flags);
            session.commit(pending.getIntentSender());
        } catch (Exception e) {
            try {
                session.abandon();
            } catch (Exception ignored) {}
            if (e instanceof IOException) throw (IOException) e;
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new IOException("Couldn't start the install", e);
        } finally {
            session.close();
        }
    }

    private static byte[] httpGet(String url, int maxBytes) throws IOException {
        HttpURLConnection conn = open(url, true);
        try {
            int code = conn.getResponseCode();
            if (code == 404 || code == 403) {
                throw new GrokLinkUpdateException(
                        "No GrokLink build has been published yet.", false);
            }
            if (code != 200) {
                throw new GrokLinkUpdateException(
                        "GitHub didn't return the update description (" + code + ").", code >= 500);
            }
            try (InputStream in = conn.getInputStream()) {
                return readLimited(in, maxBytes);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void httpGetToFile(String url, File dest, long maxBytes, DownloadCallback callback)
            throws IOException {
        HttpURLConnection conn = open(url, false);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new GrokLinkUpdateException("The build couldn't be downloaded (" + code + ").", code >= 500);
            }
            long declared = conn.getContentLengthLong();
            if (declared > maxBytes) {
                throw new GrokLinkUpdateException("The published build is an unexpected size.", false);
            }
            long total = declared > 0 ? declared : maxBytes;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                long got = 0;
                int lastPercent = -1;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    got += n;
                    if (got > maxBytes) {
                        throw new GrokLinkUpdateException("The published build is an unexpected size.", false);
                    }
                    out.write(buf, 0, n);
                    int percent = (int) Math.min(99, (got * 100L) / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        int shown = percent;
                        onMain(() -> { if (callback != null) callback.onProgress(shown); });
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Follows a short chain of HTTPS redirects, and only to GitHub's own release hosts.
     * The first request has to be the manifest or the APK named by it.
     */
    private static HttpURLConnection open(String start, boolean manifest) throws IOException {
        String current = start;
        for (int hop = 0; hop < 5; hop++) {
            if (!allowed(current, hop == 0, manifest)) {
                String host = Uri.parse(current).getHost();
                if (hop == 0 || host == null) {
                    throw new GrokLinkUpdateException(
                            "The update address isn't the GrokLink release.", false);
                }
                throw new GrokLinkUpdateException(
                        "The download was redirected to an unexpected host (" + host + ").", false);
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", GrokLinkHttp.userAgent());
            conn.setRequestProperty("Accept", "application/octet-stream, application/json, */*");
            int code;
            try {
                code = conn.getResponseCode();
            } catch (IOException e) {
                conn.disconnect();
                throw new GrokLinkUpdateException(
                        "Couldn't reach GitHub. Check the connection and try again.", true);
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new GrokLinkUpdateException("GitHub sent an incomplete redirect.", true);
                }
                try {
                    current = new URL(new URL(current), location).toString();
                } catch (IOException e) {
                    throw new GrokLinkUpdateException("GitHub sent an unusable redirect.", false);
                }
                continue;
            }
            return conn;
        }
        throw new GrokLinkUpdateException("GitHub redirected the download too many times.", false);
    }

    private static boolean allowed(String url, boolean first, boolean manifest) {
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) return false;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
        if (first) {
            if (!"github.com".equals(host)) return false;
            String path = uri.getPath();
            if (path == null) return false;
            String prefix = "/" + OWNER_REPO + "/releases/download/" + TAG + "/";
            if (!path.startsWith(prefix)) return false;
            if (manifest) return path.equals(prefix + MANIFEST_NAME) && uri.getQuery() == null;
            return uri.getQuery() == null;
        }
        return "github.com".equals(host)
                || "release-assets.githubusercontent.com".equals(host)
                || "objects.githubusercontent.com".equals(host)
                || "github-releases.githubusercontent.com".equals(host);
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        byte[] buf = new byte[4096];
        int got = 0;
        byte[] out = new byte[Math.min(max, 8192)];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (got + n > max) {
                throw new GrokLinkUpdateException("The update description was too large.", false);
            }
            if (got + n > out.length) out = Arrays.copyOf(out, Math.min(max, out.length * 2));
            System.arraycopy(buf, 0, out, got, n);
            got += n;
        }
        return Arrays.copyOf(out, got);
    }

    private static boolean sameSigners(PackageInfo archive, PackageInfo installed) {
        Signature[] a = signers(archive);
        Signature[] b = signers(installed);
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) return false;
        boolean[] used = new boolean[b.length];
        for (Signature left : a) {
            boolean found = false;
            for (int i = 0; i < b.length; i++) {
                if (!used[i] && left.equals(b[i])) {
                    used[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static Signature[] signers(PackageInfo info) {
        if (info == null || info.signingInfo == null) return null;
        Signature[] current = info.signingInfo.getApkContentsSigners();
        return current != null && current.length > 0 ? current : null;
    }

    private static GrokLinkUpdateManifest readCache(Context context) {
        String json = Prefs.get(context).getString(KEY_JSON, "");
        if (json == null || json.isEmpty()) return null;
        try {
            GrokLinkUpdateManifest manifest = GrokLinkUpdateManifest.parse(json, context.getPackageName());
            if (!acceptsApkUrl(manifest.apkUrl, manifest.apkName)) return null;
            return manifest;
        } catch (GrokLinkUpdateException e) {
            return null;
        }
    }

    private static boolean stale(Context context) {
        long at = Prefs.get(context).getLong(KEY_CHECKED, 0L);
        return at <= 0L || System.currentTimeMillis() - at > CHECK_INTERVAL_MS;
    }

    /** Unit tests must not phone GitHub while Activities are created. */
    private static boolean underTest() {
        return "robolectric".equals(Build.FINGERPRINT);
    }

    private static void deliver(CheckCallback callback, GrokLinkUpdateManifest published,
                                boolean newer, String error) {
        if (callback == null) return;
        onMain(() -> callback.onChecked(published, newer, error));
    }

    private static void deliverError(DownloadCallback callback, String message) {
        onMain(() -> { if (callback != null) callback.onError(message); });
    }

    private static void onMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else MAIN.post(runnable);
    }

    private static String hex(byte[] raw) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            int v = raw[i] & 0xff;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0f];
        }
        return new String(out);
    }
}
