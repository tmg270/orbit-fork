package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic protocol fixtures for Anthropic Messages and xAI Chat Completions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ProviderRequestMapperTest {


    @Test public void xaiUsesSystemRoleOpenAiImagesAndOnlyValidReasoningEffort() throws Exception {
        Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        AiRequest request = AiRequest.builder().prompt("Describe it").images(Arrays.asList(image))
                .selection(AiSelection.of(Prefs.PROVIDER_XAI, OrbitModelCatalog.GROK_4_7,
                        AiStrength.XHIGH)).build();
        JSONObject root = ProviderRequestMapper.xai(null, request, true);
        assertEquals("xhigh", root.getString("reasoning_effort"));
        JSONArray messages = root.getJSONArray("messages");
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        JSONArray content = messages.getJSONObject(1).getJSONArray("content");
        assertEquals("image_url", content.getJSONObject(1).getString("type"));
        assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url")
                .startsWith("data:image/jpeg;base64,"));
    }


    @Test public void errorMappingIsUsefulAndNeverReturnsRawPayload() {
        String secretPayload = "{\"error\":\"secret-provider-payload\"}";
        assertTrue(ApiKeyProviderClient.error(Prefs.PROVIDER_ANTHROPIC, 401, secretPayload,
                OrbitModelCatalog.CLAUDE_OPUS_5_5).contains("API key is invalid"));
        assertTrue(ApiKeyProviderClient.error(Prefs.PROVIDER_XAI, 429, secretPayload,
                OrbitModelCatalog.GROK_4_7).contains("rate limit"));
        assertTrue(ApiKeyProviderClient.error(Prefs.PROVIDER_ANTHROPIC, 413, secretPayload,
                OrbitModelCatalog.CLAUDE_OPUS_5_5).contains("too large"));
        assertTrue(ApiKeyProviderClient.error(Prefs.PROVIDER_ANTHROPIC, 529, secretPayload,
                OrbitModelCatalog.CLAUDE_OPUS_5_5).contains("overloaded"));
        assertFalse(ApiKeyProviderClient.error(Prefs.PROVIDER_XAI, 500, secretPayload,
                OrbitModelCatalog.GROK_4_7).contains("secret-provider-payload"));
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static AssistantClient.Callback callback(List<String> deltas) {
        return new AssistantClient.Callback() {
            @Override public void onDelta(String text) { deltas.add(text); }
            @Override public void onSuccess(AssistantReply reply) {}
            @Override public void onError(String message) {}
        };
    }
}
