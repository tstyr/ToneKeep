package jp.tonekeep.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.List;
import static org.junit.Assert.*;

/** Exercises real playback positions and dialog actions using only the original speech fixture. */
@RunWith(AndroidJUnit4.class)
public class LocalLyricsSyncUiTest {
    @Test public void recordTwoLineStartsAndSaveWithoutNetwork() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context app=instrumentation.getTargetContext(),tests=instrumentation.getContext();Uri source=null;Activity activity=null;String id=null;
        try {
            ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,"ToneKeep-local-line-sync.wav");v.put(MediaStore.Downloads.MIME_TYPE,"audio/wav");v.put(MediaStore.Downloads.RELATIVE_PATH,"Download/ToneKeep-tests");v.put(MediaStore.Downloads.IS_PENDING,1);source=app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);assertNotNull(source);
            try(InputStream in=tests.getAssets().open("lyrics-ja.wav");OutputStream out=app.getContentResolver().openOutputStream(source)){assertNotNull(out);byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);app.getContentResolver().update(source,v,null,null);
            DownloadStore downloads=new DownloadStore(app);var entry=downloads.enqueue(new MediaInfo.Track("local-line-test","自作・手動行同期テスト","","https://example.invalid/manual-test","test",6,false,List.of()),"","fixture");id=entry.id();downloads.update(id,"complete","test",100,source.toString(),"local-sync.wav");entry=downloads.find(id);
            LyricsStore lyrics=new LyricsStore(app);lyrics.saveExternal(id,source.toString(),LrcParser.plain("青い空\n小さな光",5997,"own-fixture",new Lyrics.Origin("local-file","自作歌詞","")));
            activity=instrumentation.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity screen=activity;DownloadStore.Entry selected=entry;
            instrumentation.runOnMainSync(()->{try{var open=MainActivity.class.getDeclaredMethod("openFile",DownloadStore.Entry.class);open.setAccessible(true);open.invoke(screen,selected);}catch(Exception e){throw new AssertionError(e);}});
            var field=MainActivity.class.getDeclaredField("player");field.setAccessible(true);AudioPlayer player=(AudioPlayer)field.get(activity);long ready=SystemClock.elapsedRealtime()+15000;while(player.isPreparing()&&SystemClock.elapsedRealtime()<ready)Thread.sleep(50);
            instrumentation.runOnMainSync(()->player.pause());
            instrumentation.runOnMainSync(()->{try{var f=MainActivity.class.getDeclaredField("lyricsUi");f.setAccessible(true);Object ui=f.get(screen);var s=LyricsUi.class.getDeclaredField("sources");s.setAccessible(true);Object sources=s.get(ui);var manual=LyricsSourcesUi.class.getDeclaredMethod("manual",DownloadStore.Entry.class);manual.setAccessible(true);manual.invoke(sources,selected);}catch(Exception e){throw new AssertionError(e);}});
            awaitNode(instrumentation,"現在位置を記録 → 次の行");
            seek(instrumentation,player,1000);click(instrumentation,"現在位置を記録 → 次の行");seek(instrumentation,player,3000);click(instrumentation,"現在位置を記録 → 次の行");click(instrumentation,"保存");
            long deadline=SystemClock.elapsedRealtime()+10000;Lyrics saved;
            do{saved=lyrics.load(id,source.toString());if(saved.lines().size()==2)break;Thread.sleep(100);}while(SystemClock.elapsedRealtime()<deadline);
            assertEquals(2,saved.lines().size());assertEquals(1000,saved.lines().get(0).startMs(),250);assertEquals(3000,saved.lines().get(1).startMs(),250);assertEquals("manual-line",saved.lines().get(0).alignment());assertEquals("青い空",saved.lines().get(0).text());assertFalse(saved.export("lrc").isBlank());
        } finally {
            if(activity!=null){Activity screen=activity;instrumentation.runOnMainSync(()->{PlaybackCore.get(app).stop();screen.finish();});}if(source!=null)app.getContentResolver().delete(source,null,null);
            if(id!=null){var db=new DownloadStore(app).database();for(String table:List.of("lyrics_documents","lyrics_tasks","track_metadata"))db.delete(table,"audio_id=?",new String[]{id});db.delete("jobs","id=?",new String[]{id});}
        }
    }
    private static void seek(Instrumentation i,AudioPlayer player,int ms) throws Exception {i.runOnMainSync(()->player.seekTo(ms));long end=SystemClock.elapsedRealtime()+5000;while(Math.abs(player.position()-ms)>200&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);assertEquals(ms,player.position(),200);}
    private static AccessibilityNodeInfo awaitNode(Instrumentation i,String text) throws Exception {long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo root=i.getUiAutomation().getRootInActiveWindow();if(root!=null&&root.getPackageName()!=null&&i.getTargetContext().getPackageName().contentEquals(root.getPackageName()))for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(text))if(n.getText()!=null&&text.contentEquals(n.getText()))return n;Thread.sleep(100);}throw new AssertionError("UI action not found: "+text);}
    private static void click(Instrumentation i,String text) throws Exception {assertTrue(awaitNode(i,text).performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(150);}
}
