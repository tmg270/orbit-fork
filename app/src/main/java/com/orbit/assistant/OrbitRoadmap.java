package com.orbit.assistant;

/** The milestones GrokLink is working towards, shared by the in-app Roadmap and ROADMAP.md. */
public final class OrbitRoadmap {

    /** The active milestone. */
    public static final String CURRENT = "SuperGrok sign-in";

    /** Android Auto voice questions. */
    public static final String ANDROID_AUTO = "Android Auto";

    /** Smart Vault, powered by Grok. */
    public static final String SMART_VAULT = "Smart Vault";

    /** Every milestone both roadmaps must agree about. */
    public static final String[] MILESTONES = {CURRENT, ANDROID_AUTO, SMART_VAULT};

    private OrbitRoadmap() {}
}
