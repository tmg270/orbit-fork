package com.orbit.assistant;

import java.util.regex.Pattern;

/** Whether a message reads as an instruction about something GrokLink can control on the phone. */
final class ActionIntent {
    private ActionIntent() {}
    static final int MAX_LENGTH = 140;

    private static final Pattern ACTIONABLE_SUBJECT = Pattern.compile(
            "\\b(flashlight|torch|light on|light off|brightness|screen|display|dimmer|dim|brighter|"
                    + "volume|loud|louder|quiet|quieter|sound|do not disturb|ringer|silent|silence|"
                    + "vibrate|timer|countdown|alarm|wake me|music|song|track|playing|playback|"
                    + "pause|resume|skip|settings|open|launch|start|pull up|fire up|bring up|"
                    + "bluetooth|wifi|wi-fi|internet|mobile data|"
                    // A duration with no other cue is how people ask for a timer: "give me ten
                    // minutes for the pasta" names nothing else Orbit could act on.
                    + "seconds?|minutes?|hours?)\\b");
    private static final Pattern NOT_AN_INSTRUCTION = Pattern.compile(
            "^(?:what|why|when|who|which|whats|how come|tell me about|explain|do you|does it|"
                    + "did you|is there|are there|should i|could i|can i)\\b");

    public static boolean looksActionable(String raw) {
        String q = LanguageNormalizer.stripPoliteness(LanguageNormalizer.canonical(raw));
        if (q.isEmpty() || q.length() > MAX_LENGTH) return false;
        if (LanguageNormalizer.isConceptualQuestion(q)) return false;
        if (NOT_AN_INSTRUCTION.matcher(q).find()) return false;
        return ACTIONABLE_SUBJECT.matcher(q).find();
    }
}
