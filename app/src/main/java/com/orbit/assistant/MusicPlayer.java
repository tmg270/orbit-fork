package com.orbit.assistant;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * "Play Kasabian on Poweramp" for the phone and the car.
 *
 * <p>Order of preference, because Android 10+ blocks activity starts from the background (the car
 * service is always in the background):
 * <ol>
 * <li>Poweramp: its documented API (github.com/maxmpz/powerampapi, PowerampAPI.java) accepts
 *     {@code MEDIA_PLAY_FROM_SEARCH} as an explicit broadcast to
 *     {@code com.maxmpz.audioplayer.player.PowerampAPIReceiver} (build 853+, "recommended"). A
 *     broadcast is not an activity start, so it works from the background.</li>
 * <li>Any other named app with a MediaBrowserService: connect a {@link MediaBrowserCompat} and call
 *     {@code getTransportControls().playFromSearch(query, extras)}. This is what Android Auto itself
 *     does, works from the background, and is the preferred generic path.</li>
 * <li>Last resort: {@code startActivity(MEDIA_PLAY_FROM_SEARCH)}, targeted at the app if named. This
 *     works when GrokLink is in the foreground on the phone but may be blocked from the car.</li>
 * </ol>
 */
final class MusicPlayer {
    private MusicPlayer() {}

    static final String TAG = "OrbitAuto";
    static final String POWERAMP = "com.maxmpz.audioplayer";
    static final String POWERAMP_RECEIVER = "com.maxmpz.audioplayer.player.PowerampAPIReceiver";
    private static final String BROWSER_SERVICE = "android.media.browse.MediaBrowserService";

    /** Spoken app name to package. Unknown names return null (use the default player). */
    static String packageFor(String app) {
        String a = app == null ? "" : app.toLowerCase(Locale.UK).replaceAll("[^a-z ]", "").trim();
        if (a.isEmpty()) return null;
        if (a.contains("poweramp") || a.contains("power amp")) return POWERAMP;
        if (a.contains("spotify")) return "com.spotify.music";
        if (a.contains("youtube")) return "com.google.android.apps.youtube.music";
        if (a.contains("deezer")) return "deezer.android.app";
        if (a.contains("tidal")) return "com.aspiro.tidal";
        if (a.contains("amazon")) return "com.amazon.mp3";
        if (a.contains("apple")) return "com.apple.android.music";
        if (a.contains("samsung")) return "com.sec.android.app.music";
        return null;
    }

    static String displayName(String pkg, String fallback) {
        if (POWERAMP.equals(pkg)) return "Poweramp";
        if ("com.spotify.music".equals(pkg)) return "Spotify";
        if ("com.google.android.apps.youtube.music".equals(pkg)) return "YouTube Music";
        return fallback == null || fallback.trim().isEmpty() ? "your music app" : fallback.trim();
    }

    /** Extras for MEDIA_PLAY_FROM_SEARCH: focus is artist, album, title or empty for unstructured. */
    static Bundle searchExtras(String query, String artist, String album, String title) {
        Bundle b = new Bundle();
        b.putString(SearchManager.QUERY, query);
        if (title != null && !title.isEmpty()) {
            b.putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio");
            b.putString(MediaStore.EXTRA_MEDIA_TITLE, title);
            if (artist != null && !artist.isEmpty()) b.putString(MediaStore.EXTRA_MEDIA_ARTIST, artist);
            if (album != null && !album.isEmpty()) b.putString(MediaStore.EXTRA_MEDIA_ALBUM, album);
        } else if (album != null && !album.isEmpty()) {
            b.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE);
            b.putString(MediaStore.EXTRA_MEDIA_ALBUM, album);
            if (artist != null && !artist.isEmpty()) b.putString(MediaStore.EXTRA_MEDIA_ARTIST, artist);
        } else if (artist != null && !artist.isEmpty()) {
            b.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE);
            b.putString(MediaStore.EXTRA_MEDIA_ARTIST, artist);
        } else {
            b.putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        }
        return b;
    }

    static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    /** Returns a short past-tense-free description, e.g. "Playing Kasabian on Poweramp". */
    static DeviceActionExecutor.Result play(Context c, String query, String artist, String album,
                                            String title, String app) {
        String q = query == null || query.trim().isEmpty()
                ? String.join(" ", nz(title), nz(artist), nz(album)).trim() : query.trim();
        if (q.isEmpty()) return DeviceActionExecutor.Result.failed("Tell me what to play");
        String pkg = packageFor(app);
        Bundle extras = searchExtras(q, artist, album, title);
        String name = displayName(pkg, app);
        if (pkg != null && !installed(c, pkg)) {
            return DeviceActionExecutor.Result.unavailable(name + " is not installed on this phone");
        }
        if (POWERAMP.equals(pkg)) {
            Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .setComponent(new ComponentName(POWERAMP, POWERAMP_RECEIVER)).putExtras(extras);
            c.sendBroadcast(i);
            Log.i(TAG, "music: Poweramp broadcast PLAY_FROM_SEARCH '" + q + "'");
            return DeviceActionExecutor.Result.success("Playing " + q + " on Poweramp");
        }
        if (pkg != null) {
            ComponentName svc = browserService(c, pkg);
            if (svc != null) {
                playViaBrowser(c, svc, q, extras);
                return DeviceActionExecutor.Result.success("Playing " + q + " on " + name);
            }
        }
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtras(extras)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) i.setPackage(pkg);
        if (i.resolveActivity(c.getPackageManager()) == null) {
            return DeviceActionExecutor.Result.unavailable("No music app here can play from a search");
        }
        c.startActivity(i);
        Log.i(TAG, "music: startActivity PLAY_FROM_SEARCH '" + q + "' pkg=" + pkg);
        return DeviceActionExecutor.Result.success("Playing " + q + (pkg == null ? "" : " on " + name));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static ComponentName browserService(Context c, String pkg) {
        List<ResolveInfo> list = c.getPackageManager().queryIntentServices(
                new Intent(BROWSER_SERVICE).setPackage(pkg), 0);
        if (list == null || list.isEmpty() || list.get(0).serviceInfo == null) return null;
        return new ComponentName(pkg, list.get(0).serviceInfo.name);
    }

    /** MediaBrowser connections must be made on a Looper thread, so this hops to main. */
    private static void playViaBrowser(Context c, ComponentName svc, String q, Bundle extras) {
        Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            final MediaBrowserCompat[] holder = new MediaBrowserCompat[1];
            holder[0] = new MediaBrowserCompat(app, svc, new MediaBrowserCompat.ConnectionCallback() {
                @Override public void onConnected() {
                    try {
                        MediaControllerCompat mc = new MediaControllerCompat(app, holder[0].getSessionToken());
                        mc.getTransportControls().playFromSearch(q, extras);
                        Log.i(TAG, "music: playFromSearch via " + svc.flattenToShortString());
                    } catch (Exception e) {
                        Log.e(TAG, "music: playFromSearch failed", e);
                    } finally {
                        new Handler(Looper.getMainLooper()).postDelayed(holder[0]::disconnect, 5000);
                    }
                }
                @Override public void onConnectionFailed() {
                    Log.w(TAG, "music: browser connection refused by " + svc.flattenToShortString());
                }
            }, null);
            holder[0].connect();
        });
    }
}
