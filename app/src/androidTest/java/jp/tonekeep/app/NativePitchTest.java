package jp.tonekeep.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Executes the APK's packaged Android binaries, foreground queue and MediaStore. */
@RunWith(AndroidJUnit4.class)
public class NativePitchTest {
    @Test public void importedWavM4aAndOpusAreShiftedAndPublished() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context app = instrumentation.getTargetContext();
        Context tests = instrumentation.getContext();
        List<Uri> created = new ArrayList<>();
        Activity activity = instrumentation.startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            DownloadStore store = new DownloadStore(app);
            int index = 0;
            for (String extension : List.of("wav", "m4a", "opus")) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, "ToneKeep fixture." + extension);
                values.put(MediaStore.Downloads.MIME_TYPE, extension.equals("wav") ? "audio/wav" : extension.equals("m4a") ? "audio/mp4" : "audio/ogg");
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/ToneKeep-tests");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri source = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                assertNotNull(source); created.add(source);
                try (InputStream input = tests.getAssets().open("fixture." + extension); OutputStream output = app.getContentResolver().openOutputStream(source)) {
                    assertNotNull(output); byte[] buffer = new byte[16384]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); app.getContentResolver().update(source, values, null, null);
                int key = index == 0 ? -3 : index == 1 ? 3 : -5;
                DownloadStore.Entry entry = store.enqueuePitch(source.toString(), "Fixture " + extension, new PitchSettings(key, true, index == 1 ? "wav" : "flac"));
                instrumentation.runOnMainSync(() -> DownloadService.start(app));
                long deadline = System.currentTimeMillis() + 120_000;
                while (store.find(entry.id()).active() && System.currentTimeMillis() < deadline) Thread.sleep(250);
                DownloadStore.Entry result = store.find(entry.id());
                assertEquals("Native " + extension + " failed: " + result.message(), "complete", result.status());
                Uri destination = Uri.parse(result.contentUri()); created.add(destination);
                try (var descriptor = app.getContentResolver().openFileDescriptor(destination, "r")) {
                    assertNotNull(descriptor);
                    MediaExtractor extractor = new MediaExtractor();
                    try {
                        extractor.setDataSource(descriptor.getFileDescriptor());
                        MediaFormat format = extractor.getTrackFormat(0);
                        assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        assertEquals(48000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                        assertEquals(6_000_000, format.getLong(MediaFormat.KEY_DURATION), 120_000);
                    } finally { extractor.release(); }
                }
                // Chain another job from the just-published file, as in the download library.
                if (index == 0) {
                    DownloadStore.Entry chained = store.enqueuePitch(destination.toString(), "Saved source", new PitchSettings(2, false, "wav"));
                    instrumentation.runOnMainSync(() -> DownloadService.start(app));
                    while (store.find(chained.id()).active() && System.currentTimeMillis() < deadline) Thread.sleep(250);
                    DownloadStore.Entry chainedResult = store.find(chained.id());
                    assertEquals(chainedResult.message(), "complete", chainedResult.status());
                    created.add(Uri.parse(chainedResult.contentUri()));
                }
                index++;
            }
            // Actual Android screenshots of all three screens, including realistic titles.
            var select = MainActivity.class.getDeclaredMethod("selectPitch", String.class, String.class); select.setAccessible(true);
            instrumentation.runOnMainSync(() -> {
                try { select.invoke(activity, created.get(0).toString(), "ずっと真夜中でいいのに。『あいつら全員同窓会』MV (ZUTOMAYO - Inside Joke)"); }
                catch (Exception error) { throw new RuntimeException(error); }
                findDescription(activity.findViewById(android.R.id.content), "半音下げる").performClick();
                findDescription(activity.findViewById(android.R.id.content), "半音下げる").performClick();
                findDescription(activity.findViewById(android.R.id.content), "半音下げる").performClick();
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                View save = findDescription(activity.findViewById(android.R.id.content), "テンポを保って保存");
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue(save.isEnabled()); assertTrue(save.getGlobalVisibleRect(visible));
                assertEquals("Save must be fully visible on a 360dp screen", save.getHeight(), visible.height());
            });
            capture("pitch");
            for (String tab : List.of("ライブラリ", "検索画面")) {
                instrumentation.runOnMainSync(() -> findDescription(activity.findViewById(android.R.id.content), tab).performClick());
                capture(tab.equals("ライブラリ") ? "library" : "search");
            }
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            for (Uri uri : created) app.getContentResolver().delete(uri, null, null);
        }
    }
    private void capture(String name) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.waitForIdleSync(); Thread.sleep(400);
        Context app = instrumentation.getTargetContext();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name + ".png");
        values.put(MediaStore.Downloads.MIME_TYPE, "image/png");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/ToneKeep-verification");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri destination = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values); assertNotNull(destination);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (OutputStream output = app.getContentResolver().openOutputStream(destination)) { assertNotNull(output); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); app.getContentResolver().update(destination, values, null, null);
        bitmap.recycle();
    }
    private View findDescription(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View child = findDescription(group.getChildAt(i), description); if (child != null) return child; }
        return null;
    }
}
