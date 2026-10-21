package com.orbit.assistant;

import android.content.Context;

import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions;

/**
 * One MediaPipe LLM Inference session for the downloaded {@code .task} model.
 *
 * <p>Loaded only after the file is on disk. A missing native library or a phone that cannot run
 * the model returns null so the request can still go to Grok.
 */
final class LocalLlmEngine {
    private static LlmInference engine;
    private static String loadedPath;

    private LocalLlmEngine() {}

    static synchronized String generate(Context context, String modelPath, String prompt) {
        if (context == null || modelPath == null || prompt == null || prompt.isEmpty()) return null;
        try {
            if (engine == null || !modelPath.equals(loadedPath)) {
                close();
                LlmInferenceOptions options = LlmInferenceOptions.builder()
                        .setModelPath(modelPath)
                        // The file is built with a 4096-token cache (ekv4096). A smaller cap
                        // does not match that export and the session refuses to start.
                        .setMaxTokens(4096)
                        .setMaxTopK(1)
                        .build();
                engine = LlmInference.createFromOptions(context.getApplicationContext(), options);
                loadedPath = modelPath;
            }
            String out = engine.generateResponse(prompt);
            return out == null ? "" : out;
        } catch (Throwable ignored) {
            close();
            return null;
        }
    }

    static synchronized void close() {
        if (engine != null) {
            try { engine.close(); } catch (Throwable ignored) {}
        }
        engine = null;
        loadedPath = null;
    }
}
