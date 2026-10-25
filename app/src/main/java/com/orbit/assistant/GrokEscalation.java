package com.orbit.assistant;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What happens when a simple command was not handled on the phone.
 *
 * <p>Voice requests, and typed requests that already sound like a device command, get two large
 * buttons and a two-second countdown that asks Grok if neither is pressed. Android Auto uses the
 * same wait by voice. With no connection, Grok is not asked.
 */
public final class GrokEscalation {
    public static final String NEEDS_CONNECTION = "Grok needs a connection.";
    public static final String FORGOTTEN = "Okay, forgotten.";
    public static final String ASKING = "Asking Grok";
    private static final long COUNTDOWN_MS = 2_000L;
    private static final ExecutorService MODEL =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "groklink-local-model");
                t.setDaemon(true);
                return t;
            });
    private static final AtomicInteger EPOCH = new AtomicInteger();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Drops a choice that has not been made yet. Stop uses this so a countdown cannot ask Grok later. */
    public static void cancel() { EPOCH.incrementAndGet(); }

    public interface Callback {
        void ask();
        void say(String text);
    }

    private GrokEscalation() {}

    /**
     * @return true when this class will call {@code cb} itself (now or shortly). False when the
     * caller should ask Grok immediately, which is every ordinary typed chat message.
     */
    public static boolean route(Context context, ViewGroup host, String prompt, boolean voice,
                                Callback cb) {
        if (context == null || cb == null) return false;
        if (!shouldAsk(context, prompt, voice)) return false;
        if (!NetStatus.online(context)) {
            cb.say(NEEDS_CONNECTION);
            return true;
        }
        final int epoch = EPOCH.get();
        if (LocalModel.ready(context)) {
            Context app = context.getApplicationContext();
            String text = prompt;
            MODEL.execute(() -> {
                AssistantReply local;
                try {
                    local = LocalIntentModel.tryHandle(app, text);
                } catch (Throwable ignored) {
                    local = null;
                }
                AssistantReply found = local;
                MAIN.post(() -> {
                    if (epoch != EPOCH.get()) return;
                    if (found != null) {
                        LocalIntentCache.put(text, found);
                        cb.ask();
                    } else if (host != null) {
                        showChoice(context, host, cb, epoch);
                    } else {
                        cb.ask();
                    }
                });
            });
            return true;
        }
        if (host == null) return false;
        showChoice(context, host, cb, epoch);
        return true;
    }

    public static boolean shouldAsk(Context context, String prompt, boolean voice) {
        if (context == null || prompt == null || prompt.trim().isEmpty()) return false;
        if (!Prefs.askBeforeGrok(context)) return false;
        if (resolvesLocally(context, prompt)) return false;
        if (voice) return true;
        return commandShaped(prompt);
    }

    static boolean resolvesLocally(Context context, String prompt) {
        if (MemoryCommandRouter.canHandle(prompt)) return true;
        if (DeviceStatusRouter.canHandle(prompt)) return true;
        if (LocalCommandRouter.canHandle(prompt)) return true;
        if (KitchenMathRouter.canHandle(prompt)) return true;
        if (CalculatorRouter.canHandle(prompt)) return true;
        if (UnitConversionRouter.canHandle(prompt)) return true;
        try {
            if (RoutineCommandRouter.tryHandle(context, prompt) != null) return true;
        } catch (Throwable ignored) {}
        try {
            if (CustomCommandRouter.tryHandle(context, prompt) != null) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    static boolean commandShaped(String prompt) {
        String s = LanguageNormalizer.stripPoliteness(LanguageNormalizer.canonical(prompt));
        return s.matches("^(play|put on|pause|skip|next|previous|resume|call|phone|ring|dial|"
                + "text|message|sms|alarm|wake me|wake|timer|set a timer|set an alarm|"
                + "set alarm|set timer)\\b.*");
    }

    /** Words that cancel the car's spoken countdown. */
    public static boolean forgets(String heard) {
        String s = heard == null ? "" : heard.toLowerCase(Locale.UK).replaceAll("[^a-z ]", " ").trim();
        if (s.isEmpty()) return false;
        return s.equals("forget it") || s.equals("forget") || s.equals("cancel") || s.equals("no")
                || s.equals("nope") || s.equals("stop") || s.equals("never mind")
                || s.equals("nevermind") || s.contains("forget it") || s.contains("don't")
                || s.contains("do not");
    }

    /** Words that ask Grok immediately instead of waiting out the countdown. */
    public static boolean asks(String heard) {
        if (forgets(heard)) return false;
        String s = heard == null ? "" : heard.toLowerCase(Locale.UK).replaceAll("[^a-z ]", " ").trim();
        return s.equals("ask grok") || s.equals("ask") || s.equals("grok") || s.equals("yes")
                || s.equals("yeah") || s.equals("yep") || s.contains("ask grok");
    }

    private static void showChoice(Context context, ViewGroup host, Callback cb, int epoch) {
        AtomicBoolean done = new AtomicBoolean(false);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        int bg = 0xF01C1C1E;
        try { bg = UiKit.SURFACE_2; } catch (Throwable ignored) {}
        card.setBackgroundColor(bg);
        TextView title = new TextView(context);
        title.setText(ASKING);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        int text = 0xFFFFFFFF;
        try { text = UiKit.TEXT; } catch (Throwable ignored) {}
        title.setTextColor(text);
        card.addView(title, match());
        TextView count = new TextView(context);
        count.setGravity(Gravity.CENTER);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        count.setTextColor(text);
        count.setPadding(0, dp(context, 8), 0, dp(context, 12));
        card.addView(count, match());
        Button ask = big(context, "Ask Grok");
        Button forget = big(context, "Forget it");
        card.addView(ask, matchGap(context));
        card.addView(forget, match());
        Runnable finishAsk = () -> {
            if (epoch != EPOCH.get()) { done.set(true); remove(card); return; }
            if (!done.compareAndSet(false, true)) return;
            remove(card);
            cb.ask();
        };
        Runnable finishForget = () -> {
            if (!done.compareAndSet(false, true)) return;
            remove(card);
            cb.say(FORGOTTEN);
        };
        ask.setOnClickListener(v -> finishAsk.run());
        forget.setOnClickListener(v -> finishForget.run());
        long deadline = android.os.SystemClock.uptimeMillis() + COUNTDOWN_MS;
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (done.get()) return;
                if (epoch != EPOCH.get()) { done.set(true); remove(card); return; }
                long left = deadline - android.os.SystemClock.uptimeMillis();
                if (left <= 0) {
                    count.setText("Asking Grok now");
                    finishAsk.run();
                    return;
                }
                int seconds = (int) Math.ceil(left / 1000.0);
                count.setText("Asking Grok in " + seconds + "…");
                MAIN.postDelayed(this, 200);
            }
        };
        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { MAIN.post(tick); }
            @Override public void onViewDetachedFromWindow(View v) {
                if (done.get()) return;
                // A redraw removed the card before a choice. Ask Grok rather than dropping the turn,
                // unless Stop already cancelled it.
                MAIN.post(() -> {
                    if (epoch != EPOCH.get()) { done.set(true); return; }
                    finishAsk.run();
                });
            }
        });
        host.addView(card, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        MAIN.post(tick);
    }

    private static void remove(View card) {
        if (card.getParent() instanceof ViewGroup) {
            ((ViewGroup) card.getParent()).removeView(card);
        }
    }

    private static Button big(Context context, String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setMinHeight(dp(context, 72));
        return b;
    }

    private static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams matchGap(Context context) {
        LinearLayout.LayoutParams lp = match();
        lp.bottomMargin = dp(context, 10);
        return lp;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
