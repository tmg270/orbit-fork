package com.orbit.assistant;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns one on-device model response into a device action, or nothing.
 *
 * <p>The model is untrusted. Anything that is not one allowlisted action with in-range fields is
 * refused, and calls and texts are only stored for a spoken yes — they are not returned as
 * actions yet.
 */
public final class LocalIntent {
    private LocalIntent() {}

    public static AssistantReply fromModel(String raw) {
        JSONObject obj = json(raw);
        if (obj == null) return null;
        String type = obj.optString("type", "").trim().toUpperCase(Locale.US);
        if (type.isEmpty() || "NONE".equals(type)) return null;
        try {
            switch (type) {
                case "PLAY_MUSIC":
                    return music(obj);
                case "MEDIA_CONTROL":
                    return media(obj);
                case "SET_ALARM":
                    return alarm(obj);
                case "SET_TIMER":
                    return timer(obj);
                case "DIAL_CONTACT":
                case "DIAL":
                    return call(obj);
                case "SMS_CONTACT":
                case "SMS":
                    return text(obj);
                default:
                    return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static AssistantReply music(JSONObject obj) throws Exception {
        String query = clean(obj.optString("query", ""), 80);
        String artist = clean(obj.optString("artist", ""), 80);
        String album = clean(obj.optString("album", ""), 80);
        String title = clean(obj.optString("title", ""), 80);
        String app = clean(obj.optString("app", ""), 40);
        if (query.isEmpty()) query = join(title, artist, album);
        if (query.isEmpty()) return null;
        JSONObject params = new JSONObject().put("query", query);
        if (!artist.isEmpty()) params.put("artist", artist);
        if (!album.isEmpty()) params.put("album", album);
        if (!title.isEmpty()) params.put("title", title);
        if (!app.isEmpty()) params.put("app", app);
        String where = app.isEmpty() ? "" : " on " + app;
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(new AssistantReply.Action("PLAY_MUSIC", params, false));
        return new AssistantReply("Playing " + query + where + ".", actions);
    }

    private static AssistantReply media(JSONObject obj) throws Exception {
        String command = obj.optString("command", "").trim().toUpperCase(Locale.US);
        if ("SKIP".equals(command) || "NEXT_TRACK".equals(command)) command = "NEXT";
        if (!"PAUSE".equals(command) && !"PLAY".equals(command) && !"PLAY_PAUSE".equals(command)
                && !"NEXT".equals(command) && !"PREVIOUS".equals(command)) return null;
        String spoken = "PAUSE".equals(command) ? "Pausing playback."
                : "NEXT".equals(command) ? "Skipping to the next track."
                : "PREVIOUS".equals(command) ? "Going back a track."
                : "Resuming playback.";
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(new AssistantReply.Action("MEDIA_CONTROL",
                new JSONObject().put("command", command), false));
        return new AssistantReply(spoken, actions);
    }

    private static AssistantReply alarm(JSONObject obj) throws Exception {
        int hour = obj.optInt("hour", -1);
        int minute = obj.optInt("minute", -1);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(new AssistantReply.Action("SET_ALARM", new JSONObject()
                .put("hour", hour).put("minute", minute).put("label", "GrokLink alarm"), false));
        return new AssistantReply(String.format(Locale.US, "Setting an alarm for %02d:%02d.", hour, minute),
                actions);
    }

    private static AssistantReply timer(JSONObject obj) throws Exception {
        int seconds = obj.optInt("seconds", -1);
        if (seconds < 1 || seconds > 24 * 60 * 60) return null;
        String label = clean(obj.optString("label", ""), 24);
        if (label.isEmpty()) label = "GrokLink timer";
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(new AssistantReply.Action("SET_TIMER",
                new JSONObject().put("seconds", seconds).put("label", label), false));
        return new AssistantReply("Setting a timer.", actions);
    }

    private static AssistantReply call(JSONObject obj) {
        String number = digits(obj.optString("number", ""));
        String name = clean(obj.optString("name", ""), 40);
        AssistantReply.Action action;
        String who;
        if (!number.isEmpty()) {
            action = new AssistantReply.Action("DIAL", json("number", number), false);
            who = number;
        } else if (!name.isEmpty() && !rejectName(name)) {
            action = new AssistantReply.Action("DIAL_CONTACT", json("name", name), false);
            who = name;
        } else return null;
        return SpokenConfirm.ask(action, "Should I call " + who + "? Say yes or no.");
    }

    private static AssistantReply text(JSONObject obj) {
        String number = digits(obj.optString("number", ""));
        String name = clean(obj.optString("name", ""), 40);
        String body = clean(obj.optString("body", ""), 300);
        AssistantReply.Action action;
        String who;
        if (!number.isEmpty()) {
            action = new AssistantReply.Action("SMS", json("number", number).putBody(body), false);
            who = number;
        } else if (!name.isEmpty() && !rejectName(name)) {
            action = new AssistantReply.Action("SMS_CONTACT", json("name", name).putBody(body), false);
            who = name;
        } else return null;
        String saying = body.isEmpty() ? "" : " saying " + body;
        return SpokenConfirm.ask(action, "Should I text " + who + saying + "? Say yes or no.");
    }

    private static boolean rejectName(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.equals("me") || n.equals("you") || n.equals("it") || n.split("\\s+").length > 5;
    }

    private static String digits(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        int n = 0;
        for (int i = 0; i < t.length(); i++) if (Character.isDigit(t.charAt(i))) n++;
        if (n < 5 || n > 16) return "";
        if (!t.matches("[+0-9() \\-]{5,24}")) return "";
        return t;
    }

    private static String clean(String raw, int max) {
        if (raw == null) return "";
        String t = raw.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > max) t = t.substring(0, max).trim();
        return t;
    }

    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(p);
        }
        return b.toString();
    }

    private static Params json(String key, String value) {
        return new Params().put(key, value);
    }

    private static JSONObject jsonObject(String raw) {
        return json(raw);
    }

    private static JSONObject json(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONObject(s.substring(start, end + 1));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** JSONObject wrapper so a body can be added without a checked exception at the call site. */
    private static final class Params extends JSONObject {
        Params put(String key, String value) {
            try { super.put(key, value); } catch (Exception ignored) {}
            return this;
        }
        Params putBody(String body) {
            if (body != null && !body.isEmpty()) put("body", body);
            return this;
        }
    }
}
