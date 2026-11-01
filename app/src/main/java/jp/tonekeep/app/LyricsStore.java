package jp.tonekeep.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class LyricsStore {
    private final SQLiteDatabase db;
    record Task(String id, String audioId, String uri, String model, boolean japanese, String kind, String status, String message, int progress) {
        boolean active() { return status.equals("queued") || status.equals("running"); }
    }
    LyricsStore(Context context) { db = new DownloadStore(context).database(); }
    static void createTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE lyrics_documents (revision INTEGER PRIMARY KEY AUTOINCREMENT,audio_id TEXT NOT NULL,source_uri TEXT NOT NULL,document TEXT NOT NULL,created_ms INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX lyrics_source ON lyrics_documents(audio_id,source_uri,revision)");
        db.execSQL("CREATE TABLE lyrics_tasks (id TEXT PRIMARY KEY,audio_id TEXT NOT NULL,source_uri TEXT NOT NULL,model TEXT NOT NULL,japanese INTEGER NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,progress INTEGER NOT NULL,input_document TEXT NOT NULL DEFAULT '',heartbeat_ms INTEGER NOT NULL DEFAULT 0,UNIQUE(audio_id,source_uri))");
        createRecognitionCache(db);
    }
    static void createRecognitionCache(SQLiteDatabase db){db.execSQL("CREATE TABLE lyrics_recognition_cache(audio_id TEXT NOT NULL,source_uri TEXT NOT NULL,model TEXT NOT NULL,source_hash TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(audio_id,source_uri,model))");}
    Task enqueue(String audioId, String uri, String model, boolean japanese, String kind) {
        return enqueue(audioId,uri,model,japanese,kind,"");
    }
    Task enqueueAlignment(String audioId,String uri,String model,Lyrics reference) throws Exception {return enqueue(audioId,uri,model,true,"align",reference.json());}
    record Prepared(Task work,Task download,boolean newDownload) { }
    Prepared prepare(String audioId,String uri,String model,boolean japanese,String kind,Lyrics reference,boolean needsModel) throws Exception {
        if(!kind.equals("lyrics")&&!kind.equals("align"))throw new IllegalArgumentException("未対応の歌詞処理");
        if(kind.equals("align")&&reference==null)throw new IllegalArgumentException("同期する歌詞がありません");
        db.beginTransaction();try{
            Task old=forAudio(audioId,uri);if(old!=null&&old.active())throw new IllegalStateException("この曲は処理中です。進捗を確認してください。");
            Task previousDownload=modelTask(model);Task download=needsModel?enqueue("model:"+model,"",model,true,"model"):null;
            Task work=enqueue(audioId,uri,model,japanese,kind,reference==null?"":reference.json());
            db.setTransactionSuccessful();return new Prepared(work,download,download!=null&&(previousDownload==null||!previousDownload.active()));
        }finally{db.endTransaction();}
    }
    Task modelTask(String model){return forAudio("model:"+model,"");}
    void failModelDependents(String model,String status,String message){
        ContentValues values=new ContentValues();values.put("status",status);values.put("message",message);values.put("progress",-1);
        db.update("lyrics_tasks",values,"model=? AND kind IN ('lyrics','align') AND status='queued'",new String[]{model});
    }
    private Task enqueue(String audioId, String uri, String model, boolean japanese, String kind,String input) {
        ModelManager.spec(model);
        db.beginTransaction();
        try {
            Task old = forAudio(audioId, uri);
            if(old!=null&&old.active()&&kind.equals("align"))throw new IllegalStateException("AI処理中です。完了を待つか中止してください。");
            if (old != null && old.active()) { db.setTransactionSuccessful();return old; }
            String id = UUID.randomUUID().toString(); ContentValues v = new ContentValues();
            v.put("id", id); v.put("audio_id", audioId); v.put("source_uri", uri); v.put("model", model); v.put("japanese", japanese ? 1 : 0); v.put("kind", kind);
            v.put("status", "queued"); v.put("message", "開始待ち"); v.put("progress", -1);
            v.put("input_document",input);
            v.put("heartbeat_ms",android.os.SystemClock.elapsedRealtime());
            db.insertWithOnConflict("lyrics_tasks", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful(); return find(id);
        } finally { db.endTransaction(); }
    }
    Lyrics input(String taskId) throws Exception {try(Cursor c=db.query("lyrics_tasks",new String[]{"input_document"},"id=?",new String[]{taskId},null,null,null)){return c.moveToFirst()&&!c.getString(0).isBlank()?Lyrics.parse(c.getString(0)):null;}}
    Lyrics recognition(String audioId,String uri,String model,String hash) throws Exception {
        try(Cursor c=db.query("lyrics_recognition_cache",new String[]{"document"},"audio_id=? AND source_uri=? AND model=? AND source_hash=?",new String[]{audioId,uri,model,hash},null,null,null)){if(c.moveToFirst())return Lyrics.parse(c.getString(0));}
        for(Revision row:revisions(audioId,uri)){Lyrics doc=row.document();if(doc.origin().provider().equals("whisper")&&doc.model().equals(model)&&doc.sourceHash().equals(hash)&&!doc.lines().isEmpty()&&doc.lines().stream().allMatch(l->l.alignment().equals("line-fallback")||l.alignment().equals("whisper-token-estimate")))return doc;}return null;
    }
    void cacheRecognition(Task task,Lyrics doc) throws Exception {if(!doc.origin().provider().equals("whisper"))throw new IllegalArgumentException("音声認識結果のみ保存できます");ContentValues v=new ContentValues();v.put("audio_id",task.audioId());v.put("source_uri",task.uri());v.put("model",doc.model());v.put("source_hash",doc.sourceHash());v.put("document",doc.json());db.insertWithOnConflict("lyrics_recognition_cache",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    Task forAudio(String audioId, String uri) { List<Task> tasks = select("audio_id=? AND source_uri=?", new String[]{audioId, uri}); return tasks.isEmpty() ? null : tasks.get(0); }
    Task find(String id) { List<Task> tasks = select("id=?", new String[]{id}); return tasks.isEmpty() ? null : tasks.get(0); }
    List<Task> queued() { return select("status='queued'", null); }
    List<Task> all() { return select(null, null); }
    boolean modelInUse(String model) { return !select("model=? AND status IN ('queued','running')", new String[]{model}).isEmpty(); }
    boolean update(String id, String status, String message, int progress) {
        ContentValues v = new ContentValues(); v.put("status", status); v.put("message", message); v.put("progress", progress);
        v.put("heartbeat_ms",android.os.SystemClock.elapsedRealtime());
        return db.update("lyrics_tasks", v, "id=? AND status IN ('queued','running')", new String[]{id}) > 0;
    }
    void recover() {
        ContentValues v = new ContentValues(); v.put("status", "failed"); v.put("message", "前回の処理が中断されました。再試行できます。"); v.put("progress", -1);
        db.update("lyrics_tasks", v, "status='running'", null);
    }
    void pulse(){ContentValues values=new ContentValues();values.put("heartbeat_ms",android.os.SystemClock.elapsedRealtime());db.update("lyrics_tasks",values,"status IN ('queued','running')",null);}
    int recoverStalled(){
        long now=android.os.SystemClock.elapsedRealtime();ContentValues values=new ContentValues();values.put("status","failed");values.put("message","処理の応答がなくなりました。メモリ不足やシステムによる終了が考えられます。再試行してください。");values.put("progress",-1);
        return db.update("lyrics_tasks",values,"status IN ('queued','running') AND (heartbeat_ms<? OR heartbeat_ms>?)",new String[]{Long.toString(now-60000),Long.toString(now)});
    }
    boolean save(Task task, Lyrics lyrics) throws Exception {
        String json = lyrics.json(); db.beginTransaction();
        try {
            if (!update(task.id, "complete", lyrics.unmatchedCount()>0?"同期候補を保存しました · 未同期 "+lyrics.unmatchedCount()+"行":"歌詞を保存しました", 100)) return false;
            ContentValues v = new ContentValues(); v.put("audio_id", task.audioId); v.put("source_uri", task.uri); v.put("document", json); v.put("created_ms", System.currentTimeMillis());
            db.insertOrThrow("lyrics_documents", null, v); db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    void saveExternal(String audioId, String uri, Lyrics lyrics) throws Exception {
        String json = lyrics.json(); db.beginTransaction();
        try {
            Task old = forAudio(audioId,uri);
            if (old != null && old.active()) throw new IllegalStateException("AI解析を中止してから歌詞を取り込んでください。");
            ContentValues task = new ContentValues(); task.put("id",UUID.randomUUID().toString()); task.put("audio_id",audioId); task.put("source_uri",uri); task.put("model","small"); task.put("japanese",0); task.put("kind",lyrics.origin().provider()); task.put("status","complete"); task.put("message",lyrics.lines().isEmpty() ? "歌詞を保存しました（未同期）" : "同期歌詞を保存しました"); task.put("progress",100);
            db.insertWithOnConflict("lyrics_tasks",null,task,SQLiteDatabase.CONFLICT_REPLACE);
            ContentValues v = new ContentValues(); v.put("audio_id",audioId); v.put("source_uri",uri); v.put("document",json); v.put("created_ms",System.currentTimeMillis()); db.insertOrThrow("lyrics_documents",null,v);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    Lyrics load(String audioId, String uri) throws Exception {
        try (Cursor c = db.query("lyrics_documents", new String[]{"document"}, "audio_id=? AND source_uri=?", new String[]{audioId, uri}, null, null, "revision DESC", "1")) { return c.moveToFirst() ? Lyrics.parse(c.getString(0)) : null; }
    }
    long revisionId(String audioId,String uri){try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(revision),0) FROM lyrics_documents WHERE audio_id=? AND source_uri=?",new String[]{audioId,uri})){return c.moveToFirst()?c.getLong(0):0;}}
    record Revision(long number,long createdMs,Lyrics document) { }
    List<Revision> revisions(String audioId,String uri) throws Exception {
        List<Revision> rows=new ArrayList<>();try(Cursor c=db.query("lyrics_documents",new String[]{"revision","created_ms","document"},"audio_id=? AND source_uri=?",new String[]{audioId,uri},null,null,"revision DESC","10")){while(c.moveToNext())rows.add(new Revision(c.getLong(0),c.getLong(1),Lyrics.parse(c.getString(2))));}return rows;
    }
    private List<Task> select(String where, String[] args) {
        List<Task> tasks = new ArrayList<>();
        try (Cursor c = db.query("lyrics_tasks", null, where, args, null, null, "rowid ASC")) {
            while (c.moveToNext()) tasks.add(new Task(s(c,"id"), s(c,"audio_id"), s(c,"source_uri"), s(c,"model"), c.getInt(c.getColumnIndexOrThrow("japanese")) != 0, s(c,"kind"), s(c,"status"), s(c,"message"), c.getInt(c.getColumnIndexOrThrow("progress"))));
        }
        return tasks;
    }
    private static String s(Cursor c, String name) { return c.getString(c.getColumnIndexOrThrow(name)); }
}
