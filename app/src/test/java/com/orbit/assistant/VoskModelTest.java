package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The speech model ships in the APK and unpacks without a download. Unit tests must not unpack it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public final class VoskModelTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        VoskModel.delete(context);
    }

    @After public void tearDown() {
        VoskModel.delete(context);
    }

    @Test public void theBundledArchiveIsTheSmallBritishModel() throws Exception {
        assertEquals("speech/vosk-model-small-en-gb-0.15.zip", VoskModel.ASSET);
        assertTrue(VoskModel.hasBundledAsset(context));
        long n = 0;
        try (InputStream in = context.getAssets().open(VoskModel.ASSET)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) n += r;
        }
        assertEquals(VoskModel.SIZE_BYTES, n);
    }

    @Test public void unitTestsDoNotUnpackTheBundledArchive() {
        assertFalse(VoskModel.ensureInstalled(context));
        assertFalse(VoskModel.ready(context));
    }

    @Test public void unpackingAModelMakesItReady() throws Exception {
        File dest = new File(context.getFilesDir(), "speech-unpack-test");
        VoskModel.unpack(new ByteArrayInputStream(tinyModel()), dest);
        assertTrue(new File(dest, VoskModel.FOLDER + "/conf/model.conf").isFile());
        // The same layout, dropped where the recogniser looks, counts as installed.
        VoskModel.unpack(new ByteArrayInputStream(tinyModel()), VoskModel.root(context));
        assertTrue(VoskModel.ready(context));
        deleteDir(dest);
    }

    @Test public void aZipEntryThatEscapesTheSpeechFolderIsRefused() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(raw);
        zip.putNextEntry(new ZipEntry("../escaped.txt"));
        zip.write("no".getBytes());
        zip.closeEntry();
        zip.close();
        File dest = new File(context.getFilesDir(), "speech-slip-test");
        try {
            VoskModel.unpack(new ByteArrayInputStream(raw.toByteArray()), dest);
            fail("zip slip should be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("escapes"));
        }
        assertFalse(new File(context.getFilesDir(), "escaped.txt").exists());
        deleteDir(dest);
    }

    private static byte[] tinyModel() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(raw);
        zip.putNextEntry(new ZipEntry(VoskModel.FOLDER + "/conf/model.conf"));
        zip.write("model".getBytes());
        zip.closeEntry();
        zip.close();
        return raw.toByteArray();
    }

    private static void deleteDir(File file) {
        if (file == null || !file.exists()) return;
        File[] kids = file.listFiles();
        if (kids != null) for (File kid : kids) deleteDir(kid);
        file.delete();
    }
}
