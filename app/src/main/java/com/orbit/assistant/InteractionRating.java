package com.orbit.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thumbs on a turn. The rating is saved under Downloads/GrokLink and, when a GitHub token is
 * already stored, filed as an issue. The token never enters the rating, the file, or the APK.
 *
 * <p>A thumbs-up on a turn that actually ran device actions is remembered for that exact phrase.
 * The next time, those actions run locally instead of asking Grok again. A thumbs-down forgets
 * that phrase. Built-in commands are unchanged either way.
 */
public final class InteractionRating {
    private static final String FILE = "groklink-learned-actions.json";
    private static final Object LOCK = new Object();

    private InteractionRating() {}

    public static void record(Context context, String user, String assistant, boolean up,
                              List<AssistantReply.Action> actions) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        String phrase = user == null ? "" : user.trim();
        String reply = assistant == null ? "" : assistant.trim();
        String key = key(phrase);
        JSONArray actionJson = actionsJson(actions);
        if (!key.isEmpty()) {
            synchronized (LOCK) {
                JSONObject learned = read(app);
                if (up && actionJson.length() > 0) {
                    JSONObject row = new JSONObject();
                    try {
                        row.put("text", reply);
                        row.put("actions", actionJson);
                        learned.put(key, row);
                        write(app, learned);
                    } catch (Exception ignored) {}
                } else if (!up && learned.has(key)) {
                    learned.remove(key);
                    write(app, learned);
                }
            }
        }
        String body = body(phrase, reply, up, actionJson);
        new Thread(() -> {
            BugReport.writeDownload(app, fileName(System.currentTimeMillis()), body);
            String title = "GrokLink rating: thumbs " + (up ? "up" : "down");
            BugReport.postIssue(app, title, body);
        }, "groklink-rating").start();
    }

    /** A remembered action plan for this exact phrase, or null. */
    public static AssistantReply recall(Context context, String user) {
        if (context == null) return null;
        String key = key(user);
        if (key.isEmpty()) return null;
        synchronized (LOCK) {
            JSONObject learned = read(context.getApplicationContext());
            JSONObject row = learned.optJSONObject(key);
            if (row == null) return null;
            List<AssistantReply.Action> actions = actions(row.optJSONArray("actions"));
            if (actions.isEmpty()) return null;
            String text = row.optString("text", "");
            if (text.isEmpty()) text = "Doing that again.";
            return new AssistantReply(text, actions);
        }
    }

    static String key(String raw) {
        return LanguageNormalizer.stripPoliteness(LanguageNormalizer.canonical(raw));
    }

    static String body(String user, String assistant, boolean up, JSONArray actions) {
        StringBuilder b = new StringBuilder();
        b.append("## Rating\n\n").append(up ? "thumbs up" : "thumbs down").append("\n\n");
        b.append("## User\n\n").append(user == null || user.isEmpty() ? "(empty)" : user).append("\n\n");
        b.append("## Assistant\n\n").append(assistant == null || assistant.isEmpty() ? "(empty)" : assistant)
                .append("\n\n");
        b.append("## Actions\n\n");
        b.append(actions == null ? "[]" : actions.toString()).append("\n");
        return SessionTrace.redact(b.toString());
    }

    static JSONArray actionsJson(List<AssistantReply.Action> actions) {
        JSONArray out = new JSONArray();
        if (actions == null) return out;
        for (AssistantReply.Action action : actions) {
            if (action == null || action.type == null || action.type.isEmpty()) continue;
            JSONObject row = new JSONObject();
            try {
                row.put("type", action.type);
                row.put("params", action.params == null ? new JSONObject() : action.params);
                out.put(row);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static List<AssistantReply.Action> actions(JSONArray raw) {
        List<AssistantReply.Action> out = new ArrayList<>();
        if (raw == null) return out;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject row = raw.optJSONObject(i);
            if (row == null) continue;
            String type = row.optString("type", "");
            if (type.isEmpty()) continue;
            JSONObject params = row.optJSONObject("params");
            out.add(new AssistantReply.Action(type, params == null ? new JSONObject() : params, false));
        }
        return out;
    }

    private static String fileName(long when) {
        return String.format(Locale.US, "rating-%1$tY%1$tm%1$td-%1$tH%1$tM%1$tS.txt", when);
    }

    private static JSONObject read(Context context) {
        try {
            java.io.File file = new java.io.File(context.getFilesDir(), FILE);
            if (!file.isFile()) return new JSONObject();
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void write(Context context, JSONObject learned) {
        try {
            java.io.File file = new java.io.File(context.getFilesDir(), FILE);
            java.nio.file.Files.write(file.toPath(),
                    learned.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }
}
