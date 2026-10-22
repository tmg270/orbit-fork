package com.orbit.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import androidx.core.app.NotificationCompat;

/** Downloads and unpacks the Vosk English model. Resume uses HTTP Range, same Wi-Fi gate as the command model. */
public class SpeechModelDownloadService extends Service {
    public static final String ACTION_START = "com.orbit.assistant.speech.START";
    public static final String ACTION_CANCEL = "com.orbit.assistant.speech.CANCEL";
    private static final String CHANNEL = "groklink_speech_download";
    private static final int NOTIF = 7042;

    private static volatile boolean running;
    private static volatile int percent = -1;
    private static volatile String message = "";
    private volatile boolean cancel;

    public static boolean isRunning() { return running; }
    public static int percent() { return percent; }
    public static String message() { return message == null ? "" : message; }

    static void note(String text, int pct) {
        message = text == null ? "" : text;
        percent = pct;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancel = true;
            note("Speech download cancelled.", percent);
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        running = true;
        cancel = false;
        startInForeground("Downloading offline speech…");
        new Thread(this::download, "groklink-speech-download").start();
        return START_NOT_STICKY;
    }

    private void download() {
        HttpURLConnection conn = null;
        try {
            if (VoskModel.ready(this)) {
                note(VoskModel.DISPLAY + " is ready.", 100);
                return;
            }
            if (!LocalModel.downloadAllowed(this)) {
                note(Prefs.offlineModelWifiOnly(this)
                        ? "Waiting for Wi-Fi to download offline speech."
                        : "No connection to download offline speech.", -1);
                return;
            }
            File partial = VoskModel.partial(this);
            File zip = VoskModel.zip(this);
            long have = partial.isFile() ? partial.length() : 0L;
            if (have < 0 || have >= VoskModel.SIZE_BYTES) {
                if (partial.exists() && !partial.delete()) partial.deleteOnExit();
                have = 0L;
            }
            conn = open(have);
            int code = conn.getResponseCode();
            if (have > 0 && code == 200) {
                conn.disconnect();
                if (!partial.delete()) partial.deleteOnExit();
                have = 0L;
                conn = open(0);
                code = conn.getResponseCode();
            }
            if (code != 200 && code != 206) {
                note("The speech download was refused (" + code + ").", -1);
                return;
            }
            long total = have;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(partial, have > 0 && code == 206)) {
                byte[] buf = new byte[65536];
                int n;
                long lastNote = 0L;
                while ((n = in.read(buf)) > 0) {
                    if (cancel) {
                        note("Speech download cancelled.", pct(total));
                        return;
                    }
                    if (Prefs.offlineModelWifiOnly(this) && !LocalModel.onWifi(this)) {
                        note("Waiting for Wi-Fi. Speech download will resume.", pct(total));
                        return;
                    }
                    out.write(buf, 0, n);
                    total += n;
                    if (total - lastNote > 500_000L) {
                        lastNote = total;
                        int p = pct(total);
                        note("Downloading offline speech… " + p + "%", p);
                        startInForeground("Offline speech " + p + "%");
                    }
                }
            }
            if (zip.exists() && !zip.delete()) zip.deleteOnExit();
            if (!partial.renameTo(zip)) {
                note("Offline speech downloaded but could not be saved.", -1);
                return;
            }
            note("Unpacking offline speech…", 99);
            unzip(zip, VoskModel.root(this));
            if (!VoskModel.ready(this)) {
                note("Offline speech unpacked but the model was not in the archive.", -1);
                return;
            }
            if (zip.exists() && !zip.delete()) zip.deleteOnExit();
            note(VoskModel.DISPLAY + " is ready.", 100);
        } catch (Exception e) {
            note("Speech download paused: " + (e.getMessage() == null ? "network error" : e.getMessage()), percent);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
            running = false;
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private HttpURLConnection open(long have) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(VoskModel.URL).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "GrokLink");
        if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
        conn.connect();
        return conn;
    }

    private static void unzip(File zip, File dest) throws Exception {
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new IllegalStateException("could not create " + dest);
        }
        String root = dest.getCanonicalPath();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buf = new byte[65536];
            while ((entry = in.getNextEntry()) != null) {
                File out = new File(dest, entry.getName());
                String path = out.getCanonicalPath();
                if (!path.equals(root) && !path.startsWith(root + File.separator)) {
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

    private static int pct(long have) {
        if (VoskModel.SIZE_BYTES <= 0) return 0;
        return (int) Math.max(0, Math.min(99, have * 100L / VoskModel.SIZE_BYTES));
    }

    private void startInForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Offline speech",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("GrokLink")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF, notification);
        }
    }
}
