package com.orbit.assistant;

import android.app.Activity;
import android.content.Context;

import androidx.work.WorkManager;

import java.io.File;

/**
 * Play edition: Google Play delivers updates. This build does not look for a GrokLink APK and
 * does not hand one to the installer.
 */
public final class GrokLinkUpdater {
    public static final String NEED_PERMISSION = "need-unknown-apps";

    private GrokLinkUpdater() {}

    public interface CheckCallback {
        void onChecked(GrokLinkUpdateManifest published, boolean newer, String error);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onConfirm();
        void onError(String message);
    }

    public static void schedule(Context context) {
        if (context == null) return;
        try {
            WorkManager wm = WorkManager.getInstance(context);
            wm.cancelUniqueWork("groklink-update-check");
            wm.cancelUniqueWork("orbit-stable-update-check");
        } catch (Exception ignored) {
            // Nothing was scheduled.
        }
    }

    public static void refreshSoon(Context context, CheckCallback callback) {
        if (callback != null) callback.onChecked(null, false, null);
    }

    public static void checkNow(Context context, CheckCallback callback) {
        if (callback != null) callback.onChecked(null, false, null);
    }

    public static long seenCode(Context context) {
        return 0L;
    }

    public static void rememberOffer(Context context, long versionCode) {}

    public static boolean canRequestPackageInstalls(Context context) {
        return false;
    }

    public static void openUnknownSourcesSettings(Activity activity) {
        throw new UnsupportedOperationException(OrbitDistribution.PLAY_REFUSAL);
    }

    public static void downloadAndInstall(Activity activity, GrokLinkUpdateManifest manifest,
                                           DownloadCallback callback) {
        if (callback != null) callback.onError(OrbitDistribution.PLAY_REFUSAL);
    }

    public static File updateDirectory(Context context) {
        return new File(context.getCacheDir(), "updates");
    }

    static GrokLinkUpdateManifest fetchAndStore(Context context) {
        throw new GrokLinkUpdateException(OrbitDistribution.PLAY_REFUSAL, false);
    }

    static void notifyIfWanted(Context context, GrokLinkUpdateManifest manifest) {}
}
