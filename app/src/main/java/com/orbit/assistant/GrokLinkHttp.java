package com.orbit.assistant;

/** The one User-Agent GrokLink sends on its own HTTP requests. */
final class GrokLinkHttp {
    private GrokLinkHttp() {}

    static String userAgent() {
        return "GrokLink/" + BuildConfig.VERSION_NAME + " (Android; +https://github.com/tmg270/orbit-fork)";
    }
}
