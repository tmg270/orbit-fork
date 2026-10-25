package com.orbit.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import androidx.core.app.NotificationCompat;

/**
 * First-run download of the on-device model, with progress, resume, and a SHA-256 check.
 * The partial file is kept if the download stops, and only a matching file is promoted.
 */
public class LocalModelDownloadService extends Service {
    public static final String ACTION_START = "com.orbit.assistant.local.START";
    public static final String ACTION_CANCEL = "com.orbit.assistant.local.CANCEL";
    private static final String CHANNEL = "groklink_model_download";
    private static final int NOTIF = 7041;

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
            note("Download cancelled. You can resume it from Settings.", percent);
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        running = true;
        cancel = false;
        startInForeground("Downloading the offline model…");
        new Thread(this::download, "groklink-model-download").start();
        return START_NOT_STICKY;
    }

    private void download() {
        HttpURLConnection conn = null;
        try {
            if (!LocalModel.downloadAllowed(this)) {
                note(Prefs.offlineModelWifiOnly(this)
                        ? "Waiting for Wi-Fi to download the offline model."
                        : "No connection to download the offline model.", -1);
                return;
            }
            File dir = LocalModel.dir(this);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                note("GrokLink could not create a folder for the offline model.", -1);
                return;
            }
            File partial = LocalModel.partial(this);
            File done = LocalModel.file(this);
            if (done.isFile() && done.length() == LocalModel.SIZE_BYTES) {
                note(LocalModel.DISPLAY + " is ready.", 100);
                return;
            }
            long have = partial.isFile() ? partial.length() : 0L;
            if (have < 0 || have >= LocalModel.SIZE_BYTES) {
                if (partial.exists() && !partial.delete()) partial.deleteOnExit();
                have = 0L;
            }
            conn = (HttpURLConnection) new URL(LocalModel.URL).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "GrokLink");
            if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
            conn.connect();
            int code = conn.getResponseCode();
            if (have > 0 && code == 200) {
                // The server ignored the range. Start over so the hash stays honest.
                conn.disconnect();
                if (!partial.delete()) partial.deleteOnExit();
                have = 0L;
                conn = (HttpURLConnection) new URL(LocalModel.URL).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "GrokLink");
                conn.connect();
                code = conn.getResponseCode();
            }
            if (code != 200 && code != 206) {
                note("The model download was refused (" + code + "). Try again from Settings.", -1);
                return;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (have > 0 && partial.isFile()) {
                try (InputStream in = new java.io.FileInputStream(partial)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
                }
            }
            long total = have;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(partial, have > 0 && code == 206)) {
                byte[] buf = new byte[65536];
                int n;
                long lastNote = 0L;
                while ((n = in.read(buf)) > 0) {
                    if (cancel) {
                        note("Download cancelled. You can resume it from Settings.", pct(total));
                        return;
                    }
                    if (Prefs.offlineModelWifiOnly(this) && !LocalModel.onWifi(this)) {
                        note("Waiting for Wi-Fi. The download will resume.", pct(total));
                        return;
                    }
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    total += n;
                    if (total - lastNote > 1_500_000L) {
                        lastNote = total;
                        int p = pct(total);
                        note("Downloading the offline model… " + p + "%", p);
                        startInForeground("Offline model " + p + "%");
                    }
                }
            }
            if (total != LocalModel.SIZE_BYTES) {
                note("The download stopped early (" + total + " bytes). It will resume.", pct(total));
                return;
            }
            String hash = hex(digest.digest());
            if (!LocalModel.SHA256.equalsIgnoreCase(hash)) {
                if (partial.exists() && !partial.delete()) partial.deleteOnExit();
                note("The downloaded model did not match its checksum, so it was discarded.", -1);
                return;
            }
            if (done.exists() && !done.delete()) done.deleteOnExit();
            if (!partial.renameTo(done)) {
                note("The model downloaded but could not be saved into place.", -1);
                return;
            }
            note(LocalModel.DISPLAY + " is ready.", 100);
            startInForeground("Offline model ready");
        } catch (Exception e) {
            note("Download paused: " + (e.getMessage() == null ? "network error" : e.getMessage()), percent);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
            running = false;
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private static int pct(long have) {
        if (LocalModel.SIZE_BYTES <= 0) return 0;
        return (int) Math.max(0, Math.min(99, have * 100L / LocalModel.SIZE_BYTES));
    }

    private static String hex(byte[] raw) {
        StringBuilder b = new StringBuilder(raw.length * 2);
        for (byte value : raw) b.append(String.format(Locale.US, "%02x", value));
        return b.toString();
    }

    private void startInForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Offline model",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Progress while GrokLink downloads the on-device model.");
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
