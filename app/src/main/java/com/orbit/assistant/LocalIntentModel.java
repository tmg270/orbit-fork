package com.orbit.assistant;

import android.content.Context;

/**
 * Asks the on-device model to name one simple command. A refusal, a bad answer, or a model that
 * is not on the phone yet is null, which is the signal to offer Grok.
 */
public final class LocalIntentModel {
    private static final String PROMPT =
            "You convert one spoken phone command into JSON. Reply with ONLY a JSON object, no markdown.\n"
                    + "Allowed types: PLAY_MUSIC, MEDIA_CONTROL, SET_ALARM, SET_TIMER, DIAL_CONTACT, SMS_CONTACT, NONE.\n"
                    + "PLAY_MUSIC uses query, artist, album, title, app.\n"
                    + "MEDIA_CONTROL command is PAUSE, PLAY, NEXT or PREVIOUS.\n"
                    + "SET_ALARM uses hour 0-23 and minute 0-59.\n"
                    + "SET_TIMER uses seconds and an optional label.\n"
                    + "DIAL_CONTACT uses name. SMS_CONTACT uses name and body.\n"
                    + "If this is not one of those commands, reply {\"type\":\"NONE\"}.\n"
                    + "Command: ";

    private LocalIntentModel() {}

    public static AssistantReply tryHandle(Context context, String prompt) {
        if (!LocalModel.ready(context)) return null;
        String text = prompt == null ? "" : prompt.trim();
        if (text.isEmpty() || text.length() > 400) return null;
        String raw = LocalLlmEngine.generate(context, LocalModel.file(context).getAbsolutePath(),
                PROMPT + text);
        if (raw == null) return null;
        return LocalIntent.fromModel(raw);
    }
}
