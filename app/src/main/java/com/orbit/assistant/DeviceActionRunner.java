package com.orbit.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Runs device actions off the main thread.
 *
 * <p>Poweramp's media connection posts its result to the main looper and then waits for it. Doing
 * that wait on the main thread means the connection never arrives. Music and every other device
 * action from a chat reply therefore run here, on one background thread. A confirmation dialog is
 * still shown on the main thread; the action it approves continues in the background.
 */
final class DeviceActionRunner {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** One thread, so two replies cannot talk to Poweramp at the same time. */
    private static final Executor QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "groklink-device-actions");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Executor executor = QUEUE;

    private DeviceActionRunner() {}

    static void execute(Context context, List<AssistantReply.Action> actions,
                        OrbitActionEngine.ConfirmationHandler confirmation,
                        OrbitActionEngine.Listener listener) {
        Context app = context == null ? null : context.getApplicationContext();
        Executor ex = executor;
        ex.execute(() -> {
            if (app == null) {
                if (listener != null) listener.onFinished(true, 0, 0);
                return;
            }
            OrbitActionEngine.ConfirmationHandler wrapped = confirmation == null ? null
                    : (action, onAllow, onCancel) -> MAIN.post(() -> confirmation.request(action,
                    () -> ex.execute(onAllow),
                    () -> ex.execute(onCancel)));
            try {
                OrbitActionEngine.execute(app, actions, wrapped, listener);
            } catch (Exception e) {
                if (listener != null) listener.onFinished(false, 0, actions == null ? 0 : actions.size());
            }
        });
    }

    static void resetForTests() { executor = QUEUE; }

    /** Runs work on the calling thread. Tests use this so a reply's actions finish before the assert. */
    static void setExecutorForTests(Executor replacement) {
        executor = replacement == null ? QUEUE : replacement;
    }
}
