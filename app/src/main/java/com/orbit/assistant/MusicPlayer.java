package com.orbit.assistant;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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
    /** Poweramp API command that opens a category (album, artist, playlist) and plays its tracks. */
    static final String POWERAMP_API = "com.maxmpz.audioplayer.API_COMMAND";
    static final int OPEN_TO_PLAY = 20;
    private static final int LIBRARY_CAP = 800;
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
        return play(c, query, artist, album, title, "", app);
    }

    static DeviceActionExecutor.Result play(Context c, String query, String artist, String album,
                                            String title, String playlist, String app) {
        String q = query == null || query.trim().isEmpty()
                ? String.join(" ", nz(title), nz(artist), nz(album), nz(playlist)).trim() : query.trim();
        if (q.isEmpty()) return DeviceActionExecutor.Result.failed("Tell me what to play");
        String pkg = packageFor(app);
        if (pkg == null) pkg = preferredPackage(c);
        Bundle extras = searchExtras(q, artist, album, title);
        if (playlist != null && !playlist.trim().isEmpty()) {
            extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE);
            extras.putString("android.intent.extra.playlist", playlist.trim());
        }
        String name = displayName(pkg, app);
        Log.i(MTAG, "play q='" + q + "' artist='" + nz(artist) + "' album='" + nz(album)
                + "' playlist='" + nz(playlist) + "' app='" + nz(app) + "' -> pkg=" + pkg);
        if (pkg != null && !installed(c, pkg)) {
            return DeviceActionExecutor.Result.unavailable(name + " is not installed on this phone");
        }
        String reason = "no music app found";
        MediaControllerCompat mc = null;
        MediaBrowserCompat browser = null;
        boolean connected = false;
        if (pkg != null) {
            ComponentName svc = browserService(c, pkg);
            Log.i(MTAG, "browser service for " + pkg + ": " + (svc == null ? "none" : svc.flattenToShortString()));
            if (svc != null) {
                MediaBrowserCompat[] holder = new MediaBrowserCompat[1];
                Boolean ok = connect(c, svc, holder);
                browser = holder[0];
                connected = Boolean.TRUE.equals(ok);
                if (connected) {
                    try {
                        mc = new MediaControllerCompat(c.getApplicationContext(), browser.getSessionToken());
                    } catch (Exception e) {
                        Log.e(MTAG, "controller failed", e);
                        connected = false;
                    }
                } else {
                    reason = ok == null ? "it didn't answer in time" : "it refused the media connection";
                    Log.w(MTAG, "browser connect " + (ok == null ? "timed out" : "refused"));
                }
            }
        }
        if (POWERAMP.equals(pkg)) {
            Catalog catalog = loadCatalog(c, connected ? browser : null);
            MusicLibrary.Match match = MusicLibrary.choose(q, artist, album, title, playlist,
                    catalog.albums, catalog.artists, catalog.playlists);
            if (MusicLibrary.blocksLibrarySearch(match)) {
                DeviceActionExecutor.Result queued = playCategory(c, pkg, name, browser, mc, match);
                disconnectLater(browser);
                return queued;
            }
        }
        if (connected && mc != null) {
            try {
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

    /**
     * Queues the songs Grok found, in order, as one Poweramp playlist.
     * A miss is reported. The rest of the library is not played in its place.
     */
    static DeviceActionExecutor.Result playTracks(Context c, String playlistName, JSONArray tracks, String app) {
        if (tracks == null || tracks.length() == 0) {
            return DeviceActionExecutor.Result.failed("There were no songs to queue");
        }
        String pkg = packageFor(app);
        if (pkg == null) pkg = preferredPackage(c);
        if (!POWERAMP.equals(pkg)) {
            return DeviceActionExecutor.Result.failed("Building that playlist needs Poweramp");
        }
        if (!installed(c, POWERAMP)) {
            return DeviceActionExecutor.Result.unavailable("Poweramp is not installed on this phone");
        }
        List<String> ids = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int limit = Math.min(tracks.length(), 40);
        for (int i = 0; i < limit; i++) {
            JSONObject one = tracks.optJSONObject(i);
            if (one == null) continue;
            String title = one.optString("title", "").trim();
            String artist = one.optString("artist", "").trim();
            if (title.isEmpty()) continue;
            String id = findTrackId(c, title, artist);
            if (id == null) missing.add(title);
            else ids.add(id);
        }
        if (ids.isEmpty()) {
            return DeviceActionExecutor.Result.failed("None of those songs are in Poweramp");
        }
        String name = playlistName == null || playlistName.trim().isEmpty()
                ? "GrokLink playlist" : playlistName.trim();
        Uri playlist = insertPlaylist(c, name, ids);
        if (playlist == null) {
            String gap = missing.isEmpty() ? "" : " Missing: " + joinNames(missing) + ".";
            return DeviceActionExecutor.Result.failed(
                    "Found " + ids.size() + " songs in Poweramp but couldn't make the playlist." + gap);
        }
        MusicLibrary.Match match = new MusicLibrary.Match(
                MusicLibrary.Kind.PLAYLIST, playlist.toString(), name, "");
        MediaControllerCompat mc = null;
        MediaBrowserCompat browser = null;
        ComponentName svc = browserService(c, POWERAMP);
        if (svc != null) {
            MediaBrowserCompat[] holder = new MediaBrowserCompat[1];
            if (Boolean.TRUE.equals(connect(c, svc, holder))) {
                browser = holder[0];
                try {
                    mc = new MediaControllerCompat(c.getApplicationContext(), browser.getSessionToken());
                } catch (Exception ignored) {}
            }
        }
        DeviceActionExecutor.Result played = playCategory(c, POWERAMP, "Poweramp", browser, mc, match);
        disconnectLater(browser);
        if (!played.success) return played;
        if (missing.isEmpty()) return played;
        return DeviceActionExecutor.Result.success(played.message + ". Not in Poweramp: " + joinNames(missing));
    }

    /**
     * Plays one album, artist or playlist. Never falls through to a library-wide search.
     * Poweramp's OPEN_TO_PLAY on the category URI queues that category's tracks.
     */
    private static DeviceActionExecutor.Result playCategory(Context c, String pkg, String name,
                                                           MediaBrowserCompat browser,
                                                           MediaControllerCompat mc,
                                                           MusicLibrary.Match match) {
        Uri uri = MusicLibrary.categoryUri(match.kind, match.id);
        String label = match.name == null || match.name.isEmpty() ? "that" : match.name;
        String kind = match.kind == null ? "item" : match.kind.name().toLowerCase(Locale.UK);
        if (uri == null) {
            return DeviceActionExecutor.Result.failed(
                    "Couldn't find the " + kind + " " + label + " in " + name);
        }
        Log.i(MTAG, "category " + kind + " " + uri);
        String reason = "it didn't start playing";
        if (POWERAMP.equals(pkg)) {
            try {
                Intent api = new Intent(POWERAMP_API)
                        .setComponent(new ComponentName(POWERAMP, POWERAMP_RECEIVER))
                        .setData(uri)
                        .putExtra("cmd", OPEN_TO_PLAY);
                c.sendBroadcast(api);
                if (waitPlaying(c, mc)) {
                    lastVia = "Poweramp " + kind;
                    Log.i(MTAG, "CONFIRMED " + kind + " via OPEN_TO_PLAY");
                    return DeviceActionExecutor.Result.success("Playing " + label + " on Poweramp");
                }
            } catch (Exception e) {
                Log.e(MTAG, "OPEN_TO_PLAY failed", e);
                reason = "Poweramp refused the " + kind;
            }
        }
        if (mc != null) {
            try {
                mc.getTransportControls().playFromMediaId(uri.toString(), new Bundle());
                if (waitPlaying(c, mc)) {
                    lastVia = name + " " + kind;
                    return DeviceActionExecutor.Result.success("Playing " + label + " on " + name);
                }
                reason = "it didn't start playing (state " + stateOf(mc) + ")";
            } catch (Exception e) {
                Log.e(MTAG, "playFromMediaId failed", e);
                reason = "media controls failed";
            }
        }
        return DeviceActionExecutor.Result.failed(
                "Couldn't queue the " + kind + " " + label + " in " + name + ": " + reason);
    }

    private static final class Catalog {
        final List<MusicLibrary.Item> albums = new ArrayList<>();
        final List<MusicLibrary.Item> artists = new ArrayList<>();
        final List<MusicLibrary.Item> playlists = new ArrayList<>();
    }

    private static Catalog loadCatalog(Context c, MediaBrowserCompat browser) {
        Catalog catalog = new Catalog();
        if (!queryPoweramp(c, catalog) && browser != null) browseInto(browser, catalog);
        Log.i(MTAG, "library albums=" + catalog.albums.size()
                + " artists=" + catalog.artists.size() + " playlists=" + catalog.playlists.size());
        return catalog;
    }

    /** Poweramp's own provider. False when it is missing or this app cannot read it. */
    private static boolean queryPoweramp(Context c, Catalog catalog) {
        try {
            int n = 0;
            n += fill(c, catalog.albums, Uri.parse("content://com.maxmpz.audioplayer.data/albums"),
                    "album", "artist");
            n += fill(c, catalog.artists, Uri.parse("content://com.maxmpz.audioplayer.data/artists"),
                    "artist", null);
            n += fill(c, catalog.playlists, Uri.parse("content://com.maxmpz.audioplayer.data/playlists"),
                    "name", null);
            return n > 0;
        } catch (SecurityException e) {
            Log.w(MTAG, "Poweramp library not readable: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.w(MTAG, "Poweramp library query failed", e);
            return false;
        }
    }

    private static int fill(Context c, List<MusicLibrary.Item> into, Uri uri, String nameCol, String artistCol) {
        Cursor cursor = c.getContentResolver().query(uri, null, null, null, null);
        if (cursor == null) return 0;
        int added = 0;
        try {
            int id = firstColumn(cursor, "_id", "id");
            int name = firstColumn(cursor, nameCol, "album", "artist", "name", "title", "playlist");
            int artist = artistCol == null ? -1 : firstColumn(cursor, artistCol, "album_artist", "artist");
            if (id < 0 || name < 0) return 0;
            while (cursor.moveToNext() && into.size() < LIBRARY_CAP) {
                String itemName = cursor.getString(name);
                if (itemName == null || itemName.trim().isEmpty()) continue;
                String itemArtist = artist >= 0 && !cursor.isNull(artist) ? cursor.getString(artist) : "";
                into.add(new MusicLibrary.Item(cursor.getString(id), itemName, itemArtist == null ? "" : itemArtist));
                added++;
            }
        } finally {
            cursor.close();
        }
        return added;
    }

    private static int firstColumn(Cursor cursor, String... names) {
        for (String name : names) {
            int index = cursor.getColumnIndex(name);
            if (index >= 0) return index;
        }
        return -1;
    }

    private static void browseInto(MediaBrowserCompat browser, Catalog catalog) {
        String root;
        try { root = browser.getRoot(); }
        catch (Exception e) { return; }
        if (root == null) return;
        for (MediaBrowserCompat.MediaItem item : children(browser, root)) {
            if (item == null || item.getDescription() == null || item.getMediaId() == null) continue;
            String title = String.valueOf(item.getDescription().getTitle()).toLowerCase(Locale.UK);
            MusicLibrary.Kind kind = title.contains("album") ? MusicLibrary.Kind.ALBUM
                    : title.contains("artist") ? MusicLibrary.Kind.ARTIST
                    : title.contains("playlist") ? MusicLibrary.Kind.PLAYLIST : null;
            if (kind == null) continue;
            List<MusicLibrary.Item> dest = kind == MusicLibrary.Kind.ALBUM ? catalog.albums
                    : kind == MusicLibrary.Kind.ARTIST ? catalog.artists : catalog.playlists;
            for (MediaBrowserCompat.MediaItem kid : children(browser, item.getMediaId())) {
                if (dest.size() >= LIBRARY_CAP) break;
                if (kid == null || kid.getDescription() == null || kid.getMediaId() == null) continue;
                CharSequence kidTitle = kid.getDescription().getTitle();
                if (kidTitle == null || kidTitle.toString().trim().isEmpty()) continue;
                CharSequence sub = kid.getDescription().getSubtitle();
                dest.add(new MusicLibrary.Item(kid.getMediaId(), kidTitle.toString(),
                        sub == null ? "" : sub.toString()));
            }
        }
    }

    private static List<MediaBrowserCompat.MediaItem> children(MediaBrowserCompat browser, String parent) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                browser.subscribe(parent, new MediaBrowserCompat.SubscriptionCallback() {
                    @Override public void onChildrenLoaded(String parentId, List<MediaBrowserCompat.MediaItem> children) {
                        if (children != null) out.addAll(children);
                        latch.countDown();
                        try { browser.unsubscribe(parentId); } catch (Exception ignored) {}
                    }
                    @Override public void onError(String parentId) { latch.countDown(); }
                });
            } catch (Exception e) {
                latch.countDown();
            }
        });
        try { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return out;
    }

    private static String findTrackId(Context c, String title, String artist) {
        try {
            Cursor cursor = c.getContentResolver().query(
                    Uri.parse("content://com.maxmpz.audioplayer.data/files"),
                    null, null, null, null);
            if (cursor == null) return null;
            try {
                int id = firstColumn(cursor, "_id", "id");
                int name = firstColumn(cursor, "title", "name");
                int who = firstColumn(cursor, "artist", "album_artist");
                if (id < 0 || name < 0) return null;
                String wantTitle = MusicLibrary.norm(title);
                String wantArtist = MusicLibrary.norm(artist);
                int seen = 0;
                while (cursor.moveToNext() && seen < 4000) {
                    seen++;
                    String found = MusicLibrary.norm(cursor.getString(name));
                    if (!found.equals(wantTitle)) continue;
                    if (!wantArtist.isEmpty() && who >= 0 && !cursor.isNull(who)) {
                        String foundArtist = MusicLibrary.norm(cursor.getString(who));
                        if (!foundArtist.equals(wantArtist) && !foundArtist.contains(wantArtist)) continue;
                    }
                    return cursor.getString(id);
                }
                return null;
            } finally {
                cursor.close();
            }
        } catch (Exception e) {
            Log.w(MTAG, "track lookup failed for " + title, e);
            return null;
        }
    }

    /**
     * Inserts a Poweramp playlist and its tracks. Column names differ by Poweramp build, so both
     * the names seen in the public API are tried. Null means the playlist was not created.
     */
    private static Uri insertPlaylist(Context c, String playlistName, List<String> fileIds) {
        try {
            ContentValues values = new ContentValues();
            values.put("name", playlistName);
            values.put("playlist", playlistName);
            Uri created = c.getContentResolver().insert(
                    Uri.parse("content://com.maxmpz.audioplayer.data/playlists"), values);
            if (created == null) return null;
            String id = created.getLastPathSegment();
            if (id == null || id.isEmpty()) return null;
            Uri entries = Uri.parse("content://com.maxmpz.audioplayer.data/playlists/" + id + "/files");
            int inserted = 0;
            for (String fileId : fileIds) {
                if (fileId == null || fileId.isEmpty()) continue;
                ContentValues row = new ContentValues();
                row.put("folder_file_id", fileId);
                Uri one = null;
                try { one = c.getContentResolver().insert(entries, row); }
                catch (Exception first) {
                    row.clear();
                    row.put("file_id", fileId);
                    try { one = c.getContentResolver().insert(entries, row); }
                    catch (Exception ignored) {}
                }
                if (one != null) inserted++;
            }
            if (inserted == 0) return null;
            return MusicLibrary.categoryUri(MusicLibrary.Kind.PLAYLIST, id);
        } catch (Exception e) {
            Log.w(MTAG, "playlist insert failed", e);
            return null;
        }
    }

    private static String joinNames(List<String> names) {
        int shown = Math.min(names.size(), 3);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) b.append(", ");
            b.append(names.get(i));
        }
        if (names.size() > shown) b.append(" and ").append(names.size() - shown).append(" more");
        return b.toString();
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
