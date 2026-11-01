package jp.tonekeep.app;

import android.graphics.Color;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE,sdk = 29)
public class LyricsViewTest {
    @Test public void plainLyricsAreMarkedUnsyncedAndHaveNoSeekAction() throws Exception {
        LyricsView view=new LyricsView(RuntimeEnvironment.getApplication());AtomicInteger seek=new AtomicInteger(-1);
        view.document(LrcParser.plain("青い空\n小さな光",5000,"hash",new Lyrics.Origin("lrclib","歌詞提供: LRCLIB","https://lrclib.net/api/get/42")),seek::set);
        LinearLayout content=(LinearLayout)view.getChildAt(0);assertTrue(((TextView)content.getChildAt(0)).getText().toString().contains("未同期"));view.position(2000);content.getChildAt(1).performClick();assertEquals(-1,seek.get());assertEquals("青い空\n小さな光",((TextView)content.getChildAt(1)).getText().toString());
    }
    @Test public void wordsFollowPlaybackPositionAndBackwardSeekAndLineTapSeeks() {
        LyricsView view = new LyricsView(RuntimeEnvironment.getApplication()); AtomicInteger seek = new AtomicInteger(-1); view.document(LyricsTest.sample(),seek::set);
        TextView row = (TextView)((LinearLayout)view.getChildAt(0)).getChildAt(1);
        view.position(500); Spanned first = (Spanned)row.getText(); ForegroundColorSpan[] spans = first.getSpans(0,first.length(),ForegroundColorSpan.class);
        assertEquals(2,spans.length); assertEquals(Color.rgb(183,245,207),spans[0].getForegroundColor()); assertEquals(Color.rgb(240,244,237),spans[1].getForegroundColor());
        view.position(1500); Spanned second = (Spanned)row.getText(); assertEquals(Color.rgb(183,245,207),second.getSpans(2,3,ForegroundColorSpan.class)[0].getForegroundColor());
        view.position(500); Spanned backward = (Spanned)row.getText(); assertEquals(Color.rgb(240,244,237),backward.getSpans(2,3,ForegroundColorSpan.class)[0].getForegroundColor());
        row.performClick(); assertEquals(100,seek.get());
    }
}
