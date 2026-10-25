package com.orbit.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Holds a microphone foreground service while Vosk records.
 *
 * <p>Android Auto runs GrokLink as a media browser in the background. On Android 14 and later a
 * background process cannot open the microphone unless a foreground service of type microphone
 * is running. Google's speech recognizer is not used for this; the service only keeps the mic
 * legal for the on-device recogniser.
 */
public final class MicCaptureService extends Service {
    static final String CHANNEL = "groklink_listening";
    private static final int NOTE = 7041;
    private static final Object LOCK = new Object();
    private static int holders;

    public static void acquire(Context context) {
        if (context == null) return;
        synchronized (LOCK) { holders++; }
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, MicCaptureService.class);
        try {
            androidx.core.content.ContextCompat.startForegroundService(app, intent);
        } catch (Exception e) {
            SessionTrace.note("microphone service not started: " + e.getClass().getSimpleName());
        }
    }

    public static void release(Context context) {
        if (context == null) return;
        boolean stop;
        synchronized (LOCK) {
            if (holders > 0) holders--;
            stop = holders == 0;
        }
        if (!stop) return;
        Context app = context.getApplicationContext();
        try { app.stopService(new Intent(app, MicCaptureService.class)); }
        catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startInForeground();
        } catch (Exception e) {
            SessionTrace.note("microphone foreground failed: " + e.getClass().getSimpleName());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        Notification note = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTE, note);
        }
    }

    private Notification notification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "Listening",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Shown while GrokLink is listening on device.");
                manager.createNotificationChannel(channel);
            }
            return new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("GrokLink")
                    .setContentText("Listening")
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("GrokLink")
                .setContentText("Listening")
                .build();
    }
}
