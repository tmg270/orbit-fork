package com.orbit.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetworkCapabilities;

/**
 * Unconfigured Robolectric stays online so ordinary sends still leave the phone.
 * An explicit offline network, and {@link NetStatus#setOnlineForTests}, still report offline.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class NetStatusTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        NetStatus.setOnlineForTests(null);
    }

    @After public void tearDown() {
        NetStatus.setOnlineForTests(null);
    }

    @Test public void anUnconfiguredNetworkIsOnlineInTests() {
        assertTrue(NetStatus.online(context));
    }

    @Test public void testsCanForceOfflineAndOnline() {
        NetStatus.setOnlineForTests(false);
        assertFalse(NetStatus.online(context));
        NetStatus.setOnlineForTests(true);
        assertTrue(NetStatus.online(context));
        NetStatus.setOnlineForTests(null);
        assertTrue("clearing the override returns to the unconfigured network",
                NetStatus.online(context));
    }

    @Test public void aClearedNetworkIsOfflineWithoutAnOverride() {
        connectivity().clearAllNetworks();
        assertFalse(NetStatus.online(context));
    }

    @Test public void internetCapabilityIsOnlineAndAnyOtherCapabilityIsOffline() {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        ShadowConnectivityManager shadow = Shadows.shadowOf(cm);
        Network network = cm.getActiveNetwork();
        assertNotNull("Robolectric starts with a default network", network);

        NetworkCapabilities online = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(online).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        shadow.setNetworkCapabilities(network, online);
        assertTrue(NetStatus.online(context));

        NetworkCapabilities offline = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(offline).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        shadow.setNetworkCapabilities(network, offline);
        assertFalse(NetStatus.online(context));
    }

    @Test public void aNullContextIsOffline() {
        assertFalse(NetStatus.online(null));
    }

    private ShadowConnectivityManager connectivity() {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return Shadows.shadowOf(cm);
    }
}
