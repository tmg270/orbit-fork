package com.orbit.assistant;

import android.content.Context;

/** Grok through xAI's official OpenAI-compatible inference API and account model catalog. */
final class XaiProvider implements AiProvider {
    private static final AiCapabilities CAPABILITIES = AiCapabilities.builder()
            .streaming(true).deviceActions(true).images(true).multipleImages(true)
            .offline(false).needsCredentials(true).reasoningLevels(true)
            .hostedWebSearch(false).routinePlanning(true).reasoningSummaries(false).build();

    @Override public String id() { return Prefs.PROVIDER_XAI; }
    @Override public String displayName() { return "xAI / Grok"; }
    @Override public String description() { return "Grok models through xAI's official developer API."; }
    @Override public AiCapabilities capabilities() { return CAPABILITIES; }
    @Override public Status status(Context context) {
        return SecureStore.hasXaiCredential(context) ? Status.READY : Status.NEEDS_SETUP;
    }
    @Override public String statusDetail(Context context) {
        if (SecureStore.hasXaiKey(context)) return "API key saved";
        return SecureStore.hasSuperGrokSession(context) ? "Signed in with SuperGrok"
                : "API key or SuperGrok sign-in required";
    }
    @Override public boolean selectable(Context context) { return true; }

    @Override public void send(Context context, AiRequest request, AssistantClient.Callback callback) {
        withCredential(context, callback::onError,
                (key, on401) -> ApiKeyProviderClient.send(context, id(), key, request, callback, on401));
    }

    @Override public void plan(Context context, String prompt, AiSelection selection,
                               AssistantClient.PlanCallback callback) {
        withCredential(context, callback::onError,
                (key, on401) -> ApiKeyProviderClient.complete(context, id(), key, AiSelections.resolve(selection),
                        "Return only the requested valid JSON. Do not add Markdown fences.", prompt,
                        callback, on401));
    }

    @Override public boolean supportsCompletion(Context context) { return SecureStore.hasXaiCredential(context); }

    @Override public void complete(Context context, String instructions, String prompt,
                                   AssistantClient.PlanCallback callback) {
        AiSelection selection = ModelLibraryStore.providerDefault(context, id());
        withCredential(context, callback::onError,
                (key, on401) -> ApiKeyProviderClient.complete(context, id(), key, AiSelections.resolve(selection),
                        instructions, prompt, callback, on401));
    }

    private static final java.util.concurrent.ExecutorService CREDENTIALS =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * A developer API key wins when one is saved. Otherwise the SuperGrok OAuth access token is
     * used as the Bearer credential, refreshed off the main thread first when near expiry.
     */
    private static void withCredential(Context context, java.util.function.Consumer<String> onError,
                                       java.util.function.BiConsumer<String, java.util.function.Supplier<String>> use) {
        String key = SecureStore.loadXaiKey(context);
        // A saved API key is never refreshed or retried: a 401 there means the key itself is bad.
        if (!key.isEmpty()) { use.accept(key, null); return; }
        if (!SecureStore.hasSuperGrokSession(context)) {
            onError.accept("Add an xAI API key or sign in with SuperGrok in Settings > AI Providers first.");
            return;
        }
        CREDENTIALS.execute(() -> {
            try {
                String token = SuperGrokAuth.validAccessToken(context, false);
                if (token.isEmpty()) onError.accept("Sign in with SuperGrok again.");
                else use.accept(token, () -> forcedRefresh(context));
            } catch (SuperGrokAuth.AuthException e) {
                onError.accept(e.getMessage());
            } catch (Exception e) {
                onError.accept("Could not reach xAI. Check the connection and try again.");
            }
        });
    }

    /** One forced SuperGrok refresh after a 401; "" (no retry) when it fails or the session ended. */
    private static String forcedRefresh(Context context) {
        try {
            return SuperGrokAuth.validAccessToken(context, true);
        } catch (Exception e) {
            return "";
        }
    }
}
