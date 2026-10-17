package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The Connect Orbit step, which had drifted a long way from what Orbit actually is.
 *
 * <p>It described the experience as ChatGPT plus an "OpenAI API fallback", named Claude and Gemini
 * as planned providers, and never mentioned Orbit Local at all, months after Orbit shipped a
 * provider layer with four backends and an on-device model. These cases pin the refreshed page to
 * the real provider layer, so the copy cannot drift away from it again quietly, and pin the
 * constraints a first-run page has to keep: still seven steps, still no Calendar prompt, and no
 * provider forced on anyone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OnboardingConnectTest {

    private static final int CONNECT_STEP = 1;
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    /** The Connect step, rendered. */
    private String connectText() {
        return textOf(connectActivity().get());
    }

    private ActivityController<OnboardingActivity> connectActivity() {
        ActivityController<OnboardingActivity> controller =
                Robolectric.buildActivity(OnboardingActivity.class);
        Prefs.get(context).edit().putInt("onboarding_current_step", CONNECT_STEP).commit();
        controller.setup();
        return controller;
    }

    private static String textOf(Activity activity) {
        List<String> found = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), found);
        return String.join("\n", found);
    }

    private static void collect(View view, List<String> out) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) out.add(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private static String source() {
        return RepoFiles.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OnboardingActivity.java");
    }

    // ---- what the page shows ---------------------------------------------------------------------




    // ---- Orbit Local -------------------------------------------------------------------------------



    /** A provider that could do everything should say nothing about what it cannot do. */
    @Test public void aFullyCapableProviderGetsNoLimitationSentence() {
        AiCapabilities everything = AiCapabilities.builder()
                .streaming(true).deviceActions(true).images(true)
                .hostedWebSearch(true).routinePlanning(true).build();
        assertEquals("", OnboardingActivity.missingCapabilitySentence(everything));
    }



    // ---- the advanced area ---------------------------------------------------------------------------




    @Test public void theOldFallbackWordingAndStaleProviderPlansAreGone() {
        String source = source();
        assertFalse(source.contains("OpenAI API fallback"));
        assertFalse(source.contains("Configure OpenAI API fallback"));
        assertFalse(source.contains("API fallback saved and selected"));
        assertFalse("Claude and Gemini are not what the provider architecture is now",
                source.contains("including Claude and Gemini"));
        assertFalse(source.contains("Claude"));
        assertFalse(source.contains("Gemini"));
    }


    // ---- the constraints a first-run page has to keep -------------------------------------------------

    @Test public void setupIsStillSevenSteps() {
        assertTrue(connectText().contains("2 of 7"));
        String source = source();
        assertTrue(source.contains("(step + 1) + \" of 7\""));
        assertTrue("no eighth build step may appear",
                source.contains("else if (step == 6) buildStarterRoutine(page);"));
        assertFalse(source.contains("step == 7) buildStarterRoutine"));
    }


    @Test public void nothingOnThePageForcesAProviderOnAnyone() {
        String text = connectText();
        // Every provider control is an offer; the step's own navigation is what moves the user on.
        assertTrue(text.contains("Continue") || text.contains("Next") || text.contains("Skip setup"));
        String source = source();
        assertFalse("onboarding must never install the component itself",
                source.contains("installComponent"));
        assertFalse("nor save an OpenRouter key on the user's behalf",
                source.contains("saveOpenRouterKey"));
    }

    // ---- appearance and motion are untouched ------------------------------------------------------------

    @Test public void theAppearanceSystemStillDrivesTheRefreshedPage() {
        String source = source();
        assertTrue("live Accent, AMOLED, and font changes still rebuild in place",
                source.contains("UiKit.appearanceSignature(this)"));
        assertTrue(source.contains("UiKit.syncTheme(this)"));
        assertTrue(source.contains("UiKit.applyTypography(page)"));
        assertTrue("the new cards use the shared design system, not stock controls",
                source.contains("UiKit.outlined(UiKit.SURFACE"));
        assertFalse("no stock Android dialogs may appear on this page",
                source.contains("android.app.AlertDialog.Builder("));
    }

    @Test public void theRefreshedPageStillUsesTheAccentAwareOrbitControls() {
        String source = source();
        assertTrue(source.contains("UiKit.accent(this)"));
        assertTrue(source.contains("UiKit.onAccent(this)"));
        assertTrue(source.contains("UiKit.pressScale(button)"));
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private static boolean clickByLabel(Activity activity, String label) {
        return clickByLabel(activity.getWindow().getDecorView(), label);
    }

    private static boolean clickByLabel(View view, String label) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && label.contentEquals(text) && view.isClickable()) {
                view.performClick();
                return true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (clickByLabel(group.getChildAt(i), label)) return true;
            }
        }
        return false;
    }
}
