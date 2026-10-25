package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

/** Decisions behind the 1.0.35 car fixes. Nothing here unpacks the speech model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public final class GrokLinkNextFixesTest {

    @Test public void anUnqualifiedAlbumBeatsASameNamedArtist() {
        MusicLibrary.Item album = new MusicLibrary.Item("55", "Ohio Players", "Ohio Players");
        MusicLibrary.Item artist = new MusicLibrary.Item("9", "Ohio Players", "");
        MusicLibrary.Match match = MusicLibrary.choose("Ohio Players", "", "", "", "",
                Collections.singletonList(album),
                Collections.singletonList(artist),
                Collections.emptyList());
        assertNotNull(match);
        assertEquals(MusicLibrary.Kind.ALBUM, match.kind);
        assertEquals("55", match.id);
        assertTrue(MusicLibrary.blocksLibrarySearch(match));
        assertEquals("content://com.maxmpz.audioplayer.data/albums/55/files",
                MusicLibrary.categoryUri(match.kind, match.id).toString());
    }

    @Test public void anArtistAndAPlaylistQueueThatCollection() {
        MusicLibrary.Match artist = MusicLibrary.choose("Kasabian", "Kasabian", "", "", "",
                Collections.emptyList(),
                Collections.singletonList(new MusicLibrary.Item("3", "Kasabian", "")),
                Collections.emptyList());
        assertEquals(MusicLibrary.Kind.ARTIST, artist.kind);
        assertTrue(MusicLibrary.blocksLibrarySearch(artist));
        assertEquals("content://com.maxmpz.audioplayer.data/artists/3/files",
                MusicLibrary.categoryUri(artist.kind, artist.id).toString());
        MusicLibrary.Match list = MusicLibrary.choose("road trip", "", "", "", "road trip",
                Collections.emptyList(), Collections.emptyList(),
                Arrays.asList(new MusicLibrary.Item("8", "Road trip", "")));
        assertEquals(MusicLibrary.Kind.PLAYLIST, list.kind);
        assertEquals("8", list.id);
        assertTrue(MusicLibrary.blocksLibrarySearch(list));
    }

    @Test public void aNamedAlbumIsNeverALibrarySearch() {
        MusicLibrary.Match match = MusicLibrary.choose("Ohio Players", "", "Ohio Players", "", "",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertEquals(MusicLibrary.Kind.ALBUM, match.kind);
        assertTrue(MusicLibrary.blocksLibrarySearch(match));
        assertNull(MusicLibrary.categoryUri(match.kind, match.id));
    }

    @Test public void aSingleTrackMayUseSearch() {
        MusicLibrary.Match match = MusicLibrary.choose("Love Rollercoaster", "", "", "Love Rollercoaster", "",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertEquals(MusicLibrary.Kind.TRACK, match.kind);
        assertFalse(MusicLibrary.blocksLibrarySearch(match));
    }

    @Test public void anAlbumNodeKeepsAFilesUri() {
        assertEquals("content://com.maxmpz.audioplayer.data/albums/55/files",
                MusicLibrary.categoryUri(MusicLibrary.Kind.ALBUM,
                        "content://com.maxmpz.audioplayer.data/albums/55").toString());
        assertEquals("content://com.maxmpz.audioplayer.data/albums/55/files",
                MusicLibrary.categoryUri(MusicLibrary.Kind.ALBUM,
                        "content://com.maxmpz.audioplayer.data/albums/55/files").toString());
    }

    @Test public void navigationPhrasesOpenMapsOrWazeAndLeaveAppLaunchesAlone() {
        NavigationIntents.Request maps = NavigationIntents.parse("navigate to Tesco");
        assertEquals("tesco", maps.destination);
        assertEquals(NavigationIntents.Mode.NAVIGATE, maps.mode);
        assertEquals("google.navigation:q=Tesco",
                NavigationIntents.mapsNavigation("Tesco").getData().toString());
        assertEquals("com.google.android.apps.maps",
                NavigationIntents.mapsNavigation("Tesco").getPackage());

        NavigationIntents.Request eta = NavigationIntents.parse("how long to the station");
        assertEquals(NavigationIntents.Mode.ETA, eta.mode);
        assertTrue(NavigationIntents.mapsDirections("the station").getData().toString()
                .contains("destination=the%20station"));

        NavigationIntents.Request waze = NavigationIntents.parse("drive to home with waze");
        assertEquals(NavigationIntents.App.WAZE, waze.app);
        assertTrue(NavigationIntents.waze("home").getData().toString().contains("navigate=yes"));
        assertEquals("geo:0,0?q=home", NavigationIntents.car("home").getData().toString());

        assertNull(NavigationIntents.parse("take me to Spotify"));
        assertNull(NavigationIntents.parse("go to settings"));
    }

    @Test public void spokenNavigationBecomesANavigateAction() {
        Context context = RuntimeEnvironment.getApplication();
        AssistantReply reply = LocalCommandRouter.tryHandle(context, "navigate to Tesco");
        assertNotNull(reply);
        assertEquals("NAVIGATE", reply.actions.get(0).type);
        assertEquals("tesco", reply.actions.get(0).params.optString("query"));
        assertEquals("navigate", reply.actions.get(0).params.optString("mode"));

        AssistantReply eta = LocalCommandRouter.tryHandle(context, "how long to the station");
        assertEquals("eta", eta.actions.get(0).params.optString("mode"));

        AssistantReply app = LocalCommandRouter.tryHandle(context, "take me to Spotify");
        assertEquals("OPEN_APP", app.actions.get(0).type);

        AssistantReply playlist = LocalCommandRouter.tryHandle(context, "play the playlist road trip");
        assertEquals("PLAY_MUSIC", playlist.actions.get(0).type);
        assertEquals("road trip", playlist.actions.get(0).params.optString("playlist"));
    }

    @Test public void aSetlistInTheAnswerBecomesAPlaylist() throws Exception {
        String songs = "1. Gold on the Ceiling\n2. Lonely Boy\n3. Howlin' for You\n";
        AssistantReply reply = SetlistPlaylist.attach(
                "Black Keys Victorious Festival 2026 setlist and make a playlist",
                new AssistantReply(songs));
        assertEquals(1, reply.actions.size());
        assertEquals("PLAY_PLAYLIST", reply.actions.get(0).type);
        JSONArray tracks = reply.actions.get(0).params.getJSONArray("tracks");
        assertEquals(3, tracks.length());
        assertEquals("Gold on the Ceiling", tracks.getJSONObject(0).getString("title"));
        AssistantReply again = SetlistPlaylist.attach(
                "Black Keys setlist playlist", reply);
        assertEquals(1, again.actions.size());
    }

    @Test public void webSearchStaysOnTheSelectedModel() throws Exception {
        JSONObject chat = new JSONObject()
                .put("model", "grok-4.7")
                .put("reasoning_effort", "low")
                .put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user").put("content", "setlist")));
        JSONObject body = ProviderRequestMapper.responsesFrom(chat, true, true);
        assertEquals("grok-4.7", body.getString("model"));
        assertEquals("web_search", body.getJSONArray("tools").getJSONObject(0).getString("type"));
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"));
        assertFalse(body.has("search_parameters"));
        assertTrue(ProviderRequestMapper.webSearchRefused(400, "tool web_search is not available"));
        assertTrue(ProviderRequestMapper.jsonFormatRefused(400, "text.format json_object rejected"));
        assertFalse(ProviderRequestMapper.webSearchRefused(401, "bad key"));
    }

    @Test public void theCarUsesBundledSpeechAndThePhoneOnlyWhenOffline() {
        assertTrue(OfflineSpeech.preferBundledVosk(true, true, true));
        assertTrue(OfflineSpeech.preferBundledVosk(true, false, true));
        assertFalse(OfflineSpeech.preferBundledVosk(false, true, true));
        assertTrue(OfflineSpeech.preferBundledVosk(false, false, true));
        assertFalse(OfflineSpeech.preferBundledVosk(true, false, false));
    }

    @Test public void aBugReportDropsSecretsAndKeepsTheStory() {
        SessionTrace.resetForTests();
        String secret = "Bearer ghp_exampletokenvalue";
        assertFalse(SessionTrace.redact("api_key=" + "sk-" + "abcdefghijklmnopqrstuvwxyz").contains("abcd"));
        assertTrue(SessionTrace.redact(secret).contains("[redacted]"));
        assertFalse(SessionTrace.redact(secret).contains("ghp_"));
        Context context = RuntimeEnvironment.getApplication();
        SessionTrace.conversation("user", "the album played the whole library");
        SessionTrace.api("request model=grok-4.7 " + secret);
        String report = BugReport.build(context, "Poweramp played everything");
        assertTrue(report.contains("Poweramp played everything"));
        assertTrue(report.contains("the album played the whole library"));
        assertTrue(report.contains("grok-4.7"));
        assertFalse(report.contains("ghp_"));
        assertEquals("GrokLink bug: Poweramp played everything",
                BugReport.title("Poweramp played everything"));
        assertFalse(BugReport.title("token ghp_shouldnotappear").contains("ghp_"));
    }
}
