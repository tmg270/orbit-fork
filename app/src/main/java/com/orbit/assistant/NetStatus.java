package com.orbit.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Whether Grok can be asked right now. A missing network is not treated as a failure of the command. */
public final class NetStatus {
    private NetStatus() {}

    public static boolean online(Context c) {
        if (c == null) return false;
        Object service = c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!(service instanceof ConnectivityManager)) return false;
        ConnectivityManager cm = (ConnectivityManager) service;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
