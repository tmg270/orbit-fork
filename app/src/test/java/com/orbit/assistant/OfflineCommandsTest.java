package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.speech.SpeechRecognizer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Offline commands stay on the phone: music, playback, and a spoken yes before a call.
 * Anything the phrase matchers refuse can still be offered to Grok.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OfflineCommandsTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        RecentActionContext.clear();
        SpokenConfirm.clear();
        OfflineSpeech.resetForTests();
        ProcessTrace.resetForTests();
        Prefs.get(context).edit().clear().commit();
    }

    private AssistantReply.Action action(String phrase) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, phrase);
        return reply == null || reply.actions.isEmpty() ? null : reply.actions.get(0);
    }

    @Test public void playKasabianIsMusicNotAResume() {
        AssistantReply.Action a = action("play Kasabian");
        assertNotNull(a);
        assertEquals("PLAY_MUSIC", a.type);
        assertEquals("kasabian", a.params.optString("query"));
        assertFalse(a.requiresConfirmation);
    }

    @Test public void playAlbumAndSongFillTheMusicFields() {
        AssistantReply.Action album = action("play the album West Ryder by Kasabian");
        assertNotNull(album);
        assertEquals("PLAY_MUSIC", album.type);
        assertEquals("west ryder", album.params.optString("album"));
        assertEquals("kasabian", album.params.optString("artist"));
        AssistantReply.Action song = action("play the song Fire by Kasabian");
        assertEquals("fire", song.params.optString("title"));
        assertEquals("kasabian", song.params.optString("artist"));
    }

    @Test public void barePlayAndPlayTheMusicStayPlayback() {
        assertEquals("MEDIA_CONTROL", action("play").type);
        assertEquals("PLAY", action("play").params.optString("command"));
        assertEquals("MEDIA_CONTROL", action("play the music").type);
        assertEquals("PLAY", action("play the music").params.optString("command"));
        assertEquals("PAUSE", action("pause").params.optString("command"));
        assertEquals("NEXT", action("skip").params.optString("command"));
    }

    @Test public void callMumWaitsForASpokenYes() {
        AssistantReply question = LocalCommandRouter.tryHandle(context, "call mum");
        assertNotNull(question);
        assertTrue(question.actions.isEmpty());
        assertTrue(question.text.toLowerCase().contains("yes"));
        AssistantReply yes = LocalCommandRouter.tryHandle(context, "yes");
        assertEquals(1, yes.actions.size());
        assertEquals("DIAL_CONTACT", yes.actions.get(0).type);
        assertEquals("mum", yes.actions.get(0).params.optString("name"));
        assertFalse(yes.actions.get(0).requiresConfirmation);
    }

    @Test public void noDoesNotDial() {
        LocalCommandRouter.tryHandle(context, "text mum saying hello");
        AssistantReply no = LocalCommandRouter.tryHandle(context, "no");
        assertTrue(no.actions.isEmpty());
        assertTrue(no.text.toLowerCase().contains("won't"));
    }

    @Test public void escalationOnlyAsksWhenThePhoneCannotAnswer() {
        assertFalse(GrokEscalation.shouldAsk(context, "hello", false));
        assertFalse(GrokEscalation.shouldAsk(context, "Plan Porto", false));
        assertTrue(GrokEscalation.shouldAsk(context, "hello", true));
        assertTrue(GrokEscalation.shouldAsk(context, "play a movie", false));
        assertFalse("a music command is already local",
                GrokEscalation.shouldAsk(context, "play Kasabian", true));
        assertFalse(GrokEscalation.shouldAsk(context, "pause", true));
    }

    @Test public void speechPrefersTheOnDeviceRecognizer() {
        Intent intent = new Intent();
        assertFalse(OfflineSpeech.prefersOffline(intent));
        OfflineSpeech.apply(intent);
        assertTrue(OfflineSpeech.prefersOffline(intent));
    }

    @Test public void aMissingLanguagePackFallsBackAndNamesTheError() {
        assertTrue(OfflineSpeech.isOnDeviceFailure(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED));
        assertTrue(OfflineSpeech.isOnDeviceFailure(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE));
        assertTrue(OfflineSpeech.isOnDeviceFailure(SpeechRecognizer.ERROR_SERVER));
        assertTrue(OfflineSpeech.isOnDeviceFailure(SpeechRecognizer.ERROR_CLIENT));
        assertFalse(OfflineSpeech.isOnDeviceFailure(SpeechRecognizer.ERROR_NO_MATCH));
        assertFalse(OfflineSpeech.isOnDeviceFailure(SpeechRecognizer.ERROR_SPEECH_TIMEOUT));
        assertFalse(OfflineSpeech.isOnDeviceFailure(SpeechRecognizer.ERROR_RECOGNIZER_BUSY));
        assertFalse(OfflineSpeech.isOnDeviceFailure(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS));
        assertEquals("Voice input unavailable (" + SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED + ")",
                OfflineSpeech.unavailableStatus(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED));
        Intent intent = new Intent();
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        OfflineSpeech.rememberNetworkFallback();
        OfflineSpeech.apply(intent);
        assertTrue("every recogniser intent stays offline-first, including after a pack failure",
                OfflineSpeech.prefersOffline(intent));
        assertEquals(OfflineSpeech.LANGUAGE,
                intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE));
        assertEquals(OfflineSpeech.LANGUAGE,
                intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE));
        assertTrue(OfflineSpeech.isOnDeviceFailure(SpeechRecognizer.ERROR_NETWORK));
        assertEquals(ProcessTrace.GOOGLE_OFFLINE, ProcessTrace.engineLabel(false, false));
        assertEquals(ProcessTrace.ON_DEVICE, ProcessTrace.engineLabel(true, true));
        assertEquals(ProcessTrace.GOOGLE_ONLINE, ProcessTrace.engineLabel(false, true));
        OfflineSpeech.resetForTests();
        ProcessTrace.resetForTests();
    }

    @Test public void modelJsonIsAllowlisted() {
        AssistantReply music = LocalIntent.fromModel(
                "```json\n{\"type\":\"PLAY_MUSIC\",\"query\":\"Kasabian\",\"app\":\"poweramp\"}\n```");
        assertNotNull(music);
        assertEquals("PLAY_MUSIC", music.actions.get(0).type);
        assertEquals("Kasabian", music.actions.get(0).params.optString("query"));
        assertEquals("poweramp", music.actions.get(0).params.optString("app"));
        assertNull(LocalIntent.fromModel("{\"type\":\"NONE\"}"));
        assertNull(LocalIntent.fromModel("not json"));
        AssistantReply call = LocalIntent.fromModel("{\"type\":\"DIAL_CONTACT\",\"name\":\"mum\"}");
        assertNotNull(call);
        assertTrue("a model call is a question, not a dial", call.actions.isEmpty());
        assertTrue(call.text.toLowerCase().contains("yes"));
    }

    @Test public void carWordsChooseForgetOrAsk() {
        assertTrue(GrokEscalation.forgets("forget it"));
        assertTrue(GrokEscalation.forgets("never mind"));
        assertTrue(GrokEscalation.asks("ask grok"));
        assertTrue(GrokEscalation.asks("yes"));
        assertFalse(GrokEscalation.asks("forget it"));
        assertFalse(GrokEscalation.forgets("ask grok"));
    }
}
