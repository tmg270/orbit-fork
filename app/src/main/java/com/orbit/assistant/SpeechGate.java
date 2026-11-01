package com.orbit.assistant;

import java.util.Locale;

/**
 * Half-duplex for GrokLink's own voice.
 *
 * <p>The recogniser must not be open while speech is playing, and a result that is the tail of
 * that speech is not a user command. Bluetooth and the loudspeaker keep sounding after the speech
 * engine says it has finished, so the microphone stays shut for a short tail as well.
 */
public final class SpeechGate {
    /** How long the speaker can still be audible after the engine reports done. */
    public static final long TAIL_MS = 800L;

    private SpeechGate() {}

    /**
     * True while GrokLink is speaking or the speaker tail is still in the room.
     *
     * @param sinceDoneMs milliseconds since the engine finished, or a negative value when nothing
     *                    has finished yet
     */
    public static boolean hearingOwnVoice(boolean speaking, long sinceDoneMs) {
        if (speaking) return true;
        return sinceDoneMs >= 0 && sinceDoneMs < TAIL_MS;
    }

    /**
     * A transcript the recogniser should keep. Own-voice and an echo of the line just spoken are
     * dropped. A short answer such as "yes" is kept: it is too small to be an echo, and the car
     * asks for it.
     */
    public static boolean accept(boolean speaking, long sinceDoneMs, String heard, String spoken) {
        if (hearingOwnVoice(speaking, sinceDoneMs)) return false;
        return !isEcho(heard, spoken);
    }

    /** True when {@code heard} is GrokLink reading its own line back. */
    public static boolean isEcho(String heard, String spoken) {
        String h = norm(heard);
        String s = norm(spoken);
        if (h.length() < 8 || s.isEmpty()) return false;
        if (s.contains(h) || h.contains(s)) return true;
        String[] words = h.split(" ");
        if (words.length < 4) return false;
        int hit = 0;
        for (String word : words) {
            if (word.length() > 2 && s.contains(word)) hit++;
        }
        return hit * 4 >= words.length * 3;
    }

    private static String norm(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.UK).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
