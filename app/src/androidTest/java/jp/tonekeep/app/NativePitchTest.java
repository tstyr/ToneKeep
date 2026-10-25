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
            // Every newly offered encoder must work in the APK, then play inside the app.
            var open = MainActivity.class.getDeclaredMethod("openFile", DownloadStore.Entry.class); open.setAccessible(true);
            var playerField = MainActivity.class.getDeclaredField("player"); playerField.setAccessible(true);
            AudioPlayer player = (AudioPlayer) playerField.get(activity);
            for (String extension : PitchSettings.FORMATS) {
                DownloadStore.Entry entry = store.enqueuePitch(created.get(0).toString(), "Output " + extension, new PitchSettings(4, true, extension));
                instrumentation.runOnMainSync(() -> DownloadService.start(app));
                long deadline = System.currentTimeMillis() + 120_000;
                while (store.find(entry.id()).active() && System.currentTimeMillis() < deadline) Thread.sleep(250);
                DownloadStore.Entry result = store.find(entry.id());
                assertEquals("Native " + extension + ": " + result.message(), "complete", result.status());
                Uri destination = Uri.parse(result.contentUri()); created.add(destination);
                File encoded = new File(app.getCacheDir(), "verify." + extension);
                try (InputStream input = app.getContentResolver().openInputStream(destination); OutputStream output = new FileOutputStream(encoded)) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                String encodedInfo = PitchEngine.execute(app, "libffprobe.so", List.of("-v", "error", "-show_entries", "stream=codec_name", "-of", "json", encoded.getAbsolutePath()),
                        "verify-codec", new File(app.getCacheDir(), "verify-codec.log"), () -> true, null);
                String codec = switch (extension) { case "wav" -> "pcm_s24le"; case "m4a" -> "aac"; case "ogg" -> "vorbis"; default -> extension; };
                assertEquals(codec, new org.json.JSONObject(encodedInfo).getJSONArray("streams").getJSONObject(0).getString("codec_name")); encoded.delete();
                try (var descriptor = app.getContentResolver().openFileDescriptor(destination, "r")) {
                    MediaExtractor extractor = new MediaExtractor();
                    try {
                        extractor.setDataSource(descriptor.getFileDescriptor());
                        MediaFormat format = extractor.getTrackFormat(0);
                        // Android's FLAC extractor exposes decoded PCM (audio/raw).
                        String mime = switch (extension) { case "flac", "wav" -> "audio/raw"; case "mp3" -> "audio/mpeg"; case "m4a" -> "audio/mp4a-latm"; case "opus" -> "audio/opus"; default -> "audio/vorbis"; };
                        assertEquals(mime, format.getString(MediaFormat.KEY_MIME));
                        assertEquals(48000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                        assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        assertEquals(6_000_000, format.getLong(MediaFormat.KEY_DURATION), 120_000);
                    } finally { extractor.release(); }
                }
                instrumentation.runOnMainSync(() -> { try { open.invoke(activity, result); } catch (Exception error) { throw new RuntimeException(error); } });
                awaitPlaying(player);
                instrumentation.runOnMainSync(() -> player.seekTo(1500)); Thread.sleep(300);
                instrumentation.runOnMainSync(() -> { assertTrue(player.position() >= 1400); player.pause(); assertFalse(player.isPlaying()); });
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
            int savedCount = store.count();
            instrumentation.runOnMainSync(() -> findDescription(activity.findViewById(android.R.id.content), "元音源を再生").performClick());
            awaitPlaying(player);
            instrumentation.runOnMainSync(() -> findDescription(activity.findViewById(android.R.id.content), "キー変更をプレビュー再生").performClick());
            awaitPlaying(player);
            // Rapid setting changes cancel stale renders; seeking rebuilds the requested section.
            instrumentation.runOnMainSync(() -> {
                for (int i = 0; i < 3; i++) findDescription(activity.findViewById(android.R.id.content), "半音上げる").performClick();
            });
            awaitPlaying(player);
            instrumentation.runOnMainSync(() -> player.seekTo(2000));
            awaitPlaying(player);
            instrumentation.runOnMainSync(() -> assertTrue("Preview must start at selected source position", player.position() >= 2000));
            assertEquals("Preview must not create saved jobs", savedCount, store.count());
            capture("preview");
            instrumentation.runOnMainSync(() -> {
                findDescription(activity.findViewById(android.R.id.content), "半音下げる").performClick();
                findDescription(activity.findViewById(android.R.id.content), "プレーヤーを閉じる").performClick();
            });
            Thread.sleep(750);
            instrumentation.runOnMainSync(() -> { assertFalse(player.isPlaying()); assertEquals(View.GONE, player.view.getVisibility()); });
            instrumentation.runOnMainSync(() -> {
                View save = findDescription(activity.findViewById(android.R.id.content), "テンポを保って保存");
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue(save.isEnabled()); assertTrue(save.getGlobalVisibleRect(visible));
                assertEquals("Save must be fully visible on a 360dp screen", save.getHeight(), visible.height());
                View preview = findDescription(activity.findViewById(android.R.id.content), "キー変更をプレビュー再生");
                assertTrue(preview.isEnabled()); assertTrue(preview.getGlobalVisibleRect(visible));
                assertEquals("Preview must be fully visible on a 360dp screen", preview.getHeight(), visible.height());
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
    private void awaitPlaying(AudioPlayer player) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        java.util.concurrent.atomic.AtomicBoolean playing = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> playing.set(player.isPlaying()));
            if (playing.get()) return;
            Thread.sleep(100);
        }
        fail("In-app audio did not reach playing state");
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
