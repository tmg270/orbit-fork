package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/** About & updates links to Orbit's repository, in the browser, without any way to crash. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class AboutLinksTest {
    private static final String REPO = "https://github.com/lpnovi/GrokLink-Assistant";

    @org.junit.Before public void setUp() {
        TestWorkManager.ensureInitialized(RuntimeEnvironment.getApplication());
    }



    @Test public void noBrowserMeansAMessageNotACrash() {
        android.content.ContextWrapper noBrowser =
                new android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
                    @Override public void startActivity(Intent intent) {
                        throw new android.content.ActivityNotFoundException();
                    }
                };
        assertFalse(UpdateActivity.openExternal(noBrowser, REPO));
        assertEquals("No app on this phone can open that link", ShadowToast.getTextOfLatestToast());
    }


    private static View page() {
        return Robolectric.buildActivity(UpdateActivity.class).setup().get()
                .getWindow().getDecorView();
    }

    private static TextView text(View view, String s) {
        if (view instanceof TextView && s.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = text(g.getChildAt(i), s);
                if (found != null) return found;
            }
        }
        return null;
    }
}
