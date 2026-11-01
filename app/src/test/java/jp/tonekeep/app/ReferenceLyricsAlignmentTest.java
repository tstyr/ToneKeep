package jp.tonekeep.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ReferenceLyricsAlignmentTest {
    private Lyrics reference(String text) throws Exception{return LrcParser.plain(text,10000,"same",new Lyrics.Origin("local-file","自作歌詞",""));}
    private Lyrics.Line heard(String text,long start,long end){return new Lyrics.Line(text,start,end,.9,.1,"line-fallback",List.of());}
    private Lyrics recognized(Lyrics.Line...lines){return new Lyrics(1,"same","small","ja",10000,List.of(lines));}
    @Test public void knownTextSurvivesRecognitionErrorsAndUnrelatedAsrIsSkipped() throws Exception {
        Lyrics reference=reference("青い空、光る星。\n一緒に歩こう。\nさよならまた明日");Lyrics result=ReferenceLyricsAlignment.align(reference,recognized(heard("青い空 光る星",500,2000),heard("一緒に歩こ",2300,4000),heard("謎の雑音",4500,5000),heard("さよならまた明日",7000,9000)),()->true);
        assertEquals(reference.plainText(),result.plainText());assertEquals(reference.plainText(),result.export("txt"));assertTrue(result.fullyTimed());assertEquals(500,result.lines().get(0).startMs());assertEquals("一緒に歩こう。",result.lines().get(1).text());assertEquals("local-file",result.origin().provider());assertEquals(result,Lyrics.parse(result.json()));
    }
    @Test public void missingReferenceRowsRemainVisibleAndBlockLrcExport() throws Exception {
        Lyrics reference=reference("青い空に小さな光\n遠くの海へ舟を出そう\nさよならまた明日");Lyrics result=ReferenceLyricsAlignment.align(reference,recognized(heard("青い空に小さな光",500,2000),heard("さよならまた明日",7000,9000)),()->true);
        assertEquals(1,result.unmatchedCount());assertEquals(-1,result.lines().get(1).startMs());assertEquals("遠くの海へ舟を出そう",result.lines().get(1).text());assertEquals(reference.plainText(),result.export("txt"));assertThrows(IllegalStateException.class,()->result.export("lrc"));assertEquals(result,Lyrics.parse(result.json()));
    }
    @Test public void mergedAsrSegmentNeverGetsProportionalSubLineTimes() throws Exception {
        Lyrics result=ReferenceLyricsAlignment.align(reference("青い空\n小さな光"),recognized(heard("青い空小さな光",500,2500)),()->true);assertEquals(1,result.lines().size());assertEquals("青い空\n小さな光",result.lines().get(0).text());assertEquals(500,result.lines().get(0).startMs());assertEquals(2500,result.lines().get(0).endMs());assertTrue(result.lines().get(0).words().isEmpty());
    }
    @Test public void repeatedChorusesKeepTheirChronologicalPositions() throws Exception {
        Lyrics result=ReferenceLyricsAlignment.align(reference("青い空\n朝日が昇る\n青い空\n月が輝く"),recognized(heard("青い空",0,1000),heard("朝日が昇る",1200,2200),heard("青い空",5000,6000),heard("月が輝く",7000,8000)),()->true);assertEquals(4,result.lines().size());assertEquals(5000,result.lines().get(2).startMs());
    }
    @Test public void cancellationMismatchAndNoMatchLeaveTheInputUnchanged() throws Exception {
        Lyrics ref=reference("青い空小さな光");assertThrows(InterruptedException.class,()->ReferenceLyricsAlignment.align(ref,recognized(heard("青い空小さな光",0,1000)),()->false));assertThrows(java.io.IOException.class,()->ReferenceLyricsAlignment.align(ref,recognized(heard("全く異なる文章",0,1000)),()->true));Lyrics wrong=new Lyrics(1,"different","small","ja",10000,List.of(heard("青い空小さな光",0,1000)));assertThrows(java.io.IOException.class,()->ReferenceLyricsAlignment.align(ref,wrong,()->true));assertTrue(ref.lines().isEmpty());
    }
}
