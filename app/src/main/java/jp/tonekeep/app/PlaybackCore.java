package jp.tonekeep.app;

import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** Application-owned playback. No Activity, View, or UI callback survives a UI detach. */
final class PlaybackCore {
    private static PlaybackCore instance;
    static PlaybackCore get(Context c){if(instance==null)instance=new PlaybackCore(c.getApplicationContext());return instance;}
    final android.app.Application context;
    final Handler handler=new Handler(Looper.getMainLooper());
    final Set<Runnable> listeners=new HashSet<>();
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final PlaybackOrder queue=new PlaybackOrder();
    private MediaPlayer media;
    private PreviewAudio decoder;
    private int generation,pending=-1;
    private boolean wantsPlay,completed;
    long interruptions;
    private void interrupt(){interruptions++;pause();}
    boolean active,ready,preparing,preview,shuffled,foreground;
    int offset,total,repeatMode;
    String name="",artist="",album="",artwork="",error="";
    private String source="";
    DownloadStore.Entry entry;
    Object serviceToken;
    int notificationId;
    private final BroadcastReceiver noisy=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){interrupt();}};
    private PlaybackCore(Context c){
        context=(android.app.Application)c;audio=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes()).setOnAudioFocusChangeListener(change->{if(change<0)interrupt();},handler).build();
    }
    private boolean registered;
    private void activate(){
        if(!registered){if(Build.VERSION.SDK_INT>=33)context.registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));registered=true;}
        active=true;
    }
    static AudioAttributes attributes(){return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();}
    void changed(){for(Runnable r:new ArrayList<>(listeners))r.run();}
    void options(boolean shuffle,int repeat){shuffled=shuffle;repeatMode=Math.max(0,Math.min(2,repeat));queue.shuffle(shuffle);changed();}
    boolean canMove(int direction){return entry!=null&&queue.canMove(direction,repeatMode==1);}
    void select(DownloadStore.Entry e){
        queue.select(new DownloadStore(context).all().stream().filter(x->x.status().equals("complete")&&!x.contentUri().isBlank()).map(DownloadStore.Entry::id).collect(java.util.stream.Collectors.toList()),e.id(),shuffled);
        playEntry(e);
    }
    private void playEntry(DownloadStore.Entry e){load(Uri.parse(e.contentUri()),e.title(),false,0,0);track(e,new TrackMetadata.Store(context).find(e.id(),e.contentUri()));}
    void track(DownloadStore.Entry e,TrackMetadata m){
        entry=e;name=m!=null&&!m.title().isBlank()?m.title():e.title();
        if(e.pitchJob()&&m!=null)name+=" [キー "+(e.semitones()>0?"+":"")+e.semitones()+"]";
        artist=m==null||m.artist().isBlank()?"端末に保存した音源":m.artist();album=m==null?"":m.album();artwork=m==null?"":m.artwork();changed();
    }
    void move(int direction){
        if(direction<0&&position()>3000){seek(0);return;}
        String id=queue.move(direction<0?-1:1,repeatMode==1);if(id==null)return;
        DownloadStore.Entry e=new DownloadStore(context).find(id);
        if(e!=null&&e.status().equals("complete")&&!e.contentUri().isBlank())playEntry(e);
    }
    void busy(String title){clear(false);activate();name=title;preview=true;preparing=true;changed();}
    void load(Uri uri,String title,boolean clip,int start,int duration){
        clear(false);activate();source=uri.toString();name=title;preview=clip;offset=start;total=duration;preparing=true;wantsPlay=true;error="";
        int token=generation;
        try{PlaybackService.start(context);prepare(uri,token,false);}catch(RuntimeException e){fail("再生サービスを開始できません。");}
        changed();
    }
    private void prepare(Uri uri,int token,boolean decoded){
        MediaPlayer p=new MediaPlayer();media=p;p.setAudioAttributes(attributes());p.setWakeMode(context,PowerManager.PARTIAL_WAKE_LOCK);
        p.setOnPreparedListener(v->{if(media!=v||generation!=token)return;preparing=false;ready=true;if(!preview)total=v.getDuration();if(pending>=0){v.seekTo(Math.min(total,pending));pending=-1;}if(wantsPlay)startReady();changed();});
        p.setOnCompletionListener(v->{if(media!=v)return;completed=true;wantsPlay=false;audio.abandonAudioFocusRequest(focus);if(!preview&&repeatMode==2){seek(0);play();}else if(!preview&&canMove(1))move(1);changed();});
        p.setOnSeekCompleteListener(v->{if(media==v)changed();});
        p.setOnErrorListener((v,w,e)->{if(media==v&&generation==token)decodeOrFail(uri,token,decoded);return true;});
        try{p.setDataSource(context,uri);p.prepareAsync();}catch(Exception e){if(media==p)decodeOrFail(uri,token,decoded);}
    }
    private void decodeOrFail(Uri uri,int token,boolean decoded){
        releaseMedia();if(decoded||preview){fail("この音源を再生できません。");return;}
        preparing=true;decoder=new PreviewAudio(context);decoder.decode(uri.toString(),new PreviewAudio.Callback(){
            @Override public void ready(PreviewAudio.Result r){if(generation==token)prepare(Uri.fromFile(r.file()),token,true);}
            @Override public void failed(Exception e){if(generation==token)fail("音源の読み込みに失敗しました。");}
        });changed();
    }
    private void fail(String message){releaseMedia();if(decoder!=null){decoder.close();decoder=null;}preparing=false;wantsPlay=false;error=message;changed();}
    void restorePosition(int pos){wantsPlay=false;if(ready){pause();seek(pos);}else pending=Math.max(0,pos);}
    void play(){
        if(!active)return;
        if(!ready&&!preparing&&!source.isBlank()){
            DownloadStore.Entry selected=entry;String uri=source,title=name;boolean clip=preview;int start=offset,duration=total;
            load(Uri.parse(uri),title,clip,start,duration);if(selected!=null)track(selected,new TrackMetadata.Store(context).find(selected.id(),selected.contentUri()));return;
        }
        wantsPlay=true;try{PlaybackService.start(context);}catch(RuntimeException e){wantsPlay=false;error="再生サービスを開始できません。";}changed();
    }
    // Called only after the foreground service has posted its media notification.
    void foregroundReady(){if(wantsPlay&&ready)startReady();}
    private void startReady(){
        if(!ready||media==null||!wantsPlay||!foreground)return;
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){wantsPlay=false;error="他のアプリが音声を使用しています。";changed();return;}
        try{error="";if(completed){media.seekTo(0);completed=false;}media.start();}catch(IllegalStateException e){fail("再生を再開できません。");}
        changed();
    }
    void pause(){wantsPlay=false;if(isPlaying())media.pause();audio.abandonAudioFocusRequest(focus);changed();}
    void toggle(){if(isPlaying()||preparing&&wantsPlay)pause();else play();}
    boolean isPlaying(){return ready&&media!=null&&media.isPlaying();}
    int position(){return offset+(ready&&media!=null?media.getCurrentPosition():0);}
    void seek(int ms){if(!ready||preview)return;completed=false;media.seekTo(Math.max(0,Math.min(total,ms)),MediaPlayer.SEEK_CLOSEST);changed();}
    private void releaseMedia(){ready=false;MediaPlayer old=media;media=null;if(old!=null)old.release();audio.abandonAudioFocusRequest(focus);}
    void stop(){clear(true);}
    private void clear(boolean notify){
        ++generation;releaseMedia();if(decoder!=null){decoder.close();decoder=null;}
        if(registered){context.unregisterReceiver(noisy);registered=false;}
        active=false;preparing=false;wantsPlay=false;completed=false;pending=-1;entry=null;offset=0;total=0;source="";name="";artist="";album="";artwork="";error="";if(notify)changed();
    }
}
