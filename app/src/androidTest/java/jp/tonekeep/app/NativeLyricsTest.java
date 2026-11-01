package jp.tonekeep.app;

import android.content.Context;
import android.content.ContentValues;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** CI fetches the 78MB multilingual tiny model for JNI smoke only. App default remains small. */
@RunWith(AndroidJUnit4.class)
public class NativeLyricsTest {
    @Test public void bundledFfmpegAndWhisperRecognizeOriginalJapaneseSpeechWithoutChangingSource() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation(); Context app = instrumentation.getTargetContext(), tests = instrumentation.getContext();
        if ("true".equals(InstrumentationRegistry.getArguments().getString("requireOffline")))
            assertEquals("CI emulator must remain in airplane mode",1,android.provider.Settings.Global.getInt(app.getContentResolver(),android.provider.Settings.Global.AIRPLANE_MODE_ON,0));
        File work = new File(app.getCacheDir(),"lyrics-native-test"); assertTrue(work.isDirectory() || work.mkdirs());
        File source = new File(work,"source.wav"), wav = new File(work,"pcm.wav"), model = new File(work,"model.bin");
        try {
            copy(tests,"lyrics-ja.wav",source); copy(tests,"whisper-smoke.bin",model);
            String expected = model.length() == 487601967L ? ModelManager.spec("small").sha256() : "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21";
            assertEquals(expected,ModelManager.hash(model,() -> true)); String original = ModelManager.hash(source,() -> true);
            PitchEngine.execute(app,"libffmpeg.so",List.of("-nostdin","-v","error","-y","-protocol_whitelist","file,pipe","-i",source.getAbsolutePath(),"-vn","-ar","16000","-ac","1","-c:a","pcm_s16le",wav.getAbsolutePath()),"lyrics-test",new File(work,"decode.log"),() -> true,null);
            List<Lyrics.Line> lines = new ArrayList<>(); long handle = NativeWhisper.open(model.getAbsolutePath());
            final long cancelled = handle;
            NativeWhisper.cancel(cancelled);
            assertThrows(IOException.class,() -> NativeWhisper.transcribe(cancelled,new float[16000],true,2));
            NativeWhisper.close(handle); handle = NativeWhisper.open(model.getAbsolutePath());
            try (LyricsEngine.PcmReader pcm = new LyricsEngine.PcmReader(wav)) {
                assertTrue(pcm.samples < 30 * 16000); byte[] result = NativeWhisper.transcribe(handle,pcm.read((int)pcm.samples),true,2);
                LyricsAlignment.append(lines,new JSONArray(new String(result,StandardCharsets.UTF_8)),0,pcm.samples/16,LyricsAlignment.TOKEN_ESTIMATE);
                assertFalse("Expected Japanese speech recognition",lines.isEmpty());
                assertTrue(lines.stream().anyMatch(l -> l.text().matches(".*[ぁ-んァ-ン一-龯].*")));
                Lyrics lyrics = new Lyrics(1,original,"smoke","ja",pcm.samples/16,lines);
                assertEquals(lyrics,Lyrics.parse(lyrics.json())); assertFalse(lyrics.export("elrc").isEmpty());
                evidence(app,"lyrics-native-result.json",lyrics.json().getBytes(StandardCharsets.UTF_8));
            } finally { NativeWhisper.close(handle); }
            assertEquals(original,ModelManager.hash(source,() -> true));
        } finally { LyricsEngine.clean(work); }
    }
    @Test public void invalidModelReportsJavaErrorRatherThanCrashing() {
        assertThrows(IOException.class,() -> NativeWhisper.open("/nonexistent/tonekeep-model.bin"));
    }
    @Test public void standardSmallServicePersistsLyricsForTheExactSavedUriAndPlayerDisplaysThem() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation(); Context app = instrumentation.getTargetContext(), tests = instrumentation.getContext();
        try (InputStream in = tests.getAssets().open("whisper-smoke.bin")) {
            org.junit.Assume.assumeTrue("Run fetch_lyrics_test_model.py --model small for full service verification",in.available() == ModelManager.spec("small").bytes());
        }
        ModelManager models = new ModelManager(app); File model = models.file("small"); assertTrue(model.getParentFile().isDirectory() || model.getParentFile().mkdirs()); copy(tests,"whisper-smoke.bin",model);
        android.net.Uri source = null; android.app.Activity activity = null;
        try {
            ContentValues values = new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME,"ToneKeep original lyrics test.wav"); values.put(MediaStore.Downloads.MIME_TYPE,"audio/wav"); values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/ToneKeep-tests"); values.put(MediaStore.Downloads.IS_PENDING,1);
            source = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values); assertNotNull(source);
            try (InputStream in = tests.getAssets().open("lyrics-ja.wav"); OutputStream out = app.getContentResolver().openOutputStream(source)) { assertNotNull(out); byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) out.write(b,0,n); }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING,0); app.getContentResolver().update(source,values,null,null);
            DownloadStore downloads = new DownloadStore(app);
            var entry = downloads.enqueue(new MediaInfo.Track("lyrics-test","オリジナル音声・歌詞テスト","","https://example.invalid/lyrics-fixture","test",6,false,List.of()),"","test");
            downloads.update(entry.id(),"complete","fixture",100,source.toString(),"lyrics-ja.wav");
            LyricsStore store = new LyricsStore(app); LyricsStore.Task task = store.enqueue(entry.id(),source.toString(),"small",true,"lyrics");
            activity = instrumentation.startActivitySync(new android.content.Intent(app,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.runOnMainSync(() -> LyricsService.start(app));
            long deadline = android.os.SystemClock.elapsedRealtime() + 300000;
            while (store.find(task.id()).active() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(250);
            assertEquals(store.find(task.id()).message(),"complete",store.find(task.id()).status());
            Lyrics result = store.load(entry.id(),source.toString()); assertNotNull(result); assertFalse(result.lines().isEmpty());
            assertNull(store.load("different-track",source.toString())); assertEquals("complete",downloads.find(entry.id()).status());
            android.app.Activity screen = activity;
            instrumentation.runOnMainSync(() -> {
                try {
                    java.lang.reflect.Method open = MainActivity.class.getDeclaredMethod("openFile",DownloadStore.Entry.class); open.setAccessible(true); open.invoke(screen,downloads.find(entry.id()));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            Thread.sleep(1800);
            instrumentation.runOnMainSync(() -> {
                try {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("player"); field.setAccessible(true); AudioPlayer player = (AudioPlayer)field.get(screen);
                    assertTrue("Saved fixture should play",player.isPlaying());
                    for (android.view.View v : allViews(player.view)) if (v instanceof android.widget.Button button && button.getText().toString().equals("歌詞")) button.performClick();
                    player.seekTo(1500); player.pause();
                } catch (Exception error) { throw new AssertionError(error); }
            });
            evidence(app,"lyrics-small-result.json",result.json().getBytes(StandardCharsets.UTF_8));
            // Capture only our own view: the person may be using another app during background tests.
            final android.graphics.Bitmap[] bitmap = new android.graphics.Bitmap[1];
            instrumentation.runOnMainSync(() -> { android.view.View root = screen.getWindow().getDecorView(); bitmap[0] = android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888); root.draw(new android.graphics.Canvas(bitmap[0])); });
            ByteArrayOutputStream image = new ByteArrayOutputStream(); bitmap[0].compress(android.graphics.Bitmap.CompressFormat.PNG,100,image); bitmap[0].recycle(); evidence(app,"lyrics-screen.png",image.toByteArray());
        } finally {
            if (activity != null) { android.app.Activity screen = activity; instrumentation.runOnMainSync(screen::finish); }
            if (source != null) app.getContentResolver().delete(source,null,null);
            models.delete("small");
        }
    }
    private static List<android.view.View> allViews(android.view.View view) { List<android.view.View> result = new ArrayList<>(); result.add(view); if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) result.addAll(allViews(group.getChildAt(i))); return result; }
    private static void evidence(Context app, String name, byte[] bytes) throws IOException {
        ContentValues values = new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME,name); values.put(MediaStore.Downloads.MIME_TYPE,name.endsWith("png") ? "image/png" : "application/json"); values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/ToneKeep-verification"); values.put(MediaStore.Downloads.IS_PENDING,1);
        var uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values); if (uri == null) throw new IOException("Evidence URI missing");
        try (OutputStream out = app.getContentResolver().openOutputStream(uri)) { if (out == null) throw new IOException("Evidence output missing"); out.write(bytes); }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING,0); app.getContentResolver().update(uri,values,null,null);
    }
    private static void copy(Context tests, String name, File to) throws IOException { try (InputStream in = tests.getAssets().open(name); OutputStream out = new FileOutputStream(to)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) out.write(b,0,n); } }
}
