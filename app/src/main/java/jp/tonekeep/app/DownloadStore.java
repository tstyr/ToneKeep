package jp.tonekeep.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class DownloadStore {
    record Entry(long id, String title, String quality, String url, String filename, String license, String source) { }
    record State(int status, int reason, long received, long total) { }
    final DownloadManager manager;
    final SharedPreferences prefs;
    private final List<Entry> entries = new ArrayList<>();

    DownloadStore(Context context) {
        manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        prefs = context.getSharedPreferences("tonekeep", Context.MODE_PRIVATE);
        try {
            JSONArray array = new JSONArray(prefs.getString("downloads", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                entries.add(new Entry(o.getLong("id"), o.getString("title"), o.getString("quality"), o.getString("url"), o.getString("filename"), o.optString("license"), o.optString("source")));
            }
        } catch (Exception ignored) { }
    }
    List<Entry> all() { return new ArrayList<>(entries); }
    State state(long id) {
        try (Cursor c = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) return new State(
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
        } catch (RuntimeException ignored) { }
        return new State(-1, 0, 0, 0);
    }
    Entry enqueue(ArchiveClient.Item item, AudioFile file, String license) {
        String url = file.url(item.id());
        for (Entry entry : entries) {
            State state = state(entry.id);
            if (entry.url.equals(url) && state.status != DownloadManager.STATUS_FAILED && state.status != -1)
                throw new IllegalStateException("このファイルは保存済み、またはダウンロード中です。");
        }
        return add(file.title, file.quality(), url, file.safeFilename(item.id()), license, "https://archive.org/details/" + item.id());
    }
    Entry retry(Entry entry) {
        // Remove the failed partial file before reusing the destination name.
        manager.remove(entry.id);
        entries.remove(entry);
        persist();
        return add(entry.title, entry.quality, entry.url, entry.filename, entry.license, entry.source);
    }
    private Entry add(String title, String quality, String url, String filename, String license, String source) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle(title).setDescription("ToneKeep • " + quality)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ToneKeep/" + filename)
                .setAllowedOverRoaming(false)
                .setAllowedNetworkTypes(prefs.getBoolean("wifi", false) ? DownloadManager.Request.NETWORK_WIFI
                        : DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
        Entry entry = new Entry(manager.enqueue(request), title, quality, url, filename, license, source);
        entries.add(0, entry);
        persist();
        return entry;
    }
    void cancel(Entry entry) {
        manager.remove(entry.id);
        entries.remove(entry);
        persist();
    }
    private void persist() {
        JSONArray array = new JSONArray();
        try {
            for (Entry e : entries) array.put(new JSONObject().put("id", e.id).put("title", e.title)
                    .put("quality", e.quality).put("url", e.url).put("filename", e.filename)
                    .put("license", e.license).put("source", e.source));
            prefs.edit().putString("downloads", array.toString()).apply();
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
}
