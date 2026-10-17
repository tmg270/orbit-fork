package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The provider registry is the seam the 0.7.7 line stands on: explicit selection, honest
 * capability metadata, and no silent substitution. These tests pin that contract.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AiProvidersTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @Test public void everyProviderIdIsKnownToPrefs() {
        for (AiProvider p : AiProviders.all()) {
            assertEquals("provider ids must survive preference normalization",
                    p.id(), Prefs.normalizeProvider(p.id()));
        }
    }










    /** Status and offered actions must always agree: not-ready providers are not selectable UI. */
    @Test public void cardActionsFollowProviderState() {
        assertEquals(java.util.Arrays.asList(
                        AiProvidersActivity.ACTION_USE, AiProvidersActivity.ACTION_MANAGE),
                AiProvidersActivity.actionLabels(AiProvider.Status.READY, false));
        assertEquals("the active provider needs no Use button",
                java.util.Collections.singletonList(AiProvidersActivity.ACTION_MANAGE),
                AiProvidersActivity.actionLabels(AiProvider.Status.READY, true));
        assertEquals("an unconfigured provider offers only the step that makes it usable",
                java.util.Collections.singletonList(AiProvidersActivity.ACTION_SET_UP),
                AiProvidersActivity.actionLabels(AiProvider.Status.NEEDS_SETUP, false));
        assertEquals(java.util.Collections.singletonList(AiProvidersActivity.ACTION_SET_UP),
                AiProvidersActivity.actionLabels(AiProvider.Status.NOT_INSTALLED, false));
        assertEquals(java.util.Collections.singletonList(AiProvidersActivity.ACTION_SET_UP),
                AiProvidersActivity.actionLabels(AiProvider.Status.COMING_SOON, false));
        assertEquals(java.util.Collections.singletonList(AiProvidersActivity.ACTION_DETAILS),
                AiProvidersActivity.actionLabels(AiProvider.Status.UNSUPPORTED, false));
        for (AiProvider.Status status : AiProvider.Status.values()) {
            if (status == AiProvider.Status.READY) continue;
            assertFalse("Use this provider must never appear while " + status,
                    AiProvidersActivity.actionLabels(status, false)
                            .contains(AiProvidersActivity.ACTION_USE));
        }
    }
}
