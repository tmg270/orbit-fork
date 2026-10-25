package com.orbit.assistant;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * One session's processing trace for a bug report: conversation, Grok calls, local decisions,
 * speech attempts and device actions. Secrets are stripped before anything is kept.
 */
public final class SessionTrace {
    public static final int MAX_EVENTS = 400;
    private static final int MAX_EVENT = 4000;
    private static final Object LOCK = new Object();
    private static final ArrayList<String> EVENTS = new ArrayList<>();
    private static File file;

    private SessionTrace() {}

    /** Remembers where to append a copy that survives the in-memory ring. */
    public static void attach(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            file = new File(context.getApplicationContext().getFilesDir(), "groklink-session.log");
        }
    }

    public static void conversation(String role, String text) {
        add((role == null ? "turn" : role) + ": " + clip(text));
    }

    public static void api(String summary) {
        add("api: " + clip(summary));
    }

    public static void local(String router, String detail) {
        add("local: " + (router == null ? "" : router) + (detail == null || detail.isEmpty() ? "" : " · " + clip(detail)));
    }

    public static void speech(String engine, String detail) {
        String line = "speech: " + (engine == null ? "" : engine);
        if (detail != null && !detail.isEmpty()) line += " · " + clip(detail);
        add(line);
    }

    public static void action(String type, boolean success, String message) {
        add("action: " + (type == null ? "" : type) + " · " + (success ? "ok" : "failed")
                + (message == null || message.isEmpty() ? "" : " · " + clip(message)));
    }

    public static void note(String text) {
        add("note: " + clip(text));
    }

    /** The trace, oldest first. Empty when nothing has been recorded. */
    public static String dump() {
        synchronized (LOCK) {
            StringBuilder b = new StringBuilder();
            for (String event : EVENTS) {
                if (b.length() > 0) b.append('\n');
                b.append(event);
            }
            return b.toString();
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            EVENTS.clear();
            file = null;
        }
    }

    /** Drops bearer tokens, API keys and long hex secrets. The rest of the line stays. */
    static String redact(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw;
        s = s.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._\\-+/=]{8,}", "$1[redacted]");
        s = s.replaceAll("(?i)(authorization\\s*[:=]\\s*)[^\\s,;\"]+", "$1[redacted]");
        s = s.replaceAll("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|github[_-]?token)\"?\\s*[:=]\\s*\"?)[^\\s,\"']+", "$1[redacted]");
        s = s.replaceAll("\\b(?:sk|xai|ghp|github_pat)_[A-Za-z0-9_\\-]{8,}", "[redacted]");
        return s;
    }

    private static void add(String line) {
        String safe = redact(line == null ? "" : line);
        if (safe.length() > MAX_EVENT) safe = safe.substring(0, MAX_EVENT) + "…";
        synchronized (LOCK) {
            EVENTS.add(safe);
            while (EVENTS.size() > MAX_EVENTS) EVENTS.remove(0);
            File dest = file;
            if (dest == null) return;
            try (FileOutputStream out = new FileOutputStream(dest, true)) {
                out.write((safe + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // The in-memory ring is still the report. A full disk must not break a turn.
            }
        }
    }

    private static String clip(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() > MAX_EVENT ? flat.substring(0, MAX_EVENT) + "…" : flat;
    }

    static String deviceLine() {
        return android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " · Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")";
    }

    static String versionLine(Context context) {
        String name = BuildConfig.VERSION_NAME;
        int code = BuildConfig.VERSION_CODE;
        try {
            if (context != null) {
                android.content.pm.PackageInfo info = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0);
                if (info.versionName != null && !info.versionName.isEmpty()) name = info.versionName;
                code = info.versionCode;
            }
        } catch (Exception ignored) {}
        String pkg = context == null ? "" : context.getPackageName();
        return name + " (" + code + ")" + (pkg.isEmpty() ? "" : " · " + pkg);
    }

    /** Recent logcat for this app. Empty output is reported rather than invented. */
    static String logcat() {
        Process process = null;
        try {
            process = new ProcessBuilder("logcat", "-d", "-t", "250", "-v", "time")
                    .redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = process.getInputStream().read(buf)) >= 0 && raw.size() < 40_000) {
                raw.write(buf, 0, n);
            }
            process.waitFor();
            String text = raw.toString(StandardCharsets.UTF_8.name()).trim();
            if (text.isEmpty()) return "(logcat returned nothing)";
            return redact(text);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "(logcat unavailable: " + message + ")";
        } finally {
            if (process != null) process.destroy();
        }
    }

    static String stamp() {
        return String.format(Locale.US, "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS", System.currentTimeMillis());
    }
}
