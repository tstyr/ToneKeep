package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;

record ReleaseUpdate(String packageName, long versionCode, String versionName, String url, String sha256, long size) {
    static final String API = "https://api.github.com/repos/tstyr/ToneKeep/releases?per_page=20";
    static String trustedUrl(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || !uri.normalize().getPath().startsWith("/tstyr/ToneKeep/releases/download/"))
            throw new IllegalArgumentException("更新ファイルの配布元が一致しません。");
        return uri.toASCIIString();
    }
    static ReleaseUpdate parse(String json) throws Exception {
        JSONObject item = new JSONObject(json);
        String hash = item.getString("sha256").toLowerCase(java.util.Locale.ROOT);
        long code = item.getLong("versionCode"), size = item.getLong("size");
        if (!hash.matches("[a-f0-9]{64}") || code <= 0 || size <= 0 || size > 512L * 1024 * 1024) throw new IllegalArgumentException("更新情報の形式が正しくありません。");
        return new ReleaseUpdate(item.getString("packageName"), code, item.getString("versionName"), trustedUrl(item.getString("url")), hash, size);
    }
    static ReleaseUpdate find(String releases, String packageName, long installedVersion) throws Exception {
        return find(releases, packageName, installedVersion, WebJson::get);
    }
    interface Fetcher { String get(String url) throws Exception; }
    static ReleaseUpdate find(String releases, String packageName, long installedVersion, Fetcher fetcher) throws Exception {
        JSONArray list = new JSONArray(releases); ReleaseUpdate best = null; int fetched = 0;
        for (int i = 0; i < list.length() && fetched < 3; i++) {
            JSONObject release = list.getJSONObject(i); if (release.optBoolean("draft")) continue;
            JSONArray assets = release.optJSONArray("assets"); if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                if (!"update.json".equals(asset.optString("name"))) continue;
                fetched++;
                ReleaseUpdate update = parse(fetcher.get(trustedUrl(asset.getString("browser_download_url"))));
                if (update.packageName().equals(packageName) && update.versionCode() > installedVersion
                        && (best == null || update.versionCode() > best.versionCode())) best = update;
            }
        }
        return best;
    }
}
