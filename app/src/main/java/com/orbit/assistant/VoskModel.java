package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Small British English speech model (Vosk small-en-gb 0.15, about 41 MB).
 *
 * <p>The zip is shipped in the APK ({@code assets/speech/}) and unpacked on first launch, so Vosk
 * is ready with no download. A phone that somehow has no asset can still fetch the same zip.
 * Apache 2.0, same terms as the other small Vosk models.
 */
public final class VoskModel {
    public static final String URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-gb-0.15.zip";
    public static final long SIZE_BYTES = 42_757_500L;
    public static final String FOLDER = "vosk-model-small-en-gb-0.15";
    public static final String ASSET = "speech/" + FOLDER + ".zip";
    public static final String DISPLAY = "Vosk small English (UK)";
    /** Replaced by the bundled en-GB model. Removed after a successful unpack. */
    private static final String SUPERSEDED = "vosk-model-small-en-us-0.15";

    private static final Object INSTALL = new Object();
    private static final AtomicBoolean INSTALL_QUEUED = new AtomicBoolean();
    private static volatile boolean unpacking;
    private static volatile Boolean unitTest;

    private VoskModel() {}

    public static File root(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "groklink-speech");
        if (!d.isDirectory() && !d.mkdirs()) {
            // The installer reports a failure if it still cannot create the folder.
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
        return c != null && installed(modelDir(c));
    }

    /** Starts unpacking the copy shipped in the APK. No-op once it is on disk, and in unit tests. */
    public static void installBundledAsync(Context c) {
        if (c == null || ready(c) || isUnitTestRuntime()) return;
        if (!INSTALL_QUEUED.compareAndSet(false, true)) return;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = ensureInstalled(app);
            } finally {
                if (!ok) INSTALL_QUEUED.set(false);
            }
        }, "groklink-speech-unpack").start();
    }

    /**
     * Unpacks the bundled model if it is not already on disk.
     *
     * @return true when {@link #ready} is true afterwards
     */
    public static boolean ensureInstalled(Context c) {
        if (ready(c)) return true;
        if (c == null || isUnitTestRuntime()) return false;
        synchronized (INSTALL) {
            if (ready(c)) return true;
            unpacking = true;
            File staging = new File(root(c), ".speech-staging");
            try {
                deleteTree(staging);
                if (!staging.isDirectory() && !staging.mkdirs()) return false;
                Context app = c.getApplicationContext();
                try (InputStream in = openAsset(app)) {
                    unpack(in, staging);
                }
                File unpacked = new File(staging, FOLDER);
                if (!installed(unpacked)) {
                    deleteTree(staging);
                    return false;
                }
                File dest = modelDir(c);
                if (dest.exists()) deleteTree(dest);
                if (!unpacked.renameTo(dest)) return false;
                deleteTree(staging);
                if (!SUPERSEDED.equals(FOLDER)) deleteTree(new File(root(c), SUPERSEDED));
                return ready(c);
            } catch (Throwable ignored) {
                deleteTree(staging);
                return false;
            } finally {
                unpacking = false;
            }
        }
    }

    public static void maybeAutostart(Context c) {
        if (c == null || ready(c) || isUnitTestRuntime()) return;
        if (hasBundledAsset(c)) {
            installBundledAsync(c);
            return;
        }
        if (!Prefs.offlineModelEnabled(c) || !Prefs.offlineModelAutodownload(c)) return;
        if (!LocalModel.downloadAllowed(c)) return;
        startDownload(c);
    }

    public static void startDownload(Context c) {
        if (c == null || ready(c)) return;
        if (!isUnitTestRuntime() && hasBundledAsset(c)) {
            installBundledAsync(c);
            return;
        }
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
        if (unpacking) return "Unpacking offline speech…";
        if (SpeechModelDownloadService.isRunning()) {
            int pct = SpeechModelDownloadService.percent();
            if (pct < 0) {
                String msg = SpeechModelDownloadService.message();
                return msg == null || msg.isEmpty() ? "Downloading offline speech…" : msg;
            }
            return "Downloading offline speech… " + pct + "%";
        }
        if (ready(c)) return DISPLAY + " is on this phone (41 MB).";
        if (c != null && hasBundledAsset(c)) return "Offline speech is included with GrokLink.";
        String err = SpeechModelDownloadService.message();
        if (partial(c).isFile() && partial(c).length() > 0) {
            int pct = (int) Math.min(99, partial(c).length() * 100L / SIZE_BYTES);
            return "Offline speech paused at " + pct + "%. " + (err == null ? "" : err);
        }
        if (err != null && !err.isEmpty() && !ready(c)) return err;
        return "Offline speech is not downloaded yet (about 41 MB).";
    }

    /** Unzips {@code zip} into {@code dest}. An entry that escapes {@code dest} is refused. */
    static void unpack(File zip, File dest) throws Exception {
        try (InputStream in = new FileInputStream(zip)) {
            unpack(in, dest);
        }
    }

    static void unpack(InputStream raw, File dest) throws Exception {
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new IllegalStateException("could not create " + dest);
        }
        String rootPath = dest.getCanonicalPath();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            byte[] buf = new byte[65536];
            while ((entry = in.getNextEntry()) != null) {
                File out = new File(dest, entry.getName());
                String path = out.getCanonicalPath();
                if (!path.equals(rootPath) && !path.startsWith(rootPath + File.separator)) {
                    throw new IllegalStateException("zip entry escapes the speech folder");
                }
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) {
                        throw new IllegalStateException("could not create " + out);
                    }
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IllegalStateException("could not create " + parent);
                    }
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
                in.closeEntry();
            }
        }
    }

    private static boolean installed(File dir) {
        return dir != null && (new File(dir, "conf/model.conf").isFile()
                || new File(dir, "am/final.mdl").isFile());
    }

    static boolean hasBundledAsset(Context c) {
        if (c == null) return false;
        try {
            String[] names = c.getApplicationContext().getAssets().list("speech");
            if (names == null) return false;
            String file = FOLDER + ".zip";
            for (String name : names) if (file.equals(name)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static InputStream openAsset(Context app) throws Exception {
        try {
            return app.getAssets().open(ASSET, AssetManager.ACCESS_STREAMING);
        } catch (Throwable ignored) {
            return app.getAssets().open(ASSET);
        }
    }

    /** Unit tests must not unzip the 41 MB asset into every Robolectric process. */
    private static boolean isUnitTestRuntime() {
        Boolean cached = unitTest;
        if (cached != null) return cached;
        String fingerprint = Build.FINGERPRINT;
        boolean yes = fingerprint != null
                && fingerprint.toLowerCase(Locale.US).contains("robolectric");
        if (!yes) {
            try {
                Class.forName("org.robolectric.RuntimeEnvironment");
                yes = true;
            } catch (Throwable ignored) {
                yes = false;
            }
        }
        unitTest = yes;
        return yes;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] kids = file.listFiles();
        if (kids != null) for (File kid : kids) deleteTree(kid);
        if (!file.delete()) file.deleteOnExit();
    }
}
