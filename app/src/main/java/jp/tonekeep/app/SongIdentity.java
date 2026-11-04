package jp.tonekeep.app;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

record SongIdentity(String title, String artist) {
    private static final Pattern DECORATION = Pattern.compile("(?i)\\s*[\\[（(【](?:official(?:\\s+(?:music|lyric))?\\s*(?:video|audio|mv)?|music\\s+video|lyric(?:s|\\s+video)?|mv|pv|歌詞(?:付き)?|和訳)[^\\]）)】]*[\\]）)】]");
    static SongIdentity infer(String title, String artist) {
        title = DECORATION.matcher(title == null ? "" : title).replaceAll("").trim();
        artist = artist == null ? "" : artist.replaceFirst("(?i)\\s*(?:-\\s*Topic|VEVO)$", "").trim();
        java.util.regex.Matcher quoted = Pattern.compile("^(.+?)\\s*[「『](.+?)[」』].*$").matcher(title);
        if (quoted.matches()) return new SongIdentity(quoted.group(2).trim(), quoted.group(1).trim());
        String[] parts = title.split("\\s+[-–—]\\s+", 2);
        if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank())
            return new SongIdentity(parts[1].trim(), parts[0].trim());
        return new SongIdentity(title, artist);
    }
    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
