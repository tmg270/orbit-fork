package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Smart Routing's policy, from fixed fixtures (0.8.3.0-beta.5).
 *
 * <p>Every case here is the pure routing decision: a request described by counts and signals, and
 * a fixed set of candidates with known permission and readiness. No provider is called, nothing is
 * paid for, and the same fixture must always give the same route.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SmartRouterPolicyTest {

    @After public void tearDown() {
        OrbitModelCatalog.clearDynamicForTest();
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static SmartRouter.Candidate candidate(String provider, String model) {
        for (SmartRouter.Candidate c : SmartRouter.CANDIDATES) {
            if (c.provider.equals(provider) && c.model.equals(model)) return c;
        }
        throw new AssertionError("not a candidate: " + provider + "/" + model);
    }

    private static SmartRouter.Option option(String provider, String model, boolean permitted,
                                             boolean ready) {
        return option(provider, model, permitted, ready, false, false);
    }

    private static SmartRouter.Option option(String provider, String model, boolean permitted,
                                             boolean ready, boolean unavailable, boolean favorite) {
        AiCapabilities caps = AiProviders.byId(provider).capabilities();
        return new SmartRouter.Option(candidate(provider, model),
                OrbitModelCatalog.spec(provider, model), permitted, ready, unavailable, favorite,
                caps.images, caps.deviceActions, caps.hostedWebSearch);
    }

    /**
     * The phone as most people will have it: ChatGPT signed in, Orbit Local ready, and Anthropic and
     * xAI connected but not enabled for Auto.
     */
    private static List<SmartRouter.Option> defaults(boolean localReady) {
        return new ArrayList<>(Arrays.asList(
                option(Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL, true, localReady),
                option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, true, true),
                option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, true, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_HAIKU_4_5, false, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_SONNET_5_5, false, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_OPUS_5_5, false, true),
                option(Prefs.PROVIDER_XAI, OrbitModelCatalog.GROK_4_7, false, true)));
    }

    /** Only the metered providers, both enabled: ChatGPT and Local are off. */
    private static List<SmartRouter.Option> meteredOnly() {
        return new ArrayList<>(Arrays.asList(
                option(Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL, false, true),
                option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, false, true),
                option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, false, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_HAIKU_4_5, true, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_SONNET_5_5, true, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_OPUS_5_5, true, true),
                option(Prefs.PROVIDER_XAI, OrbitModelCatalog.GROK_4_7, true, true)));
    }

    private static SmartRouter.Request text(String prompt) {
        return new SmartRouter.Request(prompt, 0, 0, 2_000, 200);
    }

    private static SmartRouter.Request sized(String prompt, int tokens) {
        return new SmartRouter.Request(prompt, 0, 0, tokens, tokens);
    }

    private static void assertRoute(SmartRouter.Route route, String provider, String model,
                                    AiStrength strength) {
        assertTrue("routed: " + route.error, route.ok());
        assertEquals(provider, route.selection.provider);
        assertEquals(model, route.selection.model);
        assertEquals(strength, route.selection.strength);
        assertEquals(SmartRouter.POLICY_VERSION, route.policy);
    }

    private static final String HARD = "Prove that the square root of two is irrational, step by "
            + "step, and explain why the proof works.";
    private static final String VERY_HARD = "Debug and optimize this algorithm, then analyze the "
            + "trade-offs of the architecture step by step:\n```java\npublic int f(int n) { "
            + "return n < 2 ? n : f(n - 1) + f(n - 2); }\n```\nWhy is it slow, and how should the "
            + "design change for millions of calls per second with strict latency targets?";

    // ---- demand ----------------------------------------------------------------------------------

    @Test public void demandIsReadFromCountsAndAFewPlainSignals() {
        assertEquals(SmartRouter.Demand.SIMPLE, SmartRouter.demand("hi there", 0, 0, 0));
        assertEquals(SmartRouter.Demand.SIMPLE, SmartRouter.demand("Thanks, that helps!", 0, 0, 0));
        assertEquals("an explanation is ordinary conversation, never a tiny request",
                SmartRouter.Demand.NORMAL, SmartRouter.demand("Explain quantum computing", 0, 0, 0));
        assertEquals("an image is never a simple request", SmartRouter.Demand.NORMAL,
                SmartRouter.demand("What is this?", 1, 0, 0));
        assertEquals(SmartRouter.Demand.NORMAL, SmartRouter.demand(
                "Can you help me write a short, friendly note to my neighbour about their dog?",
                0, 0, 0));
        assertEquals(SmartRouter.Demand.COMPLEX, SmartRouter.demand(HARD, 0, 0, 0));
        assertEquals(SmartRouter.Demand.VERY_COMPLEX, SmartRouter.demand(VERY_HARD, 0, 0, 0));
        assertEquals("a long document is more than ordinary conversation",
                SmartRouter.Demand.COMPLEX, SmartRouter.demand("Summarize this", 0, 60_000, 0));
    }

    // ---- simple and Local ------------------------------------------------------------------------







    // ---- ordinary and harder requests --------------------------------------------------------------




    // ---- images ----------------------------------------------------------------------------------



    // ---- context ---------------------------------------------------------------------------------



    @Test public void contextFitLeavesRoomForTheAnswer() {
        assertTrue(SmartRouter.fits(100_000, 200_000));
        assertFalse("a request that would fill the window leaves no room to answer",
                SmartRouter.fits(195_000, 200_000));
        assertFalse("an unknown window never fits", SmartRouter.fits(10, 0));
    }



    // ---- availability ----------------------------------------------------------------------------




    // ---- preferences and ties --------------------------------------------------------------------




    // ---- stability -------------------------------------------------------------------------------



    // ---- the curated set -------------------------------------------------------------------------

}
