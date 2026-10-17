package com.orbit.assistant;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Android Auto probe: one "Ask Grok" item that records from the phone mic and speaks Grok's reply. */
public class OrbitAutoService extends MediaBrowserServiceCompat {
    private static final String TAG = "OrbitAuto";
    private static final String ROOT = "orbit_auto_root";
    private static final String ASK_ID = "ask_grok";

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionCompat session;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;

    @Override public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSessionCompat(this, TAG);
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { ask(); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) { ask(); }
            @Override public void onPlayFromSearch(String query, Bundle extras) {
                if (query != null && !query.trim().isEmpty()) sendToGrok(query.trim()); else ask();
            }
            @Override public void onStop() { stopAll("Stopped"); }
            @Override public void onPause() { stopAll("Stopped"); }
        });
        session.setActive(true);
        setSessionToken(session.getSessionToken());
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) tts.setLanguage(Locale.getDefault());
            Log.i(TAG, "TTS init status=" + status);
        });
        status("Tap Ask Grok", PlaybackStateCompat.STATE_STOPPED, null);
    }

    @Override public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, Bundle rootHints) {
        Log.i(TAG, "onGetRoot from " + clientPackageName);
        return new BrowserRoot(ROOT, null);
    }

    @Override public void onLoadChildren(@NonNull String parentId,
                                         @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        if (ROOT.equals(parentId)) {
            MediaDescriptionCompat d = new MediaDescriptionCompat.Builder()
                    .setMediaId(ASK_ID).setTitle("Ask Grok").setSubtitle("Speak a question").build();
            items.add(new MediaBrowserCompat.MediaItem(d, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        result.sendResult(items);
    }

    private void ask() {
        main.post(() -> {
            if (tts != null) tts.stop();
            if (!SecureStore.hasXaiCredential(this)) {
                status("Not signed in to xAI/SuperGrok", PlaybackStateCompat.STATE_ERROR, null);
                return;
            }
            boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "RECORD_AUDIO granted=" + granted);
            if (!granted) {
                status("Mic permission missing - open GrokLink on phone", PlaybackStateCompat.STATE_ERROR, null);
                return;
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.w(TAG, "SpeechRecognizer not available");
                status("Speech recognition unavailable", PlaybackStateCompat.STATE_ERROR, null);
                return;
            }
            requestFocus();
            if (recognizer != null) recognizer.destroy();
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { Log.i(TAG, "onReadyForSpeech"); }
                @Override public void onBeginningOfSpeech() { Log.i(TAG, "onBeginningOfSpeech"); }
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { Log.i(TAG, "onEndOfSpeech"); }
                @Override public void onError(int error) {
                    String msg = "Error " + error + ": " + errorName(error);
                    Log.e(TAG, "onError " + msg);
                    status(msg, PlaybackStateCompat.STATE_ERROR, null);
                    abandonFocus();
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = list == null || list.isEmpty() ? "" : list.get(0);
                    Log.i(TAG, "onResults: " + text);
                    if (text.trim().isEmpty()) {
                        status("Heard nothing", PlaybackStateCompat.STATE_ERROR, null);
                        abandonFocus();
                    } else sendToGrok(text.trim());
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            status("Listening…", PlaybackStateCompat.STATE_PLAYING, null);
            recognizer.startListening(intent);
        });
    }

    private void sendToGrok(String question) {
        status("Heard: " + question, PlaybackStateCompat.STATE_BUFFERING, null);
        main.postDelayed(() -> status("Thinking…", PlaybackStateCompat.STATE_BUFFERING, question), 400);
        new XaiProvider().complete(getApplicationContext(),
                "You are answering a driver by voice. Reply in plain spoken sentences, no Markdown, under 80 words.",
                question, new AssistantClient.PlanCallback() {
                    @Override public void onText(String raw, String label) {
                        main.post(() -> speak(raw == null ? "" : raw.trim()));
                    }
                    @Override public void onError(String message) {
                        Log.e(TAG, "Grok error: " + message);
                        main.post(() -> {
                            status("Grok error: " + message, PlaybackStateCompat.STATE_ERROR, question);
                            abandonFocus();
                        });
                    }
                });
    }

    private void speak(String reply) {
        String shown = reply.length() > 100 ? reply.substring(0, 100) + "…" : reply;
        status(shown.isEmpty() ? "(empty reply)" : shown, PlaybackStateCompat.STATE_PLAYING, "Grok");
        if (ttsReady && !reply.isEmpty()) {
            tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "orbit_auto");
        } else {
            Log.w(TAG, "TTS not ready; reply shown only");
        }
    }

    private void stopAll(String why) {
        main.post(() -> {
            if (recognizer != null) recognizer.cancel();
            if (tts != null) tts.stop();
            abandonFocus();
            status(why, PlaybackStateCompat.STATE_STOPPED, null);
        });
    }

    private void status(String text, int state, String subtitle) {
        main.post(() -> {
            Log.i(TAG, "status: " + text);
            session.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ASK_ID)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, text)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, text)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle == null ? "GrokLink" : subtitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle == null ? "GrokLink" : subtitle)
                    .build());
            PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                            | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH | PlaybackStateCompat.ACTION_STOP
                            | PlaybackStateCompat.ACTION_PAUSE)
                    .setState(state, 0, 1f);
            if (state == PlaybackStateCompat.STATE_ERROR) {
                b.setErrorMessage(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR, text);
            }
            session.setPlaybackState(b.build());
        });
    }

    private void requestFocus() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs).setOnAudioFocusChangeListener(c -> Log.i(TAG, "focus change " + c)).build();
        int r = audio.requestAudioFocus(focusRequest);
        Log.i(TAG, "audio focus result=" + r);
    }

    private void abandonFocus() {
        if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
    }

    private static String errorName(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NETWORK: return "NETWORK";
            case SpeechRecognizer.ERROR_AUDIO: return "AUDIO";
            case SpeechRecognizer.ERROR_SERVER: return "SERVER";
            case SpeechRecognizer.ERROR_CLIENT: return "CLIENT";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "SPEECH_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH: return "NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "INSUFFICIENT_PERMISSIONS";
            case 10: return "TOO_MANY_REQUESTS";
            case 11: return "SERVER_DISCONNECTED";
            case 12: return "LANGUAGE_NOT_SUPPORTED";
            case 13: return "LANGUAGE_UNAVAILABLE";
            case 14: return "CANNOT_CHECK_SUPPORT";
            case 15: return "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS";
            default: return "UNKNOWN";
        }
    }

    @Override public void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        abandonFocus();
        session.release();
        super.onDestroy();
    }
}
