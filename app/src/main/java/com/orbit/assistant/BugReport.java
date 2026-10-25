package com.orbit.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A verbal bug report: the user's description plus the session trace, saved on the phone and
 * filed as a GitHub issue on tmg270/orbit-fork when a token is stored.
 *
 * <p>The token is a fine-grained personal access token with issue access, kept in the keystore.
 * It is never written into the report, the log, or the request body.
 */
public final class BugReport {
    static final String REPO = "tmg270/orbit-fork";
    private static final String ISSUES = "https://api.github.com/repos/" + REPO + "/issues";
    private static final int MAX_BODY = 60_000;

    public static final class Outcome {
        public final boolean filed;
        public final String message;
        public final String url;

        Outcome(boolean filed, String message, String url) {
            this.filed = filed;
            this.message = message == null ? "" : message;
            this.url = url == null ? "" : url;
        }
    }

    private BugReport() {}

    public static Outcome submit(Context context, String transcript) {
        String body = build(context, transcript);
        File saved = write(context, body);
        String token = SecureStore.loadGithubIssuesToken(context);
        if (token.isEmpty()) {
            return new Outcome(false,
                    "Saved the report on this phone. Add a GitHub token in Report a bug to file it.",
                    saved == null ? "" : saved.getAbsolutePath());
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
            String raw = read(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            conn.disconnect();
            if (code == 201) {
                JSONObject created = new JSONObject(raw);
                int number = created.optInt("number", 0);
                String url = created.optString("html_url", "");
                String said = number > 0
                        ? "Filed on GitHub as issue " + number + "."
                        : "Filed the report on GitHub.";
                return new Outcome(true, said, url);
            }
            if (code == 401 || code == 403) {
                return new Outcome(false,
                        "GitHub refused the token. The report is saved on this phone.",
                        saved == null ? "" : saved.getAbsolutePath());
            }
            return new Outcome(false,
                    "GitHub didn't accept the report (" + code + "). It is saved on this phone.",
                    saved == null ? "" : saved.getAbsolutePath());
        } catch (Exception e) {
            return new Outcome(false,
                    "Couldn't reach GitHub. The report is saved on this phone.",
                    saved == null ? "" : saved.getAbsolutePath());
        }
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

    private static File write(Context context, String body) {
        if (context == null) return null;
        try {
            File dir = new File(context.getFilesDir(), "bug-reports");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            String name = String.format(Locale.US, "bug-%1$tY%1$tm%1$td-%1$tH%1$tM%1$tS.txt",
                    System.currentTimeMillis());
            File out = new File(dir, name);
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String read(java.io.InputStream input) throws Exception {
        if (input == null) return "";
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = input.read(buf)) >= 0 && raw.size() < 20_000) raw.write(buf, 0, n);
        return raw.toString(StandardCharsets.UTF_8.name());
    }
}
