package com.orbit.assistant;

import android.os.SystemClock;
import android.util.Log;

/**
 * Compact trace of one turn: speech engine, intent handler, action executor, each with a time.
 * Logged under {@code GrokLink/Trace}. The phone and Android Auto show the same line.
 */
public final class ProcessTrace {
    public static final String TAG = "GrokLink/Trace";
    public static final String VOSK = "Vosk offline";
    public static final String ON_DEVICE = "Android on-device";
    public static final String GOOGLE_OFFLINE = "Google offline";
    public static final String GOOGLE_ONLINE = "Google online";
    public static final String RULES = "Rules";
    public static final String QWEN = "Qwen 2.5 1.5B local";

    private static final Object LOCK = new Object();
    private static String speech = "";
    private static long speechMs = -1;
    private static long speechAt;
    private static long speechStarted;
    private static String handler = "";
    private static long handlerMs = -1;
    private static long handlerStarted;
    private static String executor = "";
    private static long executorMs = -1;
    private static long actionStarted;

    private ProcessTrace() {}

    /** A new listen. The clock stops when {@link #speech(String)} records the engine. */
    public static void beginSpeech() {
        synchronized (LOCK) {
            speechStarted = SystemClock.elapsedRealtime();
        }
    }

    public static void speech(String engine) {
        synchronized (LOCK) {
            speech = engine == null ? "" : engine;
            long now = SystemClock.elapsedRealtime();
            speechMs = speechStarted == 0 ? 0 : Math.max(0, now - speechStarted);
            speechAt = now;
            speechStarted = 0;
            logLocked();
            SessionTrace.speech(speech, speechMs < 0 ? "" : speechMs + " ms");
        }
    }

    /**
     * Start of a send. A voice result from the last couple of seconds keeps its speech step.
     * A typed send drops a stale one so the line describes this turn.
     */
    public static void prepareSubmit(boolean voice) {
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (!voice && (speechAt == 0 || now - speechAt > 2000L)) {
                speech = "";
                speechMs = -1;
            }
            handler = "";
            handlerMs = -1;
            executor = "";
            executorMs = -1;
            handlerStarted = now;
            actionStarted = 0;
        }
    }

    public static void handler(String name) {
        synchronized (LOCK) {
            setHandler(name);
            logLocked();
        }
    }

    public static void handlerIfEmpty(String name) {
        synchronized (LOCK) {
            if (!handler.isEmpty()) return;
            setHandler(name);
            logLocked();
        }
    }

    public static void beginAction() {
        synchronized (LOCK) {
            actionStarted = SystemClock.elapsedRealtime();
        }
    }

    public static void action(String name) {
        synchronized (LOCK) {
            if (name == null || name.isEmpty()) return;
            executor = name;
            long now = SystemClock.elapsedRealtime();
            executorMs = actionStarted == 0 ? 0 : Math.max(0, now - actionStarted);
            actionStarted = 0;
            logLocked();
        }
    }

    public static String line() {
        synchronized (LOCK) {
            return format();
        }
    }

    public static void log() {
        synchronized (LOCK) {
            logLocked();
        }
    }

    /** "Grok 4.7 (Extra High)" from the selection this turn actually used. */
    public static String grokLabel(AiSelection selection) {
        if (selection == null || selection.isAuto()) return "Grok";
        String name = selection.modelName();
        if (name == null || name.isEmpty()) name = "Grok";
        if (selection.strength != null && selection.strength.label != null
                && !selection.strength.label.isEmpty()) {
            return name + " (" + selection.strength.label + ")";
        }
        return name;
    }

    public static String engineLabel(boolean onDevice, boolean online) {
        if (onDevice) return ON_DEVICE;
        return online ? GOOGLE_ONLINE : GOOGLE_OFFLINE;
    }

    static void resetForTests() {
        synchronized (LOCK) {
            speech = "";
            speechMs = -1;
            speechAt = 0;
            speechStarted = 0;
            handler = "";
            handlerMs = -1;
            handlerStarted = 0;
            executor = "";
            executorMs = -1;
            actionStarted = 0;
        }
    }

    private static void setHandler(String name) {
        handler = name == null ? "" : name;
        long now = SystemClock.elapsedRealtime();
        handlerMs = handlerStarted == 0 ? 0 : Math.max(0, now - handlerStarted);
        handlerStarted = 0;
    }

    private static void logLocked() {
        String line = format();
        if (!line.isEmpty()) Log.i(TAG, line);
    }

    private static String format() {
        StringBuilder b = new StringBuilder();
        append(b, speech, speechMs);
        append(b, handler, handlerMs);
        append(b, executor, executorMs);
        return b.toString();
    }

    private static void append(StringBuilder b, String name, long ms) {
        if (name == null || name.isEmpty()) return;
        if (b.length() > 0) b.append(" · ");
        b.append(name);
        if (ms >= 0) b.append(' ').append(ms).append(" ms");
    }
}
