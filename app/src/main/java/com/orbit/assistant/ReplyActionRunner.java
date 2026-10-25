package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs the device actions on a finished Grok reply even when no chat screen is listening.
 *
 * <p>The chat detaches its listener in {@code onPause}. A long setlist lookup often finishes
 * after that, and until now the saved reply was only text: nothing was queued and no action card
 * was kept. The worker schedules the actions here, on a background thread, and stores the card.
 * An action that must be confirmed first is remembered until a chat is on screen to ask.
 */
final class ReplyActionRunner {
    interface Listener {
        void onActionsFinished(String conversationId);
    }

    static final class Prompt {
        final String requestId;
        final int assistantIndex;
        final List<AssistantReply.Action> actions;

        Prompt(String requestId, int assistantIndex, List<AssistantReply.Action> actions) {
            this.requestId = requestId == null ? "" : requestId;
            this.assistantIndex = assistantIndex;
            this.actions = actions == null ? Collections.emptyList() : actions;
        }
    }

    private static final String FILE = "groklink_pending_prompts";
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private ReplyActionRunner() {}

    /** True when the action may run without a person tapping Continue. */
    static boolean automatic(AssistantReply.Action action) {
        return action != null && action.type != null && !action.type.trim().isEmpty()
                && !action.requiresConfirmation;
    }

    /**
     * Queues automatic actions and remembers anything that still needs a confirmation.
     * Returns immediately. Safe to call with no chat on screen.
     */
    static void schedule(Context c, String conversationId, String requestId,
                         int assistantIndex, List<AssistantReply.Action> actions) {
        if (c == null || conversationId == null || conversationId.isEmpty()
                || actions == null || actions.isEmpty()) return;
        List<AssistantReply.Action> auto = new ArrayList<>();
        List<AssistantReply.Action> prompt = new ArrayList<>();
        for (AssistantReply.Action action : actions) {
            if (action == null) continue;
            if (automatic(action)) auto.add(action);
            else prompt.add(action);
        }
        if (!prompt.isEmpty()) remember(c, conversationId, requestId, assistantIndex, prompt);
        if (auto.isEmpty()) return;
        Context app = c.getApplicationContext();
        DeviceActionRunner.execute(app, auto, null, new OrbitActionEngine.Listener() {
            @Override public void onStep(AssistantReply.Action action, DeviceActionExecutor.Result result,
                                         int index, int total) {
                ActionResultStore.record(app, conversationId, assistantIndex, action, result, index, total);
            }

            @Override public void onFinished(boolean completedAllSteps, int completedSteps, int totalSteps) {
                for (Listener listener : LISTENERS) {
                    try { listener.onActionsFinished(conversationId); }
                    catch (Exception ignored) {}
                }
            }
        });
    }

    static void addListener(Listener listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    /** The confirmations waiting for this chat, removed as they are handed to the screen. */
    static synchronized List<Prompt> takePrompts(Context c, String conversationId) {
        if (c == null || conversationId == null || conversationId.isEmpty()) return Collections.emptyList();
        SharedPreferences prefs = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String raw = prefs.getString(conversationId, "");
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        prefs.edit().remove(conversationId).commit();
        List<Prompt> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject one = arr.optJSONObject(i);
                if (one == null) continue;
                List<AssistantReply.Action> actions = new ArrayList<>();
                JSONArray stored = one.optJSONArray("actions");
                if (stored != null) {
                    for (int a = 0; a < stored.length(); a++) {
                        JSONObject action = stored.optJSONObject(a);
                        if (action == null) continue;
                        actions.add(new AssistantReply.Action(action.optString("type", ""),
                                action.optJSONObject("params"),
                                action.optBoolean("requiresConfirmation", true)));
                    }
                }
                if (!actions.isEmpty()) {
                    out.add(new Prompt(one.optString("requestId", ""),
                            one.optInt("assistantIndex", 0), actions));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    static void resetForTests(Context c) {
        LISTENERS.clear();
        DeviceActionRunner.resetForTests();
        if (c != null) c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static synchronized void remember(Context c, String conversationId, String requestId,
                                              int assistantIndex, List<AssistantReply.Action> actions) {
        try {
            SharedPreferences prefs = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(prefs.getString(conversationId, "[]"));
            JSONArray stored = new JSONArray();
            for (AssistantReply.Action action : actions) {
                stored.put(new JSONObject()
                        .put("type", action.type)
                        .put("params", action.params == null ? new JSONObject() : action.params)
                        .put("requiresConfirmation", true));
            }
            arr.put(new JSONObject()
                    .put("requestId", requestId == null ? "" : requestId)
                    .put("assistantIndex", assistantIndex)
                    .put("actions", stored));
            prefs.edit().putString(conversationId, arr.toString()).commit();
        } catch (Exception ignored) {}
    }
}
