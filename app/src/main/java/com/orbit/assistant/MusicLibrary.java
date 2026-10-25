package com.orbit.assistant;

import android.net.Uri;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether "play Ohio Players" is an album, an artist, a playlist or one track.
 *
 * <p>An unqualified name that matches an album is the album, even when an artist has the same
 * name. Poweramp's plain search then plays the first hit and keeps going through the library;
 * the album category does not.
 */
final class MusicLibrary {
    enum Kind { ALBUM, ARTIST, PLAYLIST, TRACK }

    static final class Item {
        final String id;
        final String name;
        final String artist;

        Item(String id, String name, String artist) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.artist = artist == null ? "" : artist;
        }
    }

    static final class Match {
        final Kind kind;
        final String id;
        final String name;
        final String artist;

        Match(Kind kind, String id, String name, String artist) {
            this.kind = kind;
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.artist = artist == null ? "" : artist;
        }
    }

    private MusicLibrary() {}

    /**
     * @param playlist set when the user named a playlist; album, artist and title likewise
     * @return null when nothing in the library claims this request, so a single-track search is fair
     */
    static Match choose(String query, String artist, String album, String title, String playlist,
                        List<Item> albums, List<Item> artists, List<Item> playlists) {
        String wantedList = norm(playlist);
        if (!wantedList.isEmpty()) {
            Item hit = find(playlists, wantedList, "");
            if (hit != null) return new Match(Kind.PLAYLIST, hit.id, hit.name, "");
            return new Match(Kind.PLAYLIST, "", playlist == null ? "" : playlist.trim(), "");
        }
        String wantedAlbum = norm(album);
        String wantedArtist = norm(artist);
        String wantedTitle = norm(title);
        if (!wantedAlbum.isEmpty()) {
            Item hit = find(albums, wantedAlbum, wantedArtist);
            if (hit == null && !wantedArtist.isEmpty()) hit = find(albums, wantedAlbum, "");
            if (hit != null) return new Match(Kind.ALBUM, hit.id, hit.name, hit.artist);
            return new Match(Kind.ALBUM, "", album.trim(), artist == null ? "" : artist.trim());
        }
        if (!wantedTitle.isEmpty()) return new Match(Kind.TRACK, "", title.trim(), artist == null ? "" : artist.trim());
        if (!wantedArtist.isEmpty()) {
            Item hit = find(artists, wantedArtist, "");
            if (hit != null) return new Match(Kind.ARTIST, hit.id, hit.name, "");
            return new Match(Kind.ARTIST, "", artist.trim(), "");
        }
        String q = norm(query);
        if (q.isEmpty()) return null;
        Item albumHit = find(albums, q, "");
        Item listHit = find(playlists, q, "");
        Item artistHit = find(artists, q, "");
        if (albumHit != null) return new Match(Kind.ALBUM, albumHit.id, albumHit.name, albumHit.artist);
        if (listHit != null) return new Match(Kind.PLAYLIST, listHit.id, listHit.name, "");
        if (artistHit != null) return new Match(Kind.ARTIST, artistHit.id, artistHit.name, "");
        return null;
    }

    /** Album, artist and playlist playback must not fall through to a library-wide search. */
    static boolean blocksLibrarySearch(Match match) {
        return match != null && match.kind != Kind.TRACK;
    }

    /** Poweramp category URI. A content id that already points at files is kept. */
    static Uri categoryUri(Kind kind, String id) {
        if (id == null || id.trim().isEmpty() || kind == null || kind == Kind.TRACK) return null;
        String trimmed = id.trim();
        if (trimmed.startsWith("content://")) {
            Uri uri = Uri.parse(trimmed);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith("/files") || path.matches(".*/files/\\d+")) return uri;
            return uri.buildUpon().appendEncodedPath("files").build();
        }
        String table = kind == Kind.ARTIST ? "artists" : kind == Kind.PLAYLIST ? "playlists" : "albums";
        return Uri.parse("content://com.maxmpz.audioplayer.data/" + table + "/" + trimmed + "/files");
    }

    static String norm(String value) {
        if (value == null) return "";
        String n = value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
        if (n.startsWith("the ")) n = n.substring(4).trim();
        return n;
    }

    private static Item find(List<Item> items, String name, String artist) {
        if (items == null || name.isEmpty()) return null;
        Item loose = null;
        int looseCount = 0;
        for (Item item : items) {
            if (item == null) continue;
            if (!artist.isEmpty()) {
                String who = norm(item.artist);
                if (!who.equals(artist) && !who.contains(artist)) continue;
            }
            String n = norm(item.name);
            if (n.equals(name)) return item;
            if (n.startsWith(name + " ") || n.endsWith(" " + name) || n.contains(" " + name + " ")) {
                loose = item;
                looseCount++;
            }
        }
        return looseCount == 1 ? loose : null;
    }
}
