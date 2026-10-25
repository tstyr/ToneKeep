package jp.tonekeep.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class DownloadStore {
    private static final Object LOCK = new Object();
    final SharedPreferences prefs;
    record Entry(String id, String title, String url, String formatId, String quality, String status,
                 String message, int progress, String contentUri, String filename, boolean wifiOnly) {
        boolean active() { return status.equals("queued") || status.equals("running") || status.equals("publishing"); }
    }
    DownloadStore(Context context) { prefs = context.getSharedPreferences("tonekeep", Context.MODE_PRIVATE); }
    List<Entry> all() {
        synchronized (LOCK) {
            List<Entry> result = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(prefs.getString("ytDownloads", "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    result.add(new Entry(o.getString("id"), o.getString("title"), o.getString("url"),
                            o.getString("formatId"), o.getString("quality"), o.getString("status"),
                            o.optString("message"), o.optInt("progress"), o.optString("contentUri"),
                            o.optString("filename"), o.optBoolean("wifiOnly")));
                }
            } catch (Exception ignored) { }
            return result;
        }
    }
    Entry find(String id) { for (Entry e : all()) if (e.id.equals(id)) return e; return null; }
    boolean active() { for (Entry e : all()) if (e.active()) return true; return false; }
    Entry enqueue(MediaInfo.Track track, String formatId, String quality) {
        synchronized (LOCK) {
            List<Entry> entries = all();
            for (Entry e : entries) if (e.active() && e.url.equals(track.url()) && e.formatId.equals(formatId))
                throw new IllegalStateException("この音源はダウンロード中、または開始待ちです。");
            Entry entry = new Entry(UUID.randomUUID().toString(), track.title(), track.url(), formatId, quality,
                    "queued", "開始待ち", 0, "", "", prefs.getBoolean("wifi", false));
            entries.add(0, entry);
            persist(entries);
            return entry;
        }
    }
    Entry retry(Entry entry) {
        return enqueue(new MediaInfo.Track("", entry.title, "", entry.url, "", 0, false, List.of()), entry.formatId, entry.quality);
    }
    boolean update(String id, String status, String message, int percent, String uri, String filename) {
        synchronized (LOCK) {
            List<Entry> entries = all();
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (!e.id.equals(id)) continue;
                if (!e.active()) return false;
                entries.set(i, new Entry(e.id, e.title, e.url, e.formatId, e.quality, status, message,
                        percent, uri == null ? e.contentUri : uri, filename == null ? e.filename : filename, e.wifiOnly));
                persist(entries);
                return true;
            }
            return false;
        }
    }
    void recoverInterrupted() {
        for (Entry entry : all()) if (entry.status.equals("running") || entry.status.equals("publishing"))
            update(entry.id, "failed", "前回の処理が終了しました。再試行してください。", entry.progress, "", "");
    }
    private void persist(List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry e : entries) array.put(new JSONObject().put("id", e.id).put("title", e.title)
                    .put("url", e.url).put("formatId", e.formatId).put("quality", e.quality)
                    .put("status", e.status).put("message", e.message).put("progress", e.progress)
                    .put("contentUri", e.contentUri).put("filename", e.filename).put("wifiOnly", e.wifiOnly));
            if (!prefs.edit().putString("ytDownloads", array.toString()).commit())
                throw new IllegalStateException("履歴を保存できません。端末の空き容量を確認してください。");
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
}
