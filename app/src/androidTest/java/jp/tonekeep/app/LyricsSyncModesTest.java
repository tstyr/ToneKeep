package jp.tonekeep.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Real dialog/gesture tests using owned speech and a genuine cached result of that speech. */
@RunWith(AndroidJUnit4.class)
public class LyricsSyncModesTest {
    @Test public void lineTraceDraftResumesAndSavesThePlaybackClock() throws Exception {try(Fixture f=new Fixture("青い空\n小さな光")){
        f.chooseTrace();f.awaitTraceReady();f.seek(1000);f.stroke(0);assertEquals(1,f.session().recorded());f.click("閉じる・下書き保存");f.chooseTrace();f.awaitTraceReady();assertEquals(1,f.session().recorded());f.seek(3000);f.stroke(1);f.click("同期を保存");Lyrics result=f.awaitSaved("manual-trace");assertEquals(2,result.lines().size());assertEquals(1000,result.lines().get(0).startMs(),350);assertEquals(3000,result.lines().get(1).startMs(),350);assertEquals(f.reference.plainText(),result.plainText());assertTrue(result.fullyTimed());
    }}
    @Test public void wordModeSavesEveryTouchedWordWithoutInventingGaps() throws Exception {try(Fixture f=new Fixture("青い空\n小さな光")){
        f.chooseTrace();f.awaitTraceReady();Object pane=f.pane();LinearLayout root=(LinearLayout)field(pane,"root");Spinner mode=(Spinner)((LinearLayout)root.getChildAt(2)).getChildAt(0);f.i.runOnMainSync(()->mode.setSelection(1));f.awaitTraceReady(true);LyricsTraceSession session=f.session();assertTrue("Word-mode session must be ready",session.words);assertTrue(session.units.size()>1);assertTrue(session.units.size()<=8);
        for(int index=0;index<session.units.size();index++){f.seek(300+index*600);f.stroke(index);}
        f.click("同期を保存");Lyrics result=f.awaitSaved("manual-trace");assertEquals(session.units.size(),result.lines().stream().mapToInt(l->l.words().size()).sum());assertEquals(f.reference.plainText(),result.plainText());assertTrue(result.export("elrc").contains("<"));
    }}
    @Test public void aiChoicePreservesTheProvidedTextAndUsesTheGenuineSpeechCache() throws Exception {try(Fixture f=new Fixture("青い空に、小さな光。\n今日も一緒に、歩いて行こう。")){
        Lyrics recognition;try(InputStream in=f.tests.getAssets().open("original-speech-recognition.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){copy(in,out);recognition=Lyrics.parse(out.toString(StandardCharsets.UTF_8.name()));}assertEquals(f.reference.sourceHash(),recognition.sourceHash());
        f.store.cacheRecognition(new LyricsStore.Task("cache",f.entry.id(),f.entry.contentUri(),"small",true,"align","complete","",100),recognition);
        f.choose();f.click("AIで秒数を推定（端末内・要確認）");f.click("同期候補を作る");long deadline=SystemClock.elapsedRealtime()+20000;LyricsStore.Task task=null;
        while(SystemClock.elapsedRealtime()<deadline){task=f.store.forAudio(f.entry.id(),f.entry.contentUri());if(task!=null&&task.kind().equals("align")&&!task.active())break;Thread.sleep(100);}assertNotNull(task);assertEquals(task.message(),"complete",task.status());Lyrics result=f.store.load(f.entry.id(),f.entry.contentUri());assertEquals(f.reference.plainText(),result.plainText());assertEquals("local-file",result.origin().provider());assertFalse(result.lines().isEmpty());assertTrue(result.lines().stream().anyMatch(l->l.alignment().equals("ai-reference-match")));
    }}
    private static final class Fixture implements AutoCloseable {
        final Instrumentation i=InstrumentationRegistry.getInstrumentation();final Context app=i.getTargetContext(),tests=i.getContext();final Uri uri;final DownloadStore.Entry entry;final LyricsStore store;final Lyrics reference;final Activity activity;final AudioPlayer player;final Object sources,sync;
        Fixture(String text) throws Exception {
            ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,"ToneKeep-sync-modes.wav");v.put(MediaStore.Downloads.MIME_TYPE,"audio/wav");v.put(MediaStore.Downloads.RELATIVE_PATH,"Download/ToneKeep-tests");v.put(MediaStore.Downloads.IS_PENDING,1);uri=app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);assertNotNull(uri);try(InputStream in=tests.getAssets().open("lyrics-ja.wav");OutputStream out=app.getContentResolver().openOutputStream(uri)){assertNotNull(out);copy(in,out);}v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);app.getContentResolver().update(uri,v,null,null);
            DownloadStore downloads=new DownloadStore(app);var e=downloads.enqueue(new MediaInfo.Track("sync-mode-test","自作・同期方式テスト","","https://example.invalid/sync-modes","own",6,false,List.of()),"","test");downloads.update(e.id(),"complete","fixture",100,uri.toString(),"sync-modes.wav");entry=downloads.find(e.id());SongDetails song=SongDetails.read(app,entry);reference=LrcParser.plain(text,5997,song.hash(),new Lyrics.Origin("local-file","自作歌詞",""));store=new LyricsStore(app);store.saveExternal(entry.id(),entry.contentUri(),reference);
            activity=i.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));player=(AudioPlayer)field(activity,"player");Object lyricsUi=field(activity,"lyricsUi");sources=field(lyricsUi,"sources");sync=field(sources,"sync");
        }
        void choose(){i.runOnMainSync(()->{try{var method=LyricsSourcesUi.class.getDeclaredMethod("chooseSync",DownloadStore.Entry.class);method.setAccessible(true);method.invoke(sources,entry);}catch(Exception e){throw new AssertionError(e);}});}
        void chooseTrace() throws Exception {choose();click("指でなぞって保存（行・単語）");}
        Object pane() throws Exception{return field(sync,"pane");}
        LyricsTraceSession session() throws Exception{return (LyricsTraceSession)field(pane(),"session");}
        void awaitTraceReady() throws Exception {awaitTraceReady(false);}
        void awaitTraceReady(boolean words) throws Exception {
            long end=SystemClock.elapsedRealtime()+10000;AtomicBoolean ready=new AtomicBoolean();
            while(SystemClock.elapsedRealtime()<end){
                i.runOnMainSync(()->{try{
                    Object current=pane();if(current==null)return;
                    LyricsTraceView view=(LyricsTraceView)field(current,"view");
                    LinearLayout rows=(LinearLayout)view.getChildAt(0);
                    ready.set(session().words==words&&!((Boolean)field(current,"loading"))&&!player.isPreparing()
                            &&view.isLaidOut()&&rows.getChildCount()>0&&((TextView)rows.getChildAt(0)).getLayout()!=null);
                }catch(Exception e){throw new AssertionError(e);}});
                if(ready.get())return;Thread.sleep(60);
            }
            fail("Trace editor not ready for "+(words?"word":"line")+" mode");
        }
        void seek(int ms) throws Exception {i.runOnMainSync(()->{player.seekTo(ms);if(!player.isPlaying())player.togglePlayback();});long end=SystemClock.elapsedRealtime()+3000;while(Math.abs(player.position()-ms)>250&&SystemClock.elapsedRealtime()<end)Thread.sleep(40);assertEquals(ms,player.position(),250);}
        void stroke(int unit) throws Exception {LyricsTraceView view=(LyricsTraceView)field(pane(),"view");LyricsTraceSession s=session();LyricsTraceSession.Unit u=s.units.get(unit);LinearLayout rows=(LinearLayout)view.getChildAt(0);TextView row=(TextView)rows.getChildAt(u.row());float[] point=new float[2];i.runOnMainSync(()->{android.text.Layout layout=row.getLayout();int line=layout.getLineForOffset(u.from());point[0]=row.getLeft()+row.getTotalPaddingLeft()+layout.getPrimaryHorizontal(u.from())+Math.max(1,(layout.getPrimaryHorizontal(Math.min(u.to(),u.from()+1))-layout.getPrimaryHorizontal(u.from()))/2);point[1]=row.getTop()+row.getTotalPaddingTop()+(layout.getLineTop(line)+layout.getLineBottom(line))/2f-view.getScrollY();});long time=SystemClock.uptimeMillis();i.runOnMainSync(()->{MotionEvent event=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,point[0],point[1],0);view.dispatchTouchEvent(event);event.recycle();assertEquals("Gesture hit: "+point[0]+","+point[1]+" player="+player.isPlaying()+" position="+player.position(),unit,s.active());});long started=s.start(unit),deadline=SystemClock.elapsedRealtime()+4000;while(player.position()<started+100&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(40);i.runOnMainSync(()->{MotionEvent event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,point[0],point[1],0);view.dispatchTouchEvent(event);event.recycle();assertTrue("Gesture end unit="+unit+" start="+s.start(unit)+" end="+s.end(unit)+" active="+s.active()+" player="+player.isPlaying()+" position="+player.position(),s.end(unit)>s.start(unit));});}
        void click(String text) throws Exception {long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo root=i.getUiAutomation().getRootInActiveWindow();if(root!=null&&root.getPackageName()!=null&&app.getPackageName().contentEquals(root.getPackageName()))for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(node.getText()!=null&&text.contentEquals(node.getText())&&node.isEnabled()){assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(120);return;}Thread.sleep(80);}fail("Button not found: "+text);}
        Lyrics awaitSaved(String model) throws Exception {long end=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<end){Lyrics result=store.load(entry.id(),entry.contentUri());if(result.model().equals(model))return result;Thread.sleep(80);}throw new AssertionError("Synchronized result was not saved");}
        @Override public void close(){i.runOnMainSync(()->{PlaybackCore.get(app).stop();activity.finish();});app.getContentResolver().delete(uri,null,null);var db=new DownloadStore(app).database();for(String table:List.of("lyrics_documents","lyrics_tasks","track_metadata","lyrics_recognition_cache"))db.delete(table,"audio_id=?",new String[]{entry.id()});db.delete("jobs","id=?",new String[]{entry.id()});}
    }
    private static Object field(Object target,String name) throws Exception {var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void copy(InputStream in,OutputStream out) throws IOException {byte[] bytes=new byte[16384];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}
}
