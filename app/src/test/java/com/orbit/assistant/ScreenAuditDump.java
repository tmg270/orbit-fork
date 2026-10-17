package com.orbit.assistant;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Screen audit helper: only runs when SCREEN_AUDIT=dir env is set. Dumps text + PNG per screen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ScreenAuditDump {
    private static final String DIR = (System.getenv("SCREEN_AUDIT") == null ? "" : System.getenv("SCREEN_AUDIT"));

    private static final Object[][] SCREENS = {
            {"main", MainActivity.class, null},
            {"settings", SettingsActivity.class, null},
            {"settings-assistant", SettingsActivity.class, "assistant"},
            {"settings-ai", SettingsActivity.class, "ai"},
            {"settings-voice", SettingsActivity.class, "voice"},
            {"settings-data", SettingsActivity.class, "data"},
            {"settings-deck", SettingsActivity.class, "deck"},
            {"settings-conversations", SettingsActivity.class, "conversations"},
            {"settings-routines", SettingsActivity.class, "routines"},
            {"settings-extensions", SettingsActivity.class, "extensions"},
            {"settings-appearance", SettingsActivity.class, "appearance"},
            {"settings-updates", SettingsActivity.class, "updates"},
            {"settings-advanced", SettingsActivity.class, "advanced"},
            {"diagnostics", DiagnosticsActivity.class, null},
            {"ai-providers", AiProvidersActivity.class, null},
            {"onboarding", OnboardingActivity.class, null},
            {"theme-studio", ThemeStudioActivity.class, null},
            {"memory", MemoryActivity.class, null},
            {"vault", OrbitVaultActivity.class, null},
            {"smart-vault", SmartVaultActivity.class, null},
            {"apps", AppsActivity.class, null},
            {"notifications", NotificationsActivity.class, null},
            {"capabilities", CapabilitiesActivity.class, null},
            {"deck", DeckActivity.class, null},
            {"routines", RoutinesActivity.class, null},
            {"routine-templates", RoutineTemplatesActivity.class, null},
            {"routine-editor", RoutineEditorActivity.class, null},
            {"routine-builder", RoutineBuilderActivity.class, null},
            {"routine-history", RoutineRunHistoryActivity.class, null},
            {"custom-commands", CustomCommandsActivity.class, null},
            {"custom-command-editor", CustomCommandEditorActivity.class, null},
            {"time-trigger", TimeTriggerEditorActivity.class, null},
            {"location-trigger", LocationTriggerEditorActivity.class, null},
            {"extensions", ExtensionsActivity.class, null},
            {"reminders", RemindersActivity.class, null},
            {"saved-places", SavedPlacesActivity.class, null},
            {"about", UpdateActivity.class, null},
            {"whats-new", WhatsNewActivity.class, null},
            {"roadmap", RoadmapActivity.class, null},
            {"chat", ChatActivity.class, null},
            {"widget-configure", OrbitWidgetConfigureActivity.class, null},
    };

    @Test public void dumpAll() throws Exception {
        Assume.assumeTrue(!DIR.isEmpty());
        new File(DIR).mkdirs();
        StringBuilder index = new StringBuilder();
        for (Object[] s : SCREENS) {
            String name = (String) s[0];
            try {
                Intent i = new Intent(RuntimeEnvironment.getApplication(), (Class<?>) s[1]);
                if (s[2] != null) i.putExtra(SettingsActivity.EXTRA_SECTION, (String) s[2]);
                Activity a = Robolectric.buildActivity((Class<? extends Activity>) s[1], i)
                        .setup().get();
                ShadowLooper.idleMainLooper();
                View root = a.getWindow().getDecorView();
                // expand everything expandable
                for (int pass = 0; pass < 3; pass++) {
                    List<View> collapsed = new ArrayList<>();
                    find(root, collapsed);
                    if (collapsed.isEmpty()) break;
                    List<View> all = new ArrayList<>();
                    for (View v : collapsed) if (v instanceof TextView && "Expand all".contentEquals(((TextView) v).getText())) all.add(v);
                    if (!all.isEmpty()) collapsed = all;
                    for (View v : collapsed) { try { v.performClick(); } catch (Throwable ignored) {} }
                    ShadowLooper.idleMainLooper();
                }
                StringBuilder sb = new StringBuilder();
                dump(root, sb, 0);
                write(new File(DIR, name + ".txt"), sb.toString());
                shot(root, new File(DIR, name + ".png"));
                index.append(name).append(": ok\n");
            } catch (Throwable t) {
                index.append(name).append(": FAILED ").append(t).append('\n');
            }
        }
        // Onboarding: walk every step.
        try {
            Activity a = Robolectric.buildActivity(OnboardingActivity.class).setup().get();
            for (int step = 1; step <= 9; step++) {
                ShadowLooper.idleMainLooper();
                View root = a.getWindow().getDecorView();
                StringBuilder sb = new StringBuilder();
                dump(root, sb, 0);
                write(new File(DIR, "onboarding-step" + step + ".txt"), sb.toString());
                shot(root, new File(DIR, "onboarding-step" + step + ".png"));
                View next = byText(root, "Get started", "Continue", "Skip for now", "Finish setup", "Finish", "Done", "Start using GrokLink");
                if (next == null) break;
                next.performClick();
                ShadowLooper.idleMainLooper();
                android.app.Dialog d = org.robolectric.shadows.ShadowDialog.getLatestDialog();
                if (d != null && d.isShowing()) d.dismiss();
                if (a.isFinishing()) break;
            }
            index.append("onboarding steps: ok\n");
        } catch (Throwable t) { index.append("onboarding steps: FAILED ").append(t).append('\n'); }
        // Chat model picker
        try {
            Activity a = Robolectric.buildActivity(ChatActivity.class).setup().get();
            ShadowLooper.idleMainLooper();
            View picker = byPrefix(a.getWindow().getDecorView(), "Grok 4");
            picker.performClick();
            ShadowLooper.idleMainLooper();
            android.app.Dialog d = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            View root = d != null ? d.getWindow().getDecorView() : null;
            if (root == null) {
                android.widget.PopupWindow pw = org.robolectric.shadows.ShadowPopupWindow.class == null ? null : null;
                root = pw.getContentView();
            }
            StringBuilder sb = new StringBuilder(); dump(root, sb, 0);
            write(new File(DIR, "chat-model-picker.txt"), sb.toString());
            shot(root, new File(DIR, "chat-model-picker.png"));
            index.append("chat-model-picker: ok\n");
        } catch (Throwable t) { index.append("chat-model-picker: FAILED ").append(t).append('\n'); }
        write(new File(DIR, "index.txt"), index.toString());
    }

    private static View byText(View v, String... names) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof TextView) for (String n : names) if (n.contentEquals(((TextView) v).getText())) return v;
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { View f = byText(g.getChildAt(i), names); if (f != null) return f; } }
        return null;
    }

    private static View byPrefix(View v, String p) {
        if (v instanceof TextView && ((TextView) v).getText().toString().startsWith(p)) return v;
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { View f = byPrefix(g.getChildAt(i), p); if (f != null) return f; } }
        return null;
    }

    private static void find(View v, List<View> out) {
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.toString().endsWith(", collapsed") && v.isClickable()) out.add(v);
        if (v instanceof TextView && "Expand all".contentEquals(((TextView) v).getText())) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) find(g.getChildAt(i), out);
        }
    }

    private static void dump(View v, StringBuilder sb, int depth) {
        if (v.getVisibility() != View.VISIBLE) return;
        String pad = "  ".repeat(Math.min(depth, 30));
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) sb.append(pad).append(t.toString().replace("\n", "\n" + pad)).append('\n');
            CharSequence h = ((TextView) v).getHint();
            if (h != null && h.length() > 0) sb.append(pad).append("[hint] ").append(h).append('\n');
        } else if (v.getContentDescription() != null && !(v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0)) {
            sb.append(pad).append("[desc] ").append(v.getContentDescription()).append('\n');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), sb, depth + 1);
        }
    }

    private static void shot(View root, File f) throws Exception {
        View content = root.findViewById(android.R.id.content);
        if (content == null) content = root;
        View target = content;
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
            target = ((ViewGroup) content).getChildAt(0);
            if (target instanceof ViewGroup && ((ViewGroup) target).getChildCount() == 1
                    && target instanceof android.widget.ScrollView) target = ((ViewGroup) target).getChildAt(0);
        }
        int w = 1233;
        target.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.max(200, Math.min(target.getMeasuredHeight(), 20000));
        target.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF0B0F14);
        target.draw(c);
        try (FileOutputStream out = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(s.getBytes("UTF-8")); }
    }
}
