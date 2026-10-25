package jp.tonekeep.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class DownloadStore {
    private static final String ACTIVE = "status IN ('queued','running','publishing')";
    private static volatile Database instance;
    final SharedPreferences prefs;
    private final SQLiteDatabase db;
    record Entry(String id, String title, String url, String formatId, String quality, String status,
                 String message, int progress, String contentUri, String filename, boolean wifiOnly) {
        boolean active() { return status.equals("queued") || status.equals("running") || status.equals("publishing"); }
    }
    record Batch(String id, String title, int total, int queued, int running, int complete, int failed, int cancelled) {
        String description() { return "全" + total + "曲 • 完了 " + complete + " • 処理中 " + running + " • 待機 " + queued + " • 失敗 " + failed + " • 中止 " + cancelled; }
    }
    record Added(String batchId, int added, int skipped) { }
    DownloadStore(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences("tonekeep", Context.MODE_PRIVATE);
        synchronized (DownloadStore.class) {
            if (instance == null) instance = new Database(app);
            db = instance.getWritableDatabase();
        }
    }
    List<Entry> all() { return select(null, null, "seq DESC", null); }
    List<Entry> recent(int limit) {
        return select(null, null, "CASE WHEN status IN ('running','publishing') THEN 0 WHEN status='queued' THEN 1 ELSE 2 END, CASE WHEN status IN ('queued','running','publishing') THEN seq ELSE -seq END", Integer.toString(limit));
    }
    List<Entry> queued() { return select("status='queued'", null, "seq ASC", null); }
    List<Entry> activeEntries() { return select(ACTIVE, null, "seq ASC", null); }
    Entry find(String id) {
        List<Entry> rows = select("id=?", new String[]{id}, null, "1");
        return rows.isEmpty() ? null : rows.get(0);
    }
    boolean active() { return count(ACTIVE, null) > 0; }
    int count() { return count(null, null); }
    int pending() { return count("status='queued'", null); }
    private int count(String where, String[] args) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM jobs" + (where == null ? "" : " WHERE " + where), args)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
    Entry enqueue(MediaInfo.Track track, String formatId, String quality) {
        String batch = UUID.randomUUID().toString();
        db.beginTransaction();
        try {
            if (count("url=? AND format_id=? AND " + ACTIVE, new String[]{track.url(), formatId}) > 0)
                throw new IllegalStateException("この音源はダウンロード中、または開始待ちです。");
            Entry entry = create(track, formatId, quality, prefs.getBoolean("wifi", false));
            db.insertOrThrow("jobs", null, values(entry, batch, track.title()));
            db.setTransactionSuccessful();
            return entry;
        } finally { db.endTransaction(); }
    }
    Added enqueueBatch(List<MediaInfo.Track> tracks, String title) {
        String batch = UUID.randomUUID().toString();
        int added = 0, skipped = 0;
        Set<String> seen = new HashSet<>();
        boolean wifi = prefs.getBoolean("wifi", false);
        db.beginTransaction();
        try {
            for (MediaInfo.Track track : tracks) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("キューへの追加を中止しました。");
                if (track.live() || !seen.add(track.url()) || count("url=? AND (" + ACTIVE + " OR status='complete')", new String[]{track.url()}) > 0) {
                    skipped++; continue;
                }
                db.insertOrThrow("jobs", null, values(create(track, "", "最高品質・自動選択", wifi), batch, title));
                added++;
            }
            db.setTransactionSuccessful();
            return new Added(batch, added, skipped);
        } finally { db.endTransaction(); }
    }
    Entry retry(Entry entry) { return enqueue(track(entry), entry.formatId(), entry.quality()); }
    List<MediaInfo.Track> failed(String batch) {
        List<MediaInfo.Track> tracks = new ArrayList<>();
        for (Entry e : select("batch_id=? AND status='failed'", new String[]{batch}, "seq ASC", null)) tracks.add(track(e));
        return tracks;
    }
    private MediaInfo.Track track(Entry e) { return new MediaInfo.Track("", e.title, "", e.url, "", 0, false, List.of()); }
    private Entry create(MediaInfo.Track track, String format, String quality, boolean wifi) {
        return new Entry(UUID.randomUUID().toString(), track.title(), track.url(), format, quality, "queued", "開始待ち", 0, "", "", wifi);
    }
    boolean update(String id, String status, String message, int percent, String uri, String filename) {
        ContentValues values = new ContentValues();
        values.put("status", status); values.put("message", message); values.put("progress", percent);
        if (uri != null) values.put("content_uri", uri);
        if (filename != null) values.put("filename", filename);
        return db.update("jobs", values, "id=? AND " + ACTIVE, new String[]{id}) > 0;
    }
    void cancelBatch(String batch) {
        ContentValues values = new ContentValues(); values.put("status", "cancelled"); values.put("message", "一括保存を中止しました");
        db.update("jobs", values, "batch_id=? AND " + ACTIVE, new String[]{batch});
    }
    void failBatch(String batch, String message) {
        ContentValues values = new ContentValues(); values.put("status", "failed"); values.put("message", message);
        db.update("jobs", values, "batch_id=? AND " + ACTIVE, new String[]{batch});
    }
    void recoverInterrupted() {
        ContentValues values = new ContentValues(); values.put("status", "failed");
        values.put("message", "前回の処理が終了しました。再試行してください。");
        values.put("content_uri", ""); values.put("filename", "");
        db.update("jobs", values, "status IN ('running','publishing')", null);
    }
    List<Batch> batches() {
        List<Batch> result = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT batch_id,MAX(batch_title),COUNT(*),SUM(status='queued'),SUM(status IN ('running','publishing')),SUM(status='complete'),SUM(status='failed'),SUM(status='cancelled') FROM jobs GROUP BY batch_id ORDER BY (SUM(status IN ('queued','running','publishing'))>0) DESC,MAX(seq) DESC LIMIT 8", null)) {
            while (c.moveToNext()) result.add(new Batch(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7)));
        }
        return result;
    }
    private List<Entry> select(String where, String[] args, String order, String limit) {
        List<Entry> result = new ArrayList<>();
        try (Cursor c = db.query("jobs", null, where, args, null, null, order, limit)) {
            while (c.moveToNext()) result.add(new Entry(s(c, "id"), s(c, "title"), s(c, "url"), s(c, "format_id"), s(c, "quality"),
                    s(c, "status"), s(c, "message"), c.getInt(c.getColumnIndexOrThrow("progress")), s(c, "content_uri"),
                    s(c, "filename"), c.getInt(c.getColumnIndexOrThrow("wifi")) != 0));
        }
        return result;
    }
    private static String s(Cursor c, String column) { return c.getString(c.getColumnIndexOrThrow(column)); }
    private static ContentValues values(Entry e, String batch, String title) {
        ContentValues v = new ContentValues();
        v.put("id", e.id); v.put("title", e.title); v.put("url", e.url); v.put("format_id", e.formatId);
        v.put("quality", e.quality); v.put("status", e.status); v.put("message", e.message); v.put("progress", e.progress);
        v.put("content_uri", e.contentUri); v.put("filename", e.filename); v.put("wifi", e.wifiOnly ? 1 : 0);
        v.put("batch_id", batch); v.put("batch_title", title);
        return v;
    }
    private static final class Database extends SQLiteOpenHelper {
        private final SharedPreferences previous;
        Database(Context context) { super(context, "downloads.db", null, 1); this.previous = context.getSharedPreferences("tonekeep", Context.MODE_PRIVATE); setWriteAheadLoggingEnabled(true); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE jobs (seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,title TEXT NOT NULL,url TEXT NOT NULL,format_id TEXT NOT NULL,quality TEXT NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,progress INTEGER NOT NULL,content_uri TEXT NOT NULL,filename TEXT NOT NULL,wifi INTEGER NOT NULL,batch_id TEXT NOT NULL,batch_title TEXT NOT NULL)");
            db.execSQL("CREATE INDEX jobs_status ON jobs(status,seq)");
            db.execSQL("CREATE INDEX jobs_source ON jobs(url,format_id,status)");
            db.execSQL("CREATE INDEX jobs_batch ON jobs(batch_id,status)");
            // SQLiteOpenHelper runs onCreate in a transaction: an interrupted import is retried safely.
            String old = previous.getString("ytDownloads", "[]");
            try {
                JSONArray array = new JSONArray(old);
                for (int i = array.length() - 1; i >= 0; i--) {
                    JSONObject o = array.optJSONObject(i); if (o == null) continue;
                    Entry entry = new Entry(o.getString("id"), o.getString("title"), o.getString("url"), o.getString("formatId"),
                            o.getString("quality"), o.getString("status"), o.optString("message"), o.optInt("progress"),
                            o.optString("contentUri"), o.optString("filename"), o.optBoolean("wifiOnly"));
                    db.insertOrThrow("jobs", null, values(entry, "previous-version", "以前の保存履歴"));
                }
            } catch (org.json.JSONException e) { throw new IllegalStateException("以前の履歴を読み込めませんでした。", e); }
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Unsupported database version"); }
    }
}
