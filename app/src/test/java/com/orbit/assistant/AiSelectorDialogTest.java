package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The shared provider/model/strength picker shows only what is legal and updates in place. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AiSelectorDialogTest {
    private Activity activity;

    @Before public void setUp() {
        Prefs.get(RuntimeEnvironment.getApplication()).edit().clear().commit();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }




    @Test public void orbitLocalShowsNoStrengthControls() {
        AiSelectorDialog picker = AiSelectorDialog.forTest(activity,
                AiSelection.of(Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL, null));
        assertTrue(picker.offeredStrengths().isEmpty());
    }
}
