package jp.tonekeep.app;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** User-initiated metadata requests; all saved lyrics and timing edits work locally. */
final class LyricsSourcesUi implements AutoCloseable {
    private static final int IMPORT=1071;
    private final Activity activity; private final AudioPlayer player; private final LyricsStore store;
    private final Supplier<DownloadStore.Entry> playing; private final Consumer<DownloadStore.Entry> changed;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(); private final Handler handler=new Handler(Looper.getMainLooper());
    private String importId="",importUri=""; private boolean closed;
    private final LyricsSyncUi sync;
    LyricsSourcesUi(Activity activity,AudioPlayer player,LyricsStore store,Supplier<DownloadStore.Entry> playing,Consumer<DownloadStore.Entry> changed,Consumer<DownloadStore.Entry> openAudio,Runnable models,Bundle saved){
        this.activity=activity;this.player=player;this.store=store;this.playing=playing;this.changed=changed;
        sync=new LyricsSyncUi(activity,player,store,playing,openAudio,changed,models);
        if(saved!=null){importId=saved.getString("lyrics_import_id","");importUri=saved.getString("lyrics_import_uri","");}
    }
    void search(DownloadStore.Entry entry){prepare(entry,false);}
    void processing(Consumer<DownloadStore.Entry> callback){sync.processing(callback);}
    void metadata(DownloadStore.Entry entry){prepare(entry,true);}
    void synchronize(DownloadStore.Entry entry){chooseSync(entry);}
    void importLyrics(DownloadStore.Entry entry){importFile(entry);}
    void revisions(DownloadStore.Entry entry){history(entry);}
    void edit(DownloadStore.Entry entry,Lyrics existing){
        EditText text=new EditText(activity);text.setGravity(android.view.Gravity.TOP);text.setMinLines(8);text.setMaxLines(16);text.setHint("歌詞を貼り付けてください");text.setText(existing==null?"":ReferenceLyricsAlignment.text(existing));text.setContentDescription("歌詞本文");text.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100000)});
        LinearLayout box=column();TextView note=new TextView(activity);note.setText(existing==null?"保存後に、必要ならタイミングを合わせられます。":"本文を変えると時刻は外れます。以前の歌詞は履歴に残ります。");box.addView(note);box.addView(text);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(existing==null?"歌詞を貼り付ける":"歌詞本文を編集").setView(box).setNegativeButton("戻る",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String body=text.getText().toString();if(body.isBlank()){text.setError("歌詞を入力してください");return;}if(existing!=null&&body.equals(ReferenceLyricsAlignment.text(existing))){dialog.dismiss();return;}
            job("歌詞を保存中",()->{SongDetails song=SongDetails.read(activity,entry);Lyrics doc=LrcParser.plain(body,song.duration(),song.hash(),new Lyrics.Origin("user","自分で入力した歌詞",""));store.saveExternal(entry.id(),entry.contentUri(),doc);return doc;},doc->{changed.accept(entry);dialog.dismiss();toast("歌詞を保存しました");});}));dialog.show();
    }
    private void chooseSync(DownloadStore.Entry entry){job("保存した歌詞を読み込み中",()->store.load(entry.id(),entry.contentUri()),doc->{if(doc==null){toast("先に歌詞を取得・読み込みしてください");return;}sync.choose(entry,doc);});}
    private void prepare(DownloadStore.Entry entry,boolean metadata){job("音源の情報を確認中",()->SongDetails.read(activity,entry),song->{
        LinearLayout box=column();EditText title=input(song.title(),"曲名"),artist=input(song.artist(),"アーティスト");box.addView(title);box.addView(artist);
        TextView note=new TextView(activity);note.setText((metadata?"MusicBrainz / Cover Art Archive":"LRCLIB")+"へ曲名・アーティスト名を送信します。音源は送信しません。\n候補を確認して選択し、取得後は端末に保存します。");box.addView(note);
        new AlertDialog.Builder(activity).setTitle(metadata?"曲情報を検索":"既存の歌詞を探す").setView(box).setNegativeButton("戻る",null).setPositiveButton("検索",(d,w)->{
            if(metadata)job("曲情報を検索中",()->MusicCatalog.search(activity,title.getText().toString(),artist.getText().toString()),matches->catalogChoices(entry,song,matches));
            else job("歌詞を検索中",()->LyricsLookup.search(activity,title.getText().toString(),artist.getText().toString(),song.duration()),matches->lyricsChoices(entry,song,matches));
        }).show();
    });}
    private void lyricsChoices(DownloadStore.Entry entry,SongDetails song,List<LyricsLookup.Candidate> matches){
        if(matches.isEmpty()){toast("歌詞が見つかりません。曲名やアーティスト名を変えるかLRCを読み込んでください。");return;}
        String[] labels=matches.stream().map(m->m.description(song.duration())).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle("歌詞の候補").setItems(labels,(d,i)->{
            LyricsLookup.Candidate candidate=matches.get(i);if(!candidate.matchesDuration(song.duration())){toast("曲長が異なるため保存しません。同じバージョンを選んでください。");return;}
            String body=candidate.synced().isBlank()?candidate.plain():candidate.synced();TextView preview=new TextView(activity);preview.setText(body);preview.setPadding(24,12,24,12);ScrollView scroll=new ScrollView(activity);scroll.addView(preview);
            new AlertDialog.Builder(activity).setTitle(candidate.synced().isBlank()?"時刻なしの歌詞 · LRCLIB":"同期歌詞 · LRCLIB").setView(scroll).setNegativeButton("戻る",null).setPositiveButton("端末に保存",(p,w)->job("歌詞を保存中",()->{Lyrics doc=candidate.document(song.duration(),song.hash());store.saveExternal(entry.id(),entry.contentUri(),doc);return doc;},doc->{changed.accept(entry);toast(doc.lines().isEmpty()?"歌詞を保存しました。必要なら端末内で行時刻を付けられます。":"同期歌詞を保存しました");})).show();
        }).setNegativeButton("閉じる",null).show();
    }
    private void catalogChoices(DownloadStore.Entry entry,SongDetails song,List<MusicCatalog.Match> matches){
        if(matches.isEmpty()){toast("曲情報が見つかりませんでした");return;}
        new AlertDialog.Builder(activity).setTitle("曲情報の候補").setItems(matches.stream().map(MusicCatalog.Match::description).toArray(String[]::new),(d,i)->{
            MusicCatalog.Match match=matches.get(i);String mismatch=match.duration()>0&&Math.abs(match.duration()-song.duration())>3000?"\n音源と曲長が異なります。ライブ版・別編集版でないか確認してください。":"";
            new AlertDialog.Builder(activity).setTitle("曲情報を確認").setMessage(match.description()+mismatch+"\n元の音源ファイル名と音声は変更しません。").setNegativeButton("戻る",null).setPositiveButton("この情報を使う",(p,w)->job("曲情報と画像を取得中",()->MusicCatalog.apply(activity,entry,match),result->{changed.accept(entry);toast(result.status());})).show();
        }).setNegativeButton("閉じる",null).show();
    }
    private void importFile(DownloadStore.Entry entry){
        importId=entry.id();importUri=entry.contentUri();
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/*","application/octet-stream","application/x-lrc"});
        try{activity.startActivityForResult(pick,IMPORT);}catch(RuntimeException e){toast(e.getMessage());}
    }
    private void history(DownloadStore.Entry entry){job("歌詞の履歴を読み込み中",()->store.revisions(entry.id(),entry.contentUri()),revisions->{
        if(revisions.isEmpty()){toast("保存した歌詞はありません");return;}
        String[] labels=revisions.stream().map(r->android.text.format.DateFormat.format("MM/dd HH:mm",r.createdMs())+" · "+r.document().origin().provider()+" · "+(r.document().lines().isEmpty()?"未同期":r.document().lines().size()+"行")).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle("歌詞の履歴を選択").setItems(labels,(d,i)->new AlertDialog.Builder(activity).setMessage("選んだ歌詞を表示に使用します。現在の歌詞も履歴に残ります。").setNegativeButton("戻る",null).setPositiveButton("この歌詞を使う",(p,w)->job("歌詞を切り替え中",()->{store.saveExternal(entry.id(),entry.contentUri(),revisions.get(i).document());return true;},done->{changed.accept(entry);toast("歌詞を切り替えました");})).show()).setNegativeButton("閉じる",null).show();
    });}
    boolean result(int request,int result,Intent data){
        if(request!=IMPORT)return false;
        if(result==Activity.RESULT_OK&&data!=null&&data.getData()!=null){
            DownloadStore.Entry entry=new DownloadStore(activity).find(importId);if(entry==null||!entry.contentUri().equals(importUri)){toast("対象の音源が見つかりません");return true;}Uri uri=data.getData();
            job("歌詞ファイルを読み込み中",()->{SongDetails song=SongDetails.read(activity,entry);String text;
                try(InputStream in=activity.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("歌詞を開けません");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IOException("歌詞ファイルは1MB以下にしてください");out.write(b,0,n);}text=out.toString(StandardCharsets.UTF_8.name());}
                return new Object[]{song,text};
            },values->{SongDetails song=(SongDetails)values[0];String text=(String)values[1];new AlertDialog.Builder(activity).setTitle("歌詞ファイルの種類").setItems(new String[]{"時刻付きLRC / Enhanced LRC","時刻なしテキスト"},(d,i)->job("歌詞を保存中",()->{
                    Lyrics.Origin source=new Lyrics.Origin("local-file","ローカル歌詞ファイル","");Lyrics doc=i==0?LrcParser.parse(text,song.duration(),song.hash(),source):LrcParser.plain(text,song.duration(),song.hash(),source);store.saveExternal(entry.id(),entry.contentUri(),doc);return doc;
                },doc->{changed.accept(entry);toast("歌詞を保存しました");})).show();});
        }
        return true;
    }
    private void manual(DownloadStore.Entry entry){
        DownloadStore.Entry current=playing.get();if(current==null||!current.id().equals(entry.id())||player.view.getVisibility()!=View.VISIBLE){toast("この曲をプレーヤーで再生してから開いてください");return;}
        job("保存済み歌詞を読み込み中",()->store.load(entry.id(),entry.contentUri()),doc->{
            if(doc==null){toast("先に歌詞を取得・読み込みしてください");return;}
            String text=doc.plainText().isBlank()?doc.lines().stream().map(Lyrics.Line::text).collect(java.util.stream.Collectors.joining("\n")):doc.plainText();
            List<String> lines=new ArrayList<>();for(String line:text.split("\\R"))if(!line.isBlank())lines.add(line);if(lines.isEmpty()){toast("歌詞本文がありません");return;}
            LinearLayout box=column();TextView help=new TextView(activity);help.setText("曲を再生し、各行の歌い始めで時刻を記録します。音声解析や通信は行いません。");box.addView(help);TextView row=new TextView(activity);row.setTextSize(20);box.addView(row);TextView time=new TextView(activity);box.addView(time);
            Button record=new Button(activity);record.setText("現在位置を記録 → 次の行");box.addView(record);Button undo=new Button(activity);undo.setText("1行戻す");box.addView(undo);Button toggle=new Button(activity);toggle.setText("再生・一時停止");box.addView(toggle);toggle.setOnClickListener(v->player.togglePlayback());
            long[] starts=new long[lines.size()];int[] index={0};Runnable update=()->row.setText(Math.min(index[0]+1,lines.size())+" / "+lines.size()+"\n"+(index[0]<lines.size()?lines.get(index[0]):"すべて記録しました。保存できます。"));update.run();
            AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("端末内で行同期を作成").setView(box).setNegativeButton("破棄",null).setPositiveButton("保存",null).create();
            record.setOnClickListener(v->{int i=index[0];DownloadStore.Entry now=playing.get();if(now==null||!now.id().equals(entry.id())){toast("再生中の音源が変わりました");return;}if(i>=starts.length)return;long ms=player.position();if(ms>=doc.durationMs()||(i>0&&ms<=starts[i-1])){toast("前の行より後の位置を指定してください");return;}starts[i]=ms;index[0]++;update.run();});
            undo.setOnClickListener(v->{if(index[0]>0){index[0]--;update.run();}});
            Runnable tick=new Runnable(){@Override public void run(){if(closed||!dialog.isShowing())return;time.setText("再生位置 "+AudioPlayer.clock(player.position()));handler.postDelayed(this,250);}};
            dialog.setOnDismissListener(d->handler.removeCallbacks(tick));dialog.setOnShowListener(d->{handler.post(tick);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(index[0]!=lines.size()){toast("すべての行を記録してから保存してください");return;}
                List<Lyrics.Line> timed=new ArrayList<>();for(int i=0;i<lines.size();i++)timed.add(new Lyrics.Line(lines.get(i),starts[i],i+1<lines.size()?starts[i+1]:doc.durationMs(),null,0,"manual-line",List.of()));
                Lyrics resultDoc=new Lyrics(1,doc.sourceHash(),"manual",doc.language(),doc.durationMs(),timed,text,new Lyrics.Origin(doc.origin().provider(),doc.origin().label()+" · 手動行同期",doc.origin().url()));
                job("行時刻を保存中",()->{store.saveExternal(entry.id(),entry.contentUri(),resultDoc);return resultDoc;},saved->{changed.accept(entry);dialog.dismiss();toast("行同期を保存しました");});
            });});dialog.show();
        });
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(24,12,24,12);return v;}
    private EditText input(String text,String hint){EditText v=new EditText(activity);v.setSingleLine();v.setText(text);v.setHint(hint);v.setContentDescription(hint);return v;}
    private <T> void job(String message,Callable<T> task,Consumer<T> success){
        ProgressDialog dialog=new ProgressDialog(activity);dialog.setMessage(message);dialog.setCancelable(true);dialog.show();
        java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        Future<?> pending=worker.submit(()->{try{T value=task.call();handler.post(()->{if(!closed&&!cancelled.get()){dialog.dismiss();success.accept(value);}});}catch(Exception e){handler.post(()->{if(!closed&&!cancelled.get()){dialog.dismiss();new AlertDialog.Builder(activity).setTitle("処理を完了できませんでした").setMessage(e.getMessage()==null?"通信や音源へのアクセスを確認してください。":e.getMessage()).setNegativeButton("閉じる",null).setPositiveButton("再試行",(d,w)->job(message,task,success)).show();}});}});
        dialog.setOnCancelListener(d->{cancelled.set(true);pending.cancel(true);});
    }
    void save(Bundle out){out.putString("lyrics_import_id",importId);out.putString("lyrics_import_uri",importUri);}
    private void toast(String text){if(!closed)Toast.makeText(activity,text==null?"処理に失敗しました":text,Toast.LENGTH_LONG).show();}
    @Override public void close(){sync.close();closed=true;handler.removeCallbacksAndMessages(null);worker.shutdownNow();}
}
