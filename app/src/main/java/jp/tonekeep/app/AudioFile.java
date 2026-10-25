package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Chooses between existing files of the same recording; never transcodes audio. */
public final class AudioFile {
    public final String name, title, format, original;
    public final long size;
    public final int bitrate, sampleRate, bitDepth;
    public final boolean lossless;

    AudioFile(JSONObject file) {
        name = file.optString("name");
        original = file.optString("original", "");
        title = cleanTitle(file.optString("title", ""), name);
        format = extension(name).toUpperCase(Locale.ROOT);
        lossless = format.matches("FLAC|WAV|AIFF|AIF|ALAC|APE");
        size = file.optLong("size", 0);
        bitrate = file.optInt("bitrate", 0);
        sampleRate = file.optInt("samplerate", file.optInt("sample_rate", 0));
        bitDepth = file.optInt("bits_per_sample", file.optInt("bitdepth", 0));
    }

    public String quality() {
        String text = format + (lossless ? " / ロスレス" : "");
        if (bitDepth > 0) text += " / " + bitDepth + "bit";
        if (sampleRate > 0) text += String.format(Locale.JAPAN, " / %.1fkHz", sampleRate / 1000.0);
        if (!lossless && bitrate > 0) text += " / " + bitrate + "kbps";
        return text;
    }

    public String url(String identifier) {
        StringBuilder path = new StringBuilder("https://archive.org/download/").append(encode(identifier));
        for (String part : name.split("/", -1)) path.append('/').append(encode(part));
        return path.toString();
    }

    public String safeFilename(String identifier) {
        String base = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (base.length() > 145) base = base.substring(0, 130) + "." + extension(name);
        // Include the entire source path: albums may contain disc1/song and disc2/song.
        String key = java.util.UUID.nameUUIDFromBytes((identifier + "/" + name).getBytes(StandardCharsets.UTF_8)).toString();
        return key + "-" + base;
    }

    public static List<List<AudioFile>> tracks(JSONArray files, boolean losslessOnly) {
        Map<String, JSONObject> byName = new LinkedHashMap<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f != null) byName.put(f.optString("name"), f);
        }
        Map<String, List<AudioFile>> groups = new LinkedHashMap<>();
        for (JSONObject f : byName.values()) {
            if (f.optBoolean("private") || !extension(f.optString("name")).matches("flac|wav|aiff|aif|alac|ape|mp3|ogg|opus|m4a|aac")) continue;
            AudioFile audio = new AudioFile(f);
            if (losslessOnly && !audio.lossless) continue;
            // Follow IA's explicit derivative lineage; don't merge distinct performances by title.
            String root = audio.name;
            for (int hop = 0; hop < 12; hop++) {
                JSONObject source = byName.get(root);
                String parent = source == null ? "" : source.optString("original");
                if (parent.isEmpty() || parent.equals(root)) break;
                root = parent;
            }
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(audio);
        }
        List<List<AudioFile>> result = new ArrayList<>(groups.values());
        Comparator<AudioFile> bestFirst = Comparator.comparing((AudioFile a) -> a.lossless)
                .thenComparingInt(a -> a.lossless ? a.bitDepth : a.bitrate)
                .thenComparingInt(a -> a.sampleRate)
                .thenComparingInt(a -> a.format.equals("FLAC") ? 1 : 0).reversed();
        for (List<AudioFile> variants : result) variants.sort(bestFirst);
        result.sort(Comparator.comparing(a -> a.get(0).title, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public static String licenseLabel(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) return "";
            if (!("creativecommons.org".equalsIgnoreCase(uri.getHost()) || "www.creativecommons.org".equalsIgnoreCase(uri.getHost()))) return "";
            if (uri.getUserInfo() != null || uri.getPort() != -1) return "";
            String p = uri.getPath().toLowerCase(Locale.ROOT);
            if (p.matches("/publicdomain/zero/1\\.0/?")) return "CC0";
            if (p.matches("/publicdomain/mark/1\\.0/?")) return "Public Domain";
            if (p.matches("/licenses/(by|by-sa|by-nd|by-nc|by-nc-sa|by-nc-nd)/[1-4]\\.0(/[a-z]{2})?/?")) {
                return "CC " + p.split("/")[2].toUpperCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) { }
        return "";
    }

    static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException e) { throw new AssertionError(e); }
    }
    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    static String cleanTitle(String title, String name) {
        if (!title.trim().isEmpty()) return title;
        String base = name.substring(name.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        return (dot > 0 ? base.substring(0, dot) : base).replace('_', ' ');
    }
    public static String sizeLabel(long bytes) {
        if (bytes <= 0) return "容量不明";
        return bytes >= 1_073_741_824L ? String.format(Locale.JAPAN, "%.2f GB", bytes / 1_073_741_824.0)
                : String.format(Locale.JAPAN, "%.1f MB", bytes / 1_048_576.0);
    }
}
