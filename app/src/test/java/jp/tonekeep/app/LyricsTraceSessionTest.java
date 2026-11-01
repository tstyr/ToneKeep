package jp.tonekeep.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=29)
public class LyricsTraceSessionTest {
    private Lyrics doc() throws Exception{return LrcParser.plain("青い空\n小さな光",6000,"own",new Lyrics.Origin("local-file","自作歌詞",""));}
    @Test public void continuousLineTraceAndUndoUseOnlyRecordedClockTimes() throws Exception {
        LyricsTraceSession s=new LyricsTraceSession(doc(),false);assertFalse(s.begin(1,500));assertTrue(s.begin(0,500));assertFalse(s.finish(500));assertEquals(0,s.recorded());assertTrue(s.begin(0,600));assertTrue(s.finish(1800));assertTrue(s.begin(1,2300));assertTrue(s.finish(4000));assertTrue(s.complete());Lyrics result=s.document(doc());assertEquals(600,result.lines().get(0).startMs());assertEquals(1800,result.lines().get(0).endMs());assertEquals(2300,result.lines().get(1).startMs());assertEquals(doc().plainText(),result.export("txt"));s.undo();assertFalse(s.complete());assertEquals(1,s.next());assertFalse(s.begin(1,1000));
    }
    @Test public void wordTracePreservesJapaneseSpacingAndPunctuation() throws Exception {
        Lyrics doc=LrcParser.plain("「青い空」、 Hello 🌙",6000,"own",new Lyrics.Origin("local-file","自作歌詞",""));LyricsTraceSession s=new LyricsTraceSession(doc,true);assertTrue(s.units.size()>1);long ms=100;for(int i=0;i<s.units.size();i++){assertTrue(s.begin(i,ms));assertTrue(s.finish(ms+200));ms+=300;}Lyrics result=s.document(doc);assertEquals(doc.plainText(),result.lines().get(0).text());assertEquals(doc.plainText(),result.lines().get(0).words().stream().map(Lyrics.Word::text).collect(java.util.stream.Collectors.joining()));assertTrue(result.export("elrc").contains("<00:00.10>"));
    }
    @Test public void draftsResumeAndRejectDifferentSourcesOrInvalidTimes() throws Exception {
        LyricsTraceSession s=new LyricsTraceSession(doc(),false);s.begin(0,500);s.finish(1500);s.begin(1,2000);String json=s.draft("fingerprint");LyricsTraceSession restored=new LyricsTraceSession(doc(),false);assertTrue(restored.restore(json,"fingerprint"));assertEquals(1,restored.next());assertEquals(-1,restored.start(1));assertFalse(restored.restore(json,"different"));
        org.json.JSONObject invalid=new org.json.JSONObject(json);invalid.getJSONArray("ends").put(0,8000);assertFalse(restored.restore(invalid.toString(),"fingerprint"));assertEquals(1500,restored.end(0));
    }
    @Test public void existingAiTimesCanBeKeptWhileMissingRowIsTraced() throws Exception {
        Lyrics base=new Lyrics(1,"own","small","ja",6000,java.util.List.of(new Lyrics.Line("青い空",-1,-1,null,0,"unmatched",java.util.List.of()),new Lyrics.Line("小さな光",3000,5000,.9,0,"ai-reference-match",java.util.List.of())),doc().plainText(),doc().origin());LyricsTraceSession s=new LyricsTraceSession(base,false);assertEquals(1,s.recorded());assertFalse(s.begin(0,3200));assertTrue(s.begin(0,500));assertFalse(s.finish(3500));assertTrue(s.begin(0,500));assertTrue(s.finish(2000));assertTrue(s.complete());assertEquals(3000,s.document(base).lines().get(1).startMs());
    }
}
