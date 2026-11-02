package jp.tonekeep.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

record TrackMetadata(String audioId,String uri,String title,String artist,String album,long duration,String provider,String url,String artwork,String imageLabel,String status) {
    static final class Store {
        private final SQLiteDatabase db;
        private final SharedPreferences changes;
        Store(Context context) { db=new DownloadStore(context).database();changes=context.getApplicationContext().getSharedPreferences("metadata-changes",Context.MODE_PRIVATE); }
        static void create(SQLiteDatabase db) { db.execSQL("CREATE TABLE track_metadata (audio_id TEXT PRIMARY KEY,content_uri TEXT NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,duration_ms INTEGER NOT NULL,provider TEXT NOT NULL,provider_url TEXT NOT NULL,artwork TEXT NOT NULL,image_label TEXT NOT NULL,status TEXT NOT NULL)"); }
        TrackMetadata find(String id,String uri) { try (Cursor c=db.query("track_metadata",null,"audio_id=? AND content_uri=?",new String[]{id,uri},null,null,null)) {
            if (!c.moveToFirst()) return null;String provider=s(c,"provider");return new TrackMetadata(id,uri,tag(c,"title",provider),tag(c,"artist",provider),tag(c,"album",provider),c.getLong(c.getColumnIndexOrThrow("duration_ms")),provider,s(c,"provider_url"),s(c,"artwork"),s(c,"image_label"),s(c,"status"));
        } }
        TrackMetadata forUri(String uri) { try (Cursor c=db.query("track_metadata",new String[]{"audio_id"},"content_uri=?",new String[]{uri},null,null,null,"1")) { return c.moveToFirst()?find(c.getString(0),uri):null; } }
        void save(TrackMetadata m) { ContentValues v=new ContentValues(); v.put("audio_id",m.audioId);v.put("content_uri",m.uri);v.put("title",m.title);v.put("artist",m.artist);v.put("album",m.album);v.put("duration_ms",m.duration);v.put("provider",m.provider);v.put("provider_url",m.url);v.put("artwork",m.artwork);v.put("image_label",m.imageLabel);v.put("status",m.status);
            synchronized(Store.class){if(db.insertWithOnConflict("track_metadata",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new android.database.sqlite.SQLiteException("曲情報を保存できません");changes.edit().putLong("version",changes.getLong("version",0)+1).apply();}
        }
        // Length sums miss album-only edits and different titles with the same length.
        String snapshot() { return Long.toString(changes.getLong("version",0)); }
        private static String s(Cursor c,String name) { return c.getString(c.getColumnIndexOrThrow(name)); }
        // Older Android JSONObject.optString turned missing source tags into the string "null".
        // Leave the stored row intact and never normalize confirmed catalog/user values.
        private static String tag(Cursor c,String name,String provider){String value=s(c,name);return provider.equals("配信元")&&value.equals("null")?"":value;}
    }
}
