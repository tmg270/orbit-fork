package com.orbit.assistant;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Receives the package installer's status and shows Android's own Install screen.
 *
 * <p>The session is created elsewhere. This page only forwards the confirmation Android already
 * prepared, then closes. The user still taps Install. Nothing is installed from here on its own.
 */
public final class GrokLinkInstallActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!OrbitDistribution.selfUpdates()) {
            finish();
            return;
        }
        Intent intent = getIntent();
        int status = intent == null
                ? PackageInstaller.STATUS_FAILURE
                : intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = confirmIntent(intent);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(confirm);
                } catch (Exception e) {
                    toast("Couldn't open the install screen. Allow installs from GrokLink, then try again.");
                }
            } else {
                toast("Android didn't offer an install confirmation.");
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            // The new build is replacing this process. Nothing else to show.
        } else if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            String detail = intent == null ? "" : intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (detail == null || detail.length() > 140) detail = "";
            toast(detail.isEmpty() ? "The install didn't finish." : "The install didn't finish. " + detail);
        } else {
            toast("Install cancelled.");
        }
        finish();
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    @SuppressWarnings("deprecation")
    private static Intent confirmIntent(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }
}
