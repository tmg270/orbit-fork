package com.orbit.assistant;

/**
 * One model answer waiting for the normal send path to deliver it, so chat and the overlay do not
 * grow a second way to draw a reply.
 */
final class LocalIntentCache {
    private static String prompt;
    private static AssistantReply reply;

    private LocalIntentCache() {}

    static synchronized void put(String userPrompt, AssistantReply value) {
        prompt = userPrompt == null ? "" : userPrompt;
        reply = value;
    }

    static synchronized AssistantReply take(String userPrompt) {
        if (reply == null) return null;
        String key = userPrompt == null ? "" : userPrompt;
        if (!key.equals(prompt)) return null;
        AssistantReply out = reply;
        prompt = null;
        reply = null;
        return out;
    }
}
