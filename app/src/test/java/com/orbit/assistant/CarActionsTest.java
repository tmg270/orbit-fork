package com.orbit.assistant;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CarActionsTest {
    private static AssistantReply parse(String raw) { return DeviceActionContract.parseEnvelope(raw, true); }

    @Test public void envelopeIsUnwrappedAndOnlyTextSpoken() {
        AssistantReply r = parse("{\"text\":\"Setting a timer.\",\"actions\":[{\"type\":\"SET_TIMER\",\"params\":{\"seconds\":600}}]}");
        assertEquals("Setting a timer.", OrbitAutoService.cleanReply(r.text));
        assertEquals(1, CarActions.keep(r.actions).size());
        assertFalse(CarActions.needsConfirmation(CarActions.keep(r.actions)));
    }

    @Test public void callsAndTextsNeedConfirmationAndUnsafeActionsAreDropped() {
        AssistantReply r = parse("{\"text\":\"ok\",\"actions\":[{\"type\":\"SMS_CONTACT\",\"params\":{\"name\":\"Mum\",\"body\":\"late\"}},{\"type\":\"SHARE\",\"params\":{\"text\":\"x\"}}]}");
        List<AssistantReply.Action> kept = CarActions.keep(r.actions);
        assertEquals(1, kept.size());
        assertTrue(CarActions.needsConfirmation(kept));
        assertEquals("Should I text Mum saying late? Say yes or no.", CarActions.confirmQuestion(kept));
    }

    @Test public void yesNoParsing() {
        assertEquals(Boolean.TRUE, CarActions.parseYesNo("Yes please"));
        assertEquals(Boolean.FALSE, CarActions.parseYesNo("no don't"));
        assertEquals(Boolean.FALSE, CarActions.parseYesNo("yes no"));
        assertNull(CarActions.parseYesNo("what's the weather"));
        assertNull(CarActions.parseYesNo(""));
    }

    @Test public void historyIsPrependedForFollowUps() {
        List<String[]> h = new ArrayList<>();
        assertEquals("q", OrbitAutoService.withHistory(h, "q"));
        h.add(new String[]{"Who won?", "Arsenal."});
        String p = OrbitAutoService.withHistory(h, "By how much?");
        assertTrue(p.contains("Driver: Who won?"));
        assertTrue(p.endsWith("Now the driver asks: By how much?"));
    }

    @Test public void retryableErrors() {
        assertTrue(OrbitAutoService.isRetryable(7));
        assertTrue(OrbitAutoService.isRetryable(11));
        assertTrue(OrbitAutoService.isRetryable(5));
        assertTrue(OrbitAutoService.isRetryable(8));
        assertFalse(OrbitAutoService.isRetryable(9));
        assertFalse(OrbitAutoService.friendlyError(7).contains("Error"));
    }
}
