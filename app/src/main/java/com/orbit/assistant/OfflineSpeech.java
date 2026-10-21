package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

/**
 * On-device speech recognition for the phone and Android Auto.
 *
 * <p>Android 12+ uses {@link SpeechRecognizer#createOnDeviceSpeechRecognizer} when the offline
 * recogniser is installed, so a turn does not need a data connection. Older releases, and phones
 * without the offline pack, still get {@code EXTRA_PREFER_OFFLINE} so the platform uses a local
 * model when it has one instead of failing over to the network.
 */
public final class OfflineSpeech {
    private OfflineSpeech() {}

    public static SpeechRecognizer create(Context context) {
        if (context == null) return null;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    return SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
                }
            } catch (Throwable ignored) {
                // Missing offline pack or a test double: fall through to the platform recogniser.
            }
        }
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) return null;
            return SpeechRecognizer.createSpeechRecognizer(context);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Marks a recognition intent as preferring the on-device model. */
    public static void apply(Intent intent) {
        if (intent == null) return;
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
    }

    public static boolean prefersOffline(Intent intent) {
        return intent != null && intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
    }
}
