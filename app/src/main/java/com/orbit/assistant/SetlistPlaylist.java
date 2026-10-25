package com.orbit.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When Grok has looked a setlist up and written the songs, but did not return a playlist action,
 * build one from the list in the answer. Songs are taken only from that answer.
 */
final class SetlistPlaylist {
    private static final Pattern LINE = Pattern.compile(
            "^(?:\\d{1,2}[.)]|[-*•])\\s+(.{2,80})$");

    private SetlistPlaylist() {}

    static AssistantReply attach(String user, AssistantReply reply) {
        if (reply == null || !wantsPlaylist(user) || hasPlaylist(reply)) return reply;
        JSONArray tracks = tracksFrom(reply.text);
        if (tracks.length() < 3) return reply;
        try {
            JSONObject params = new JSONObject();
            params.put("name", nameFrom(user));
            params.put("tracks", tracks);
            params.put("app", "Poweramp");
            List<AssistantReply.Action> actions = new ArrayList<>();
            if (reply.actions != null) actions.addAll(reply.actions);
            actions.add(new AssistantReply.Action("PLAY_PLAYLIST", params, false));
            return new AssistantReply(reply.text, actions);
        } catch (Exception ignored) {
            return reply;
        }
    }

    static boolean wantsPlaylist(String user) {
        if (user == null) return false;
        String q = user.toLowerCase(Locale.US);
        return q.contains("setlist") || q.contains("set list") || q.contains("playlist")
                || q.contains("their set") || q.contains("the set ") || q.contains("play the songs");
    }

    private static boolean hasPlaylist(AssistantReply reply) {
        if (reply.actions == null) return false;
        for (AssistantReply.Action action : reply.actions) {
            if (action == null || action.type == null) continue;
            if ("PLAY_PLAYLIST".equals(action.type)) return true;
        }
        return false;
    }

    static JSONArray tracksFrom(String text) {
        JSONArray tracks = new JSONArray();
        if (text == null) return tracks;
        for (String raw : text.split("\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.toLowerCase(Locale.US).startsWith("source:")) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String body = m.group(1).trim();
            if (body.contains("http") || body.length() > 80) continue;
            String title = body;
            String artist = "";
            int by = indexOfSeparator(body);
            if (by > 0) {
                title = body.substring(0, by).trim();
                artist = body.substring(by).replaceFirst("^(?:\\s*[-|\\u2013\\u2014]\\s*|\\s+by\\s+)", "").trim();
            }
            title = title.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
            if (title.length() < 2) continue;
            try {
                JSONObject one = new JSONObject().put("title", title);
                if (!artist.isEmpty()) one.put("artist", artist);
                tracks.put(one);
            } catch (Exception ignored) {}
        }
        return tracks;
    }

    private static int indexOfSeparator(String body) {
        int by = lowerIndex(body, " by ");
        int dash = body.indexOf(" - ");
        int pipe = body.indexOf(" | ");
        int en = body.indexOf(" \u2013 ");
        int em = body.indexOf(" \u2014 ");
        int best = -1;
        for (int i : new int[] {by, dash, pipe, en, em}) {
            if (i > 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static int lowerIndex(String body, String token) {
        return body.toLowerCase(Locale.US).indexOf(token);
    }

    private static String nameFrom(String user) {
        String q = user == null ? "" : user.replaceAll("\\s+", " ").trim();
        if (q.length() > 60) q = q.substring(0, 60).trim();
        return q.isEmpty() ? "GrokLink playlist" : q;
    }
}
