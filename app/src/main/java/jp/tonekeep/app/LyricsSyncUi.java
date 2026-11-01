package jp.tonekeep.app;

import android.app.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Chooses between direct gesture recording and explicitly requested offline AI matching. */
final class LyricsSyncUi implements AutoCloseable {
    private final Activity activity;private final AudioPlayer player;private final LyricsStore store;private final Supplier<DownloadStore.Entry> playing;private final Consumer<DownloadStore.Entry> openAudio,changed;private final Runnable models;
    private final Handler handler=new Handler(Looper.getMainLooper());private final ExecutorService worker=Executors.newSingleThreadExecutor();private final LyricsTraceDrafts drafts;private boolean closed;private TracePane pane;
    private Consumer<DownloadStore.Entry> processing=entry->{};
    void processing(Consumer<DownloadStore.Entry> callback){processing=callback;}
    LyricsSyncUi(Activity activity,AudioPlayer player,LyricsStore store,Supplier<DownloadStore.Entry> playing,Consumer<DownloadStore.Entry> openAudio,Consumer<DownloadStore.Entry> changed,Runnable models){this.activity=activity;this.player=player;this.store=store;this.playing=playing;this.openAudio=openAudio;this.changed=changed;this.models=models;drafts=new LyricsTraceDrafts(activity.getApplicationContext());}
    void choose(DownloadStore.Entry entry,Lyrics doc){
        new AlertDialog.Builder(activity).setTitle("歌詞の同期方法").setItems(new String[]{"指でなぞって保存（行・単語）","AIで秒数を推定（端末内・要確認）"},(d,i)->{if(i==0)trace(entry,doc);else ai(entry,doc);}).setNegativeButton("戻る",null).show();
    }
    private void trace(DownloadStore.Entry entry,Lyrics doc){try{DownloadStore.Entry current=playing.get();if(current==null||!current.id().equals(entry.id())||player.view.getVisibility()!=View.VISIBLE)openAudio.accept(entry);pane=new TracePane(entry,doc);pane.show();}catch(RuntimeException e){toast(e.getMessage());}}
    private void ai(DownloadStore.Entry entry,Lyrics doc){
        LinearLayout box=column();TextView info=label("歌詞本文を保って、端末内Whisperの解析時刻と照合します。推定結果は要確認です。一致しない部分は未同期になります。",14);box.addView(info);
        TextView cache=label("解析済みの音源は結果を再利用します。初回は認識データを取得した後、そのまま時刻の推定を始めます。音源や歌詞は送信しません。",12);box.addView(cache);
        new AlertDialog.Builder(activity).setTitle("AIで同期候補を作成").setView(box).setNegativeButton("戻る",null).setNeutralButton("詳細設定",(d,w)->models.run()).setPositiveButton("同期候補を作る",(d,w)->{
            String id=LyricsJobs.model(activity);ProgressDialog progress=new ProgressDialog(activity);progress.setMessage("音源と歌詞を確認しています");progress.setCancelable(true);progress.show();java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
            Future<?> pending=worker.submit(()->{try{SongDetails song=SongDetails.read(activity,entry);if(!song.hash().equals(doc.sourceHash()))throw new java.io.IOException("元音源の内容が変わっています。歌詞を読み込み直してください。");
                handler.post(()->{if(closed||cancelled.get())return;progress.dismiss();try{LyricsJobs.start(activity,entry,id,true,"align",doc);processing.accept(entry);}catch(Exception e){new AlertDialog.Builder(activity).setTitle("同期を開始できませんでした").setMessage(e.getMessage()).setPositiveButton("閉じる",null).show();}});
            }catch(Exception e){handler.post(()->{if(!closed&&!cancelled.get()){progress.dismiss();toast(e.getMessage());}});}});progress.setOnCancelListener(x->{cancelled.set(true);pending.cancel(true);});
        }).show();
    }
    private final class TracePane {
        final DownloadStore.Entry entry;final Lyrics doc;final String key;final Dialog dialog;final LinearLayout root;final TextView status;final Button play,scroll,save;final SeekBar seek;
        LyricsTraceSession session;LyricsTraceView view;boolean finished,dragging,loading,saving;
        final Runnable tick=new Runnable(){@Override public void run(){if(closed||!dialog.isShowing())return;boolean ready=current();play.setEnabled(ready&&!player.isPreparing());play.setText(player.isPlaying()?"一時停止":"再生");if(!dragging)seek.setProgress(Math.min(seek.getMax(),Math.max(0,player.position())));refresh();handler.postDelayed(this,150);}};
        TracePane(DownloadStore.Entry entry,Lyrics doc){
            this.entry=entry;this.doc=doc;key=LyricsTraceDrafts.fingerprint(entry,doc);dialog=new Dialog(activity);root=column();root.setBackgroundColor(Color.rgb(16,23,22));
            root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(12)+bars.left,dp(8)+bars.top,dp(12)+bars.right,dp(8)+bars.bottom);}return insets;});
            root.addView(label(entry.title()+" · なぞり同期",20));root.addView(label("歌に合わせて次の行・単語を順になぞります。緑は記録済み。指を離すと終了時刻を記録します。",12));
            Spinner mode=new Spinner(activity);mode.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"行をなぞる","単語をなぞる"}));LinearLayout modes=row();modes.addView(mode,new LinearLayout.LayoutParams(0,dp(48),1));modes.addView(button("最初から",v->new AlertDialog.Builder(activity).setMessage("下書きの時刻を消して記録し直します。保存済みの歌詞は残ります。").setNegativeButton("戻る",null).setPositiveButton("記録し直す",(d,w)->{view.finish();session.clear();view.paint();stash();refresh();}).show()));root.addView(modes);
            status=label("",13);root.addView(status);session=new LyricsTraceSession(doc,false);view=createView();root.addView(view,new LinearLayout.LayoutParams(-1,0,1));
            seek=new SeekBar(activity);seek.setContentDescription("なぞり同期の再生位置");seek.setMax((int)Math.min(Integer.MAX_VALUE,doc.durationMs()));root.addView(seek,new LinearLayout.LayoutParams(-1,dp(48)));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){@Override public void onProgressChanged(SeekBar b,int p,boolean user){}@Override public void onStartTrackingTouch(SeekBar b){view.finish();dragging=true;}@Override public void onStopTrackingTouch(SeekBar b){dragging=false;player.seekTo(b.getProgress());}});
            LinearLayout controls=row();play=button("再生",v->{view.finish();if(current())player.togglePlayback();});Button undo=button("1つ戻す",v->{view.finish();session.undo();view.paint();stash();refresh();});scroll=button("スクロール",v->{view.tracing(!view.tracing());((Button)v).setText(view.tracing()?"スクロール":"なぞる");});controls.addView(play,weight());controls.addView(undo,weight());controls.addView(scroll,weight());root.addView(controls);
            LinearLayout bottom=row();bottom.addView(button("閉じる・下書き保存",v->dialog.dismiss()),weight());save=button("同期を保存",v->save());bottom.addView(save,weight());root.addView(bottom);
            mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(AdapterView<?> parent,View child,int position,long id){boolean words=position==1;if(words!=session.words){view.finish();stash();root.removeView(view);session=new LyricsTraceSession(doc,words);view=createView();root.addView(view,4,new LinearLayout.LayoutParams(-1,0,1));scroll.setText("スクロール");restore();}}@Override public void onNothingSelected(AdapterView<?> p){}});
            dialog.setContentView(root);dialog.setOnDismissListener(d->{handler.removeCallbacks(tick);if(!finished&&!saving){view.finish();stash();}if(pane==this)pane=null;});
        }
        LyricsTraceView createView(){return new LyricsTraceView(activity,session,()->Math.min(session.duration,player.position()),()->!loading&&current()&&player.isPlaying(),()->{stash();refresh();},LyricsSyncUi.this::toast);}
        boolean current(){DownloadStore.Entry now=playing.get();return now!=null&&now.id().equals(entry.id());}
        void show(){dialog.show();Window window=dialog.getWindow();if(window!=null){window.setLayout(-1,-1);window.setStatusBarColor(Color.rgb(16,23,22));window.setNavigationBarColor(Color.rgb(16,23,22));}restore();handler.post(tick);refresh();}
        void restore(){loading=true;final LyricsTraceSession expected=session;worker.submit(()->{String saved=drafts.load(key,expected.words);handler.post(()->{if(closed||finished||session!=expected)return;expected.restore(saved,key);loading=false;view.paint();view.revealNext();refresh();});});}
        void stash(){if(finished||closed)return;try{String json=session.draft(key);boolean words=session.words;worker.execute(()->{try{drafts.write(key,words,json);}catch(java.io.IOException e){handler.post(()->toast("下書きを保存できません。空き容量を確認してください。"));}});}catch(org.json.JSONException e){toast(e.getMessage());}}
        void refresh(){if(save==null)return;status.setText(AudioPlayer.clock(player.position())+" / "+AudioPlayer.clock((int)doc.durationMs())+" · 記録 "+session.recorded()+" / "+session.units.size()+(loading?" · 下書き読込中":""));save.setEnabled(!loading&&!saving&&session.complete()&&session.active()<0);}
        void save(){view.finish();if(!session.complete()){toast("未記録の歌詞があります");return;}Lyrics output;try{output=session.document(doc);}catch(RuntimeException e){toast(e.getMessage());return;}saving=true;refresh();boolean words=session.words;worker.execute(()->{try{store.saveExternal(entry.id(),entry.contentUri(),output);drafts.remove(key,words);handler.post(()->{if(!closed){finished=true;changed.accept(entry);dialog.dismiss();toast("なぞった時刻を保存しました");}});}catch(Exception e){handler.post(()->{if(!closed){saving=false;refresh();toast(e.getMessage());}});}});}
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(12),dp(8),dp(12),dp(8));return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(52),1);}
    private TextView label(String text,int size){TextView v=new TextView(activity);v.setText(text);v.setTextColor(Color.rgb(240,244,237));v.setTextSize(size);v.setPadding(0,dp(5),0,dp(5));return v;}
    private Button button(String text,View.OnClickListener action){Button v=new Button(activity);v.setText(text);v.setTextSize(12);v.setAllCaps(false);v.setOnClickListener(action);return v;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private void toast(String text){if(!closed)Toast.makeText(activity,text==null?"処理に失敗しました":text,Toast.LENGTH_LONG).show();}
    @Override public void close(){if(pane!=null)pane.dialog.dismiss();closed=true;handler.removeCallbacksAndMessages(null);worker.shutdown();}
}
