package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;

import java.io.File;

/**
 * Small English speech model (Vosk small-en-us 0.15, about 39 MB).
 * Downloaded on first run next to the command model. Not bundled in the APK.
 */
public final class VoskModel {
    public static final String URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    public static final long SIZE_BYTES = 41_205_931L;
    public static final String FOLDER = "vosk-model-small-en-us-0.15";
    public static final String DISPLAY = "Vosk small English";

    private VoskModel() {}

    public static File root(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "groklink-speech");
        if (!d.isDirectory() && !d.mkdirs()) {
            // The downloader reports a failure if it still cannot create the folder.
        }
        return d;
    }

    public static File modelDir(Context c) {
        return new File(root(c), FOLDER);
    }

    public static File zip(Context c) {
        return new File(root(c), FOLDER + ".zip");
    }

    public static File partial(Context c) {
        return new File(root(c), FOLDER + ".zip.partial");
    }

    public static boolean ready(Context c) {
        if (c == null) return false;
        File dir = modelDir(c);
        return new File(dir, "conf/model.conf").isFile()
                || new File(dir, "am/final.mdl").isFile();
    }

    public static void maybeAutostart(Context c) {
        if (c == null || !Prefs.offlineModelEnabled(c) || !Prefs.offlineModelAutodownload(c)) return;
        if (ready(c)) return;
        if (!LocalModel.downloadAllowed(c)) return;
        startDownload(c);
    }

    public static void startDownload(Context c) {
        if (c == null || ready(c)) return;
        Context app = c.getApplicationContext();
        Intent i = new Intent(app, SpeechModelDownloadService.class);
        i.setAction(SpeechModelDownloadService.ACTION_START);
        try {
            androidx.core.content.ContextCompat.startForegroundService(app, i);
        } catch (Exception ignored) {
            try { app.startService(i); } catch (Exception ignoredAgain) {}
        }
    }

    public static void delete(Context c) {
        if (c == null) return;
        deleteTree(modelDir(c));
        File z = zip(c);
        File p = partial(c);
        if (z.exists() && !z.delete()) z.deleteOnExit();
        if (p.exists() && !p.delete()) p.deleteOnExit();
        SpeechModelDownloadService.note("Offline speech removed.", 0);
    }

    public static String statusLine(Context c) {
        if (SpeechModelDownloadService.isRunning()) {
            int pct = SpeechModelDownloadService.percent();
            if (pct < 0) {
                String msg = SpeechModelDownloadService.message();
                return msg == null || msg.isEmpty() ? "Downloading offline speech…" : msg;
            }
            return "Downloading offline speech… " + pct + "%";
        }
        if (ready(c)) return DISPLAY + " is on this phone (39 MB).";
        String err = SpeechModelDownloadService.message();
        if (partial(c).isFile() && partial(c).length() > 0) {
            int pct = (int) Math.min(99, partial(c).length() * 100L / SIZE_BYTES);
            return "Offline speech paused at " + pct + "%. " + (err == null ? "" : err);
        }
        if (err != null && !err.isEmpty() && !ready(c)) return err;
        return "Offline speech is not downloaded yet (about 39 MB).";
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] kids = file.listFiles();
        if (kids != null) for (File kid : kids) deleteTree(kid);
        if (!file.delete()) file.deleteOnExit();
    }
}
