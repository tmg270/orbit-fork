package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Phone bug report: listen, show the words, read them back, then Send.
 */
public final class BugReportPrompt {
    private BugReportPrompt() {}

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "Allow the microphone, then report the bug", Toast.LENGTH_LONG).show();
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(activity, 18);
        box.setPadding(pad, pad, pad, 0);
        box.addView(UiKit.text(activity,
                "Describe the bug. GrokLink reads it back. Send saves the report to Downloads, where you can open or share it.",
                14, UiKit.MUTED, false));
        EditText transcript = UiKit.input(activity, "What went wrong", true);
        LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        field.topMargin = UiKit.dp(activity, 12);
        box.addView(transcript, field);
        EditText token = UiKit.input(activity, "GitHub token (optional)", false);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams tokenLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tokenLp.topMargin = UiKit.dp(activity, 8);
        box.addView(token, tokenLp);
        if (!SecureStore.loadGithubIssuesToken(activity).isEmpty()) {
            token.setHint("Optional. A token is saved; paste a new one to replace it");
        }
        Button again = new Button(activity);
        again.setText("Listen again");
        again.setAllCaps(false);
        LinearLayout.LayoutParams againLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        againLp.topMargin = UiKit.dp(activity, 8);
        box.addView(again, againLp);

        TextToSpeech[] tts = new TextToSpeech[1];
        SpeechRecognizer[] google = new SpeechRecognizer[1];
        boolean[] open = new boolean[] {true};
        Runnable stop = () -> stopListening(activity, google);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Report a bug")
                .setView(box)
                .setNegativeButton("Cancel", (d, w) -> stop.run())
                .setPositiveButton("Send", null)
                .create();
        dialog.setOnDismissListener(d -> {
            open[0] = false;
            stop.run();
            if (tts[0] != null) tts[0].shutdown();
        });
        UiKit.styleOrbitDialog(dialog, activity, false);
        dialog.show();
        Button send = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        send.setOnClickListener(v -> {
            String said = transcript.getText() == null ? "" : transcript.getText().toString().trim();
            if (said.isEmpty()) {
                Toast.makeText(activity, "Describe the bug first", Toast.LENGTH_SHORT).show();
                return;
            }
            String pasted = token.getText() == null ? "" : token.getText().toString().trim();
            if (!pasted.isEmpty() && !SecureStore.saveGithubIssuesToken(activity, pasted)) {
                Toast.makeText(activity, "Couldn't save that token", Toast.LENGTH_LONG).show();
                return;
            }
            send.setEnabled(false);
            stop.run();
            new Thread(() -> {
                BugReport.Outcome outcome = BugReport.submit(activity.getApplicationContext(), said);
                main.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (open[0]) dialog.dismiss();
                    Toast.makeText(activity, outcome.message, Toast.LENGTH_LONG).show();
                    if (outcome.savedUri != null) offerSaved(activity, outcome);
                });
            }, "groklink-bug-report").start();
        });

        tts[0] = new TextToSpeech(activity, status -> {});
        Runnable listen = () -> listen(activity, transcript, tts, google, open);
        again.setOnClickListener(v -> listen.run());
        listen.run();
    }

    private static void offerSaved(Activity activity, BugReport.Outcome outcome) {
        if (activity == null || activity.isFinishing() || outcome == null || outcome.savedUri == null) return;
        String where = outcome.message.isEmpty()
                ? (outcome.where.isEmpty() ? BugReport.PUBLIC_FOLDER : outcome.where)
                : outcome.message;
        AlertDialog follow = new AlertDialog.Builder(activity)
                .setTitle("Report saved")
                .setMessage(where)
                .setPositiveButton("Share", null)
                .setNeutralButton("Open", null)
                .setNegativeButton("Done", null)
                .create();
        UiKit.styleOrbitDialog(follow, activity, false);
        follow.show();
        Button share = follow.getButton(AlertDialog.BUTTON_POSITIVE);
        Button open = follow.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (share != null) {
            share.setOnClickListener(v -> UiKit.handoffOrbitDialogToSystemSurface(follow,
                    () -> share(activity, outcome)));
        }
        if (open != null) {
            open.setOnClickListener(v -> UiKit.handoffOrbitDialogToSystemSurface(follow,
                    () -> open(activity, outcome)));
        }
    }

    private static void share(Activity activity, BugReport.Outcome outcome) {
        if (activity.isFinishing() || outcome.savedUri == null) return;
        String body = BugReport.readSaved(activity, outcome.savedUri);
        Intent send = BugReport.shareIntent(outcome.savedUri, body);
        Intent chooser = Intent.createChooser(send, "Share bug report");
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(activity, "Couldn't open the share sheet. It's in " + outcome.where,
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void open(Activity activity, BugReport.Outcome outcome) {
        if (activity.isFinishing() || outcome.savedUri == null) return;
        Intent view = BugReport.openIntent(outcome.savedUri);
        Intent chooser = Intent.createChooser(view, "Open bug report");
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(activity, "Couldn't open the report. It's in " + outcome.where,
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void listen(Activity activity, EditText transcript, TextToSpeech[] tts,
                               SpeechRecognizer[] google, boolean[] open) {
        stopListening(activity, google);
        transcript.setText("");
        transcript.setHint("Listening…");
        if (VoskModel.ready(activity) || VoskModel.hasBundledAsset(activity)) {
            MicCaptureService.acquire(activity);
            new Thread(() -> {
                boolean ok = VoskModel.ensureInstalled(activity);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!open[0]) {
                        MicCaptureService.release(activity);
                        return;
                    }
                    if (!ok || !VoskModel.ready(activity)) {
                        MicCaptureService.release(activity);
                        if (LocalModel.online(activity)) listenGoogle(activity, transcript, tts, google, open);
                        else transcript.setHint("Offline speech is not ready");
                        return;
                    }
                    SessionTrace.speech(ProcessTrace.VOSK, "bug report");
                    VoskSpeech.listen(activity, new VoskSpeech.Listener() {
                        @Override public void onPartial(String text) {
                            if (open[0]) transcript.setText(text);
                        }
                        @Override public void onFinal(String text) {
                            finishHeard(activity, transcript, tts, open, text);
                        }
                        @Override public void onError(String message) {
                            MicCaptureService.release(activity);
                            SessionTrace.speech(ProcessTrace.VOSK, message == null ? "error" : message);
                            if (!open[0]) return;
                            if (LocalModel.online(activity)) listenGoogle(activity, transcript, tts, google, open);
                            else transcript.setHint("Didn't catch that");
                        }
                    });
                });
            }, "groklink-bug-speech").start();
            return;
        }
        if (LocalModel.online(activity)) listenGoogle(activity, transcript, tts, google, open);
        else transcript.setHint("Offline speech is not on this phone");
    }

    private static void listenGoogle(Activity activity, EditText transcript, TextToSpeech[] tts,
                                     SpeechRecognizer[] google, boolean[] open) {
        OfflineSpeech.Engine engine = OfflineSpeech.open(activity);
        if (engine == null || engine.recognizer == null) {
            transcript.setHint("Voice input isn't available");
            return;
        }
        google[0] = engine.recognizer;
        String engineName = ProcessTrace.engineLabel(engine.onDevice, true);
        SessionTrace.speech(engineName, "bug report");
        engine.recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                SessionTrace.speech(engineName, "error " + error);
                stopListening(activity, google);
                if (!open[0]) return;
                if (VoskModel.ready(activity)) {
                    MicCaptureService.acquire(activity);
                    VoskSpeech.listen(activity, new VoskSpeech.Listener() {
                        @Override public void onPartial(String text) { if (open[0]) transcript.setText(text); }
                        @Override public void onFinal(String text) { finishHeard(activity, transcript, tts, open, text); }
                        @Override public void onError(String message) {
                            MicCaptureService.release(activity);
                            if (open[0]) transcript.setHint("Didn't catch that");
                        }
                    });
                    return;
                }
                transcript.setHint("Didn't catch that");
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> list = results == null ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = list == null || list.isEmpty() ? "" : list.get(0);
                stopListening(activity, google);
                finishHeard(activity, transcript, tts, open, text);
            }
            @Override public void onPartialResults(Bundle partialResults) {
                if (!open[0] || partialResults == null) return;
                ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) transcript.setText(list.get(0));
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        OfflineSpeech.apply(intent);
        try {
            engine.recognizer.startListening(intent);
        } catch (Exception e) {
            transcript.setHint("Voice input isn't available");
        }
    }

    private static void finishHeard(Activity activity, EditText transcript, TextToSpeech[] tts,
                                    boolean[] open, String text) {
        MicCaptureService.release(activity);
        if (!open[0]) return;
        String heard = text == null ? "" : text.trim();
        transcript.setText(heard);
        transcript.setHint(heard.isEmpty() ? "Didn't catch that" : "What went wrong");
        if (heard.isEmpty()) return;
        SessionTrace.speech("bug report", "transcript " + heard);
        if (tts[0] != null) {
            tts[0].setLanguage(Locale.UK);
            tts[0].speak("I heard: " + heard, TextToSpeech.QUEUE_FLUSH, null, "bug-readback");
        }
    }

    private static void stopListening(Activity activity, SpeechRecognizer[] google) {
        VoskSpeech.cancel();
        MicCaptureService.release(activity);
        if (google[0] != null) {
            try { google[0].cancel(); } catch (Exception ignored) {}
            try { google[0].destroy(); } catch (Exception ignored) {}
            google[0] = null;
        }
    }
}
