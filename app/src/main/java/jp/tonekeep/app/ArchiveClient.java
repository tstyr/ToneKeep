package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ArchiveClient {
    public static final int PAGE_SIZE = 20;
    public record Item(String id, String title, String creator, String license) { }
    public record Page(List<Item> items, int total) { }
    public record Details(List<List<AudioFile>> tracks, String license) { }

    public Page search(String text, int page) throws Exception {
        String q = query(text);
        JSONObject response = get("https://archive.org/advancedsearch.php?q=" + AudioFile.encode(q)
                + "&output=json&rows=" + PAGE_SIZE + "&page=" + page
                + "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl").getJSONObject("response");
        JSONArray docs = response.getJSONArray("docs");
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject doc = docs.getJSONObject(i);
            String license = firstLicense(doc.opt("licenseurl"));
            String id = doc.optString("identifier");
            if (!license.isEmpty() && id.matches("[A-Za-z0-9_.-]+")) {
                items.add(new Item(id, value(doc.opt("title")), value(doc.opt("creator")), license));
            }
        }
        return new Page(items, response.optInt("numFound", 0));
    }

    public Details details(Item item, boolean losslessOnly) throws Exception {
        JSONObject response = get("https://archive.org/metadata/" + AudioFile.encode(item.id));
        JSONObject meta = response.optJSONObject("metadata");
        if (meta == null || meta.optBoolean("is_dark") || response.optBoolean("is_dark")
                || meta.optBoolean("is_access_restricted")) throw new IOException("この音源は現在ダウンロードできません。");
        String license = firstLicense(meta.opt("licenseurl"));
        if (license.isEmpty()) throw new IOException("ダウンロード可能なライセンスを確認できませんでした。");
        JSONArray files = response.optJSONArray("files");
        if (files == null) throw new IOException("音源ファイルが見つかりませんでした。");
        return new Details(AudioFile.tracks(files, losslessOnly), license);
    }

    static String query(String text) {
        // Use literal terms, so user text can't alter the license/mediatype constraints.
        String clean = text.replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim();
        StringBuilder terms = new StringBuilder();
        for (String term : clean.split("\\s+")) {
            if (term.isEmpty()) continue;
            if (terms.length() > 0) terms.append(" AND ");
            terms.append("(title:\"").append(term).append("\" OR creator:\"").append(term)
                    .append("\" OR description:\"").append(term).append("\")");
        }
        if (terms.length() == 0) throw new IllegalArgumentException("曲名やアーティスト名を入力してください。");
        return "mediatype:audio AND licenseurl:(*creativecommons.org*) AND (" + terms + ")";
    }

    static String firstLicense(Object value) {
        if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                String license = firstLicense(array.opt(i));
                if (!license.isEmpty()) return license;
            }
            return "";
        }
        String raw = value instanceof String ? (String) value : "";
        return AudioFile.licenseLabel(raw).isEmpty() ? "" : raw.trim();
    }

    static String value(Object object) {
        if (object instanceof JSONArray a) {
            List<String> values = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) values.add(a.optString(i));
            return String.join(" / ", values);
        }
        return object instanceof String ? (String) object : "";
    }

    private JSONObject get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "ToneKeep/1.0 (Android; music discovery)");
        try {
            int status = connection.getResponseCode();
            if (status == 429) throw new IOException("アクセスが集中しています。少し待って再検索してください。");
            if (status != 200) throw new IOException("配信元への接続に失敗しました（HTTP " + status + "）。");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("検索を中止しました。");
                    if (output.size() + count > 16 * 1024 * 1024) throw new IOException("音源情報が大きすぎます。別の音源を選んでください。");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { connection.disconnect(); }
    }
}
