package com.orbit.assistant;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.format.DateFormat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "When is my next alarm?" and "show my alarms", answered from the phone.
 *
 * <p>Setting an alarm stays on the existing clock action. This only answers the question that
 * used to go to Grok, and opens Android's alarm list when the user asks to see it. If Android 12
 * or later has not allowed exact alarms, setting one asks for that access.
 */
public final class AlarmQueries {
    private AlarmQueries() {}

    public static AssistantReply tryHandle(Context context, String raw) {
        String q = LanguageNormalizer.stripPoliteness(LanguageNormalizer.canonical(raw));
        if (q.isEmpty() || context == null) return null;
        if (isCommand(q)) return null;
        boolean about = q.contains("alarm");
        if (!about) return null;
        boolean show = q.startsWith("show") || q.startsWith("see") || q.startsWith("open");
        boolean ask = q.contains("when") || q.contains("what time") || q.contains("next")
                || q.contains("what is") || show;
        if (!ask) return null;
        String said = describe(context);
        if (!show) return new AssistantReply(said);
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(new AssistantReply.Action("SHOW_ALARMS", new JSONObject(), false));
        return new AssistantReply(said, actions);
    }

    /** A phrase that sets an alarm rather than asking about one. */
    static boolean isCommand(String q) {
        return q.contains("set ") || q.contains("wake me") || q.contains("create ")
                || q.contains("add an alarm") || q.contains("add a alarm");
    }

    /** What to say. Opens the exact-alarm setting only when Android refused the read. */
    public static String describe(Context context) {
        if (context == null) return "I can't see a next alarm on this phone.";
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return "I can't see a next alarm on this phone.";
        try {
            AlarmManager.AlarmClockInfo info = alarms.getNextAlarmClock();
            if (info != null && info.getTriggerTime() > 0) {
                String when = DateFormat.getTimeFormat(context).format(new Date(info.getTriggerTime()));
                return "Your next alarm is " + when + ".";
            }
        } catch (SecurityException denied) {
            RoutineTriggerScheduler.openExactAlarmAccess(context);
            return "Android hasn't allowed GrokLink to read alarms. Allow exact alarms, then ask again.";
        } catch (Exception ignored) {
            // The formatted setting below is the older place some phones still write.
        }
        try {
            String legacy = Settings.System.getString(context.getContentResolver(),
                    Settings.System.NEXT_ALARM_FORMATTED);
            if (legacy != null && !legacy.trim().isEmpty()) {
                return "Your next alarm is " + legacy.trim() + ".";
            }
        } catch (Exception ignored) {}
        return "I can't see a next alarm on this phone.";
    }

    /** Clock screen. Safe to call when the action runs. */
    public static Intent showAlarms() {
        Intent intent = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Spoken when an alarm was handed to Clock. On Android 12 and later, GrokLink also asks for
     * exact-alarm access when it does not have it, so a later "next alarm" can be answered.
     */
    public static String setResult(Context context, boolean handedToClock) {
        if (!handedToClock) return "I couldn't open the Clock app.";
        if (Build.VERSION.SDK_INT >= 31 && !RoutineTriggerScheduler.canScheduleExact(context)) {
            return "Alarm handed to Clock. Allow exact alarms for GrokLink so the next one can be set and read.";
        }
        return "Alarm set";
    }
}
