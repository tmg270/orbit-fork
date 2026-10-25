package com.orbit.assistant;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The public description of one published GrokLink build.
 *
 * <p>Written by GitHub Actions onto the floating {@code groklink} release as
 * {@code groklink-update.json}. It names the version and the APK. It does not contain a token
 * or any other secret. The phone decides whether the build is newer from {@code versionCode}
 * alone, which is the number Android itself uses to allow or refuse an update.
 */
public final class GrokLinkUpdateManifest {
    /** Larger than a GrokLink debug APK, smaller than a mistake. */
    static final long MAX_BYTES = 256L * 1024L * 1024L;

    public final long versionCode;
    public final String versionName;
    public final String apkName;
    public final String apkUrl;
    public final String sha256;
    public final long sizeBytes;
    public final String commit;

    private GrokLinkUpdateManifest(long versionCode, String versionName, String apkName,
                                   String apkUrl, String sha256, long sizeBytes, String commit) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkName = apkName;
        this.apkUrl = apkUrl;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.commit = commit == null ? "" : commit;
    }

    public boolean newerThan(long installedVersionCode) {
        return versionCode > installedVersionCode;
    }

    /** True when this version has already been offered, so the phone does not ask again. */
    public boolean alreadyOffered(long seenVersionCode) {
        return versionCode <= seenVersionCode;
    }

    public String comparison(String installedName, long installedCode) {
        return "Installed " + installedName + " (" + installedCode + "). Available "
                + versionName + " (" + versionCode + ").";
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("versionCode", versionCode);
            o.put("versionName", versionName);
            o.put("apkName", apkName);
            o.put("apkUrl", apkUrl);
            o.put("sha256", sha256);
            o.put("size", sizeBytes);
            o.put("commit", commit);
            o.put("packageName", "com.tmg270.orbit");
        } catch (JSONException ignored) {
            // The fields are plain values we just validated. This cannot fail.
        }
        return o.toString();
    }

    /**
     * Reads a manifest. Rejects anything that is not a single GrokLink APK description.
     * The download address is checked separately, where the edition is allowed to name GitHub.
     */
    public static GrokLinkUpdateManifest parse(String json, String expectedPackage) {
        if (json == null || json.trim().isEmpty()) {
            throw new GrokLinkUpdateException("The update description was empty.", false);
        }
        if (json.length() > 65536) {
            throw new GrokLinkUpdateException("The update description was too large.", false);
        }
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            throw new GrokLinkUpdateException("The update description wasn't valid.", false);
        }
        String packageName = o.optString("packageName", expectedPackage);
        if (expectedPackage == null || !expectedPackage.equals(packageName)) {
            throw new GrokLinkUpdateException("That build is for a different app.", false);
        }
        long code;
        try {
            code = o.getLong("versionCode");
        } catch (JSONException e) {
            throw new GrokLinkUpdateException("The update description has no version code.", false);
        }
        if (code < 1 || code > Integer.MAX_VALUE) {
            throw new GrokLinkUpdateException("The published version code isn't usable.", false);
        }
        String versionName = text(o, "versionName", 80);
        String apkName = text(o, "apkName", 120);
        if (!safeApkName(apkName)) {
            throw new GrokLinkUpdateException("The published file name isn't a GrokLink APK.", false);
        }
        String apkUrl = text(o, "apkUrl", 400);
        String sha = text(o, "sha256", 64).toLowerCase(java.util.Locale.US);
        if (!sha.matches("[0-9a-f]{64}")) {
            throw new GrokLinkUpdateException("The published checksum isn't usable.", false);
        }
        long size = o.optLong("size", 0L);
        if (size < 0 || size > MAX_BYTES) {
            throw new GrokLinkUpdateException("The published build is an unexpected size.", false);
        }
        String commit = o.optString("commit", "");
        if (commit.length() > 64 || !plain(commit)) commit = "";
        return new GrokLinkUpdateManifest(code, versionName, apkName, apkUrl, sha, size, commit);
    }

    /** A file name we are willing to write under the updates directory. */
    static boolean safeApkName(String name) {
        if (name == null || !name.startsWith("GrokLink-") || !name.endsWith(".apk")) return false;
        if (name.length() < 12 || name.length() > 120 || name.contains("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private static String text(JSONObject o, String key, int max) {
        String value = o.optString(key, "").trim();
        if (value.isEmpty() || value.length() > max || !plain(value)) {
            throw new GrokLinkUpdateException("The update description is missing " + key + ".", false);
        }
        return value;
    }

    private static boolean plain(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
        }
        return true;
    }
}

/** A fetch or a manifest the updater will not install. {@code retry} is for the background check. */
final class GrokLinkUpdateException extends RuntimeException {
    final boolean retry;

    GrokLinkUpdateException(String message, boolean retry) {
        super(message);
        this.retry = retry;
    }
}
