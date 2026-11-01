package jp.tonekeep.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.Locale;
import java.util.function.IntConsumer;

/** One MediaPlayer clock drives the mini player, full player and expanded lyrics. */
final class AudioPlayer implements AutoCloseable {
    private static final int WHITE=0xfffafafa, MUTED=0xffb3b3b3, GREEN=0xff1ed760, BG=0xff121212;
    final LinearLayout view;
    private final Activity context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final PlaybackCore core;
    private final Runnable observer=this::onCoreChanged;
    private long seenInterruptions;
    private final TextView title,miniArtist;
    private final ImageView miniArt;
    private final ImageButton play;
    private final ProgressBar miniProgress;
    private final LyricsView lyrics;
    private final IntConsumer previewSeek;
    private final Runnable dismissed,interrupted;
    private IntConsumer navigate=d->{};
    private Runnable optionsChanged=()->{},more=()->{},share=()->{};
    private Runnable lyricsAction=()->{};
    private Button lyricsManage;
    private boolean ready,preparing,preview,dragging,shuffled,hasPrevious,hasNext,savedTrack;
    private int total,repeatMode,accent=0xff364b62;
    private String trackIdentity="",requestName="",displayTitle="",artist="",album="",artwork="";
    private Lyrics document;
    private Dialog sheet,lyricsDialog;
    private LinearLayout sheetRoot,lyricCard;
    private TextView sheetTitle,sheetArtist,sheetAlbum,currentTime,endTime;
    private ImageView sheetArt,saved;
    private ImageButton fullPlay,previous,next,shuffle,repeat,shareButton,menuButton;
    private SeekBar seek;
    private LyricsView expandedLyrics;
    private final Runnable tick=new Runnable(){@Override public void run(){update();handler.postDelayed(this,100);}};

