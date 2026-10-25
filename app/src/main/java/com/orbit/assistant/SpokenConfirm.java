package com.orbit.assistant;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds a call or text until the user says yes. Nothing is dialled or composed before that.
 */
final class SpokenConfirm {
    private static final long TTL_MS = 30_000L;

    private static AssistantReply.Action pending;
    private static long armedAt;
    private static int generation;

    private SpokenConfirm() {}

    static boolean isArmed() {
        return pending != null && SystemClock.elapsedRealtime() - armedAt <= TTL_MS;
    }

    static int generation() { return generation; }

    static boolean answers(String raw) {
        AssistantReply.Action action = pending;
        if (action == null) return false;
        if (SystemClock.elapsedRealtime() - armedAt > TTL_MS) {
            clear();
            return false;
        }
        return CarActions.parseYesNo(raw) != null;
    }

    static AssistantReply ask(AssistantReply.Action action, String question) {
        pending = action;
        armedAt = SystemClock.elapsedRealtime();
        generation++;
        return new AssistantReply(question == null ? "Say yes or no." : question);
    }

    static AssistantReply consume(String raw) {
        AssistantReply.Action action = pending;
        Boolean yes = CarActions.parseYesNo(raw);
        clear();
        if (action == null || yes == null) return new AssistantReply("Okay, I won't do that.");
        if (!yes) return new AssistantReply("Okay, I won't do that.");
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(action);
        return new AssistantReply("Okay.", actions);
    }

    static void clear() {
        pending = null;
        armedAt = 0L;
        generation++;
    }
}
