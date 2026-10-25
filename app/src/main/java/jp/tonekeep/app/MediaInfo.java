package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Pure metadata and input parsing, independent of Android and the native runtime. */
public final class MediaInfo {
    public record Track(String id, String title, String artist, String url, String site, long duration,
                        boolean live, List<Format> formats) { }
    public record Collection(String title, List<Track> tracks, int unavailable, int duplicates) { }
    public record Format(String id, String extension, String codec, double bitrate, long size, int sampleRate) {
        public String label() {
            String text = extension.toUpperCase(Locale.ROOT) + " / " + codec;
            if (bitrate > 0) text += String.format(Locale.JAPAN, " / 約%.0fkbps", bitrate);
            if (sampleRate > 0) text += String.format(Locale.JAPAN, " / %.1fkHz", sampleRate / 1000.0);
            return text + " / " + (size > 0 ? "約" : "") + AudioFile.sizeLabel(size);
        }
    }
    public static String webUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null) return uri.toASCIIString();
        } catch (IllegalArgumentException ignored) { }
        throw new IllegalArgumentException("http:// または https:// で始まる音源ページのURLを入力してください。");
    }
    public static String target(String text, boolean soundcloud) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("曲名・アーティスト名またはURLを入力してください。");
        if (trimmed.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) return webUrl(trimmed);
        return (soundcloud ? "scsearch12:" : "ytsearch12:") + trimmed;
    }
    public static String downloadPage(String text) {
        String url = webUrl(text);
        String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        if (host.equals("spotify.com") || host.endsWith(".spotify.com") || host.equals("spotify.link"))
            throw new IllegalArgumentException("Spotify音源の直接保存には非対応です。Spotifyの曲一覧を別配信元で探す場合も、取得する音源はSpotify版とは異なります。");
        return url;
    }
    public static Collection parseCollection(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        LinkedHashMap<String, Track> tracks = new LinkedHashMap<>();
        int[] skipped = {0, 0};
        collect(root, tracks, skipped, 0);
        return new Collection(root.optString("title", "プレイリスト"), new ArrayList<>(tracks.values()), skipped[0], skipped[1]);
    }
    private static void collect(JSONObject item, LinkedHashMap<String, Track> tracks, int[] skipped, int depth) {
        if (depth > 32) { skipped[0]++; return; }
        JSONArray entries = item.optJSONArray("entries");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject child = entries.optJSONObject(i);
                if (child == null) skipped[0]++; else collect(child, tracks, skipped, depth + 1);
            }
            return;
        }
        String availability = item.optString("availability");
        if (availability.matches("private|premium_only|subscriber_only|needs_auth")
                || item.optString("title").matches("\\[(Private|Deleted) video\\]")) { skipped[0]++; return; }
        try {
            Track track = parseTrack(item);
            if (track.live()) { skipped[0]++; return; }
            downloadPage(track.url());
            if (tracks.putIfAbsent(track.url(), track) != null) skipped[1]++;
        } catch (IllegalArgumentException e) { skipped[0]++; }
    }
    public static List<Track> parseSearch(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray entries = root.optJSONArray("entries");
        List<Track> result = new ArrayList<>();
        if (entries == null) result.add(parseTrack(root));
        else for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item != null) {
                try { result.add(parseTrack(item)); } catch (IllegalArgumentException ignored) { }
            }
        }
        return result;
    }
    public static Track parseTrack(JSONObject object) {
        String id = object.optString("id");
        String url = object.optString("webpage_url", "");
        if (url.isEmpty()) url = object.optString("original_url", "");
        if (!url.startsWith("http")) url = object.optString("url", "");
        String site = object.optString("extractor_key", object.optString("ie_key", object.optString("extractor", "Web")));
        if (!url.startsWith("http") && site.toLowerCase(Locale.ROOT).contains("youtube") && id.matches("[A-Za-z0-9_-]{11}"))
            url = "https://www.youtube.com/watch?v=" + id;
        url = webUrl(url);
        String artist = object.optString("artist", object.optString("uploader", object.optString("channel", "")));
        return new Track(id, object.optString("title", id), artist, url, site, object.optLong("duration", 0),
                object.optBoolean("is_live"), formats(object.optJSONArray("formats")));
    }
    public static List<Format> formats(JSONArray array) {
        List<Format> result = new ArrayList<>();
        if (array == null) return result;
        // yt-dlp supplies formats from worst to best; preserve its extractor-aware ordering.
        for (int i = 0; i < array.length(); i++) {
            JSONObject f = array.optJSONObject(i);
            if (f == null || f.optBoolean("has_drm") || !"none".equals(f.optString("vcodec"))) continue;
            String codec = f.optString("acodec", "none");
            String id = f.optString("format_id");
            if (codec.equals("none") || !id.matches("[A-Za-z0-9_.-]{1,120}")) continue;
            result.add(new Format(id, f.optString("ext", "audio"), codec, f.optDouble("abr", 0),
                    f.optLong("filesize", f.optLong("filesize_approx", 0)), f.optInt("asr", 0)));
        }
        Collections.reverse(result);
        return result;
    }
    public static String duration(long seconds) {
        return seconds <= 0 ? "長さ不明" : String.format(Locale.JAPAN, "%d:%02d", seconds / 60, seconds % 60);
    }
    public static String filename(String title, String id, String ext) {
        String name = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) name = "audio";
        if (name.codePointCount(0, name.length()) > 56) name = name.substring(0, name.offsetByCodePoints(0, 56));
        return name + "-" + id.substring(0, 8) + "." + (ext.matches("[A-Za-z0-9]{1,10}") ? ext : "audio");
    }
}
