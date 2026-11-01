package jp.tonekeep.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

/** Explicit opt-in real-device test. The user's audio is never copied into the repository. */
@RunWith(AndroidJUnit4.class)
public class DeviceLyricsFileTest {
    private static final String TAG = "ToneKeepFileTest";
    @Test public void recognizeRequestedLocalFile() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("sourcePath");
        org.junit.Assume.assumeTrue("Provide sourcePath explicitly for a device-file test",path != null);
        if (!path.startsWith("/storage/emulated/0/Download/") || path.contains("\n") || path.contains("\r")) throw new IllegalArgumentException("Expected a local Download file");
        var instrumentation = InstrumentationRegistry.getInstrumentation(); Context app = instrumentation.getTargetContext(), tests = instrumentation.getContext();
        // UiAutomation executes argument vectors without shell quoting. A shell-owned staging path
        // avoids ambiguities in user filenames, and the host checks the original before/after.
        String readable = InstrumentationRegistry.getArguments().getString("stagedPath",path);
        if (!readable.matches("/data/local/tmp/tonekeep-lyrics-[a-z0-9]+/input\\.mp3")) throw new IllegalArgumentException("Expected an explicitly staged test input");
        String before = shell("sha256sum " + readable).trim().split("\\s+")[0]; assertTrue(before.matches("[0-9a-f]{64}"));
        ModelManager models = new ModelManager(app); File model = models.file("small"); assertTrue(model.getParentFile().isDirectory() || model.getParentFile().mkdirs());
        try (InputStream in = tests.getAssets().open("whisper-smoke.bin"); OutputStream out = new FileOutputStream(model)) { transfer(in,out); }
        assertEquals(ModelManager.spec("small").bytes(),model.length());
        Uri copy = null; Activity activity = null; LyricsStore.Task task = null; LyricsStore store = new LyricsStore(app);
        long started = 0; int peakThermal = 0;
        try {
            ContentValues values = new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME,"ToneKeep-device-lyrics-test.mp3"); values.put(MediaStore.Downloads.MIME_TYPE,"audio/mpeg"); values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/ToneKeep-tests"); values.put(MediaStore.Downloads.IS_PENDING,1);
            copy = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values); assertNotNull(copy);
            // executeShellCommand reads as shell; only the copy is owned/written by the test app.
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand("cat " + readable)); OutputStream out = app.getContentResolver().openOutputStream(copy)) { assertNotNull(out); transfer(in,out); }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING,0); app.getContentResolver().update(copy,values,null,null);
            DownloadStore downloads = new DownloadStore(app);
            var entry = downloads.enqueue(new MediaInfo.Track("device-file","SAYONARA MAYBE · 実機解析","","https://example.invalid/local-device-test","local",0,false,List.of()),"","local");
            downloads.update(entry.id(),"complete","local test",100,copy.toString(),"SAYONARA MAYBE.mp3");
            task = store.enqueue(entry.id(),copy.toString(),"small",true,"lyrics");
            activity = instrumentation.startActivitySync(new Intent(app,MainActivity.class).putExtra("library",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            started = SystemClock.elapsedRealtime(); instrumentation.runOnMainSync(() -> LyricsService.start(app));
            long deadline = started + 30L * 60 * 1000; String last = "";
            while (store.find(task.id()).active() && SystemClock.elapsedRealtime() < deadline) {
                LyricsStore.Task current = store.find(task.id()); String state = current.status() + " " + current.progress() + " " + current.message();
                if (!state.equals(last)) { Log.i(TAG,"elapsedMs="+(SystemClock.elapsedRealtime()-started)+" "+state); last = state; }
                peakThermal = Math.max(peakThermal,app.getSystemService(PowerManager.class).getCurrentThermalStatus()); Thread.sleep(1000);
            }
            long elapsed = SystemClock.elapsedRealtime() - started;
            assertEquals(store.find(task.id()).message(),"complete",store.find(task.id()).status());
            Lyrics result = store.load(entry.id(),copy.toString()); assertNotNull(result); assertEquals(before,result.sourceHash()); assertEquals(result,Lyrics.parse(result.json()));
            assertNull(store.load("different-source",copy.toString()));
            int words = result.lines().stream().mapToInt(l -> l.words().size()).sum(); long wordLines = result.lines().stream().filter(l -> !l.words().isEmpty()).count();
            JSONObject summary = new JSONObject().put("sourceName","SAYONARA MAYBE.mp3").put("sourceSha256",before).put("model","small").put("language","ja").put("durationMs",result.durationMs()).put("analysisElapsedMs",elapsed).put("lines",result.lines().size()).put("wordAlignedLines",wordLines).put("lineFallbackLines",result.lines().size()-wordLines).put("words",words).put("maxThermalStatus",peakThermal).put("sourceUnchanged",before.equals(shell("sha256sum "+readable).trim().split("\\s+")[0]));
            assertTrue(summary.getBoolean("sourceUnchanged"));
            evidence(app,"SAYONARA-MAYBE-lyrics.json",result.json()); evidence(app,"SAYONARA-MAYBE.lrc",result.export("lrc")); evidence(app,"SAYONARA-MAYBE.enhanced.lrc",result.export("elrc")); evidence(app,"SAYONARA-MAYBE-lyrics.txt",result.export("txt")); evidence(app,"SAYONARA-MAYBE-test-summary.json",summary.toString(2));
            Log.i(TAG,"RESULT "+summary);
        } finally {
            if (task != null && store.find(task.id()).active()) { store.update(task.id(),"cancelled","Device test finished",-1); instrumentation.runOnMainSync(() -> LyricsService.start(app)); }
            if (activity != null) { Activity screen = activity; instrumentation.runOnMainSync(screen::finish); }
            if (copy != null) app.getContentResolver().delete(copy,null,null);
            models.delete("small");
            assertEquals("Staged input changed",before,shell("sha256sum "+readable).trim().split("\\s+")[0]);
        }
    }
    private static void transfer(InputStream in, OutputStream out) throws IOException { byte[] b = new byte[65536]; int n; while ((n=in.read(b)) != -1) out.write(b,0,n); }
    private static String shell(String command) throws IOException { try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command)); ByteArrayOutputStream out = new ByteArrayOutputStream()) { transfer(in,out); return out.toString(StandardCharsets.UTF_8.name()); } }
    private static void evidence(Context app, String name, String text) throws IOException {
        ContentValues values = new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME,name); values.put(MediaStore.Downloads.MIME_TYPE,name.endsWith("json") ? "application/json" : "text/plain"); values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/ToneKeep-verification"); values.put(MediaStore.Downloads.IS_PENDING,1);
        Uri uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values); assertNotNull(uri);
        try (OutputStream out = app.getContentResolver().openOutputStream(uri)) { assertNotNull(out); out.write(text.getBytes(StandardCharsets.UTF_8)); }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING,0); app.getContentResolver().update(uri,values,null,null);
    }
}
