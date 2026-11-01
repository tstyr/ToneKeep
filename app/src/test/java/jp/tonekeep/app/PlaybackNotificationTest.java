package jp.tonekeep.app;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteOpenHelper;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import org.robolectric.shadows.util.DataSource;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29,shadows=PlaybackNotificationTest.SessionShadow.class) @SQLiteMode(SQLiteMode.Mode.LEGACY)
public class PlaybackNotificationTest {
    // Robolectric's built-in MediaSession shadow has no binder/session implementation.
    // Capture that framework boundary; all playback, notification and Activity code is real app code.
    @Implements(MediaSession.class) public static class SessionShadow {
        static MediaSession.Callback callback;static MediaMetadata metadata;static PlaybackState state;static boolean active;
        @Implementation protected void __constructor__(Context c,String tag){}
        @Implementation protected void setCallback(MediaSession.Callback c,Handler h){callback=c;}
        @Implementation protected void setSessionActivity(PendingIntent i){}
        @Implementation protected void setActive(boolean value){active=value;}
        @Implementation protected void setMetadata(MediaMetadata value){metadata=value;}
        @Implementation protected void setPlaybackState(PlaybackState value){state=value;}
        @Implementation protected MediaSession.Token getSessionToken(){return null;}
        @Implementation protected void release(){active=false;}
    }
    private Context context;private PlaybackCore core;private ServiceController<PlaybackService> service;private DownloadStore store;
    private DownloadStore.Entry first,second;private final List<ShadowMediaPlayer> media=new ArrayList<>();
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();reset("instance",PlaybackCore.class);resetDb();
        ShadowMediaPlayer.setCreateListener((p,shadow)->{shadow.setSeekDelay(0);media.add(shadow);});
        store=new DownloadStore(context);first=song("original","同名の曲");second=song("key-two","同名の曲");
        new TrackMetadata.Store(context).save(new TrackMetadata(first.id(),first.contentUri(),"正式な曲名","自作アーティスト","自作アルバム",60000,"self","","","","ready"));
        core=PlaybackCore.get(context);core.select(first);
        service=Robolectric.buildService(PlaybackService.class).create();service.get().onStartCommand(new Intent(context,PlaybackService.class),0,1);idle();
    }
    @After public void close() throws Exception {if(core!=null)core.stop();if(service!=null)service.destroy();reset("instance",PlaybackCore.class);resetDb();}
    private DownloadStore.Entry song(String id,String title){
        DownloadStore.Entry e=store.enqueue(new MediaInfo.Track(id,title,"","https://example.invalid/"+id,"self",60,false,List.of()),"","test");
        String uri="content://self/"+id;store.update(e.id(),"complete","saved",100,uri,id+".wav");
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context,Uri.parse(uri)),new ShadowMediaPlayer.MediaInfo(60000,1));return store.find(e.id());
    }
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));}
    private Notification notification(){return Shadows.shadowOf(context.getSystemService(NotificationManager.class)).getNotification(core.notificationId);}
    @Test public void mediaCardUsesActualTitleArtistDurationAndPlaybackActions(){
        assertTrue(core.isPlaying());Notification n=notification();assertNotNull(n);assertEquals("正式な曲名",n.extras.getCharSequence(Notification.EXTRA_TITLE));assertEquals("自作アーティスト",n.extras.getCharSequence(Notification.EXTRA_TEXT));assertEquals("android.app.Notification$MediaStyle",n.extras.getString(Notification.EXTRA_TEMPLATE));
        assertEquals(first.id(),SessionShadow.metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));assertEquals(60000,SessionShadow.metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));assertEquals(PlaybackState.STATE_PLAYING,SessionShadow.state.getState());assertTrue((SessionShadow.state.getActions()&PlaybackState.ACTION_SEEK_TO)!=0);assertTrue(SessionShadow.active);
    }
    @Test public void sessionPauseSeekPlayAndHeadphoneDisconnectShareOneClock(){
        SessionShadow.callback.onPause();assertFalse(core.isPlaying());assertEquals(PlaybackState.STATE_PAUSED,SessionShadow.state.getState());
        SessionShadow.callback.onSeekTo(12500);idle();assertEquals(12500,core.position());SessionShadow.callback.onPlay();service.get().onStartCommand(new Intent(),0,2);assertTrue(core.isPlaying());
        context.sendBroadcast(new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));idle();assertFalse(core.isPlaying());
    }
    @Test public void queueNavigationUsesSavedIdsEvenWhenTitlesMatch(){
        SessionShadow.callback.onSkipToPrevious();idle();assertEquals(second.id(),core.entry.id());assertEquals(second.id(),SessionShadow.metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
        SessionShadow.callback.onSkipToNext();idle();assertEquals(first.id(),core.entry.id());
    }
    @Test public void activityRecreationAndClosingDoNotStopNormalMusic() throws Exception {
        var activity=Robolectric.buildActivity(MainActivity.class,new Intent(context,MainActivity.class).putExtra("library",true).putExtra("playback",true)).setup();
        assertTrue(((AudioPlayer)field(activity.get(),"player")).isExpanded());
        SessionShadow.callback.onSeekTo(10000);idle();activity.recreate();assertTrue(((AudioPlayer)field(activity.get(),"player")).isExpanded());assertTrue(core.isPlaying());assertTrue(core.position()>=10000);
        DownloadStore.Entry attached=(DownloadStore.Entry)field(activity.get(),"playingEntry");assertEquals(first.id(),attached.id());activity.close();assertTrue(core.isPlaying());assertNotNull(notification());
    }
    @Test public void completionRepeatAndExplicitStopReleaseResources(){
        core.options(false,2);media.get(media.size()-1).invokeCompletionListener();service.get().onStartCommand(new Intent(),0,2);idle();assertTrue(core.isPlaying());assertEquals(first.id(),core.entry.id());
        SessionShadow.callback.onStop();idle();assertFalse(core.active);assertFalse(core.isPlaying());assertNull(notification());assertEquals(ShadowMediaPlayer.State.END,media.get(media.size()-1).getState());
    }
    @Test public void stoppingDuringPreparePreventsLateAudioAndCanRetry(){
        core.load(Uri.parse(first.contentUri()),"retry",false,0,0);ShadowMediaPlayer stale=media.get(media.size()-1);core.stop();idle();assertFalse(core.isPlaying());assertEquals(ShadowMediaPlayer.State.END,stale.getState());
        core.select(first);service.get().onStartCommand(new Intent(),0,3);idle();assertTrue(core.isPlaying());
    }
    @Test public void notificationTapOpensExistingPlayerAndPreviewCannotSeekOutsideItsClip(){
        Intent open=Shadows.shadowOf(notification().contentIntent).getSavedIntent();assertTrue(open.getBooleanExtra("playback",false));assertTrue(open.getBooleanExtra("library",false));
        core.load(Uri.parse(first.contentUri()),"試聴",true,12000,60000);service.get().onStartCommand(new Intent(),0,2);idle();assertEquals(0,SessionShadow.state.getActions()&PlaybackState.ACTION_SEEK_TO);assertEquals(0,SessionShadow.metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
    }
    @Test public void oldServiceAndDismissedCardCannotStopTheNewPlayback(){
        MediaSession.Callback oldCallback=SessionShadow.callback;Intent oldDismiss=Shadows.shadowOf(notification().deleteIntent).getSavedIntent();int oldId=core.notificationId;
        core.stop();idle();core.select(first);
        ServiceController<PlaybackService> oldService=service;
        service=Robolectric.buildService(PlaybackService.class).create();service.get().onStartCommand(new Intent(),0,2);idle();
        assertNotEquals(oldId,core.notificationId);assertTrue(core.isPlaying());oldService.destroy();
        oldCallback.onPause();oldCallback.onStop();assertTrue(core.isPlaying());assertNotNull(notification());
        service.get().onStartCommand(oldDismiss,0,3);idle();assertTrue(core.isPlaying());assertEquals(first.id(),core.entry.id());assertNotNull(notification());
    }
    private static Object field(Object o,String name) throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void reset(String name,Class<?> type) throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(null,null);}
    private void resetDb() throws Exception {Field f=DownloadStore.class.getDeclaredField("instance");f.setAccessible(true);SQLiteOpenHelper db=(SQLiteOpenHelper)f.get(null);if(db!=null)db.close();f.set(null,null);context.deleteDatabase("downloads.db");}
}
