package com.orbit.assistant;

import static org.junit.Assert.assertFalse;
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
 * Orbit keeps two roadmaps, and by v0.7.8.4 they had drifted far enough to mislead.
 *
 * <p>`ROADMAP.md` is where the plan is argued out and where the development history lives.
 * {@link RoadmapActivity} is what somebody opens in About &amp; updates. Nothing kept them in step,
 * so the in-app page went on presenting 0.7.7-era priorities as next while the repository had moved
 * on through Orbit Deck, Theme Studio and three Vault Betas. Neither document was individually
 * false; together they gave two different answers to "what is GrokLink working on?".
 *
 * <p>This is the smallest guard that stops it happening again, and it is deliberately small. There
 * is no Markdown parser, no schema, no generated file, and nothing that runs on a user's phone.
 * {@link OrbitRoadmap} holds the names of the active milestones, the in-app page draws its headings
 * from those constants, and this test asserts the same names appear in `ROADMAP.md` above its
 * history. Rename or drop a milestone in one place and the build says so. A milestone that ships
 * leaves those constants and is checked here as recorded history instead.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RoadmapSyncTest {

    /** Where the plan stops and the record of how Orbit got here begins. */
    private static final String HISTORY_HEADING = "# Development history";

    /**
     * Two names this file still has to check that are no longer active milestones.
     *
     * <p>Both shipped in {@code 0.7.8.5}. They left {@link OrbitRoadmap} at Stable promotion,
     * because the in-app page is future-only and a constant for either would have kept offering a
     * user something they already have. What they must still do is stay correctly recorded in
     * `ROADMAP.md`, so the commitments made about them are named here instead.
     */
    private static final String RICH_ANSWERS = "Rich Answers / Visual Web Results";
    private static final String SETTINGS_SEARCH = "Settings search";

    private static String markdown() {
        return RepoFiles.readRepositoryFile("ROADMAP.md");
    }

    private static String inApp() {
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

    /** The one assertion this whole file exists for. */
    @Test public void everyActiveMilestoneAppearsInBothRoadmaps() {
        String file = markdown();
        String page = inApp();
        for (String milestone : OrbitRoadmap.MILESTONES) {
            assertTrue(milestone + " is an active milestone and must be in ROADMAP.md",
                    file.contains(milestone));
            assertTrue(milestone + " is an active milestone and must be in the in-app Roadmap",
                    page.contains(milestone));
        }
    }








    /**
     * Neither roadmap may present this release's own work as something still to come.
     *
     * <p>The Vault itself, saving from Orbit's surfaces, and Beta 4's filters and pinning are all
     * shipped by the time this runs. What is future is Rich Answers and Smart Vault.
     */
    @Test public void shippedVaultWorkIsNotOfferedAsFutureInTheApp() {
        String page = inApp();
        for (String shipped : new String[]{"Quick Capture", "Attach from Vault",
                "Save page to Vault", "Vault and Quick Capture as GrokLink Deck destinations"}) {
            assertTrue(shipped + " has shipped and must not be listed as upcoming",
                    !page.contains(shipped));
        }
    }
}
