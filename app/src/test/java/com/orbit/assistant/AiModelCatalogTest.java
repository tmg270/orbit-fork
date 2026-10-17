package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The 0.8.3.0 model catalog: exact ids, names, strengths, and honest availability handling.
 *
 * <p>Successor of AstraModelTest. What that test protected (no invented names, no unsupported
 * effort reaching the backend, network failures never reported as entitlement problems, nothing
 * polling for an allowance) is still protected here; what it protected about Auto routing and the
 * Astra-to-Sol fallback is gone with those features.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AiModelCatalogTest {

    private static List<String> ids(List<AiModelSpec> specs) {
        List<String> out = new ArrayList<>();
        for (AiModelSpec s : specs) out.add(s.id);
        return out;
    }





    @Test public void backendEffortIdsAreExact() {
        assertEquals("none", AiStrength.NONE.id);
        assertEquals("low", AiStrength.LOW.id);
        assertEquals("medium", AiStrength.MEDIUM.id);
        assertEquals("high", AiStrength.HIGH.id);
        assertEquals("xhigh", AiStrength.XHIGH.id);
        assertEquals("max", AiStrength.MAX.id);
        assertEquals("Extra High", AiStrength.XHIGH.label);
        assertEquals(AiStrength.XHIGH, AiStrength.fromId("Extra High"));
        assertNull(AiStrength.fromId("turbo"));
    }

    @Test public void everySupportedStrengthIsSentAsChosen() {
        for (AiModelSpec spec : OrbitModelCatalog.modelsFor(Prefs.PROVIDER_CHATGPT)) {
            for (AiStrength s : spec.strengths) assertEquals(s, spec.resolveStrength(s));
        }
    }

    @Test public void everyOfficialChatGptModelCarriesTheVerifiedContextWindow() {
        for (AiModelSpec spec : OrbitModelCatalog.modelsFor(Prefs.PROVIDER_CHATGPT)) {
            assertEquals(spec.id, 1_050_000, spec.contextWindowTokens);
        }
    }








    // ---- availability --------------------------------------------------------------------------

    @Test public void aRealEntitlementRefusalIsRecognisedForAnyModel() {
        assertTrue(OrbitModelCatalog.looksUnavailable(OrbitModelCatalog.ASTRA,
                "The ChatGPT/Codex backend did not make gpt-6-astra available for this request."));
        assertTrue(OrbitModelCatalog.looksUnavailable(OrbitModelCatalog.SOL,
                "ChatGPT request failed (HTTP 400): The model gpt-6.1-sol is not supported when using Codex with a ChatGPT account."));
        assertTrue(OrbitModelCatalog.looksUnavailable(OrbitModelCatalog.LUNA,
                "Your account does not have access to model gpt-6-luna"));
    }

    @Test public void networkFailuresAreNeverEntitlementProblems() {
        for (String error : new String[]{
                "ChatGPT request failed: timeout",
                "ChatGPT request failed: Unable to resolve host \"chatgpt.com\": No address associated with hostname",
                "ChatGPT request failed: Connection reset",
                "GrokLink could not reach the network. The model is unavailable offline.",
                "java.net.SocketTimeoutException: Read timed out",
                "ChatGPT request failed: UnknownHostException"}) {
            for (String model : OrbitModelCatalog.currentModelIds()) {
                assertFalse(error, OrbitModelCatalog.looksUnavailable(model, error));
            }
        }
        assertFalse("an unrelated error is not about a model",
                OrbitModelCatalog.looksUnavailable(OrbitModelCatalog.SOL, "Rate limit reached. Try again later."));
    }



    @Test public void nothingPollsForAnAllowance() {
        String catalog = RepoFiles.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitModelCatalog.java");
        for (String forbidden : new String[]{"HttpURLConnection", "quota", "remaining", "usage"}) {
            assertFalse(forbidden, catalog.contains(forbidden));
        }
    }

    @Test public void modelIdentifiersLiveOnlyInTheCatalog() {
        for (String file : new String[]{"Prefs.java", "ThinkingUpdate.java", "SettingsActivity.java",
                "ChatActivity.java", "OrbitSession.java"}) {
            String source = RepoFiles.readRepositoryFile(
                    "app/src/main/java/com/orbit/assistant/" + file);
            assertFalse(file, source.contains("\"gpt-6-luna\"") || source.contains("\"gpt-6.1-sol\"")
                    || source.contains("\"gpt-6-astra\"") || source.contains("\"gpt-5.6-terra\""));
        }
    }

}
