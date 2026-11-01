package jp.tonekeep.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.media.MediaMetadata;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Android owns the media card layout; the session shares the player's actual clock. */
public final class PlaybackService extends Service {
    private static final AtomicInteger ids=new AtomicInteger(1000+(int)(SystemClock.elapsedRealtime()%1_000_000_000L));
    private final int notificationId=ids.getAndIncrement();
    private final Object ownership=new Object();
    static final String CHANNEL="playback";
    private PlaybackCore core;
    private MediaSession session;
    private NotificationManager notifications;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService artWorker=Executors.newSingleThreadExecutor();
    private String artPath="";
    private Bitmap art;
    private boolean destroyed,foreground,stopping;
    private final Runnable observe=this::publish;
    private final Runnable idle=()->{if(ownsPlayback()&&!core.isPlaying()){stopForeground(STOP_FOREGROUND_DETACH);foreground=false;core.foreground=false;}};
    private boolean ownsPlayback(){return !destroyed&&core!=null&&core.serviceToken==ownership;}
    static void start(Context c){c.startForegroundService(new Intent(c,PlaybackService.class));}
    @Override public void onCreate(){
        super.onCreate();core=PlaybackCore.get(this);core.serviceToken=ownership;core.notificationId=notificationId;notifications=getSystemService(NotificationManager.class);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"音楽の再生",NotificationManager.IMPORTANCE_LOW);channel.setSound(null,null);channel.setShowBadge(false);notifications.createNotificationChannel(channel);
        session=new MediaSession(this,"ToneKeep playback");
        session.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){if(ownsPlayback())core.play();}
            @Override public void onPause(){if(ownsPlayback())core.pause();}
            @Override public void onStop(){if(ownsPlayback())core.stop();}
            @Override public void onSeekTo(long pos){if(ownsPlayback())core.seek((int)Math.max(0,Math.min(Integer.MAX_VALUE,pos)));}
            @Override public void onSkipToNext(){if(ownsPlayback())core.move(1);}
            @Override public void onSkipToPrevious(){if(ownsPlayback())core.move(-1);}
        },handler);
        session.setSessionActivity(openPlayer());session.setActive(true);core.listeners.add(observe);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        // Promotion precedes audio focus, including play requests from a locked screen.
        startForeground(notificationId,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);foreground=true;core.foreground=true;stopping=false;
        String action=intent==null?null:intent.getAction();
        // An old card may be dismissed after a new service/session has already started.
        if(action!=null&&intent.getIntExtra("playback_session",-1)!=notificationId){publish();return START_NOT_STICKY;}
        if("pause".equals(action))core.pause();else if("next".equals(action))core.move(1);else if("previous".equals(action))core.move(-1);else if("stop".equals(action))core.stop();else if("play".equals(action))core.play();
        core.foregroundReady();publish();return START_NOT_STICKY;
    }
    private PendingIntent openPlayer(){return PendingIntent.getActivity(this,45,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("library",true).putExtra("playback",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private PendingIntent command(String action){return PendingIntent.getForegroundService(this,action.hashCode(),new Intent(this,PlaybackService.class).setAction(action).setData(Uri.parse("tonekeep://playback/"+notificationId+"/"+action)).putExtra("playback_session",notificationId),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification.Action action(int icon,String label,String action){return new Notification.Action.Builder(icon,label,command(action)).build();}
    private Notification notification(){
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_music).setContentTitle(core.name.isBlank()?"ToneKeep":core.name).setContentText(core.preparing?"再生の準備中…":core.error.isBlank()?core.artist:core.error).setSubText(core.album.isBlank()?null:core.album).setContentIntent(openPlayer()).setDeleteIntent(command("stop")).setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setShowWhen(false).setCategory(Notification.CATEGORY_TRANSPORT).setOngoing(core.isPlaying()).setLargeIcon(art);
        int count=0,center;
        if(core.canMove(-1)||core.ready&&!core.preview){b.addAction(action(android.R.drawable.ic_media_previous,"前の曲","previous"));count++;}
        center=count;b.addAction(action(core.isPlaying()?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,core.isPlaying()?"一時停止":"再生",core.isPlaying()?"pause":"play"));count++;
        if(core.canMove(1)){b.addAction(action(android.R.drawable.ic_media_next,"次の曲","next"));count++;}
        int[] compact=count==3?new int[]{0,1,2}:count==2?new int[]{0,1}:new int[]{center};
        return b.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(compact)).build();
    }
    private void publish(){
        if(!ownsPlayback())return;
        if(!core.active){handler.post(()->{if(ownsPlayback()&&!core.active){stopping=true;foreground=false;core.foreground=false;stopForeground(STOP_FOREGROUND_REMOVE);notifications.cancel(notificationId);stopSelf();}});return;}
        if(!artPath.equals(core.artwork)){
            artPath=core.artwork;art=null;String requested=artPath;
            if(!requested.isBlank())artWorker.submit(()->{Bitmap bitmap=decodeArtwork(requested);handler.post(()->{if(!destroyed&&artPath.equals(requested)){art=bitmap;publish();}});});
        }
        MediaMetadata.Builder m=new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID,core.entry==null?"":core.entry.id()).putString(MediaMetadata.METADATA_KEY_TITLE,core.name).putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,core.name).putString(MediaMetadata.METADATA_KEY_ARTIST,core.artist).putString(MediaMetadata.METADATA_KEY_ALBUM,core.album).putLong(MediaMetadata.METADATA_KEY_DURATION,core.preview?0:core.total);
        if(art!=null)m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,art).putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON,art);
        session.setMetadata(m.build());
        long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_STOP;
        if(core.ready&&!core.preview)actions|=PlaybackState.ACTION_SEEK_TO;
        if(core.canMove(-1)||core.ready&&!core.preview)actions|=PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if(core.canMove(1))actions|=PlaybackState.ACTION_SKIP_TO_NEXT;
        int state=!core.error.isBlank()?PlaybackState.STATE_ERROR:core.preparing?PlaybackState.STATE_BUFFERING:core.isPlaying()?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED;
        PlaybackState.Builder p=new PlaybackState.Builder().setActions(actions).setState(state,core.preview?0:core.position(),core.isPlaying()?1f:0f,SystemClock.elapsedRealtime());
        if(!core.error.isBlank())p.setErrorMessage(core.error);session.setPlaybackState(p.build());
        notifications.notify(notificationId,notification());
        handler.removeCallbacks(idle);if(!core.isPlaying()&&!core.preparing&&foreground)handler.postDelayed(idle,5*60*1000);
    }
    static Bitmap decodeArtwork(String path){
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,o);if(o.outWidth<1||o.outHeight<1||o.outWidth>20000||o.outHeight>20000)return null;
        o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>640)o.inSampleSize*=2;o.inJustDecodeBounds=false;
        try{return BitmapFactory.decodeFile(path,o);}catch(OutOfMemoryError e){return null;}
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        destroyed=true;handler.removeCallbacksAndMessages(null);artWorker.shutdownNow();core.listeners.remove(observe);
        if(core.serviceToken==ownership){core.serviceToken=null;core.foreground=false;if(!stopping)core.stop();}
        session.setActive(false);session.release();notifications.cancel(notificationId);super.onDestroy();
    }
}
