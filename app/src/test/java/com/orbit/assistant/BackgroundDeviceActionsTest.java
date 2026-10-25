package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Grok reply's device actions run from the worker, not from whichever chat screen happens to
 * still be listening. Poweramp title matching prefers the tag title over the file name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public final class BackgroundDeviceActionsTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, false).commit();
        ConversationStore.clear(context);
        context.getSharedPreferences("orbit_action_results", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("orbit_pending_requests", Context.MODE_PRIVATE).edit().clear().commit();
        OrbitRequestManager.resetForTest();
        ReplyActionRunner.resetForTests(context);
        DeviceActionRunner.setExecutorForTests(Runnable::run);
        UiPresence.clearForTests();
        MusicPlayer.permissionRetryMs = 0;
    }

    @After public void tearDown() {
        ReplyActionRunner.resetForTests(context);
        OrbitRequestManager.resetForTest();
        MusicPlayer.permissionRetryMs = 400;
    }

    @Test public void aReplyRunsItsActionWhenNoChatIsListening() throws Exception {
        String chat = conversation();
        PendingRequestStore.Item item = request(chat);
        AtomicBoolean heard = new AtomicBoolean(false);
        OrbitRequestManager.Listener listener = new OrbitRequestManager.Listener() {
            @Override public void onSuccess(String requestId, AssistantReply reply) {
                heard.set(true);
            }
        };
        OrbitRequestManager.addListener(item.id, listener);
        OrbitRequestManager.removeListener(item.id, listener);

        OrbitRequestWorker.completeProviderReply(context, item, copyReply(), WorkerAttempt.of(1, false));

        assertFalse(heard.get());
        assertFalse(OrbitRequestManager.hasListeners(item.id));
        java.util.List<ActionResultStore.Entry> cards = ActionResultStore.forAssistant(context, chat, 1);
        assertEquals(1, cards.size());
        assertEquals("COPY", cards.get(0).action.type);
        assertTrue(cards.get(0).success);
        assertTrue(cards.get(0).message.toLowerCase(java.util.Locale.US).contains("clipboard"));
    }

    @Test public void aVisibleListenerIsNotRequiredAndDoesNotRunTheActionTwice() throws Exception {
        String chat = conversation();
        PendingRequestStore.Item item = request(chat);
        AtomicBoolean heard = new AtomicBoolean(false);
        OrbitRequestManager.addListener(item.id, new OrbitRequestManager.Listener() {
            @Override public void onSuccess(String requestId, AssistantReply reply) { heard.set(true); }
        });

        OrbitRequestWorker.completeProviderReply(context, item, copyReply(), WorkerAttempt.of(1, false));

        assertTrue(heard.get());
        assertEquals(1, ActionResultStore.forAssistant(context, chat, 1).size());
    }

    @Test public void aConfirmationWaitsForTheChatAndAStoppedReplyRunsNothing() throws Exception {
        String chat = conversation();
        PendingRequestStore.Item item = request(chat);
        JSONObject params = new JSONObject().put("on", true);
        AssistantReply.Action flashlight = new AssistantReply.Action("FLASHLIGHT", params, true);
        OrbitRequestWorker.completeProviderReply(context, item,
                new AssistantReply("Turn it on?", Collections.singletonList(flashlight)),
                WorkerAttempt.of(1, false));

        assertTrue(ActionResultStore.forAssistant(context, chat, 1).isEmpty());
        java.util.List<ReplyActionRunner.Prompt> prompts = ReplyActionRunner.takePrompts(context, chat);
        assertEquals(1, prompts.size());
        assertEquals(1, prompts.get(0).assistantIndex);
        assertEquals("FLASHLIGHT", prompts.get(0).actions.get(0).type);
        assertTrue(prompts.get(0).actions.get(0).requiresConfirmation);
        assertTrue(ReplyActionRunner.takePrompts(context, chat).isEmpty());

        PendingRequestStore.Item stopped = request(chat);
        assertTrue(OrbitRequestManager.cancel(context, stopped.id));
        OrbitRequestWorker.completeProviderReply(context, stopped, copyReply(), WorkerAttempt.of(1, false));
        assertEquals(2, ConversationStore.load(context, chat).messages.size());
        assertFalse(ConversationStore.load(context, chat).messages.get(1).content.contains("Queuing"));
        assertTrue(ActionResultStore.forAssistant(context, chat, 1).isEmpty());
        assertTrue(ActionResultStore.forAssistant(context, chat, 2).isEmpty());
        assertTrue(ReplyActionRunner.takePrompts(context, chat).isEmpty());
    }

    @Test public void powerampMatchesTheTagTitleAndAsksBeforeASearchFallback() throws Exception {
        assertTrue(MusicPlayer.titleMatches("Gold on the Ceiling", "Gold on the Ceiling.mp3",
                "01 Gold.mp3", "The Black Keys", "Gold on the Ceiling", "Black Keys"));
        assertTrue(MusicPlayer.titleMatches("", "Little Black Submarines", "track.mp3", null,
                "Little Black Submarines", ""));
        assertTrue(MusicPlayer.titleMatches("Lonely Boy (Remastered)", "", "x.mp3", "The Black Keys",
                "Lonely Boy", "The Black Keys"));
        assertFalse(MusicPlayer.titleMatches("", "", "01 Gold on the Ceiling.mp3", "The Black Keys",
                "Gold on the Ceiling", "The Black Keys"));
        assertFalse(MusicPlayer.titleMatches("Gold on the Ceiling", "", "", "Radiohead",
                "Gold on the Ceiling", "Black Keys"));
        assertEquals("Gold on the Ceiling",
                MusicPlayer.trackLabel("Gold on the Ceiling", "Something else", "file.mp3"));
        assertTrue(MusicPlayer.searchInstead(0, 16));
        assertFalse(MusicPlayer.searchInstead(4, 16));
        assertFalse(MusicPlayer.searchInstead(0, 0));

        Intent ask = MusicPlayer.libraryPermissionIntent(context, false);
        assertEquals(MusicPlayer.ASK_DATA_PERMISSION, ask.getAction());
        assertEquals(context.getPackageName(), ask.getStringExtra("package"));
        assertEquals(MusicPlayer.POWERAMP_RECEIVER, ask.getComponent().getClassName());
        Intent shown = MusicPlayer.libraryPermissionIntent(context, true);
        assertEquals(MusicPlayer.POWERAMP_API_ACTIVITY, shown.getComponent().getClassName());
        assertTrue((shown.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);

        JSONArray tracks = new JSONArray().put(new JSONObject().put("title", "Gold on the Ceiling"));
        DeviceActionExecutor.Result missing = MusicPlayer.playTracks(context, "Victorious", tracks, "Poweramp");
        assertFalse(missing.success);
        assertTrue(missing.message.toLowerCase(java.util.Locale.US).contains("not installed"));
    }

    @Test public void aNumberedSetlistWithLinksStillBecomesAPlaylist() throws Exception {
        String songs = ""
                + "Black Keys at Victorious Festival:\n"
                + "1. **Gold on the Ceiling** ([setlist.fm](https://www.setlist.fm/black-keys))\n"
                + "2. \"Lonely Boy\"\n"
                + "3. Howlin' for You — The Black Keys\n"
                + "4. Little Black Submarines\n"
                + "Source: https://www.setlist.fm/example\n";
        AssistantReply reply = SetlistPlaylist.attach(
                "Black Keys Victorious Festival setlist and play the tracks he has",
                new AssistantReply(songs));
        assertEquals("PLAY_PLAYLIST", reply.actions.get(0).type);
        JSONArray tracks = reply.actions.get(0).params.getJSONArray("tracks");
        assertEquals(4, tracks.length());
        assertEquals("Gold on the Ceiling", tracks.getJSONObject(0).getString("title"));
        assertEquals("Lonely Boy", tracks.getJSONObject(1).getString("title"));
        assertEquals("Howlin' for You", tracks.getJSONObject(2).getString("title"));
        assertEquals("The Black Keys", tracks.getJSONObject(2).getString("artist"));
        assertFalse(tracks.getJSONObject(0).getString("title").contains("http"));

        AssistantReply followUp = SetlistPlaylist.attach("Nothing played?", new AssistantReply(songs));
        assertEquals(1, followUp.actions.size());
        assertEquals("PLAY_PLAYLIST", followUp.actions.get(0).type);
    }

    private String conversation() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Collections.singletonList(
                new AssistantClient.History("user", "Black Keys Victorious Festival setlist")));
        return id;
    }

    private PendingRequestStore.Item request(String chat) {
        return PendingRequestStore.create(context, chat, "Black Keys Victorious Festival setlist",
                "", "", false, false, AiSelections.FALLBACK, false, "");
    }

    private static AssistantReply copyReply() throws Exception {
        JSONObject params = new JSONObject().put("text", "Gold on the Ceiling");
        return new AssistantReply("Queuing it.",
                Collections.singletonList(new AssistantReply.Action("COPY", params, false)));
    }
}
