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
    static volatile String lastVia = "";

    /** "Poweramp MediaBrowser", "broadcast", or "" when the last play did not confirm. */
    public static String lastVia() { return lastVia == null ? "" : lastVia; }
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

    static final String PREFS = "groklink_music";
    static final String KEY_PREFERRED = "preferred_app";
    static final String MTAG = "OrbitAuto/Music";
    private static final long CONNECT_MS = 3000, VERIFY_MS = 4000;

    /** Preferred player package: the saved setting, else Poweramp when installed, else null. */
    static String preferredPackage(Context c) {
        String saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PREFERRED, null);
        if (saved != null && !saved.isEmpty() && installed(c, saved)) return saved;
        return installed(c, POWERAMP) ? POWERAMP : null;
    }

    static void setPreferredPackage(Context c, String pkg) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PREFERRED, pkg).apply();
    }

    /**
     * Blocking: call off the main thread. Only reports success once playback is confirmed (the
     * player's session reports PLAYING, or the music stream becomes active) within ~4s.
     */
    static DeviceActionExecutor.Result play(Context c, String query, String artist, String album,
                                            String title, String app) {
        String q = query == null || query.trim().isEmpty()
                ? String.join(" ", nz(title), nz(artist), nz(album)).trim() : query.trim();
        if (q.isEmpty()) return DeviceActionExecutor.Result.failed("Tell me what to play");
        String pkg = packageFor(app);
        if (pkg == null) pkg = preferredPackage(c);
        Bundle extras = searchExtras(q, artist, album, title);
        String name = displayName(pkg, app);
        Log.i(MTAG, "play q='" + q + "' artist='" + nz(artist) + "' app='" + nz(app) + "' -> pkg=" + pkg);
        if (pkg != null && !installed(c, pkg)) {
            return DeviceActionExecutor.Result.unavailable(name + " is not installed on this phone");
        }
        String reason = "no music app found";
        MediaControllerCompat mc = null;
        MediaBrowserCompat browser = null;
        if (pkg != null) {
            ComponentName svc = browserService(c, pkg);
            Log.i(MTAG, "browser service for " + pkg + ": " + (svc == null ? "none" : svc.flattenToShortString()));
            if (svc != null) {
                MediaBrowserCompat[] holder = new MediaBrowserCompat[1];
                Boolean ok = connect(c, svc, holder);
                browser = holder[0];
                if (Boolean.TRUE.equals(ok)) {
                    try {
                        mc = new MediaControllerCompat(c.getApplicationContext(), browser.getSessionToken());
                        Log.i(MTAG, "connected, state=" + stateOf(mc) + "; playFromSearch");
                        mc.getTransportControls().playFromSearch(q, extras);
                        if (waitPlaying(c, mc)) {
                            disconnectLater(browser);
                            lastVia = (POWERAMP.equals(pkg) ? "Poweramp" : name) + " MediaBrowser";
                            Log.i(MTAG, "CONFIRMED playing via MediaBrowser");
                            return DeviceActionExecutor.Result.success("Playing " + q + " on " + name);
                        }
                        reason = "it didn't start playing (state " + stateOf(mc) + ")";
                    } catch (Exception e) {
                        Log.e(MTAG, "playFromSearch failed", e);
                        reason = "media controls failed";
                    }
                } else {
                    reason = ok == null ? "it didn't answer in time" : "it refused the media connection";
                    Log.w(MTAG, "browser connect " + (ok == null ? "timed out" : "refused"));
                }
            }
        }
        if (POWERAMP.equals(pkg)) {
            try {
                c.sendBroadcast(new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        .setComponent(new ComponentName(POWERAMP, POWERAMP_RECEIVER)).putExtras(extras));
                Log.i(MTAG, "fallback: Poweramp API receiver broadcast PLAY_FROM_SEARCH");
                if (waitPlaying(c, mc)) {
                    disconnectLater(browser);
                    lastVia = "broadcast";
                    Log.i(MTAG, "CONFIRMED playing via Poweramp API broadcast");
                    return DeviceActionExecutor.Result.success("Playing " + q + " on Poweramp");
                }
                reason = "it didn't start playing, check Poweramp has " + q + " in its library";
            } catch (Exception e) {
                Log.e(MTAG, "broadcast failed", e);
            }
        }
        disconnectLater(browser);
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtras(extras)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) i.setPackage(pkg);
        if (i.resolveActivity(c.getPackageManager()) != null) {
            try {
                c.startActivity(i);
                Log.i(MTAG, "fallback: startActivity PLAY_FROM_SEARCH pkg=" + pkg);
                if (waitPlaying(c, null)) {
                    Log.i(MTAG, "CONFIRMED playing via activity");
                    return DeviceActionExecutor.Result.success("Playing " + q + (pkg == null ? "" : " on " + name));
                }
            } catch (Exception e) {
                Log.w(MTAG, "startActivity blocked: " + e);
                reason = "Android blocked opening it from the car";
            }
        }
        Log.w(MTAG, "FAILED: " + reason);
        return DeviceActionExecutor.Result.failed("Couldn't start " + name + ": " + reason);
    }

    /** TRUE connected, FALSE refused, null timed out. */
    private static Boolean connect(Context c, ComponentName svc, MediaBrowserCompat[] holder) {
        Context app = c.getApplicationContext();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Boolean[] result = new Boolean[1];
        new Handler(Looper.getMainLooper()).post(() -> {
            holder[0] = new MediaBrowserCompat(app, svc, new MediaBrowserCompat.ConnectionCallback() {
                @Override public void onConnected() { result[0] = true; latch.countDown(); }
                @Override public void onConnectionFailed() { result[0] = false; latch.countDown(); }
                @Override public void onConnectionSuspended() { Log.w(MTAG, "connection suspended"); }
            }, null);
            holder[0].connect();
        });
        try { latch.await(CONNECT_MS, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result[0];
    }

    private static int stateOf(MediaControllerCompat mc) {
        android.support.v4.media.session.PlaybackStateCompat st = mc == null ? null : mc.getPlaybackState();
        return st == null ? -1 : st.getState();
    }

    private static boolean waitPlaying(Context c, MediaControllerCompat mc) {
        android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        long end = android.os.SystemClock.elapsedRealtime() + VERIFY_MS;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            if (stateOf(mc) == android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING) return true;
            if (mc == null && am != null && am.isMusicActive()) return true;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        Log.i(MTAG, "not playing after " + VERIFY_MS + "ms, state=" + stateOf(mc));
        return false;
    }

    private static void disconnectLater(MediaBrowserCompat b) {
        if (b != null) new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { b.disconnect(); } catch (Exception ignored) {}
        }, 1000);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static ComponentName browserService(Context c, String pkg) {
        List<ResolveInfo> list = c.getPackageManager().queryIntentServices(
                new Intent(BROWSER_SERVICE).setPackage(pkg), 0);
        if (list == null || list.isEmpty() || list.get(0).serviceInfo == null) return null;
        return new ComponentName(pkg, list.get(0).serviceInfo.name);
    }

}
