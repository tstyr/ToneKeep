package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class LyricsTest {
    static Lyrics sample() {
        return new Lyrics(1,"sha256","small","ja",4000,List.of(new Lyrics.Line("青い空",100,2200,.8,.1,"whisper-token-estimate",List.of(new Lyrics.Word("青い",100,900,0,.9),new Lyrics.Word("空",1100,2200,0,.7)))));
    }
    @Test public void japaneseJsonRoundTripsWithEndTimesAndDiagnostics() throws Exception { Lyrics l = sample(); assertEquals(l,Lyrics.parse(l.json())); assertEquals(2200,l.lines().get(0).words().get(1).endMs()); }
    @Test public void enhancedLrcKeepsStartsEndsAndGaps() { assertEquals("[00:00.10]<00:00.10>青い<00:00.90><00:01.10>空<00:02.20>\n",sample().export("elrc")); assertEquals("[00:00.10]青い空\n",sample().export("lrc")); assertEquals("青い空\n",sample().export("txt")); }
    @Test public void invalidTimesAndWrongLineAreRejected() {
        for (Lyrics.Word word : List.of(new Lyrics.Word("青い空",0,100,0,.5),new Lyrics.Word("青い空",100,5000,0,.5),new Lyrics.Word("青い空",100,100,0,.5),new Lyrics.Word("青い空",100,1000,1,.5))) {
            assertThrows(IllegalArgumentException.class,() -> new Lyrics(1,"x","small","ja",4000,List.of(new Lyrics.Line("青い空",100,2200,.8,.1,"estimate",List.of(word)))));
        }
    }
    @Test public void lineFallbackEnhancedExportDoesNotInventWordTimes() { Lyrics l = new Lyrics(1,"x","small","ja",2000,List.of(new Lyrics.Line("こんにちは",0,1000,.4,.1,"line-fallback",List.of()))); assertEquals("[00:00.00]こんにちは\n",l.export("elrc")); }
    @Test public void offsetAndSilenceFilterKeepRepeatedLyricsAtSeparateTimes() throws Exception {
        JSONArray tokens = new JSONArray().put(new JSONObject().put("from",0).put("to",3).put("start",0).put("end",800).put("p",.8));
        JSONObject segment = new JSONObject().put("text","空").put("start",0).put("end",1000).put("noSpeech",.1).put("tokens",tokens);
        List<Lyrics.Line> lines = new ArrayList<>();
        LyricsAlignment.Aligner fallback = (a,b,c,d,e,f) -> List.of();
        LyricsAlignment.append(lines,new JSONArray().put(segment),0,30000,fallback);
        LyricsAlignment.append(lines,new JSONArray().put(segment),30000,60000,fallback);
        assertEquals(2,lines.size()); assertEquals(30000,lines.get(1).startMs());
        segment.put("noSpeech",.95); LyricsAlignment.append(lines,new JSONArray().put(segment),60000,90000,fallback); assertEquals(2,lines.size());
    }
    @Test public void modelRangeAndCancellationAreStrict() throws Exception {
        assertTrue(ModelManager.validRange("bytes 100-999/1000",100,1000)); assertFalse(ModelManager.validRange("bytes 99-999/1000",100,1000)); assertFalse(ModelManager.validRange(null,100,1000));
        assertThrows(InterruptedException.class,() -> ModelManager.require(() -> false));
        File f = File.createTempFile("lyrics-hash",".tmp"); try { Files.write(f.toPath(),"abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)); assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",ModelManager.hash(f,() -> true)); } finally { f.delete(); }
    }
    @Test public void malformedPcmIsRejected() throws Exception {
        File f = File.createTempFile("lyrics-pcm",".wav"); try { Files.write(f.toPath(),new byte[44]); assertThrows(java.io.IOException.class,() -> new LyricsEngine.PcmReader(f)); } finally { f.delete(); }
    }
}
