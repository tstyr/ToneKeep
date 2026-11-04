package jp.tonekeep.app;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Runs the actual Android ONNX JNI and packaged FFmpeg, including the first model download. */
@RunWith(AndroidJUnit4.class)
public class NativeVocalTest {
    @Test public void localSourceSeparatesToStereoFlacAndKeepsTheOriginal() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, "ToneKeep-owned-vocal-test.wav");
        values.put(MediaStore.Downloads.MIME_TYPE, "audio/wav");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/ToneKeep-verification");
        Uri source = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull(source);
        File work = new File(app.getCacheDir(), "native-vocals-test");
        DownloadStore store = new DownloadStore(app); DownloadStore.Entry entry = null;
        // One second of owned PCM exercises the real JNI while keeping this device smoke small.
        ByteBuffer wav = ByteBuffer.allocate(44 + 44100 * 4).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(wav.capacity() - 8).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wav.putInt(16).putShort((short) 1).putShort((short) 2).putInt(44100).putInt(176400).putShort((short) 4).putShort((short) 16);
        wav.put(new byte[]{'d','a','t','a'}).putInt(44100 * 4);
        for (int i = 0; i < 44100; i++) for (int channel = 0; channel < 2; channel++)
            wav.putShort((short) (8000 * Math.sin(2 * Math.PI * (440 + channel * 220) * i / 44100)));
        try {
            try (OutputStream output = app.getContentResolver().openOutputStream(source)) { assertNotNull(output); output.write(wav.array()); }
            entry = store.enqueueVocals(source.toString(), "Owned fixture", VocalQuality.HIGH);
            AtomicInteger progress = new AtomicInteger();
            File output = VocalEngine.render(app, entry, work, () -> true, (percent, message) -> {
                assertTrue(percent >= progress.get()); progress.set(percent);
            });
            assertTrue(output.isFile()); assertTrue(VocalEngine.modelAvailable(app));
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(output.getAbsolutePath());
                MediaFormat format = extractor.getTrackFormat(0);
                assertEquals(44100, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                assertEquals(1_000_000, format.getLong(MediaFormat.KEY_DURATION), 30_000);
            } finally { extractor.release(); }
            try (var input = app.getContentResolver().openInputStream(source)) {
                assertNotNull(input);
                java.io.ByteArrayOutputStream original = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) original.write(buffer, 0, count);
                assertArrayEquals(wav.array(), original.toByteArray());
            }
        } finally {
            if (entry != null) store.update(entry.id(), "cancelled", "test finished", 0, null, null);
            app.getContentResolver().delete(source, null, null);
            File[] files = work.listFiles(); if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(work.toPath());
        }
    }
}
