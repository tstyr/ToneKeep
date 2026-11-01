package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class LyricsAlignment {
    interface Aligner { List<Lyrics.Word> align(String text, JSONArray tokens, long offset, long start, long end, int line) throws Exception; }
    // No fabricated proportional timing: words need matching UTF-8 token boundaries and valid acoustic times.
    static final Aligner TOKEN_ESTIMATE = (text, tokens, offset, start, end, line) -> {
        List<Lyrics.Word> words = new ArrayList<>(); long previous = start;
        for (JapaneseWords.Span span : JapaneseWords.split(text)) {
            long a = -1, b = -1; double confidence = 0; int count = 0, covered = span.fromByte();
            for (int t = 0; t < tokens.length(); t++) {
                JSONObject token = tokens.getJSONObject(t); int from = token.getInt("from"), to = token.getInt("to");
                if (to <= span.fromByte() || from >= span.toByte()) continue;
                if (from != covered || to > span.toByte()) return List.of();
                long ta = offset + token.getLong("start"), tb = offset + token.getLong("end");
                double p = token.getDouble("p");
                if (token.getLong("start") < 0 || tb <= ta || ta < previous || tb > end || !Double.isFinite(p) || p < .45 || p > 1) return List.of();
                if (a < 0) a = ta; b = tb; previous = tb; covered = to; confidence += p; count++;
            }
            if (a < start || covered != span.toByte() || count == 0) return List.of();
            words.add(new Lyrics.Word(span.text(), a, b, line, confidence / count));
        }
        return List.copyOf(words);
    };
    static void append(List<Lyrics.Line> lines, JSONArray segments, long offset, long chunkEnd, Aligner aligner) throws Exception {
        long previous = lines.isEmpty() ? 0 : lines.get(lines.size() - 1).endMs();
        for (int i = 0; i < segments.length(); i++) {
            JSONObject s = segments.getJSONObject(i); String text = s.getString("text");
            long start = Math.max(previous, offset + s.getLong("start")), end = Math.min(chunkEnd, offset + s.getLong("end"));
            double noSpeech = s.getDouble("noSpeech"); JSONArray tokens = s.getJSONArray("tokens"); double sum = 0;
            for (int t = 0; t < tokens.length(); t++) sum += tokens.getJSONObject(t).getDouble("p");
            double confidence = tokens.length() == 0 ? 0 : sum / tokens.length();
            // Conservative silence/low confidence filter. Repeated lyrics at distinct times are retained.
            if (text.isBlank() || end <= start || !Double.isFinite(noSpeech) || noSpeech < 0 || noSpeech > 1 || noSpeech > .6 || !Double.isFinite(confidence) || confidence < .2 || confidence > 1) continue;
            List<Lyrics.Word> words = aligner.align(text, tokens, offset, start, end, lines.size());
            lines.add(new Lyrics.Line(text, start, end, confidence, noSpeech, words.isEmpty() ? "line-fallback" : "whisper-token-estimate", words)); previous = end;
        }
    }
}
