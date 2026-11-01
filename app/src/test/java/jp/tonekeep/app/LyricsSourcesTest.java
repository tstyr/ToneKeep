package jp.tonekeep.app;

import org.json.*;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class LyricsSourcesTest {
    private final Lyrics.Origin local=new Lyrics.Origin("local-file","自作歌詞","");
    @Test public void repeatedLrcStampsOffsetsAndInstrumentalGapArePreserved() throws Exception {
        Lyrics doc=LrcParser.parse("[ar:自作]\n[offset:200]\n[00:01.00][00:03.00]青い空\n[00:02.50]\n[00:04.00]小さな光\n[00:06.00]",6200,"hash",local);
        assertEquals(3,doc.lines().size());assertEquals(1200,doc.lines().get(0).startMs());assertEquals(2700,doc.lines().get(0).endMs());assertEquals(3200,doc.lines().get(1).startMs());assertEquals(6200,doc.lines().get(2).endMs());assertEquals(doc,Lyrics.parse(doc.json()));
    }
    @Test public void enhancedWordEndsAndGapsRoundTrip() throws Exception {
        Lyrics doc=LrcParser.parse("[00:01.00]<00:01.00>青い<00:01.80><00:02.00>空<00:02.40>\n[00:03.00]",4000,"hash",local);
        assertEquals(2,doc.lines().get(0).words().size());assertEquals(1800,doc.lines().get(0).words().get(0).endMs());assertEquals(2000,doc.lines().get(0).words().get(1).startMs());
        Lyrics again=LrcParser.parse(doc.export("elrc"),4000,"hash",local);assertEquals(doc.lines().get(0).words(),again.lines().get(0).words());
    }
    @Test public void invalidLrcDoesNotBecomeInventedSynchronization() {
        for(String text:new String[]{"時刻なしの歌詞","[00:01.00]空\n[00:01.00]光","[01:00.00]空","[offset:-9999]\n[00:01.00]空"})assertThrows(IOException.class,()->LrcParser.parse(text,5000,"hash",local));
    }
    @Test public void unsyncedTextHasNoTimesAndCannotBeExportedAsLrc() throws Exception {
        Lyrics doc=LrcParser.plain("青い空\n小さな光",5000,"hash",local);assertTrue(doc.lines().isEmpty());assertEquals(doc,Lyrics.parse(doc.json()));assertEquals(doc.plainText(),doc.export("txt"));assertThrows(IllegalStateException.class,()->doc.export("lrc"));
    }
    @Test public void searchRanksTheMatchingVersionAndRejectsShortClip() throws Exception {
        JSONArray rows=new JSONArray();for(int duration:new int[]{4,244,30})rows.put(new JSONObject().put("id",duration).put("trackName","自作の歌").put("artistName","自作アーティスト").put("albumName","自作アルバム").put("duration",duration).put("plainLyrics","青い空").put("syncedLyrics",JSONObject.NULL));
        var candidates=LyricsLookup.parse(rows.toString(),244390);assertEquals(244,candidates.get(0).id());assertTrue(candidates.get(0).document(244390,"hash").lines().isEmpty());assertThrows(IOException.class,()->candidates.get(1).document(244390,"hash"));
    }
    @Test public void legacyDocumentsRemainReadable() throws Exception {JSONObject old=new JSONObject(LyricsTest.sample().json());old.remove("plainText");old.remove("origin");assertEquals(LyricsTest.sample(),Lyrics.parse(old.toString()));}
    @Test public void catalogRequiresArtistAndLengthForAutomaticMatch() throws Exception {
        String json=new JSONObject().put("recordings",new JSONArray().put(new JSONObject().put("id","00000000-0000-0000-0000-000000000001").put("title","青い空").put("length",120000).put("score",100).put("artist-credit",new JSONArray().put(new JSONObject().put("name","自作"))))).toString();
        var match=MusicCatalog.parse(json).get(0);assertTrue(match.exact("青い空","自作",121000));assertFalse(match.exact("青い空","",121000));assertFalse(match.exact("青い空","自作",180000));assertFalse(match.exact("別の曲","自作",120000));
    }
    @Test public void retryAfterIsBoundedAndEncodedTitlesStayInTheirParameter() {assertEquals(120,MetadataHttp.retrySeconds("120"));assertEquals(60,MetadataHttp.retrySeconds("unknown"));assertEquals(86400,MetadataHttp.retrySeconds("999999"));assertTrue(MetadataHttp.encode("a&b=日本語").contains("%26"));}
}