    AudioPlayer(Context context,IntConsumer previewSeek,Runnable dismissed,Runnable interrupted){
        this.context=(Activity)context;this.previewSeek=previewSeek;this.dismissed=dismissed;this.interrupted=interrupted;
        core=PlaybackCore.get(context);seenInterruptions=core.interruptions;core.listeners.add(observer);
        view=column();view.setPadding(dp(8),0,dp(8),0);view.setVisibility(View.GONE);
        LinearLayout surface=column();surface.setBackground(round(0xff293c40,8));
        LinearLayout row=row();row.setPadding(dp(8),dp(6),dp(4),dp(2));
        miniArt=cover(false);row.addView(miniArt,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout names=column();names.setPadding(dp(10),0,0,0);title=text("",13,WHITE,true);miniArtist=text("",11,0xffdddddd,false);names.addView(title);names.addView(miniArtist);row.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        names.setOnClickListener(v->expand());miniArt.setOnClickListener(v->expand());
        play=icon(UiIcon.Kind.PLAY_SOLID,"再生・一時停止",WHITE,48,v->toggle());row.addView(play);
        row.addView(icon(UiIcon.Kind.CLOSE,"プレーヤーを閉じる",MUTED,40,v->{stop();dismissed.run();}));
        surface.addView(row);surface.setOnClickListener(v->expand());surface.setContentDescription("再生画面を開く");
        miniProgress=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);miniProgress.setMax(1000);miniProgress.setProgressTintList(ColorStateList.valueOf(WHITE));miniProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0x44777777));
        LinearLayout.LayoutParams progressParams=new LinearLayout.LayoutParams(-1,dp(2));progressParams.setMargins(dp(8),dp(2),dp(8),0);surface.addView(miniProgress,progressParams);view.addView(surface);
        lyrics=new LyricsView(context);lyrics.document(null,this::seekTo);update();handler.post(tick);
    }
    void transport(IntConsumer navigate,Runnable optionsChanged,Runnable more,Runnable share){this.navigate=navigate;this.optionsChanged=optionsChanged;this.more=more;this.share=share;}
    void lyricsAction(Runnable action){lyricsAction=action;}
    boolean shuffleEnabled(){return shuffled;}
    boolean repeatsAll(){return repeatMode==1;}
    int repeatMode(){return repeatMode;}
    boolean lyricsExpanded(){return lyricsDialog!=null&&lyricsDialog.isShowing();}
    void restoreOptions(boolean shuffle,int repeat){core.options(shuffle,repeat);}
    void restorePosition(int position){core.restorePosition(position);}
    void queueControls(boolean before,boolean after){hasPrevious=before;hasNext=after;update();}
    boolean isExpanded(){return sheet!=null&&sheet.isShowing();}
    void track(DownloadStore.Entry entry,TrackMetadata m){core.track(entry,m);}
    private void presentation(){
        title.setText(displayTitle);miniArtist.setText(preparing?"再生の準備中…":artist);miniArtist.setVisibility(artist.isBlank()&&!preparing?View.GONE:View.VISIBLE);
        art(miniArt,256);
        if(sheetTitle!=null){sheetTitle.setText(displayTitle);sheetArtist.setText(artist);sheetAlbum.setText(album.isBlank()?preview?"キー変更の試聴":"保存した音源":album);art(sheetArt,1280);saved.setVisibility(savedTrack?View.VISIBLE:View.GONE);shareButton.setVisibility(savedTrack?View.VISIBLE:View.GONE);menuButton.setVisibility(savedTrack?View.VISIBLE:View.GONE);}
    }
    void expand(){
        if(view.getVisibility()!=View.VISIBLE)return;
        if(sheet!=null){sheet.show();return;}
        sheet=new Dialog(context,android.R.style.Theme_Material_NoActionBar);sheetRoot=column();sheetRoot.setBackground(gradient());insets(sheetRoot);
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content=column();content.setPadding(dp(20),0,dp(20),dp(24));scroll.addView(content);sheetRoot.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout header=row();header.addView(icon(UiIcon.Kind.DOWN,"再生画面を閉じる",WHITE,48,v->sheet.dismiss()));
        LinearLayout heading=column();TextView caption=text("保存した音源から再生中",10,0xffdddddd,false);caption.setGravity(Gravity.CENTER);heading.addView(caption);sheetAlbum=text("",12,WHITE,true);sheetAlbum.setGravity(Gravity.CENTER);heading.addView(sheetAlbum);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        menuButton=icon(UiIcon.Kind.MORE,"再生中の音源の操作",WHITE,48,v->{sheet.dismiss();more.run();});header.addView(menuButton);content.addView(header);
        sheetArt=cover(true);LinearLayout.LayoutParams artParams=new LinearLayout.LayoutParams(-1,-2);artParams.setMargins(dp(4),dp(22),dp(4),dp(28));content.addView(sheetArt,artParams);
        LinearLayout info=row();LinearLayout names=column();sheetTitle=text("",23,WHITE,true);sheetTitle.setMaxLines(2);sheetArtist=text("",15,0xffd3d0cf,false);names.addView(sheetTitle);LinearLayout.LayoutParams artistParams=new LinearLayout.LayoutParams(-1,-2);artistParams.topMargin=dp(5);names.addView(sheetArtist,artistParams);info.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        saved=new ImageView(context);saved.setImageDrawable(new UiIcon(UiIcon.Kind.CHECK,0xff102413));saved.setPadding(dp(5),dp(5),dp(5),dp(5));saved.setBackground(round(GREEN,50));saved.setContentDescription("端末に保存済み");LinearLayout.LayoutParams savedParams=new LinearLayout.LayoutParams(dp(28),dp(28));savedParams.leftMargin=dp(12);info.addView(saved,savedParams);content.addView(info);
        seek=new SeekBar(context);seek.setContentDescription("再生位置");seek.setPadding(0,dp(10),0,0);seek.setProgressTintList(ColorStateList.valueOf(WHITE));seek.setThumbTintList(ColorStateList.valueOf(WHITE));seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x557f7f7f));content.addView(seek,new LinearLayout.LayoutParams(-1,dp(42)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){@Override public void onProgressChanged(SeekBar bar,int value,boolean user){if(user)currentTime.setText(clock(value));}@Override public void onStartTrackingTouch(SeekBar b){dragging=true;}@Override public void onStopTrackingTouch(SeekBar b){dragging=false;seekTo(b.getProgress());}});
        LinearLayout times=row();currentTime=text("0:00",12,0xffd1caca,false);endTime=text("0:00",12,0xffd1caca,false);endTime.setGravity(Gravity.END);times.addView(currentTime,new LinearLayout.LayoutParams(0,-2,1));times.addView(endTime);content.addView(times);
        LinearLayout controls=row();controls.setGravity(Gravity.CENTER);controls.setPadding(0,dp(14),0,dp(12));
        shuffle=icon(UiIcon.Kind.SHUFFLE,"シャッフル",WHITE,48,v->{core.options(!shuffled,repeatMode);optionsChanged.run();update();});previous=icon(UiIcon.Kind.PREVIOUS,"前の曲",WHITE,48,v->{if(position()>3000)seekTo(0);else navigate.accept(-1);});
        fullPlay=icon(UiIcon.Kind.PLAY_SOLID,"再生・一時停止",BG,72,v->toggle());fullPlay.setPadding(dp(22),dp(22),dp(22),dp(22));fullPlay.setBackground(round(WHITE,50));
        next=icon(UiIcon.Kind.NEXT,"次の曲",WHITE,48,v->navigate.accept(1));repeat=icon(UiIcon.Kind.REPEAT,"リピート",WHITE,48,v->{core.options(shuffled,(repeatMode+1)%3);optionsChanged.run();update();});
        controls.addView(shuffle);controls.addView(previous,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(fullPlay);controls.addView(next,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(repeat);content.addView(controls);
        LinearLayout extras=row();ImageView device=new ImageView(context);device.setImageDrawable(new UiIcon(UiIcon.Kind.DOWNLOAD,GREEN));extras.addView(device,new LinearLayout.LayoutParams(dp(16),dp(16)));TextView local=text("端末内で再生",11,GREEN,false);local.setPadding(dp(6),0,0,0);extras.addView(local,new LinearLayout.LayoutParams(0,-2,1));shareButton=icon(UiIcon.Kind.SHARE,"音源を共有",WHITE,48,v->{sheet.dismiss();share.run();});extras.addView(shareButton);content.addView(extras);
        lyricCard=column();lyricCard.setPadding(dp(14),dp(8),dp(14),dp(12));lyricCard.setBackground(round(mix(accent,BG,.5f),12));LinearLayout lyricHeader=row();TextView lyricLabel=text("歌詞",17,WHITE,true);lyricHeader.addView(lyricLabel,new LinearLayout.LayoutParams(0,-2,1));lyricHeader.addView(icon(UiIcon.Kind.EXPAND,"歌詞を拡大",WHITE,48,v->expandLyrics()));lyricHeader.setOnClickListener(v->expandLyrics());lyricCard.addView(lyricHeader);
        if(lyrics.getParent()!=null)((android.view.ViewGroup)lyrics.getParent()).removeView(lyrics);lyricCard.addView(lyrics,new LinearLayout.LayoutParams(-1,dp(230)));LinearLayout.LayoutParams lyricParams=new LinearLayout.LayoutParams(-1,-2);lyricParams.topMargin=dp(18);content.addView(lyricCard,lyricParams);
        lyricsManage=new Button(context);lyricsManage.setText(document==null?"歌詞を探す":"歌詞の取得・同期");lyricsManage.setAllCaps(false);lyricsManage.setTextColor(WHITE);lyricsManage.setBackgroundColor(Color.TRANSPARENT);lyricsManage.setOnClickListener(v->{if(sheet!=null)sheet.dismiss();lyricsAction.run();});lyricsManage.setVisibility(savedTrack?View.VISIBLE:View.GONE);lyricCard.addView(lyricsManage,new LinearLayout.LayoutParams(-1,dp(48)));
        sheet.setContentView(sheetRoot);Dialog current=sheet;sheet.setOnDismissListener(d->{if(sheet==current){if(lyricsDialog!=null)lyricsDialog.dismiss();clearSheet(current);}});showDialog(sheet);presentation();update();
    }
    void expandLyrics(){
        if(lyricsDialog!=null&&lyricsDialog.isShowing())return;
        lyricsDialog=new Dialog(context,android.R.style.Theme_Material_NoActionBar);LinearLayout root=column();root.setBackground(gradient());insets(root);
        LinearLayout header=row();header.setPadding(dp(8),0,dp(8),0);header.addView(icon(UiIcon.Kind.DOWN,"歌詞の拡大表示を閉じる",WHITE,48,v->lyricsDialog.dismiss()));TextView name=text(displayTitle,14,WHITE,true);header.addView(name,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);
        expandedLyrics=new LyricsView(context);expandedLyrics.setPadding(dp(16),dp(16),dp(16),dp(16));expandedLyrics.document(document,this::seekTo);root.addView(expandedLyrics,new LinearLayout.LayoutParams(-1,0,1));
        if(savedTrack){Button manage=new Button(context);manage.setText(document==null?"歌詞を探す":"歌詞の取得・同期");manage.setAllCaps(false);manage.setOnClickListener(v->{lyricsDialog.dismiss();if(sheet!=null)sheet.dismiss();lyricsAction.run();});root.addView(manage,new LinearLayout.LayoutParams(-1,dp(48)));}
        lyricsDialog.setContentView(root);Dialog current=lyricsDialog;lyricsDialog.setOnDismissListener(d->{if(lyricsDialog==current){expandedLyrics=null;lyricsDialog=null;}});showDialog(lyricsDialog);update();
    }
    private void showDialog(Dialog dialog){dialog.show();Window w=dialog.getWindow();if(w!=null){w.setLayout(-1,-1);w.setStatusBarColor(Color.TRANSPARENT);w.setNavigationBarColor(BG);}}
    private void insets(View view){view.setOnApplyWindowInsetsListener((v,i)->{if(Build.VERSION.SDK_INT>=30){Insets b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);}else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});}
    private void art(ImageView target,int size){
        if(target==null)return;String key=artwork+"@"+size;if(key.equals(target.getTag()))return;
        target.setTag(null);target.setImageDrawable(new UiIcon(UiIcon.Kind.MUSIC,0xffb8c4cf));
        if(artwork.isBlank()){target.setTag(key);return;}
        ArtworkLoader.load(target,artwork,size,bitmap->{accent=artColor(bitmap);view.getChildAt(0).setBackground(round(mix(accent,BG,.4f),8));if(sheetRoot!=null)sheetRoot.setBackground(gradient());if(lyricCard!=null)lyricCard.setBackground(round(mix(accent,BG,.4f),12));});
    }
    private static int artColor(Bitmap b){
        long r=0,g=0,blue=0,weight=0;float[] hsv=new float[3];
        for(int y=0;y<b.getHeight();y+=Math.max(1,b.getHeight()/24))for(int x=0;x<b.getWidth();x+=Math.max(1,b.getWidth()/24)){int c=b.getPixel(x,y);Color.colorToHSV(c,hsv);if(Color.alpha(c)<128||hsv[2]<.12||hsv[2]>.94&&hsv[1]<.15)continue;int w=1+(int)(hsv[1]*8);r+=Color.red(c)*w;g+=Color.green(c)*w;blue+=Color.blue(c)*w;weight+=w;}
        if(weight==0)return 0xff364b62;int average=Color.rgb((int)(r/weight),(int)(g/weight),(int)(blue/weight));Color.colorToHSV(average,hsv);hsv[1]=Math.max(.25f,hsv[1]);hsv[2]=Math.max(.28f,Math.min(.62f,hsv[2]));return Color.HSVToColor(hsv);
    }
    private GradientDrawable gradient(){return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{accent,mix(accent,BG,.65f),BG});}
    private static int mix(int a,int b,float fraction){return Color.rgb((int)(Color.red(a)*(1-fraction)+Color.red(b)*fraction),(int)(Color.green(a)*(1-fraction)+Color.green(b)*fraction),(int)(Color.blue(a)*(1-fraction)+Color.blue(b)*fraction));}
    void busy(String name){core.busy(name);}
    void load(Uri uri,String name,boolean isPreview,int offsetMs,int durationMs,Runnable failed){core.load(uri,name,isPreview,offsetMs,durationMs);}
    private void toggle(){core.toggle();}
    void togglePlayback(){toggle();}
    void pause(){core.pause();}
    boolean isPlaying(){return core.isPlaying();}
    boolean isPreparing(){return core.preparing;}
    boolean isPreview(){return core.preview;}
    void seekTo(int positionMs){if(core.preview){if(core.ready)previewSeek.accept(positionMs);}else core.seek(positionMs);}
    int position(){return core.position();}
    private void onCoreChanged(){if(seenInterruptions!=core.interruptions){seenInterruptions=core.interruptions;if(core.preview)interrupted.run();}update();}
    private void update(){
        ready=core.ready;preparing=core.preparing;preview=core.preview;total=core.total;shuffled=core.shuffled;repeatMode=core.repeatMode;
        hasPrevious=core.canMove(-1);hasNext=core.canMove(1);savedTrack=core.entry!=null;
        String identity=core.entry==null?core.name:core.entry.id()+"@"+core.entry.contentUri();
        boolean newTrack=!trackIdentity.equals(identity);trackIdentity=identity;
        boolean changed=newTrack||!requestName.equals(core.name)||!artist.equals(core.artist)||!artwork.equals(core.artwork)||!album.equals(core.album);
        requestName=core.name;displayTitle=core.name;artist=core.artist;album=core.album;artwork=core.artwork;
        view.setVisibility(core.active?View.VISIBLE:View.GONE);if(!core.active)dismissSheets();
        if(newTrack)setLyricsSilently(null);if(changed)presentation();
        miniArtist.setText(core.error.isBlank()?preparing?"再生の準備中…":artist:core.error);
        boolean playing=isPlaying();UiIcon.Kind action=playing?UiIcon.Kind.PAUSE_SOLID:UiIcon.Kind.PLAY_SOLID;
        play.setImageDrawable(new UiIcon(action,WHITE));play.setEnabled(ready);miniProgress.setIndeterminate(preparing);if(total>0)miniProgress.setProgress((int)Math.min(1000,1000L*position()/total));
        if(fullPlay!=null){fullPlay.setImageDrawable(new UiIcon(action,BG));fullPlay.setEnabled(ready);fullPlay.setContentDescription(playing?"一時停止":"再生");seek.setEnabled(ready);seek.setMax(Math.max(1,total));if(!dragging){seek.setProgress(Math.min(total,position()));currentTime.setText(clock(position()));}endTime.setText(clock(total));
            previous.setEnabled(ready&&(hasPrevious||position()>3000));previous.setAlpha(previous.isEnabled()?1f:.3f);next.setEnabled(ready&&hasNext);next.setAlpha(next.isEnabled()?1f:.3f);
            shuffle.setImageDrawable(new UiIcon(UiIcon.Kind.SHUFFLE,shuffled?GREEN:WHITE));shuffle.setSelected(shuffled);shuffle.setContentDescription(shuffled?"シャッフルを解除":"シャッフルを有効にする");
            shuffle.setEnabled(savedTrack);shuffle.setAlpha(savedTrack?1f:.3f);repeat.setEnabled(!preview);repeat.setAlpha(preview?.3f:1f);
            repeat.setImageDrawable(new UiIcon(repeatMode==2?UiIcon.Kind.REPEAT_ONE:UiIcon.Kind.REPEAT,repeatMode==0?WHITE:GREEN));repeat.setContentDescription(repeatMode==0?"リピートなし。タップして全曲リピート":repeatMode==1?"全曲リピート。タップして1曲リピート":"1曲リピート。タップして解除");
        }
        if(ready){lyrics.position(position());if(expandedLyrics!=null)expandedLyrics.position(position());}
    }
    private void setLyricsSilently(Lyrics value){document=value;lyrics.document(value,this::seekTo);if(expandedLyrics!=null)expandedLyrics.document(value,this::seekTo);}
    void setLyrics(Lyrics value){document=value;lyrics.document(value,this::seekTo);if(lyricsManage!=null)lyricsManage.setText(value==null?"歌詞を探す":"歌詞の取得・同期");if(expandedLyrics!=null)expandedLyrics.document(value,this::seekTo);update();}
    private void clearSheet(Dialog expected){if(sheet==expected){sheet=null;sheetTitle=null;sheetArt=null;seek=null;fullPlay=null;sheetRoot=null;lyricCard=null;dragging=false;}}
    private void dismissSheets(){if(lyricsDialog!=null)lyricsDialog.dismiss();if(sheet!=null)sheet.dismiss();}
    void stop(){core.stop();setLyrics(null);dismissSheets();accent=0xff364b62;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(context);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(context);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(context);v.setText(value);v.setTextColor(color);v.setTextSize(size);v.setMaxLines(1);v.setEllipsize(TextUtils.TruncateAt.END);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private ImageView cover(boolean square){ImageView v=square?new SquareCover(context):new ImageView(context);v.setScaleType(ImageView.ScaleType.CENTER_CROP);v.setBackground(round(0xff29343c,5));v.setClipToOutline(true);v.setContentDescription("アルバムジャケット");return v;}
    private static final class SquareCover extends ImageView {SquareCover(Context c){super(c);}@Override protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w);int max=Math.round(420*getResources().getDisplayMetrics().density);int size=Math.min(width,max);super.onMeasure(MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY));}}
    private ImageButton icon(UiIcon.Kind kind,String description,int color,int size,View.OnClickListener click){ImageButton b=new ImageButton(context);b.setImageDrawable(new UiIcon(kind,color));b.setContentDescription(description);b.setTooltipText(description);int padding=(size-24)/2;b.setPadding(dp(padding),dp(padding),dp(padding),dp(padding));b.setBackgroundColor(Color.TRANSPARENT);b.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));b.setOnClickListener(click);return b;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    static String clock(int ms){int seconds=Math.max(0,ms/1000);return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    @Override public void close(){core.listeners.remove(observer);handler.removeCallbacksAndMessages(null);dismissSheets();if(core.preview)core.stop();}
}
