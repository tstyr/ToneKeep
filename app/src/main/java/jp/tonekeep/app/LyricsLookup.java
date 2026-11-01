package jp.tonekeep.app;

import android.content.Context;
import org.json.*;
import java.util.*;

final class LyricsLookup {
    record Candidate(long id,String title,String artist,String album,double seconds,String synced,String plain) {
        boolean matchesDuration(long ms) { return Double.isFinite(seconds) && Math.abs(seconds*1000-ms)<=2500; }
        String description(long ms) { return title+" · "+artist+"\n"+album+" · "+MediaInfo.duration((long)seconds)+" · "+(synced.isBlank() ? "時刻なし" : "行同期あり")+(matchesDuration(ms) ? "" : " · 曲長が異なります"); }
        Lyrics document(long duration,String hash) throws Exception {
            Lyrics.Origin origin=new Lyrics.Origin("lrclib","歌詞提供: LRCLIB","https://lrclib.net/api/get/"+id);
            if (!matchesDuration(duration)) throw new java.io.IOException("歌詞と音源の長さが異なります。同じバージョンを選んでください。");
            return synced.isBlank() ? LrcParser.plain(plain,duration,hash,origin) : LrcParser.parse(synced,duration,hash,origin);
        }
    }
    static List<Candidate> search(Context context,String title,String artist,long duration) throws Exception {
        if (title.isBlank()) throw new IllegalArgumentException("曲名を入力してください");
        String query="track_name="+MetadataHttp.encode(title.trim())+(artist.isBlank() ? "" : "&artist_name="+MetadataHttp.encode(artist.trim()));
        return parse(MetadataHttp.json(context,"https://lrclib.net/api/search?"+query),duration);
    }
    static List<Candidate> parse(String json,long duration) throws JSONException {
        JSONArray array=new JSONArray(json); List<Candidate> results=new ArrayList<>();
        for (int i=0;i<array.length();i++) { JSONObject r=array.getJSONObject(i); if (r.optBoolean("instrumental")) continue;
            String synced=r.isNull("syncedLyrics") ? "" : r.optString("syncedLyrics"), plain=r.isNull("plainLyrics") ? "" : r.optString("plainLyrics");
            if (synced.isBlank() && plain.isBlank()) continue;
            results.add(new Candidate(r.getLong("id"),r.optString("trackName"),r.optString("artistName"),r.optString("albumName"),r.optDouble("duration",0),synced,plain));
        }
        results.sort(Comparator.comparing((Candidate c)->!c.matchesDuration(duration)).thenComparing(c->c.synced.isBlank()));
        return List.copyOf(results.subList(0,Math.min(30,results.size())));
    }
}
