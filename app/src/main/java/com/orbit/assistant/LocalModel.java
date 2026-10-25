package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.File;

/**
 * The optional on-device model GrokLink uses for simple commands the phrase matchers miss.
 *
 * <p>Same runtime and the same file as Orbit Assistant's chat model: MediaPipe LLM Inference and
 * LiteRT's Qwen 2.5 1.5B Instruct {@code .task} (1,598,556,720 bytes, about 1.60 GB). The file is
 * downloaded on first run, never bundled in the APK, and checked against a pinned SHA-256 before
 * it is used. xAI is still the only cloud provider.
 */
public final class LocalModel {
    public static final String FILE_NAME =
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task";
    public static final String URL =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/"
                    + FILE_NAME;
    /** Pinned by the publisher's Hugging Face export and by upstream Orbit Local. */
    public static final long SIZE_BYTES = 1_598_556_720L;
    public static final String SHA256 =
            "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3";
    public static final String DISPLAY = "Qwen 2.5 1.5B Instruct";

    private LocalModel() {}

    public static File file(Context c) {
        return new File(dir(c), FILE_NAME);
    }

    public static File partial(Context c) {
        return new File(dir(c), FILE_NAME + ".partial");
    }

    public static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "groklink-local");
        if (!d.isDirectory() && !d.mkdirs()) {
            // The downloader reports the failure when it cannot create the directory.
        }
        return d;
    }

    public static boolean ready(Context c) {
        if (c == null || !Prefs.offlineModelEnabled(c)) return false;
        File f = file(c);
        return f.isFile() && f.length() == SIZE_BYTES;
    }

    public static boolean onWifi(Context c) {
        return transport(c, NetworkCapabilities.TRANSPORT_WIFI);
    }

    public static boolean online(Context c) {
        ConnectivityManager cm = manager(c);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    public static boolean downloadAllowed(Context c) {
        if (!online(c)) return false;
        return !Prefs.offlineModelWifiOnly(c) || onWifi(c);
    }

    /** Starts the first-run download when the model is enabled, missing, and the network allows it. */
    public static void maybeAutostart(Context c) {
        if (c == null || !Prefs.offlineModelEnabled(c) || !Prefs.offlineModelAutodownload(c)) return;
        if (file(c).isFile() && file(c).length() == SIZE_BYTES) return;
        if (!downloadAllowed(c)) return;
        startDownload(c);
    }

    public static void startDownload(Context c) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        Intent i = new Intent(app, LocalModelDownloadService.class);
        i.setAction(LocalModelDownloadService.ACTION_START);
        try {
            androidx.core.content.ContextCompat.startForegroundService(app, i);
        } catch (Exception ignored) {
            try { app.startService(i); } catch (Exception ignoredAgain) {}
        }
    }

    public static void cancelDownload(Context c) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        Intent i = new Intent(app, LocalModelDownloadService.class);
        i.setAction(LocalModelDownloadService.ACTION_CANCEL);
        try { app.startService(i); } catch (Exception ignored) {}
    }

    public static void delete(Context c) {
        cancelDownload(c);
        LocalLlmEngine.close();
        File f = file(c);
        File p = partial(c);
        if (f.exists() && !f.delete()) f.deleteOnExit();
        if (p.exists() && !p.delete()) p.deleteOnExit();
        publish(c, "Removed", 0);
    }

    public static String statusLine(Context c) {
        if (LocalModelDownloadService.isRunning()) {
            int pct = LocalModelDownloadService.percent();
            String err = LocalModelDownloadService.message();
            if (pct < 0) return err == null || err.isEmpty() ? "Downloading the offline model…" : err;
            return "Downloading the offline model… " + pct + "%";
        }
        String err = LocalModelDownloadService.message();
        if (file(c).isFile() && file(c).length() == SIZE_BYTES) {
            return DISPLAY + " is on this phone (1.60 GB). Commands stay here until Grok is needed.";
        }
        if (partial(c).isFile() && partial(c).length() > 0) {
            int pct = (int) Math.min(99, partial(c).length() * 100L / SIZE_BYTES);
            return "Download paused at " + pct + "%. " + (err == null ? "" : err);
        }
        if (err != null && !err.isEmpty()) return err;
        return "Not downloaded yet. About 1.60 GB, Wi-Fi unless you turn that limit off.";
    }

    static void publish(Context c, String message, int percent) {
        LocalModelDownloadService.note(message, percent);
    }

    private static boolean transport(Context c, int transport) {
        ConnectivityManager cm = manager(c);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasTransport(transport);
    }

    private static ConnectivityManager manager(Context c) {
        if (c == null) return null;
        Object s = c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        return s instanceof ConnectivityManager ? (ConnectivityManager) s : null;
    }
}
