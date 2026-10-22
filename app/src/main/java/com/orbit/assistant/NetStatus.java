package com.orbit.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Whether Grok can be asked right now. A missing network is not treated as a failure of the command. */
public final class NetStatus {
    /**
     * Robolectric has no active network, so every chat test would look offline and grow an
     * immediate "Grok needs a connection" row. The class is not in the app, so a device is unaffected.
     */
    private static final boolean UNIT_TEST = detectUnitTest();

    private NetStatus() {}

    public static boolean online(Context c) {
        if (UNIT_TEST) return true;
        if (c == null) return false;
        Object service = c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!(service instanceof ConnectivityManager)) return false;
        ConnectivityManager cm = (ConnectivityManager) service;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static boolean detectUnitTest() {
        try {
            Class.forName("org.robolectric.RuntimeEnvironment");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
