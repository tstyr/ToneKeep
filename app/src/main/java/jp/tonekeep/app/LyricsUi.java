package jp.tonekeep.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small adapter for the existing programmatic UI; analysis belongs to the service. */
final class LyricsUi implements AutoCloseable {
    private static final int EXPORT = 1070;
    private final Activity activity;
    private final AudioPlayer player;
    private final LyricsStore store;
    private final ModelManager models;
    private final LyricsSourcesUi sources;
    private final java.util.function.Consumer<DownloadStore.Entry> openAudio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private DownloadStore.Entry playing;
    private String attachedTask = "", exportAudio = "", exportUri = "", exportFormat = "";
    private boolean closed;
    private Dialog panel;
    private LinearLayout panelBody;
    private DownloadStore.Entry panelEntry;
    private String panelStamp="";
    private boolean panelPending;
    private final Runnable panelTick=new Runnable(){@Override public void run(){if(closed||panel==null||!panel.isShowing())return;refreshPanel();handler.postDelayed(this,750);}};
    LyricsUi(Activity activity, AudioPlayer player, Bundle saved,java.util.function.Consumer<DownloadStore.Entry> openAudio) {
        this.activity = activity; this.player = player; this.openAudio=openAudio;store = new LyricsStore(activity); models = new ModelManager(activity);
        sources = new LyricsSourcesUi(activity,player,store,()->playing,entry->{if(playing!=null&&playing.id().equals(entry.id()))load(playing);},openAudio,this::modelDialog,saved);
        sources.processing(this::open);
        if(saved!=null){String id=saved.getString("lyrics_panel_id",""),uri=saved.getString("lyrics_panel_uri","");if(!id.isBlank())handler.post(()->{if(closed||panel!=null)return;DownloadStore.Entry entry=new DownloadStore(activity).find(id);if(entry!=null&&entry.contentUri().equals(uri))open(entry);});}
        if (saved != null) { exportAudio = saved.getString("lyrics_export_audio",""); exportUri = saved.getString("lyrics_export_uri",""); exportFormat = saved.getString("lyrics_export_format",""); }
        if (store.all().stream().anyMatch(LyricsStore.Task::active)) { try { LyricsService.start(activity); } catch (RuntimeException e) { toast(e.getMessage()); } }
    }
    String snapshot() { return store.all().toString(); }
    String status(DownloadStore.Entry entry) { LyricsStore.Task t = store.forAudio(entry.id(),entry.contentUri()); return t == null ? "" : "歌詞 · " + t.message() + (t.progress() >= 0 && t.active() ? " " + t.progress() + "%" : ""); }
    void actions(PopupMenu menu, DownloadStore.Entry entry) {
        menu.getMenu().add("歌詞").setOnMenuItemClickListener(item->{open(entry);return true;});
        menu.getMenu().add("曲情報・ジャケット").setOnMenuItemClickListener(item->{sources.metadata(entry);return true;});
    }
    void open(DownloadStore.Entry entry){
        if(closed)return;closePanel();panelEntry=entry;panelStamp="";panelPending=false;
        Dialog dialog=new Dialog(activity,android.R.style.Theme_Material_NoActionBar);panel=dialog;
        LinearLayout root=column();root.setBackgroundColor(0xff121212);root.setPadding(dp(20),dp(12),dp(20),dp(12));
        root.setOnApplyWindowInsetsListener((v,i)->{if(android.os.Build.VERSION.SDK_INT>=30){var bars=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(20)+bars.left,dp(12)+bars.top,dp(20)+bars.right,dp(12)+bars.bottom);}return i;});
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);ImageButton back=new ImageButton(activity);back.setImageDrawable(new UiIcon(UiIcon.Kind.DOWN,Color.WHITE));back.setBackgroundColor(Color.TRANSPARENT);back.setPadding(dp(12),dp(12),dp(12),dp(12));back.setContentDescription("歌詞画面を閉じる");back.setOnClickListener(v->dialog.dismiss());heading.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));heading.addView(label("歌詞",24,Color.WHITE,true));root.addView(heading);
        TrackMetadata metadata=new TrackMetadata.Store(activity).find(entry.id(),entry.contentUri());TextView name=label(metadata==null?entry.title():metadata.title(),16,Color.WHITE,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);root.addView(name);if(metadata!=null&&!metadata.artist().isBlank())root.addView(label(metadata.artist(),13,0xffb3b3b3,false));
        ScrollView scroll=new ScrollView(activity);panelBody=column();scroll.addView(panelBody);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));panelBody.addView(label("歌詞の状態を確認しています…",14,0xffb3b3b3,false));
        dialog.setContentView(root);dialog.setOnDismissListener(d->{if(panel==dialog){panel=null;handler.removeCallbacks(panelTick);}});dialog.show();dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setStatusBarColor(0xff121212);dialog.getWindow().setNavigationBarColor(0xff121212);handler.post(panelTick);
    }
    private void closePanel(){if(panel!=null){Dialog old=panel;panel=null;old.dismiss();}handler.removeCallbacks(panelTick);}
    private void refreshPanel(){
        if(panelPending)return;panelPending=true;Dialog expected=panel;DownloadStore.Entry entry=panelEntry;
        worker.submit(()->{try{store.recoverStalled();LyricsStore.Task task=store.forAudio(entry.id(),entry.contentUri());String model=task!=null&&(task.kind().equals("lyrics")||task.kind().equals("align"))?task.model():LyricsJobs.model(activity);LyricsStore.Task download=store.modelTask(model);long revision=store.revisionId(entry.id(),entry.contentUri());String stamp=revision+"|"+task+"|"+download;
            Lyrics doc=stamp.equals(panelStamp)?null:store.load(entry.id(),entry.contentUri());handler.post(()->{if(closed||panel!=expected)return;panelPending=false;if(!stamp.equals(panelStamp)){panelStamp=stamp;renderPanel(entry,doc,task,download);}});
        }catch(Exception error){handler.post(()->{if(closed||panel!=expected)return;panelPending=false;panelBody.removeAllViews();panelBody.addView(label("歌詞を読み込めませんでした。\n"+error.getMessage(),14,0xffffb5a0,false));action(panelBody,"再試行",true,()->{panelStamp="";refreshPanel();});});}});
    }
    private void renderPanel(DownloadStore.Entry entry,Lyrics doc,LyricsStore.Task task,LyricsStore.Task download){
        panelBody.removeAllViews();
        LinearLayout summary=card();String state=doc==null?"まだ歌詞がありません":doc.origin().provider().equals("whisper")?"音声から認識した歌詞 · 要確認":doc.lines().isEmpty()?"歌詞を保存済み · 時刻なし":doc.unmatchedCount()>0?"歌詞を保存済み · 未同期 "+doc.unmatchedCount()+"行":"歌詞とタイミングを保存済み";
        summary.addView(label(state,16,Color.WHITE,true));summary.addView(label(doc==null?"まず歌詞を保存し、必要ならタイミングを合わせます。":doc.origin().provider().equals("whisper")?"歌唱は誤認識することがあります。歌詞検索から差し替えられます。":"保存した歌詞はオフラインで表示できます。",13,0xffb3b3b3,false));panelBody.addView(summary);
        if(task!=null&&task.active()){
            boolean getting=task.status().equals("queued")&&download!=null&&download.active();LyricsStore.Task progressTask=getting?download:task;LinearLayout progressCard=card();progressCard.addView(label(getting?"認識データを取得しています":task.kind().equals("align")?"タイミングを推定しています":"音声から歌詞を認識しています",16,Color.WHITE,true));progressCard.addView(label(progressTask.message(),13,0xffcccccc,false));
            ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(progressTask.progress()<0);progress.setMax(100);progress.setProgress(Math.max(0,progressTask.progress()));progressCard.addView(progress);action(progressCard,"中止",false,()->{try{LyricsJobs.cancel(activity,task);refreshPanel();}catch(RuntimeException e){showError(e);}});panelBody.addView(progressCard);return;
        }
        boolean retryable=task!=null&&(task.kind().equals("lyrics")||task.kind().equals("align"))&&(task.status().equals("failed")||task.status().equals("cancelled"));
        if(retryable){LinearLayout failure=card();failure.addView(label(task.status().equals("cancelled")?"処理を中止しました":"処理を完了できませんでした",16,0xffffb5a0,true));failure.addView(label(task.message(),13,0xffdddddd,false));action(failure,"再試行",true,()->retry(entry,task));panelBody.addView(failure);}
        if(doc==null){action(panelBody,"歌詞を探す",!retryable,()->{closePanel();sources.search(entry);});panelBody.addView(label("曲名・アーティスト名で検索し、候補を確認して保存します。音源は送信しません。",12,0xffb3b3b3,false));action(panelBody,"歌詞を貼り付ける",false,()->{closePanel();sources.edit(entry,null);});}
        else{action(panelBody,"歌詞を表示",!retryable,()->{closePanel();if(playing==null||!playing.id().equals(entry.id())||player.view.getVisibility()!=View.VISIBLE)openAudio.accept(entry);player.setLyrics(doc);player.expandLyrics();});action(panelBody,doc.fullyTimed()?"タイミングを調整する":"タイミングを合わせる",false,()->{closePanel();sources.synchronize(entry);});}
        action(panelBody,"その他の方法・設定",false,()->other(entry,doc));
    }
    private void other(DownloadStore.Entry entry,Lyrics doc){
        String[] names=doc==null?new String[]{"ファイルから読み込む","音声から認識（試験中）","詳細設定"}:new String[]{"歌詞を探し直す","本文を編集","ファイルから読み込む","音声から認識し直す（試験中）","書き出す","履歴から戻す","詳細設定"};
        new AlertDialog.Builder(activity).setTitle("歌詞の操作").setItems(names,(d,index)->{String choice=names[index];closePanel();if(choice.startsWith("歌詞を探"))sources.search(entry);else if(choice.startsWith("本文"))sources.edit(entry,doc);else if(choice.startsWith("ファイル"))sources.importLyrics(entry);else if(choice.startsWith("音声"))generate(entry,true);else if(choice.startsWith("書き出"))export(entry);else if(choice.startsWith("履歴"))sources.revisions(entry);else modelDialog();}).setNegativeButton("戻る",null).show();
    }
    private void retry(DownloadStore.Entry entry,LyricsStore.Task old){
        worker.submit(()->{try{Lyrics reference=old.kind().equals("align")?store.input(old.id()):null;handler.post(()->{if(!closed)submit(entry,LyricsJobs.model(activity),old.kind().equals("align")||LyricsJobs.japanese(activity),old.kind(),reference);});}catch(Exception error){handler.post(()->showError(error));}});
    }
    private void submit(DownloadStore.Entry entry,String model,boolean japanese,String kind,Lyrics reference){try{LyricsJobs.start(activity,entry,model,japanese,kind,reference);open(entry);}catch(Exception e){showError(e);}}
    private void showError(Exception error){if(!closed)new AlertDialog.Builder(activity).setTitle("処理を開始できませんでした").setMessage(error.getMessage()==null?"もう一度お試しください。":error.getMessage()).setPositiveButton("閉じる",null).show();}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(16),dp(14),dp(16),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(0xff242424);bg.setCornerRadius(dp(12));v.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(16),0,dp(8));v.setLayoutParams(p);return v;}
    private TextView label(String value,int size,int color,boolean bold){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(5),0,dp(5));if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private void action(LinearLayout parent,String text,boolean primary,Runnable run){Button b=new Button(activity);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?0xff121212:Color.WHITE);GradientDrawable bg=new GradientDrawable();bg.setColor(primary?0xff1ed760:0xff303030);bg.setCornerRadius(dp(24));b.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(12),0,0);b.setMinimumHeight(dp(48));parent.addView(b,p);b.setOnClickListener(v->run.run());}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    void attach(DownloadStore.Entry entry) { playing = entry; attachedTask = ""; load(entry); }
    void detach() { playing = null; attachedTask = ""; }
    void refresh() {
        store.recoverStalled();
        if (playing == null) return;
        LyricsStore.Task t = store.forAudio(playing.id(),playing.contentUri());
        String state = t == null ? "" : t.id() + t.status();
        if (!attachedTask.equals(state)) { attachedTask = state; if (t != null && t.status().equals("complete")) load(playing); }
    }
    private void load(DownloadStore.Entry entry) {
        worker.submit(() -> {
            try { Lyrics doc = store.load(entry.id(),entry.contentUri()); handler.post(() -> { if (!closed && playing == entry) player.setLyrics(doc); }); }
            catch (Exception e) { handler.post(() -> toast("歌詞を読み込めません：" + e.getMessage())); }
        });
    }
    private void generate(DownloadStore.Entry entry, boolean force) {
        String model=LyricsJobs.model(activity);boolean needsData=!models.installed(model);
        new AlertDialog.Builder(activity).setTitle("音声から歌詞を認識")
                .setMessage("端末内で音声を認識します。歌唱は誤認識することがある試験機能です。既存の歌詞は履歴に残ります。"+(needsData?"\n\n初回は認識データ約"+(model.equals("medium")?"1.53GB":"488MB")+"を取得し、取得後に自動で認識を始めます。":"\n\n認識データは取得済みです。ネット接続なしで解析できます。"))
                .setNegativeButton("戻る",null).setNeutralButton("詳細設定",(d,w)->modelDialog())
                .setPositiveButton(needsData?"取得して認識を始める":"認識を始める",(d,w)->submit(entry,model,LyricsJobs.japanese(activity),"lyrics",null)).show();
    }
    private void start(String audio, String uri, String model, boolean japanese, String kind) {
        LyricsStore.Task task = store.enqueue(audio,uri,model,japanese,kind);
        try { LyricsService.start(activity); toast("処理を受け付けました"); }
        catch (RuntimeException e) { store.update(task.id(),"failed","処理を開始できませんでした。再試行してください。",-1); toast(e.getMessage()); }
    }
    void modelDialog() {
        LinearLayout box=column();box.setPadding(dp(20),dp(8),dp(20),dp(12));
        box.addView(label("端末内AIの設定",16,Color.WHITE,true));
        Spinner choice=new Spinner(activity);choice.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"small · 標準（約488MB）","medium · 大容量（約1.53GB）"}));choice.setSelection(LyricsJobs.model(activity).equals("medium")?1:0);box.addView(choice);
        CheckBox japanese=new CheckBox(activity);japanese.setText("日本語を優先する");japanese.setChecked(LyricsJobs.japanese(activity));box.addView(japanese);japanese.setOnCheckedChangeListener((b,on)->activity.getSharedPreferences("lyrics-options",Activity.MODE_PRIVATE).edit().putBoolean("japanese",on).apply());
        TextView info=label("",13,0xffcccccc,false);box.addView(info);Button download=new Button(activity);box.addView(download);Button delete=new Button(activity);delete.setText("取得したデータを削除");box.addView(delete);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("歌詞の詳細設定").setView(box).setPositiveButton("閉じる",null).create();
        Runnable update=new Runnable(){@Override public void run(){if(closed||!dialog.isShowing())return;String id=choice.getSelectedItemPosition()==0?"small":"medium";LyricsStore.Task task=store.modelTask(id);boolean active=task!=null&&task.active(),ready=models.installed(id);long partial=models.partialBytes(id);
            info.setText((ready?"認識データは取得済みです。":partial>0?"中断したデータ "+AudioFile.sizeLabel(partial)+"。続きから取得できます。":"初回だけネット接続と保存容量が必要です。")+(task!=null&&(!ready||active)?"\n"+task.message():"")+"\n音源と歌詞は外部へ送信しません。");
            download.setVisibility(ready&&!active?View.GONE:View.VISIBLE);download.setText(active?"取得を中止":partial>0?"取得を再開":"認識データを取得");delete.setVisibility(ready||partial>0?View.VISIBLE:View.GONE);delete.setEnabled(!store.modelInUse(id));handler.postDelayed(this,750);
        }};
        choice.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int index,long id){activity.getSharedPreferences("lyrics-options",Activity.MODE_PRIVATE).edit().putString("model",index==0?"small":"medium").apply();}@Override public void onNothingSelected(android.widget.AdapterView<?> p){}});
        download.setOnClickListener(v->{String id=choice.getSelectedItemPosition()==0?"small":"medium";LyricsStore.Task task=store.modelTask(id);try{if(task!=null&&task.active())LyricsService.cancel(activity,task.id());else start("model:"+id,"",id,true,"model");}catch(RuntimeException error){showError(error);}});
        delete.setOnClickListener(v->{String id=choice.getSelectedItemPosition()==0?"small":"medium";if(store.modelInUse(id))return;new AlertDialog.Builder(activity).setMessage("取得した認識データを削除します。保存した音源と歌詞は残ります。").setNegativeButton("戻る",null).setPositiveButton("削除",(d,w)->{try{models.delete(id);}catch(Exception error){showError(error);}}).show();});
        dialog.setOnDismissListener(d->handler.removeCallbacks(update));dialog.show();handler.post(update);
    }
    private void export(DownloadStore.Entry entry) {
        Lyrics document;
        try { document=store.load(entry.id(),entry.contentUri()); if (document == null) { toast("先に歌詞を取得・読み込みしてください"); return; } }
        catch (Exception e) { toast(e.getMessage()); return; }
        boolean timed=document.fullyTimed(); String[] labels=timed?new String[]{"Enhanced LRC","LRC","テキスト","内部JSON"}:new String[]{"テキスト","内部JSON"};String[] formats=timed?new String[]{"elrc","lrc","txt","json"}:new String[]{"txt","json"};
        new AlertDialog.Builder(activity).setTitle("歌詞を書き出す").setItems(labels,(d,which) -> {
            exportAudio = entry.id(); exportUri = entry.contentUri(); exportFormat = formats[which];
            String mime=exportFormat.equals("json")?"application/json":exportFormat.equals("txt")?"text/plain":"application/octet-stream";
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"ToneKeep-"+entry.id()+ (exportFormat.equals("elrc") ? ".enhanced.lrc" : "."+exportFormat));
            try { activity.startActivityForResult(create,EXPORT); } catch (RuntimeException e) { toast(e.getMessage()); }
        }).show();
    }
    boolean result(int request, int result, Intent data) {
        if(sources.result(request,result,data))return true;
        if (request != EXPORT) return false;
        if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
            String audio = exportAudio, uri = exportUri, format = exportFormat; android.net.Uri destination = data.getData();
            worker.submit(() -> {
                try { Lyrics doc = store.load(audio,uri); if (doc == null) throw new java.io.IOException("歌詞が見つかりません");
                    try (var out = activity.getContentResolver().openOutputStream(destination,"wt")) { if (out == null) throw new java.io.IOException("書き出し先を開けません"); out.write((format.equals("json") ? doc.json() : doc.export(format)).getBytes(StandardCharsets.UTF_8)); }
                    handler.post(() -> toast("歌詞を書き出しました"));
                } catch (Exception e) { handler.post(() -> toast(e.getMessage())); }
            });
        }
        return true;
    }
    void save(Bundle out) { sources.save(out);out.putString("lyrics_panel_id",panel==null||panelEntry==null?"":panelEntry.id());out.putString("lyrics_panel_uri",panel==null||panelEntry==null?"":panelEntry.contentUri()); out.putString("lyrics_export_audio",exportAudio); out.putString("lyrics_export_uri",exportUri); out.putString("lyrics_export_format",exportFormat); }
    private void toast(String text) { if (!closed) Toast.makeText(activity,text == null ? "処理に失敗しました" : text,Toast.LENGTH_LONG).show(); }
    @Override public void close() { closed=true;closePanel();sources.close(); handler.removeCallbacksAndMessages(null); worker.shutdownNow(); }
}
