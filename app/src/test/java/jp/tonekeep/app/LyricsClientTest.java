package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LyricsClientTest {
    private JSONObject lyrics(String title, String artist, int duration, String text) throws Exception {
        return new JSONObject().put("id", duration).put("trackName", title).put("artistName", artist).put("duration", duration).put("plainLyrics", text);
    }
    @Test public void songNamesCleanVideoDecorationsButKeepVersionNames() {
        assertEquals(new SongIdentity("Song", "Artist"), SongIdentity.infer("Artist - Song (Official Music Video)", "Label"));
        assertEquals(new SongIdentity("歌", "歌手"), SongIdentity.infer("歌手「歌」Official Video", "Channel"));
        assertEquals("Song (Live)", SongIdentity.infer("Song (Live)", "Artist - Topic").title());
        assertEquals("Artist", SongIdentity.infer("Song", "Artist - Topic").artist());
    }
    @Test public void lyricsRejectWrongArtistsAndVersionsAndRankClosestDuration() throws Exception {
        JSONArray response = new JSONArray().put(lyrics("Song", "Other", 200, "Wrong"))
                .put(lyrics("Song", "Artist", 210, "Owned text"))
                .put(lyrics("Ｓｏｎｇ", "Artist", 200, "Matching owned text"))
                .put(lyrics("Song", "Artist", 260, "Wrong version"));
        var result = LyricsClient.candidates(response.toString(), "Song", "Artist", 200);
        assertEquals(2, result.size()); assertEquals(200, result.get(0).duration(), 0);
        assertEquals("Matching owned text", result.get(0).lyrics());
    }
    @Test public void synchronizedLyricsAndInstrumentalsHandleNullPlainLyrics() throws Exception {
        JSONArray response = new JSONArray().put(lyrics("Song", "Artist", 120, "").put("plainLyrics", JSONObject.NULL).put("syncedLyrics", "[00:01.00]Owned first line\n[00:02.00]Owned second line"));
        assertEquals("Owned first line\nOwned second line", LyricsClient.candidates(response.toString(), "Song", "Artist", 120).get(0).lyrics());
        assertTrue(LyricsClient.candidates(new JSONArray().put(lyrics("Song", "Artist", 120, "").put("instrumental", true)).toString(), "Song", "Artist", 120).get(0).instrumental());
        assertTrue(LyricsClient.candidates("[]", "Song", "Artist", 120).isEmpty());
    }
}
