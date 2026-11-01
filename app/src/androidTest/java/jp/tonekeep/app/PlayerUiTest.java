package jp.tonekeep.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real APK playback, transport, lyric seeks and recreation using owned speech/artwork only. */
@RunWith(AndroidJUnit4.class)
public class PlayerUiTest {
    @Test public void realMediaSessionExposesNotificationMetadataAndControls() throws Exception {try(Fixture f=new Fixture()){
        f.open();android.app.Notification notification=null;long deadline=SystemClock.elapsedRealtime()+6000;
        while(SystemClock.elapsedRealtime()<deadline){for(var n:f.app.getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==PlaybackCore.get(f.app).notificationId)notification=n.getNotification();if(notification!=null&&notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)!=null)break;Thread.sleep(40);}
        assertNotNull("Media playback notification",notification);android.media.session.MediaSession.Token token=notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);assertNotNull(token);
        var controller=new android.media.session.MediaController(f.app,token);assertEquals(f.entries.get(1).id(),controller.getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID));assertEquals("ToneKeep test",controller.getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_ARTIST));assertTrue(controller.getMetadata().getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)>5000);
        controller.getTransportControls().pause();deadline=SystemClock.elapsedRealtime()+3000;while(f.player().isPlaying()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(30);assertFalse(f.player().isPlaying());controller.getTransportControls().seekTo(1500);f.awaitPosition(1500);
        f.i.runOnMainSync(f.activity::finish);controller.getTransportControls().play();deadline=SystemClock.elapsedRealtime()+5000;while(!PlaybackCore.get(f.app).isPlaying()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(30);assertTrue("Playback from notification after Activity finish",PlaybackCore.get(f.app).isPlaying());
    }}
    @Test public void miniPlayerFullPlayerAndLyricsShareOnePlaybackClock() throws Exception {try(Fixture f=new Fixture()){
        f.open();f.i.runOnMainSync(()->{f.player().pause();f.player().expand();});
        f.clickDescription("シャッフルを有効にする");assertTrue(f.player().shuffleEnabled());f.clickDescription("シャッフルを解除");assertFalse(f.player().shuffleEnabled());
        f.clickDescription("リピートなし。タップして全曲リピート");assertTrue(f.player().repeatsAll());
        f.clickDescription("全曲リピート。タップして1曲リピート");assertEquals(2,f.player().repeatMode());
        f.clickDescription("1曲リピート。タップして解除");assertEquals(0,f.player().repeatMode());
        f.clickDescription("歌詞を拡大");f.clickText("小さな光");f.awaitPosition(3000);f.clickDescription("歌詞の拡大表示を閉じる");
        f.clickDescription("次の曲");f.awaitReady();f.i.runOnMainSync(()->f.player().pause());assertEquals(f.entries.get(0).id(),((DownloadStore.Entry)field(f.activity,"playingEntry")).id());assertTrue(f.player().isExpanded());
        f.clickDescription("再生画面を閉じる");assertFalse(f.player().isExpanded());assertEquals(View.VISIBLE,f.player().view.getVisibility());
        f.i.runOnMainSync(()->f.player().view.getChildAt(0).performClick());assertTrue(f.player().isExpanded());f.clickDescription("再生");long end=SystemClock.elapsedRealtime()+3000;while(!f.player().isPlaying()&&SystemClock.elapsedRealtime()<end)Thread.sleep(30);assertTrue(f.player().isPlaying());f.clickDescription("一時停止");assertFalse(f.player().isPlaying());
    }}
    @Test public void recreationKeepsTheFilePositionAndExpandedPlayerPaused() throws Exception {try(Fixture f=new Fixture()){
        f.open();f.i.runOnMainSync(()->{f.player().pause();f.player().seekTo(2000);f.player().expand();});f.awaitPosition(2000);
        Activity before=f.activity;f.i.runOnMainSync(before::recreate);long deadline=SystemClock.elapsedRealtime()+10000;
        AtomicReference<Activity> recreated=new AtomicReference<>();while(SystemClock.elapsedRealtime()<deadline){f.i.runOnMainSync(()->{for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a instanceof MainActivity&&a!=before)recreated.set(a);});if(recreated.get()!=null)break;Thread.sleep(60);}
        assertNotNull("Recreated Activity",recreated.get());f.activity=recreated.get();f.awaitReady();f.awaitPosition(2000);assertTrue(f.player().isExpanded());assertFalse(f.player().isPlaying());assertEquals(f.entries.get(1).id(),((DownloadStore.Entry)field(f.activity,"playingEntry")).id());
    }}
    private static final class Fixture implements AutoCloseable {
        final Instrumentation i=InstrumentationRegistry.getInstrumentation();final Context app=i.getTargetContext();final List<Uri> uris=new ArrayList<>();final List<DownloadStore.Entry> entries=new ArrayList<>();final File artwork=new File(app.getCacheDir(),"owned-player-art.png");Activity activity;
        Fixture() throws Exception {
            Bitmap bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xff425b92);Paint paint=new Paint();paint.setColor(0xff94b5dc);canvas.drawCircle(64,64,32,paint);try(OutputStream out=new FileOutputStream(artwork)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
            DownloadStore downloads=new DownloadStore(app);
            for(int n=0;n<2;n++){
                ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,"ToneKeep-player-ui-"+n+".wav");v.put(MediaStore.Downloads.MIME_TYPE,"audio/wav");v.put(MediaStore.Downloads.RELATIVE_PATH,"Download/ToneKeep-tests");v.put(MediaStore.Downloads.IS_PENDING,1);Uri uri=app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);assertNotNull(uri);uris.add(uri);
                try(InputStream in=i.getContext().getAssets().open("lyrics-ja.wav");OutputStream out=app.getContentResolver().openOutputStream(uri)){assertNotNull(out);byte[] b=new byte[8192];int count;while((count=in.read(b))!=-1)out.write(b,0,count);}v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);app.getContentResolver().update(uri,v,null,null);
                var entry=downloads.enqueue(new MediaInfo.Track("player-ui-"+n,"自作音声 "+n,"","https://example.invalid/player-ui/"+n,"own",6,false,List.of()),"","test");downloads.update(entry.id(),"complete","fixture",100,uri.toString(),"player-ui.wav");entry=downloads.find(entry.id());entries.add(entry);
                new TrackMetadata.Store(app).save(new TrackMetadata(entry.id(),uri.toString(),entry.title(),"ToneKeep test","青い空",5997,"self","",artwork.getAbsolutePath(),"自作画像","fixture"));
                new LyricsStore(app).saveExternal(entry.id(),uri.toString(),LrcParser.parse("[00:01.00]青い空\n[00:03.00]小さな光",5997,"owned",new Lyrics.Origin("local-file","自作歌詞","")));
            }
            activity=i.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("library",true));
        }
        AudioPlayer player(){try{return (AudioPlayer)field(activity,"player");}catch(Exception e){throw new AssertionError(e);}}
        void open() throws Exception {i.runOnMainSync(()->{try{var method=MainActivity.class.getDeclaredMethod("openFile",DownloadStore.Entry.class);method.setAccessible(true);method.invoke(activity,entries.get(1));}catch(Exception e){throw new AssertionError(e);}});awaitReady();}
        void awaitReady() throws Exception {long end=SystemClock.elapsedRealtime()+10000;while(player().isPreparing()&&SystemClock.elapsedRealtime()<end)Thread.sleep(40);assertFalse(player().isPreparing());assertEquals(View.VISIBLE,player().view.getVisibility());}
        void awaitPosition(int ms) throws Exception {long end=SystemClock.elapsedRealtime()+4000;while(Math.abs(player().position()-ms)>200&&SystemClock.elapsedRealtime()<end)Thread.sleep(40);assertEquals(ms,player().position(),200);}
        void clickDescription(String label) throws Exception {click(label,true);}
        void clickText(String label) throws Exception {click(label,false);}
        void click(String label,boolean description) throws Exception {
            long deadline=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<deadline){AccessibilityNodeInfo root=i.getUiAutomation().getRootInActiveWindow();if(root!=null&&app.getPackageName().contentEquals(root.getPackageName())){AccessibilityNodeInfo node=find(root,label,description);if(node!=null&&node.isEnabled()&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(100);return;}}Thread.sleep(60);}fail("Missing player action: "+label);
        }
        @Override public void close(){i.runOnMainSync(()->{PlaybackCore.get(app).stop();activity.finish();});for(Uri uri:uris)app.getContentResolver().delete(uri,null,null);var db=new DownloadStore(app).database();for(var entry:entries){for(String table:List.of("lyrics_documents","lyrics_tasks","track_metadata","lyrics_recognition_cache"))db.delete(table,"audio_id=?",new String[]{entry.id()});db.delete("jobs","id=?",new String[]{entry.id()});}artwork.delete();}
    }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String label,boolean description){CharSequence value=description?node.getContentDescription():node.getText();if(value!=null&&(label.contentEquals(value)||!description&&value.toString().startsWith(label)))return node;for(int n=0;n<node.getChildCount();n++){AccessibilityNodeInfo child=node.getChild(n);if(child!=null){AccessibilityNodeInfo found=find(child,label,description);if(found!=null)return found;}}return null;}
    private static Object field(Object target,String name) throws Exception {var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
}
