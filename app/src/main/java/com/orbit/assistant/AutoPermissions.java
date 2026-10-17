package com.orbit.assistant;

import android.content.Context;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * GrokLink: Auto only ever routes to xAI / Grok. The permission is locked on and the old
 * per-provider switches (ChatGPT, Local, Anthropic, OpenRouter) are ignored and cleared.
 */
public final class AutoPermissions {

    public static final List<String> PROVIDERS =
            Collections.unmodifiableList(Collections.singletonList(Prefs.PROVIDER_XAI));

    private AutoPermissions() {}

    /** Not metered: SuperGrok sign-in uses the user's subscription; Auto stays xAI-only either way. */
    public static boolean metered(String provider) { return false; }

    static String key(String provider) {
        return Prefs.PROVIDER_XAI.equals(provider) ? Prefs.AUTO_USE_XAI : null;
    }

    static boolean defaultFor(String provider) { return Prefs.PROVIDER_XAI.equals(provider); }

    public static boolean allows(Context c, String provider) {
        return c != null && Prefs.PROVIDER_XAI.equals(provider);
    }

    /** Locked: no switch can change xAI-only routing. */
    public static void set(Context c, String provider, boolean allowed) {}

    static void revokeOnCredentialRemoval(Context c, String provider) {}

    /** One-time cleanup of the upstream multi-provider Auto switches. */
    public static void migrate(Context c) {
        if (c == null) return;
        Prefs.get(c).edit()
                .remove(Prefs.AUTO_USE_CHATGPT).remove(Prefs.AUTO_USE_LOCAL)
                .remove(Prefs.AUTO_USE_ANTHROPIC).remove(Prefs.AUTO_USE_OPENROUTER)
                .putBoolean(Prefs.AUTO_USE_XAI, true).apply();
    }

    public static String summary(Context c) { return "xAI / Grok"; }

    static String shortName(String provider) {
        return Prefs.PROVIDER_XAI.equals(provider) ? "xAI" : AiProviders.byId(provider).displayName();
    }
}
