package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The published-build description, and the checks that decide whether it may be installed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class GrokLinkUpdateTest {
    private static final String APK = "GrokLink-1.0.42-groklink-debug.apk";
    private static final String URL =
            "https://github.com/tmg270/orbit-fork/releases/download/groklink/" + APK;
    private static final String SHA =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        clearUpdatePrefs();
    }

    @After public void tearDown() {
        clearUpdatePrefs();
    }

    @Test public void aPublishedBuildNewerThanThisPhoneIsAnUpdate() {
        GrokLinkUpdateManifest manifest = manifest(1042);
        assertTrue(manifest.newerThan(1035));
        assertFalse(manifest.newerThan(1042));
        assertFalse(manifest.newerThan(1043));
        assertEquals("Installed 1.0.35-groklink (1035). Available 1.0.42-groklink (1042).",
                manifest.comparison("1.0.35-groklink", 1035));
    }

    @Test public void alreadyOfferedMeansThisVersionWasShown() {
        GrokLinkUpdateManifest manifest = manifest(1042);
        assertFalse(manifest.alreadyOffered(0));
        assertFalse(manifest.alreadyOffered(1041));
        assertTrue(manifest.alreadyOffered(1042));
        assertTrue(manifest.alreadyOffered(1043));
    }

    @Test public void theDescriptionRoundTripsAndNamesThisApp() {
        GrokLinkUpdateManifest manifest = GrokLinkUpdateManifest.parse(sampleJson(), "com.tmg270.orbit");
        GrokLinkUpdateManifest again = GrokLinkUpdateManifest.parse(manifest.toJson(), "com.tmg270.orbit");
        assertEquals(1042L, again.versionCode);
        assertEquals("1.0.42-groklink", again.versionName);
        assertEquals(APK, again.apkName);
        assertEquals(URL, again.apkUrl);
        assertEquals(SHA, again.sha256);
        assertEquals(1000L, again.sizeBytes);
        assertTrue(GrokLinkUpdater.acceptsApkUrl(again.apkUrl, again.apkName));
    }

    @Test public void aDescriptionForAnotherAppIsRefused() {
        try {
            GrokLinkUpdateManifest.parse(sampleJson(), "com.orbit.assistant");
            fail("a different package must not be offered");
        } catch (GrokLinkUpdateException e) {
            assertTrue(e.getMessage().contains("different app"));
        }
    }

    @Test public void aTamperedChecksumOrNameIsRefused() {
        assertRejected(sampleJson().replace(SHA, "zzzz"), "checksum");
        assertRejected(sampleJson().replace(APK, "other.apk"), "file name");
        assertRejected(sampleJson().replace(APK, "GrokLink-../evil.apk"), "file name");
        assertRejected(sampleJson().replace("1042", "0"), "version");
    }

    @Test public void theApkAddressHasToBeThisForksGrokLinkRelease() {
        assertTrue(GrokLinkUpdater.acceptsApkUrl(URL, APK));
        assertFalse(GrokLinkUpdater.acceptsApkUrl(URL.replace("https", "http"), APK));
        assertFalse(GrokLinkUpdater.acceptsApkUrl(
                "https://example.com/tmg270/orbit-fork/releases/download/groklink/" + APK, APK));
        assertFalse(GrokLinkUpdater.acceptsApkUrl(
                "https://github.com/lpnovi/Orbit-Assistant/releases/download/groklink/" + APK, APK));
        assertFalse(GrokLinkUpdater.acceptsApkUrl(
                "https://github.com/tmg270/orbit-fork/releases/download/v1.0.0/" + APK, APK));
        assertFalse(GrokLinkUpdater.acceptsApkUrl(URL + "?raw=1", APK));
        assertFalse(GrokLinkUpdater.acceptsApkUrl(URL + "#frag", APK));
        assertFalse(GrokLinkUpdater.acceptsApkUrl(
                "https://user:token@github.com/tmg270/orbit-fork/releases/download/groklink/" + APK, APK));
        assertFalse(GrokLinkUpdater.acceptsApkUrl(URL, "not-the-file.apk"));
        assertEquals("https://github.com/tmg270/orbit-fork/releases/download/groklink/groklink-update.json",
                GrokLinkUpdater.manifestUrl());
    }

    @Test public void theChecksumIsSha256OfTheFile() throws Exception {
        File file = File.createTempFile("groklink", ".apk");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("abc".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(SHA, GrokLinkUpdater.sha256Hex(file));
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Test public void aFileThatIsNotAnApkIsNotInstalled() throws Exception {
        File file = new File(GrokLinkUpdater.updateDirectory(context), APK);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("abc".getBytes(StandardCharsets.UTF_8));
        }
        GrokLinkUpdateManifest manifest = GrokLinkUpdateManifest.parse(
                sampleJson().replace("\"size\":1000", "\"size\":3"), "com.tmg270.orbit");
        String problem = GrokLinkUpdater.verifyApk(context, file, manifest);
        assertNotNull(problem);
        assertTrue(problem.contains("isn't an installable"));
        assertEquals("updates", GrokLinkUpdater.updateDirectory(context).getName());
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Test public void aboutShowsTheInstalledVersionAndACheckButton() {
        TestWorkManager.ensureInitialized(context);
        UpdateActivity activity = Robolectric.buildActivity(UpdateActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        String tree = texts(activity.getWindow().getDecorView());
        assertTrue(tree, tree.contains("Check for update"));
        assertTrue(tree, tree.contains(String.valueOf(BuildConfig.VERSION_CODE)));
        assertTrue(tree, tree.contains(BuildConfig.VERSION_NAME));
        assertFalse(tree, tree.contains("does not update itself"));
        assertFalse(tree, tree.contains("Get new builds"));
    }

    @Test public void theFeedIsPublicAndTheApkContainsNoToken() throws Exception {
        String updater = source("app/src/github/java/com/orbit/assistant/GrokLinkUpdater.java");
        String play = source("app/src/play/java/com/orbit/assistant/GrokLinkUpdater.java");
        assertFalse(updater.contains("Authorization"));
        assertFalse(updater.contains("ghp_"));
        assertFalse(updater.contains("GITHUB_TOKEN"));
        assertFalse(play.contains("github.com"));
        assertFalse(play.contains("createSession("));
        assertFalse(play.contains("HttpURLConnection"));
        String manifest = source("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"));
        assertTrue(manifest.contains("GrokLinkInstallActivity"));
    }

    @Test public void ciPublishesTheReleaseOnlyForAGreenGrokLinkPush() throws Exception {
        String yml = source(".github/workflows/build-apk.yml");
        assertTrue(yml.contains("groklink-update.json"));
        assertTrue(yml.contains("gh release create groklink"));
        assertTrue(yml.contains("contents: write"));
        assertTrue(yml.contains("success()"));
        assertTrue(yml.contains("github.event_name != 'pull_request'"));
        assertTrue(yml.contains("refs/heads/groklink"));
        assertTrue(yml.contains("tmg270/orbit-fork"));
        assertFalse(yml.contains("ghp_"));
    }

    private static GrokLinkUpdateManifest manifest(long code) {
        return GrokLinkUpdateManifest.parse(sampleJson().replace("1042", Long.toString(code)),
                "com.tmg270.orbit");
    }

    private static String sampleJson() {
        return "{"
                + "\"versionCode\":1042,"
                + "\"versionName\":\"1.0.42-groklink\","
                + "\"apkName\":\"" + APK + "\","
                + "\"apkUrl\":\"" + URL + "\","
                + "\"sha256\":\"" + SHA + "\","
                + "\"size\":1000,"
                + "\"commit\":\"abcdefabcdefabcdefabcdefabcdefabcdefabcd\","
                + "\"packageName\":\"com.tmg270.orbit\""
                + "}";
    }

    private static void assertRejected(String json, String reason) {
        try {
            GrokLinkUpdateManifest.parse(json, "com.tmg270.orbit");
            fail("should have rejected: " + reason);
        } catch (GrokLinkUpdateException expected) {
            assertFalse(expected.retry);
        }
    }

    private void clearUpdatePrefs() {
        Prefs.get(context).edit()
                .remove(GrokLinkUpdater.KEY_JSON)
                .remove(GrokLinkUpdater.KEY_CHECKED)
                .remove(GrokLinkUpdater.KEY_SEEN)
                .commit();
    }

    private static String texts(View view) {
        StringBuilder out = new StringBuilder();
        collect(view, out);
        return out.toString();
    }

    private static void collect(View view, StringBuilder out) {
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private static String source(String relative) throws Exception {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path file = dir.resolve(relative);
            if (Files.isRegularFile(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
        }
        fail("missing " + relative);
        return "";
    }
}
