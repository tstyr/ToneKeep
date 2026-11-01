package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE,sdk = 29)
public class LyricsAlignmentTest {
    @Test public void segmentationPreservesJapaneseEnglishSpacesAndEmoji() {
        String text = "青い空 Hello 🌙";
        var spans = JapaneseWords.split(text); assertEquals(text,spans.stream().map(JapaneseWords.Span::text).collect(java.util.stream.Collectors.joining()));
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length,spans.get(spans.size()-1).toByte());
    }
    @Test public void matchingAcousticTokensAlignAndOneTokenAcrossWordsFallsBack() throws Exception {
        String text = "青い空"; JSONArray tokens = new JSONArray(); long time = 0;
        for (JapaneseWords.Span s : JapaneseWords.split(text)) { tokens.put(new JSONObject().put("from",s.fromByte()).put("to",s.toByte()).put("start",time).put("end",time+500).put("p",.9)); time += 500; }
        var words = LyricsAlignment.TOKEN_ESTIMATE.align(text,tokens,30000,30000,35000,2);
        assertFalse(words.isEmpty()); assertEquals(30000,words.get(0).startMs()); assertEquals(2,words.get(0).line());
        assertTrue(LyricsAlignment.TOKEN_ESTIMATE.align(text,new JSONArray().put(new JSONObject().put("from",0).put("to",100).put("start",0).put("end",500).put("p",.9)),0,0,5000,0).isEmpty());
    }
}
