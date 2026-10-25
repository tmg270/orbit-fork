package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.Locale;

/**
 * Speech recognition for the phone and Android Auto.
 *
 * <p>Every recogniser intent sets {@link RecognizerIntent#EXTRA_PREFER_OFFLINE} and
 * {@code EXTRA_LANGUAGE=en-GB}, including the ordinary system recogniser. That is what makes
 * airplane mode use the downloaded English (UK) pack instead of failing with
 * {@link SpeechRecognizer#ERROR_NETWORK}. When the phone is offline, {@link #open} uses that
 * system recogniser first and does not try {@link SpeechRecognizer#createOnDeviceSpeechRecognizer}.
 * A hard error after that (including error 2) is the caller's cue to fall back to Vosk.
 */
public final class OfflineSpeech {
    /** British English pack. The phone under test has this offline pack installed. */
    public static final String LANGUAGE = "en-GB";

    /** Once set, this process skips the on-device recogniser and uses the system one. */
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
        // Offline: the system recogniser, with EXTRA_PREFER_OFFLINE, before Vosk.
        // The on-device factory is skipped here because a missing pack used to
        // fall through to the online recogniser and return ERROR_NETWORK.
        if (!LocalModel.online(context)) return openPlatform(context);
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
            download.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            onDevice.triggerModelDownload(download);
        } catch (Throwable ignored) {
            // The platform recogniser still takes the turn. The pack can be installed later.
        }
    }

    /** Marks every recognition intent as offline-first British English. */
    public static void apply(Intent intent) {
        if (intent == null) return;
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE);
    }

    public static boolean prefersOffline(Intent intent) {
        return intent != null && intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
    }

    /**
     * Speech that must not touch Google's recogniser.
     *
     * <p>The car always uses the bundled Vosk model when it is in the APK. Google's recogniser is
     * what tells the driver speech cannot work without a connection, including before
     * {@code onError} runs. On the phone the same skip happens only while the phone is offline,
     * so an online chat can still use the system recogniser.
     */
    public static boolean preferBundledVosk(Context context, boolean car) {
        boolean model = context != null && (VoskModel.ready(context) || VoskModel.hasBundledAsset(context));
        return preferBundledVosk(car, context != null && LocalModel.online(context), model);
    }

    static boolean preferBundledVosk(boolean car, boolean online, boolean modelAvailable) {
        if (!modelAvailable) return false;
        if (car) return true;
        return !online;
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
