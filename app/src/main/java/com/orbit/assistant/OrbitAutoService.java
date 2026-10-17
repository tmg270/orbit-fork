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
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Android Auto: one "Ask Grok" item that records from the phone mic and speaks Grok's reply. */
public class OrbitAutoService extends MediaBrowserServiceCompat {
    private static final String TAG = "OrbitAuto";
    private static final String ROOT = "orbit_auto_root";
    private static final String ASK_ID = "ask_grok";
    private static final String UTTERANCE = "orbit_auto_reply";

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionCompat session;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsFailed;
    private String pendingSpeech;
    private AudioManager audio;
    private AudioFocusRequest recogFocus;
    private AudioFocusRequest speechFocus;
    private AudioAttributes speechAttrs;
    private long speechStartMs;
    private long speechDurationMs;

    @Override public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        speechAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
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
        initTts();
        status("Tap Ask Grok", PlaybackStateCompat.STATE_STOPPED, null, null, 0, 0);
    }

    private void initTts() {
        Log.i(TAG, "TTS init start");
        tts = new TextToSpeech(this, st -> main.post(() -> onTtsInit(st)));
    }

    private void onTtsInit(int st) {
        Log.i(TAG, "TTS onInit status=" + st);
        if (st != TextToSpeech.SUCCESS || tts == null) {
            ttsFailed = true;
            Log.e(TAG, "TTS init failed");
            if (pendingSpeech != null) { pendingSpeech = null; finishSpeech("Voice unavailable"); }
            return;
        }
        int lang = tts.setLanguage(Locale.getDefault());
        Log.i(TAG, "TTS setLanguage(" + Locale.getDefault() + ")=" + lang);
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            int en = tts.setLanguage(Locale.UK);
            Log.i(TAG, "TTS fallback setLanguage(en_GB)=" + en);
        }
        tts.setAudioAttributes(speechAttrs);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { Log.i(TAG, "TTS onStart " + id); }
            @Override public void onDone(String id) {
                Log.i(TAG, "TTS onDone " + id);
                main.post(() -> finishSpeech(null));
            }
            @Override public void onError(String id) {
                Log.e(TAG, "TTS onError " + id);
                main.post(() -> finishSpeech("Voice error"));
            }
            @Override public void onError(String id, int code) {
                Log.e(TAG, "TTS onError " + id + " code=" + code);
                main.post(() -> finishSpeech("Voice error " + code));
            }
            @Override public void onStop(String id, boolean interrupted) {
                Log.i(TAG, "TTS onStop " + id + " interrupted=" + interrupted);
                main.post(() -> abandon(speechFocus));
            }
        });
        ttsReady = true;
        if (pendingSpeech != null) {
            String s = pendingSpeech;
            pendingSpeech = null;
            Log.i(TAG, "TTS speaking queued reply");
            startSpeaking(s);
        }
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
            pendingSpeech = null;
            if (tts != null) tts.stop();
            abandon(speechFocus);
            if (!SecureStore.hasXaiCredential(this)) {
                status("Not signed in to xAI/SuperGrok", PlaybackStateCompat.STATE_ERROR, null, null, 0, 0);
                return;
            }
            boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "RECORD_AUDIO granted=" + granted);
            if (!granted) {
                status("Mic permission missing - open GrokLink on phone", PlaybackStateCompat.STATE_ERROR, null, null, 0, 0);
                return;
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.w(TAG, "SpeechRecognizer not available");
                status("Speech recognition unavailable", PlaybackStateCompat.STATE_ERROR, null, null, 0, 0);
                return;
            }
            recogFocus = focus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(), "recogniser");
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
                    status(msg, PlaybackStateCompat.STATE_ERROR, null, null, 0, 0);
                    abandon(recogFocus);
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = list == null || list.isEmpty() ? "" : list.get(0);
                    Log.i(TAG, "onResults: " + text);
                    abandon(recogFocus);
                    if (text.trim().isEmpty()) {
                        status("Heard nothing", PlaybackStateCompat.STATE_ERROR, null, null, 0, 0);
                    } else sendToGrok(text.trim());
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            status("Listening…", PlaybackStateCompat.STATE_PLAYING, null, null, 0, 0);
            recognizer.startListening(intent);
        });
    }

    private void sendToGrok(String question) {
        status("Heard: " + question, PlaybackStateCompat.STATE_BUFFERING, null, null, 0, 0);
        main.postDelayed(() -> status("Thinking…", PlaybackStateCompat.STATE_BUFFERING, question, null, 0, 0), 400);
        new XaiProvider().complete(getApplicationContext(),
                "You are answering a driver by voice. Reply with plain spoken sentences only: "
                        + "no Markdown, no lists, no code, no structured data. Keep it under 80 words.",
                question, new AssistantClient.PlanCallback() {
                    @Override public void onText(String raw, String label) {
                        String reply = cleanReply(raw);
                        Log.i(TAG, "Grok reply (" + label + "), " + reply.length() + " chars");
                        main.post(() -> speak(reply));
                    }
                    @Override public void onError(String message) {
                        Log.e(TAG, "Grok error: " + message);
                        main.post(() -> status("Grok error: " + message, PlaybackStateCompat.STATE_ERROR,
                                question, null, 0, 0));
                    }
                });
    }

    /** Unwraps a {"response": ...} envelope if the model sent one anyway, then strips Markdown. */
    static String cleanReply(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(s);
                String r = o.optString("response", "");
                if (r.isEmpty()) r = o.optString("text", "");
                if (r.isEmpty()) r = o.optString("message", "");
                if (!r.isEmpty()) s = r.trim();
            } catch (Exception e) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"response\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").matcher(s);
                if (m.find()) {
                    s = m.group(1).replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\");
                }
            }
        }
        return stripMarkdown(s);
    }

    static String stripMarkdown(String s) {
        return s.replaceAll("```[a-zA-Z]*", "")
                .replaceAll("`", "")
                .replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "")
                .replaceAll("(?m)^\\s*>\\s?", "")
                .replaceAll("\\*\\*|__", "")
                .replaceAll("(?<![\\w*])[*_](?=\\S)|(?<=\\S)[*_](?![\\w*])", "")
                .replaceAll("[ \\t]*\\n+[ \\t]*", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private void speak(String reply) {
        if (reply.isEmpty()) {
            status("(empty reply)", PlaybackStateCompat.STATE_STOPPED, "Grok", null, 0, 0);
            return;
        }
        if (ttsFailed) {
            Log.w(TAG, "TTS failed earlier; reinitialising");
            ttsFailed = false;
            ttsReady = false;
            if (tts != null) tts.shutdown();
            initTts();
        }
        if (!ttsReady) {
            Log.i(TAG, "TTS not ready yet; queueing reply");
            pendingSpeech = reply;
            showReply(reply, PlaybackStateCompat.STATE_BUFFERING, 0);
            return;
        }
        startSpeaking(reply);
    }

    private void startSpeaking(String reply) {
        abandon(recogFocus);
        speechFocus = focus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, speechAttrs, "speech");
        // Rough estimate (~15 chars/second) so Auto shows a sensible progress bar.
        speechDurationMs = Math.max(2000, reply.length() * 1000L / 15);
        speechStartMs = SystemClock.elapsedRealtime();
        showReply(reply, PlaybackStateCompat.STATE_PLAYING, speechDurationMs);
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        int r = tts.speak(reply, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE);
        Log.i(TAG, "TTS speak() result=" + r + " chars=" + reply.length());
        if (r != TextToSpeech.SUCCESS) finishSpeech("Voice error");
    }

    private String lastReply = "";

    private void showReply(String reply, int state, long durationMs) {
        lastReply = reply;
        String title = reply.length() > 100 ? reply.substring(0, 100) + "…" : reply;
        status(title, state, "Grok", reply, 0, durationMs);
    }

    private void finishSpeech(String error) {
        Log.i(TAG, "speech finished" + (error == null ? "" : " with " + error));
        abandon(speechFocus);
        speechFocus = null;
        String title = lastReply.length() > 100 ? lastReply.substring(0, 100) + "…" : lastReply;
        if (error != null) title = error + ": " + title;
        status(title, PlaybackStateCompat.STATE_PAUSED, "Grok", lastReply, speechDurationMs, speechDurationMs);
    }

    private void stopAll(String why) {
        main.post(() -> {
            pendingSpeech = null;
            if (recognizer != null) recognizer.cancel();
            if (tts != null) tts.stop();
            abandon(recogFocus);
            abandon(speechFocus);
            status(why, PlaybackStateCompat.STATE_STOPPED, null, null, 0, 0);
        });
    }

    private void status(String text, int state, String subtitle, String description,
                        long positionMs, long durationMs) {
        main.post(() -> {
            Log.i(TAG, "status[" + state + "]: " + text);
            String sub = subtitle == null ? "GrokLink" : subtitle;
            MediaMetadataCompat.Builder m = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ASK_ID)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, text)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, text)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sub)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                            description == null ? sub : description)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs > 0 ? durationMs : -1);
            if (description != null) {
                m.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, description);
                m.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, description);
            }
            session.setMetadata(m.build());
            PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                            | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH | PlaybackStateCompat.ACTION_STOP
                            | PlaybackStateCompat.ACTION_PAUSE)
                    .setState(state, positionMs, state == PlaybackStateCompat.STATE_PLAYING ? 1f : 0f,
                            SystemClock.elapsedRealtime());
            if (state == PlaybackStateCompat.STATE_ERROR) {
                b.setErrorMessage(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR, text);
            }
            session.setPlaybackState(b.build());
        });
    }

    private AudioFocusRequest focus(int gain, AudioAttributes attrs, String what) {
        AudioFocusRequest req = new AudioFocusRequest.Builder(gain)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(c -> Log.i(TAG, what + " focus change " + c)).build();
        int r = audio.requestAudioFocus(req);
        Log.i(TAG, what + " audio focus request gain=" + gain + " result=" + r);
        return req;
    }

    private void abandon(AudioFocusRequest req) {
        if (req == null) return;
        int r = audio.abandonAudioFocusRequest(req);
        Log.i(TAG, "abandon focus result=" + r);
        if (req == recogFocus) recogFocus = null;
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
        abandon(recogFocus);
        abandon(speechFocus);
        session.release();
        super.onDestroy();
    }
}
