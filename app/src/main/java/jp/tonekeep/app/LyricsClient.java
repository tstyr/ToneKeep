package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class LyricsClient {
    record Result(long id, String title, String artist, double duration, String lyrics, boolean instrumental) { }
    static List<Result> search(String title, String artist, long duration) throws Exception {
        String query = "track_name=" + encode(title.trim());
        if (!artist.isBlank()) query += "&artist_name=" + encode(artist.trim());
        String response = WebJson.get("https://lrclib.net/api/search?" + query);
        List<Result> matches = candidates(response, title, artist, duration);
        if (matches.isEmpty() && !artist.isBlank()) matches = candidates(WebJson.get("https://lrclib.net/api/search?q=" + encode(artist + " " + title)), title, artist, duration);
        return matches;
    }
    static List<Result> candidates(String response, String title, String artist, long duration) throws Exception {
        JSONArray list = new JSONArray(response); List<Result> matches = new ArrayList<>();
        String wantedTitle = SongIdentity.normalize(title), wantedArtist = SongIdentity.normalize(artist);
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i); if (item == null) continue;
            String candidateTitle = item.optString("trackName"), candidateArtist = item.optString("artistName");
            if (!SongIdentity.normalize(candidateTitle).equals(wantedTitle)) continue;
            String normalizedArtist = SongIdentity.normalize(candidateArtist);
            if (!wantedArtist.isEmpty() && !normalizedArtist.equals(wantedArtist)) continue;
            double seconds = item.optDouble("duration", 0);
            if (duration > 0 && seconds > 0 && Math.abs(seconds - duration) > 20) continue;
            String lyrics = item.optString("plainLyrics", "");
            if (lyrics.equals("null") || lyrics.isBlank()) lyrics = item.optString("syncedLyrics", "").replaceAll("(?m)^\\[\\d+:\\d+(?:\\.\\d+)?]", "");
            if (lyrics.equals("null")) lyrics = "";
            boolean instrumental = item.optBoolean("instrumental");
            if (lyrics.isBlank() && !instrumental) continue;
            matches.add(new Result(item.optLong("id"), candidateTitle, candidateArtist, seconds, lyrics, instrumental));
        }
        matches.sort(Comparator.comparingDouble(result -> duration > 0 && result.duration() > 0 ? Math.abs(result.duration() - duration) : 0));
        return new ArrayList<>(matches.subList(0, Math.min(10, matches.size())));
    }
    private static String encode(String value) throws IOException { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
}
