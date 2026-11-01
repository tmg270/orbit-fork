package com.orbit.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * The Bug control from a surface that is not itself an activity, such as the side-button overlay.
 * The dialog is the same Report a bug screen the chat list opens.
 */
public final class BugReportActivity extends Activity {
    public static void open(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, BugReportActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            finish();
            return;
        }
        BugReportPrompt.show(this);
    }
}
