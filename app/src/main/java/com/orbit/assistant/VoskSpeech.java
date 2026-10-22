package com.orbit.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * On-device recogniser that does not use Google. Loaded only when a listen actually starts,
 * so unit tests never touch the native library.
 */
public final class VoskSpeech {
    public interface Listener {
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static Object model;
    private static Object service;
    private static int generation;

    private VoskSpeech() {}

    public static void cancel() {
        synchronized (LOCK) {
            generation++;
            // Return before any org.vosk reference. stop() and destroy() call this with no
            // session, including from unit tests, and loading the native library there crashes.
            if (service == null) return;
            stopService(true);
        }
    }

    public static void listen(Context context, Listener listener) {
        if (context == null || listener == null) return;
        if (!VoskModel.ready(context)) {
            listener.onError("offline speech model is not downloaded");
            return;
        }
        final Context app = context.getApplicationContext();
        final int gen;
        synchronized (LOCK) {
            gen = ++generation;
            stopService(true);
        }
        new Thread(() -> run(app, listener, gen), "groklink-vosk").start();
    }

    private static void run(Context app, Listener listener, int gen) {
        try {
            org.vosk.Model loaded;
            synchronized (LOCK) {
                if (gen != generation) return;
                if (!(model instanceof org.vosk.Model)) {
                    model = new org.vosk.Model(VoskModel.modelDir(app).getAbsolutePath());
                }
                loaded = (org.vosk.Model) model;
            }
            org.vosk.Recognizer recognizer = new org.vosk.Recognizer(loaded, 16000.0f);
            org.vosk.android.SpeechService speech = new org.vosk.android.SpeechService(recognizer, 16000.0f);
            synchronized (LOCK) {
                if (gen != generation) {
                    speech.shutdown();
                    return;
                }
                service = speech;
            }
            speech.startListening(new org.vosk.android.RecognitionListener() {
                @Override public void onPartialResult(String hypothesis) {
                    if (!current(gen)) return;
                    String text = field(hypothesis, "partial");
                    if (!text.isEmpty()) MAIN.post(() -> listener.onPartial(text));
                }

                @Override public void onResult(String hypothesis) {
                    deliver(gen, hypothesis, listener);
                }

                @Override public void onFinalResult(String hypothesis) {
                    deliver(gen, hypothesis, listener);
                }

                @Override public void onError(Exception e) {
                    if (!current(gen)) return;
                    String message = e == null || e.getMessage() == null ? "Vosk error" : e.getMessage();
                    MAIN.post(() -> listener.onError(message));
                }

                @Override public void onTimeout() {
                    if (!current(gen)) return;
                    MAIN.post(() -> listener.onFinal(""));
                }
            });
        } catch (Throwable t) {
            if (!current(gen)) return;
            String message = t.getMessage() == null ? "Vosk could not start" : t.getMessage();
            MAIN.post(() -> listener.onError(message));
        }
    }

    private static void deliver(int gen, String hypothesis, Listener listener) {
        if (!current(gen)) return;
        String text = field(hypothesis, "text");
        if (text.isEmpty()) return;
        MAIN.post(() -> {
            if (!current(gen)) return;
            listener.onFinal(text);
        });
    }

    private static boolean current(int gen) {
        synchronized (LOCK) {
            return gen == generation;
        }
    }

    private static void stopService(boolean shutdown) {
        Object running = service;
        service = null;
        if (!(running instanceof org.vosk.android.SpeechService)) return;
        org.vosk.android.SpeechService speech = (org.vosk.android.SpeechService) running;
        try {
            if (shutdown) speech.cancel();
            else speech.stop();
        } catch (Throwable ignored) {}
        try { speech.shutdown(); } catch (Throwable ignored) {}
    }

    static String field(String json, String key) {
        if (json == null || json.isEmpty()) return "";
        try {
            return new JSONObject(json).optString(key, "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
