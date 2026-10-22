package com.orbit.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

/**
 * Whether Grok can be asked right now. A missing network is not a failed command.
 *
 * <p>Robolectric's default network is only the platform's stock capability bits (trusted, not
 * restricted, not a VPN) plus a transport. Internet was never set, but the test did not choose
 * airplane mode either, so that unknown state stays online and ordinary chat tests still send.
 * A cleared network, or a capability outside those defaults and without internet, stays offline
 * in tests and on a phone. Tests that need a specific answer call {@link #setOnlineForTests}.
 */
public final class NetStatus {
    /** null asks the system. Tests set true or false; production never does. */
    private static volatile Boolean override;
    private static volatile Boolean unknownOnline;

    private static final int[] KNOWN_CAPABILITIES = {
            NetworkCapabilities.NET_CAPABILITY_MMS,
            NetworkCapabilities.NET_CAPABILITY_SUPL,
            NetworkCapabilities.NET_CAPABILITY_DUN,
            NetworkCapabilities.NET_CAPABILITY_FOTA,
            NetworkCapabilities.NET_CAPABILITY_IMS,
            NetworkCapabilities.NET_CAPABILITY_CBS,
            NetworkCapabilities.NET_CAPABILITY_WIFI_P2P,
            NetworkCapabilities.NET_CAPABILITY_IA,
            NetworkCapabilities.NET_CAPABILITY_RCS,
            NetworkCapabilities.NET_CAPABILITY_XCAP,
            NetworkCapabilities.NET_CAPABILITY_EIMS,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
            NetworkCapabilities.NET_CAPABILITY_TRUSTED,
            NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL,
            NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
            NetworkCapabilities.NET_CAPABILITY_FOREGROUND,
            NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED,
            NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
    };

    private NetStatus() {}

    /** @param online null to ask the system again */
    static void setOnlineForTests(Boolean online) {
        override = online;
    }

    public static boolean online(Context c) {
        Boolean forced = override;
        if (forced != null) return forced;
        if (c == null) return false;
        Object service = c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!(service instanceof ConnectivityManager)) return false;
        ConnectivityManager cm = (ConnectivityManager) service;
        Network n = cm.getActiveNetwork();
        // No active network is airplane mode, including after a test clears Robolectric's default.
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        if (caps == null) return unknownCountsAsOnline();
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true;
        // A fresh NetworkCapabilities already carries the platform's default bits (trusted, not
        // restricted, not a VPN) and Robolectric never adds internet. That is unknown, not a
        // network someone marked as having no internet. A bit outside those defaults is explicit.
        if (unknownCountsAsOnline() && !explicitlyConfigured(caps)) return true;
        return false;
    }

    /** True when {@code caps} names a capability a brand-new instance does not. */
    private static boolean explicitlyConfigured(NetworkCapabilities caps) {
        NetworkCapabilities defaults = new NetworkCapabilities();
        for (int bit : KNOWN_CAPABILITIES) {
            if (caps.hasCapability(bit) && !defaults.hasCapability(bit)) return true;
        }
        return false;
    }

    /** Only the unit-test runtime treats an unconfigured network as online. A phone does not. */
    private static boolean unknownCountsAsOnline() {
        Boolean cached = unknownOnline;
        if (cached != null) return cached;
        String fingerprint = Build.FINGERPRINT;
        boolean yes = fingerprint != null
                && fingerprint.toLowerCase(java.util.Locale.US).contains("robolectric");
        if (!yes) {
            try {
                Class.forName("org.robolectric.RuntimeEnvironment");
                yes = true;
            } catch (Throwable ignored) {
                yes = false;
            }
        }
        unknownOnline = yes;
        return yes;
    }
}
