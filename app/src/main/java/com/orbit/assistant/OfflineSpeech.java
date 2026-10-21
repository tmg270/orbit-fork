package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.Locale;

/**
 * On-device speech recognition for the phone and Android Auto.
 *
 * <p>Android 12+ tries {@link SpeechRecognizer#createOnDeviceSpeechRecognizer} first so a turn
 * does not need a data connection. If that recogniser cannot be created, or it fails because the
 * offline language pack is missing, the session remembers the failure and the next attempt uses
 * the ordinary {@link SpeechRecognizer#createSpeechRecognizer}. While online, Android 13+ is
 * asked to download the pack in the background.
 */
public final class OfflineSpeech {
    /** Once set, this process keeps using the platform recogniser. */
    private static volatile boolean networkFallback;

    private OfflineSpeech() {}

    /** A recogniser plus whether it is the on-device one. */
    public static final class Engine {
        public final SpeechRecognizer recognizer;
        public final boolean onDevice;

        Engine(SpeechRecognizer recognizer, boolean onDevice) {
            this.recognizer = recognizer;
            this.onDevice = onDevice;
        }
    }

    public static SpeechRecognizer create(Context context) {
        Engine engine = open(context);
        return engine == null ? null : engine.recognizer;
    }

    public static Engine open(Context context) {
        if (context == null) return null;
        if (!networkFallback) {
            Engine onDevice = openOnDevice(context);
            if (onDevice != null) return onDevice;
        }
        return openPlatform(context);
    }

    public static Engine openPlatform(Context context) {
        if (context == null) return null;
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) return null;
            SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            if (recognizer == null) return null;
            return new Engine(recognizer, false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Later {@link #open} calls in this process skip the on-device recogniser. */
    public static void rememberNetworkFallback() {
        networkFallback = true;
    }

    public static boolean usingNetworkFallback() {
        return networkFallback;
    }

    /** Test-only. Production never clears the fallback mid-session. */
    static void resetForTests() {
        networkFallback = false;
    }

    /**
     * Errors that mean this on-device recogniser cannot take the turn. Silence, a busy engine,
     * and a missing microphone permission are not reasons to swap recognisers.
     */
    public static boolean isOnDeviceFailure(int error) {
        return error != SpeechRecognizer.ERROR_NO_MATCH
                && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                && error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
    }

    public static String unavailableStatus(int error) {
        return "Voice input unavailable (" + error + ")";
    }

    /**
     * Asks Android to fetch the offline language pack. No-op below API 33, offline, or if the
     * recogniser has already been destroyed.
     */
    public static void requestLanguagePack(Context context, SpeechRecognizer onDevice, String languageTag) {
        if (onDevice == null || context == null) return;
        if (Build.VERSION.SDK_INT < 33) return;
        if (!LocalModel.online(context)) return;
        try {
            Intent download = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            download.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            String language = languageTag == null || languageTag.trim().isEmpty()
                    ? Locale.getDefault().toLanguageTag() : languageTag.trim();
            download.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
            onDevice.triggerModelDownload(download);
        } catch (Throwable ignored) {
            // The platform recogniser still takes the turn. The pack can be installed later.
        }
    }

    /** Marks a recognition intent as preferring the on-device model. */
    public static void apply(Intent intent) {
        if (intent == null || networkFallback) return;
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
    }

    public static boolean prefersOffline(Intent intent) {
        return intent != null && intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
    }

    private static Engine openOnDevice(Context context) {
        if (Build.VERSION.SDK_INT < 31) return null;
        boolean available;
        try {
            available = SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
        } catch (Throwable ignored) {
            return null;
        }
        if (!available) return null;
        try {
            SpeechRecognizer recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
            if (recognizer == null) {
                networkFallback = true;
                return null;
            }
            return new Engine(recognizer, true);
        } catch (Throwable ignored) {
            // The platform says on-device recognition exists, but this language pack
            // cannot be created. Stop trying it for the rest of the process.
            networkFallback = true;
            return null;
        }
    }
}
