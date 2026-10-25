package com.orbit.assistant;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A verbal bug report: the user's description plus the session trace, written to
 * Downloads/GrokLink so it can be opened from Files, and filed as a GitHub issue on
 * tmg270/orbit-fork only when a token is already stored.
 *
 * <p>The token is a fine-grained personal access token the user pastes into Report a bug.
 * It stays in the keystore. It is never written into the report, the log, the APK, or the
 * request body. Without a token the Downloads copy is the whole result.
 */
public final class BugReport {
    static final String REPO = "tmg270/orbit-fork";
    private static final String ISSUES = "https://api.github.com/repos/" + REPO + "/issues";
    private static final int MAX_BODY = 60_000;

    /** MediaStore relative path. {@code Download} is the on-disk name of the public Downloads folder. */
    static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/GrokLink";
    /** What a person looks for in the Files app. */
    static final String PUBLIC_FOLDER = "Downloads/GrokLink";

    public static final class Outcome {
        public final boolean filed;
        public final String message;
        /** GitHub issue page when one was created; empty otherwise. */
        public final String url;
        /** Content URI of the Downloads copy, or null when it could not be written. */
        public final Uri savedUri;
        /** Plain location such as {@code Downloads/GrokLink/bug-20261025-120000.txt}. */
        public final String where;

        Outcome(boolean filed, String message, String url, Uri savedUri, String where) {
            this.filed = filed;
            this.message = message == null ? "" : message;
            this.url = url == null ? "" : url;
            this.savedUri = savedUri;
            this.where = where == null ? "" : where;
        }
    }

    private BugReport() {}

    public static Outcome submit(Context context, String transcript) {
        String body = build(context, transcript);
        Saved saved = write(context, body);
        String local = savedLine(saved);
        String token = SecureStore.loadGithubIssuesToken(context);
        if (token.isEmpty()) {
            return outcome(false, local, "", saved);
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(ISSUES).openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", GrokLinkHttp.userAgent());
            JSONObject payload = new JSONObject();
            payload.put("title", title(transcript));
            payload.put("body", body);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            conn.getOutputStream().write(bytes);
            int code = conn.getResponseCode();
            // The created issue echoes the report, so the reply is larger than the 60k body.
            String raw = readUpTo(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                    512_000);
            conn.disconnect();
            if (code == 201) {
                JSONObject created = new JSONObject(raw);
                int number = created.optInt("number", 0);
                String url = created.optString("html_url", "");
                String said = number > 0
                        ? "Filed on GitHub as issue " + number + ". " + local
                        : "Filed the report on GitHub. " + local;
                return outcome(true, said, url, saved);
            }
            if (code == 401 || code == 403) {
                return outcome(false, "GitHub refused the token. " + local, "", saved);
            }
            return outcome(false,
                    "GitHub didn't accept the report (" + code + "). " + local, "", saved);
        } catch (Exception e) {
            return outcome(false, "Couldn't reach GitHub. " + local, "", saved);
        }
    }

    private static Outcome outcome(boolean filed, String message, String url, Saved saved) {
        return new Outcome(filed, message, url,
                saved == null ? null : saved.uri,
                saved == null ? "" : saved.where);
    }

    private static String savedLine(Saved saved) {
        if (saved == null || saved.where.isEmpty()) return "Couldn't save the report to Downloads.";
        return "Saved to " + saved.where + ".";
    }

    static String title(String transcript) {
        String t = transcript == null ? "" : transcript.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) t = "session report";
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return SessionTrace.redact("GrokLink bug: " + t);
    }

    static String build(Context context, String transcript) {
        String said = transcript == null ? "" : transcript.trim();
        StringBuilder b = new StringBuilder();
        b.append("## What happened\n\n");
        b.append(said.isEmpty() ? "(no description)" : said).append("\n\n");
        b.append("## App\n\n").append(SessionTrace.versionLine(context)).append("\n\n");
        b.append("## Device\n\n").append(SessionTrace.deviceLine()).append("\n\n");
        b.append("## Session\n\n");
        String trace = SessionTrace.dump();
        b.append(trace.isEmpty() ? "(nothing recorded this session)" : trace).append("\n\n");
        b.append("## Logcat\n\n```\n").append(SessionTrace.logcat()).append("\n```\n");
        b.append("\n_Filed from GrokLink at ").append(SessionTrace.stamp()).append("._\n");
        String all = SessionTrace.redact(b.toString());
        if (all.length() > MAX_BODY) all = all.substring(0, MAX_BODY) + "\n…(truncated)";
        return all;
    }

    /**
     * Hands the Downloads copy to another app. The stream is the file; the text is the same
     * report for apps that only accept a message.
     */
    static Intent shareIntent(Uri uri, String body) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, "GrokLink bug report");
        if (body != null && !body.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, body);
        send.setClipData(ClipData.newRawUri("GrokLink bug report", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return send;
    }

    static Intent openIntent(Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, "text/plain");
        view.setClipData(ClipData.newRawUri("GrokLink bug report", uri));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return view;
    }

    static String readSaved(Context context, Uri uri) {
        if (context == null || uri == null) return "";
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            return readUpTo(input, MAX_BODY + 4096);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static final class Saved {
        final Uri uri;
        final String where;

        Saved(Uri uri, String where) {
            this.uri = uri;
            this.where = where == null ? "" : where;
        }
    }

    private static Saved write(Context context, String body) {
        if (context == null || body == null) return null;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String name = fileName(System.currentTimeMillis());
        Saved saved = insertDownload(context, name, bytes, true);
        if (saved != null) return saved;
        return insertDownload(context, name, bytes, false);
    }

    private static String fileName(long when) {
        return String.format(Locale.US, "bug-%1$tY%1$tm%1$td-%1$tH%1$tM%1$tS.txt", when);
    }

    private static Saved insertDownload(Context context, String name, byte[] bytes, boolean pending) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
        if (pending) values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = insertRow(resolver, values);
        if (uri == null) return null;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) {
                abandon(resolver, uri);
                return null;
            }
            out.write(bytes);
        } catch (Exception e) {
            abandon(resolver, uri);
            return null;
        }
        if (pending) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try {
                resolver.update(uri, done, null, null);
            } catch (Exception e) {
                abandon(resolver, uri);
                return null;
            }
        }
        String shown = storedName(resolver, uri, name);
        return new Saved(uri, PUBLIC_FOLDER + "/" + shown);
    }

    private static Uri insertRow(ContentResolver resolver, ContentValues values) {
        Uri primary = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try {
            Uri uri = resolver.insert(primary, new ContentValues(values));
            if (uri != null) return uri;
        } catch (Exception ignored) {}
        try {
            return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, new ContentValues(values));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String storedName(ContentResolver resolver, Uri uri, String fallback) {
        try (android.database.Cursor cursor = resolver.query(uri,
                new String[] {MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static void abandon(ContentResolver resolver, Uri uri) {
        try {
            resolver.delete(uri, null, null);
        } catch (Exception ignored) {}
    }

    private static String readUpTo(InputStream input, int limit) throws Exception {
        if (input == null) return "";
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = input.read(buf)) >= 0 && raw.size() < limit) raw.write(buf, 0, n);
        return raw.toString(StandardCharsets.UTF_8.name());
    }
}
