package jp.tonekeep.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import static org.junit.Assert.*;

/** Opt-in device demo setup; leaves the song copy and cached result for normal UI use. */
@RunWith(AndroidJUnit4.class)
public class DeviceLyricsDemoSetup {
    @Test public void installPreviousRecognitionForNormalPlayer() throws Exception {
        org.junit.Assume.assumeTrue("Explicit opt-in required", "true".equals(InstrumentationRegistry.getArguments().getString("prepareDemo")));
        var instrumentation = InstrumentationRegistry.getInstrumentation(); Context app = instrumentation.getTargetContext(), tests = instrumentation.getContext();
        Lyrics lyrics;
        try (InputStream in = tests.getAssets().open("demo-lyrics.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) { transfer(in,out,null); lyrics = Lyrics.parse(out.toString(StandardCharsets.UTF_8.name())); }
        Uri source = null; boolean saved = false;
        try {
            ContentValues values = new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME,"SAYONARA MAYBE.mp3"); values.put(MediaStore.Downloads.MIME_TYPE,"audio/mpeg"); values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/ToneKeep-lyrics-demo"); values.put(MediaStore.Downloads.IS_PENDING,1);
            source = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values); assertNotNull(source);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand("cat /data/local/tmp/tonekeep-lyrics-cb8720a6/input.mp3")); OutputStream out = app.getContentResolver().openOutputStream(source)) { assertNotNull(out); transfer(in,out,digest); }
            StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(Character.forDigit((b >>> 4) & 15,16)).append(Character.forDigit(b & 15,16)); assertEquals(lyrics.sourceHash(),hash.toString());
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING,0); app.getContentResolver().update(source,values,null,null);
            DownloadStore downloads = new DownloadStore(app);
            var entry = downloads.enqueue(new MediaInfo.Track("local-demo","SAYONARA MAYBE","","https://example.invalid/local-lyrics-demo","local",244,false,List.of()),"","端末内MP3");
            assertTrue(downloads.update(entry.id(),"complete","端末内MP3・認識済み歌詞",100,source.toString(),"SAYONARA MAYBE.mp3"));
            LyricsStore store = new LyricsStore(app); var task = store.enqueue(entry.id(),source.toString(),"small",true,"lyrics"); assertTrue(store.save(task,lyrics));
            assertEquals(lyrics,store.load(entry.id(),source.toString())); saved = true;
        } finally { if (!saved && source != null) app.getContentResolver().delete(source,null,null); }
    }
    private static void transfer(InputStream in, OutputStream out, MessageDigest digest) throws IOException { byte[] b = new byte[65536]; int n; while ((n=in.read(b)) != -1) { out.write(b,0,n); if (digest != null) digest.update(b,0,n); } }
}
