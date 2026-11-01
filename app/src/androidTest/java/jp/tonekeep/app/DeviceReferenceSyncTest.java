package jp.tonekeep.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicit opt-in: creates a new revision for the user's retained demo, never edits its audio. */
@RunWith(AndroidJUnit4.class)
public class DeviceReferenceSyncTest {
    @Test public void retainedSongUsesCachedRecognitionAndKeepsTheFetchedText() throws Exception {
        org.junit.Assume.assumeTrue("Explicit opt-in required", "true".equals(InstrumentationRegistry.getArguments().getString("referenceSong")));
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context app=instrumentation.getTargetContext();
        DownloadStore.Entry entry=new DownloadStore(app).all().stream().filter(e->e.title().equals("SAYONARA MAYBE")&&e.status().equals("complete")).findFirst().orElseThrow();
        LyricsStore store=new LyricsStore(app);Lyrics reference=store.load(entry.id(),entry.contentUri());assertNotNull(reference);assertEquals("lrclib",reference.origin().provider());
        SongDetails before=SongDetails.read(app,entry);assertEquals("cb8720a671a60a0dc6027bbf8213c35057907575735ee79e8baf881fbfd59fe6",before.hash());
        Lyrics recognition=store.recognition(entry.id(),entry.contentUri(),"small",before.hash());assertNotNull("Existing real-song ASR cache required; no new inference in this test",recognition);
        Activity activity=instrumentation.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("library",true));
        try {
            LyricsStore.Task task=store.enqueueAlignment(entry.id(),entry.contentUri(),"small",reference);instrumentation.runOnMainSync(()->LyricsService.start(app));
            long deadline=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<deadline&&store.find(task.id()).active())Thread.sleep(100);
            task=store.find(task.id());assertEquals(task.message(),"complete",task.status());Lyrics result=store.load(entry.id(),entry.contentUri());
            assertEquals(reference.plainText(),result.plainText());assertEquals(reference.origin().url(),result.origin().url());assertEquals(before.hash(),SongDetails.read(app,entry).hash());
            assertTrue(result.lines().stream().anyMatch(l->l.startMs()>=0));assertEquals(ReferenceLyricsAlignment.rows(reference),ReferenceLyricsAlignment.rows(result));
            Bundle report=new Bundle();report.putString("stream","reference-sync: groups="+result.lines().size()+", timed="+result.lines().stream().filter(l->l.startMs()>=0).count()+", unmatched="+result.unmatchedCount()+", textPreserved=true, audioUnchanged=true\n");instrumentation.sendStatus(0,report);
        } finally {instrumentation.runOnMainSync(activity::finish);}
    }
}
