package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;

/** Half-duplex, the Bug wording, alarm questions, and a thumbs-up that stays on the phone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class GrokLinkVoiceFixesTest {

    @Test public void aFinishedReplyDoesNotOpenTheMicrophone() {
        assertFalse(SpeechGate.opensMicAfterSpeech());
    }

    @Test public void theMicrophoneStaysShutWhileGrokLinkIsSpeaking() {
        assertTrue(SpeechGate.hearingOwnVoice(true, -1));
        assertTrue(SpeechGate.hearingOwnVoice(false, 100));
        assertFalse(SpeechGate.hearingOwnVoice(false, SpeechGate.TAIL_MS));
        assertFalse(SpeechGate.hearingOwnVoice(false, -1));
    }

    @Test public void aReadBackIsNotACommandAndYesStillIs() {
        String spoken = "I heard: the voice is nonsense. Tap Send to file it, or tap Bug to say it again.";
        assertTrue(SpeechGate.isEcho(spoken, spoken));
        assertTrue(SpeechGate.isEcho("the voice is nonsense", spoken));
        assertFalse(SpeechGate.isEcho("yes", spoken));
        assertFalse(SpeechGate.isEcho("play the black keys", spoken));
        assertFalse(SpeechGate.accept(true, SpeechGate.TAIL_MS, "play the black keys", spoken));
        assertTrue(SpeechGate.accept(false, SpeechGate.TAIL_MS, "play the black keys", spoken));
    }

    @Test public void theCarNamesTheBugAndSendControlsThatExist() {
        String said = OrbitAutoService.bugPrompt("the mic heard itself", "Downloads/GrokLink/bug-1.txt");
        assertTrue(said.contains("Tap Send"));
        assertTrue(said.contains("tap Bug"));
        assertTrue(said.contains("Downloads/GrokLink/bug-1.txt"));
        assertFalse(said.toLowerCase().contains("report button"));
        assertFalse(said.contains("Send bug report"));
        assertEquals("Bug", OrbitAutoService.LABEL_BUG);
        assertEquals("Send", OrbitAutoService.LABEL_SEND);
    }

    @Test public void nextAlarmIsAnsweredOnThePhone() {
        Context context = RuntimeEnvironment.getApplication();
        AssistantReply reply = AssistantClient.tryOfflineReply(context, "When's my next alarm");
        assertNotNull(reply);
        assertTrue(reply.text.toLowerCase().contains("alarm"));
        assertTrue(reply.actions.isEmpty());
        AssistantReply show = AssistantClient.tryOfflineReply(context, "show my alarms");
        assertNotNull(show);
        assertEquals("SHOW_ALARMS", show.actions.get(0).type);
        assertNull(AlarmQueries.tryHandle(context, "set an alarm for 7:30"));
        assertNull(AlarmQueries.tryHandle(context, "wake me at 6:45"));
        AssistantReply set = LocalCommandRouter.tryHandle(context, "set an alarm for 7:30");
        assertEquals("SET_ALARM", set.actions.get(0).type);
    }

    @Test public void aThumbsUpReplaysTheActionLocally() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String phrase = "grab the setlist and play the tracks I have";
        assertNull(InteractionRating.recall(context, phrase));
        AssistantReply.Action action = new AssistantReply.Action("PLAY_PLAYLIST",
                new JSONObject().put("name", "Victorious"), false);
        InteractionRating.record(context, phrase, "Playing 16 songs on Poweramp", true,
                Collections.singletonList(action));
        AssistantReply again = AssistantClient.tryOfflineReply(context, phrase);
        assertNotNull(again);
        assertEquals("PLAY_PLAYLIST", again.actions.get(0).type);
        assertEquals("Victorious", again.actions.get(0).params.optString("name"));
        InteractionRating.record(context, phrase, "Playing 16 songs on Poweramp", false,
                Collections.singletonList(action));
        assertNull(InteractionRating.recall(context, phrase));
        assertFalse(InteractionRating.body(phrase, "token ghp_shouldnotappear", true, new org.json.JSONArray())
                .contains("ghp_"));
    }
}
