package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which device actions the car (Android Auto) may run, and how. Pure logic so it can be unit tested.
 *
 * <p>SAFE actions run straight away (alarms, timers, reminders, calendar writes, media and volume).
 * CONFIRM actions call or message someone and need a spoken "yes" first. PHONE actions open a screen
 * on the phone, which Android may block from the background, so the reply says to check the phone.
 * Everything else is dropped in the car.
 */
final class CarActions {
    private CarActions() {}

    enum Kind { SAFE, CONFIRM, PHONE, DROP }

    static final Set<String> SAFE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "SET_ALARM", "SET_TIMER", "SET_REMINDER", "ADD_CALENDAR_EVENTS", "MEDIA_CONTROL",
            "SET_VOLUME", "SET_DND", "SET_RINGER_MODE", "PLAY_MUSIC", "PLAY_PLAYLIST", "NAVIGATE")));
    static final Set<String> CONFIRM = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "DIAL", "DIAL_CONTACT", "SMS", "SMS_CONTACT")));
    static final Set<String> PHONE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "CREATE_EVENT", "OPEN_APP")));

    static Kind kindOf(String type) {
        String t = type == null ? "" : type.toUpperCase(Locale.US);
        if (SAFE.contains(t)) return Kind.SAFE;
        if (CONFIRM.contains(t)) return Kind.CONFIRM;
        if (PHONE.contains(t)) return Kind.PHONE;
        // Anything that deletes, shares or otherwise falls outside the list is not run in the car.
        return Kind.DROP;
    }

    static List<AssistantReply.Action> keep(List<AssistantReply.Action> actions) {
        List<AssistantReply.Action> out = new ArrayList<>();
        if (actions == null) return out;
        for (AssistantReply.Action a : actions) if (a != null && kindOf(a.type) != Kind.DROP) out.add(a);
        return out;
    }

    static boolean needsConfirmation(List<AssistantReply.Action> actions) {
        for (AssistantReply.Action a : actions) if (kindOf(a.type) == Kind.CONFIRM) return true;
        return false;
    }

    /** "call Mum" / "text 07700 900123 saying running late". */
    static String describe(AssistantReply.Action a) {
        org.json.JSONObject p = a.params == null ? new org.json.JSONObject() : a.params;
        switch (a.type.toUpperCase(Locale.US)) {
            case "DIAL": return "call " + p.optString("number", "that number");
            case "DIAL_CONTACT": return "call " + p.optString("name", "that contact");
            case "SMS": return "text " + p.optString("number", "that number") + body(p);
            case "SMS_CONTACT": return "text " + p.optString("name", "that contact") + body(p);
            default: return a.type.toLowerCase(Locale.US).replace('_', ' ');
        }
    }

    private static String body(org.json.JSONObject p) {
        String b = p.optString("body", "").trim();
        return b.isEmpty() ? "" : " saying " + b;
    }

    static String confirmQuestion(List<AssistantReply.Action> actions) {
        List<String> parts = new ArrayList<>();
        for (AssistantReply.Action a : actions) if (kindOf(a.type) == Kind.CONFIRM) parts.add(describe(a));
        return "Should I " + String.join(" and ", parts) + "? Say yes or no.";
    }

    /** Strict yes/no reading of a spoken answer. Returns null when it is neither. */
    static Boolean parseYesNo(String heard) {
        String s = heard == null ? "" : heard.toLowerCase(Locale.UK).replaceAll("[^a-z ]", " ").trim();
        if (s.isEmpty()) return null;
        String[] w = s.split("\\s+");
        Set<String> words = new HashSet<>(Arrays.asList(w));
        boolean no = words.contains("no") || words.contains("nope") || words.contains("cancel")
                || words.contains("don't") || words.contains("dont") || words.contains("stop") || s.contains("do not");
        boolean yes = words.contains("yes") || words.contains("yeah") || words.contains("yep")
                || words.contains("sure") || words.contains("ok") || words.contains("okay")
                || words.contains("confirm") || words.contains("go") || s.contains("do it");
        if (no) return Boolean.FALSE;
        if (yes) return Boolean.TRUE;
        return null;
    }

    static final String CAR_SYSTEM =
            "You are GrokLink, answering a driver by voice through Android Auto. "
            + "Return ONLY valid JSON with exactly this shape, text first: "
            + "{\"text\":\"what to say aloud\",\"actions\":[{\"type\":\"ACTION\",\"params\":{},\"requiresConfirmation\":false}]}. "
            + "The text is spoken: plain sentences only, no Markdown, lists, code, URLs or JSON, under 80 words. "
            + "Only add actions when the driver asks for one; otherwise actions is an empty array. "
            + "Never claim an action has already happened; say what you will do, for example: Setting a timer for ten minutes. "
            + "For calls and texts say you will ask for confirmation. Use the driver's local time and 24-hour hours. "
            + "Available actions: SET_ALARM {hour,minute,label}; SET_TIMER {seconds,label}; "
            + "SET_REMINDER {message,year,month,day,hour,minute}; "
            + "ADD_CALENDAR_EVENTS {events:[{title,date,hour,minute,timezone,durationMinutes,allDay,location,description}]} with date as YYYY-MM-DD; "
            + "NAVIGATE {query}; DIAL {number}; DIAL_CONTACT {name}; SMS {number,body}; SMS_CONTACT {name,body}; "
            + "MEDIA_CONTROL {command} with PLAY, PAUSE, PLAY_PAUSE, NEXT or PREVIOUS; SET_VOLUME {percent}; "
            + "SET_DND {enabled}; SET_RINGER_MODE {mode} with normal, vibrate or silent; "
            + "A server-side web search tool is available. Use it for setlists, lineups, schedules and anything current, then say what you found. "
            + "PLAY_MUSIC {query,artist,album,title,playlist,app} plays an album, artist, saved playlist or one track already on the phone. Set album, artist or playlist to match what they asked for. "
            + "PLAY_PLAYLIST {name,tracks:[{title,artist}]} queues those songs in Poweramp after you look a setlist up. Only songs you found. "
            + "NAVIGATE {query,app,mode} with app maps or waze and mode navigate or eta. For eta, do not invent a travel time. "
            + "For a normal play request the text should be just: Playing Kasabian on Poweramp. "
            + "Never return a DIAL for an emergency number unless the driver directly asked to call it. "
            + "Never use an em dash.";
}
