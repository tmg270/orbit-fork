package com.orbit.assistant;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Looks for a published GrokLink build while the app is not open.
 *
 * <p>The check is the same one Settings runs. A newer build becomes a notification, once, when
 * update notifications are allowed. The download still waits until somebody asks for it.
 */
public final class GrokLinkUpdateWorker extends Worker {
    public GrokLinkUpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        if (!OrbitDistribution.selfUpdates()) return Result.success();
        if ("robolectric".equals(android.os.Build.FINGERPRINT)) return Result.success();
        try {
            GrokLinkUpdateManifest manifest = GrokLinkUpdater.fetchAndStore(getApplicationContext());
            if (manifest != null && manifest.newerThan(BuildConfig.VERSION_CODE)) {
                GrokLinkUpdater.notifyIfWanted(getApplicationContext(), manifest);
            }
            return Result.success();
        } catch (GrokLinkUpdateException e) {
            return e.retry ? Result.retry() : Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
