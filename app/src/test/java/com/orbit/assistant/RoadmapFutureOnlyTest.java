package com.orbit.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-app Roadmap is future-only, and it keeps drifting in two different ways.
 *
 * <p>The first drift is the old one: the page offered natural-language Routine creation as upcoming
 * long after Create with Orbit shipped it, and several more entries went the same way. That is what
 * {@link #shippedFeaturesAreNotOfferedAsUpcoming} exists for, and the list only ever grows.
 *
 * <p>The second is worse and is what v0.7.8.4-beta.4 fixed. By the Vault line the page still opened
 * with 0.7.7-era priorities under "NEXT UP" while the whole project had moved to Orbit Vault, so
 * nothing on it was false and the page as a whole was still misleading: a reader came away with a
 * confident and wrong idea of what Orbit was working on. Stale-but-real work is therefore asserted
 * to be listed <em>below</em> the active plan rather than merely listed, and the active
 * milestone is asserted to be present in the page and in {@code ROADMAP.md} at once - see
 * {@link RoadmapSyncTest}. A milestone that ships leaves those constants and joins the list above.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RoadmapFutureOnlyTest {

    /** Shipped work, in the wording the Roadmap used to describe it as upcoming. */
    private static final String[] ALREADY_SHIPPED = {
            "Natural-language Routine creation",
            "Automation history",
            "Richer quick access",
            // v0.7.5.0 shipped one dependable level of IF / ELSE branching.
            "Richer automation & branching",
            // v0.7.7.0 shipped the provider layer, provider management, and the first Orbit Local.
            "Choice of AI provider",
            "More AI providers & models",
            // v0.7.7.3 shipped the deterministic kitchen maths. Only the cooking session, its
            // hands-free vocabulary, and optional Orbit-owned timers remain unbuilt.
            "Kitchen conversions",
            "Cooking conversions",
            "Recipe scaling",
            "Smarter timer labels",
            // v0.7.7.4 shipped the Stable/Beta update channels themselves. The Beta channel may be
            // referred to as a way future work will be delivered, but never offered as upcoming.
            "Update channel",
            "Stable / Beta updates",
            "Choose your update channel",
            // v0.7.7.5 promoted Modular Orbit Local from Beta to Stable, so the component itself
            // is shipped work. Teaching it Orbit's device actions is what genuinely remains.
            "Modular GrokLink Local",
            // v0.7.8.4 beta.1 to beta.3 shipped the Vault itself, saving from Orbit's own
            // surfaces, notes, Ask Orbit, Attach from Vault and the Deck destinations. Betas 4 to
            // 6 finished organization and polish, and the complete line is now Stable.
            //
            // "Save to Vault" is deliberately absent from this list: it is shipped, and it is also
            // a real part of the Rich Answers plan, where an inline web image has to reach the
            // Vault through the path that already exists rather than a second one.
            "GrokLink Vault arrives",
            "Vault organization",
            "Quick Capture",
            "Attach from Vault",
            "Vault notes",
            "Theme Studio",
            // v0.7.8.5 shipped Rich Answers, Settings search and optional GPT-6 Astra, and
            // was promoted to Stable from the tested Beta 9. All three left OrbitRoadmap with
            // it, and none of them may be offered on a future-only page any more.
            "Rich Answers / Visual Web Results",
            "Settings search",
            "GPT-6 Astra",
            "Deck layouts",
            // v0.8.1.0 made Smart Vault Stable. Its milestone stays NOW for the work still ahead,
            // but what shipped in the two Betas may no longer be offered as upcoming.
            "search by meaning",
            "Search by meaning",
            "text read from your pictures",
            "Read text in pictures",
            "topic filters",
            "Related in your Vault",
            // v0.8.2.0 made Orbit Local 2.0 Stable. It stays NOW for the work still ahead, but what
            // its two Betas shipped may no longer be offered as upcoming.
            "Ask Vault offline",
            "What works on this phone",
            "multi-step device actions",
            "live download progress",
            // v0.8.3.0-beta.4 shipped provider expansion and the library behind it.
            "Claude and Grok as providers",
            "Model Library",
            "Favorites and Recents",
            // v0.8.3.0-beta.5 shipped Smart Routing.
            "Smart Routing",
            // v0.8.3.0-beta.6 shipped OpenRouter.
            "OpenRouter",
    };

    private String roadmapText() {
        Activity activity = Robolectric.buildActivity(RoadmapActivity.class).setup().get();
        List<String> found = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), found);
        return String.join("\n", found);
    }

    private static void collect(View view, List<String> into) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) into.add(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
        }
    }

    @Test public void shippedFeaturesAreNotOfferedAsUpcoming() {
        String text = roadmapText();
        for (String shipped : ALREADY_SHIPPED) {
            assertFalse(shipped + " has already shipped and must not appear on the Roadmap",
                    offeredAsUpcoming(text, shipped));
        }
    }

    /**
     * Whether the page offers this shipped feature as future work.
     *
     * <p>A premium companion to a shipped feature is a different feature, and it is genuinely
     * unbuilt. Theme Studio shipped and must never be promised again; Theme Studio Pro has not
     * shipped and belongs on the page. A plain substring check cannot tell those apart and would
     * force the shipped name off this list, losing the guard that matters.
     */
    private static boolean offeredAsUpcoming(String text, String shipped) {
        for (int at = text.indexOf(shipped); at >= 0; at = text.indexOf(shipped, at + 1)) {
            if (!text.startsWith(shipped + " Pro", at)) return true;
        }
        return false;
    }







    /**
     * OpenRouter shipped in 0.8.3.0-beta.6. It was the page's only deferred entry, so the deferred
     * group goes with it: a shipped feature belongs to What's New, never here.
     */
    @Test public void shippedOpenRouterLeftThePage() {
        String text = roadmapText();
        assertFalse(text.contains("DEFERRED"));
        assertFalse(text.contains("OpenRouter"));
        assertFalse(text.contains("account to test it with"));
    }

    @Test public void thePageStillSaysItIsFutureOnly() {
        assertTrue(roadmapText().contains("future-only"));
    }
}
