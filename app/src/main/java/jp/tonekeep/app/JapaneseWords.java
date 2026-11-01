package jp.tonekeep.app;

import android.icu.text.BreakIterator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Linguistic segmentation is independent of the acoustic timestamp estimator. */
final class JapaneseWords {
    record Span(String text, int fromByte, int toByte) { }
    static List<Span> split(String text) {
        BreakIterator iterator = BreakIterator.getWordInstance(Locale.JAPANESE); iterator.setText(text);
        List<Span> result = new ArrayList<>(); int from = iterator.first();
        for (int to = iterator.next(); to != BreakIterator.DONE; from = to, to = iterator.next())
            result.add(new Span(text.substring(from, to), bytes(text.substring(0, from)), bytes(text.substring(0, to))));
        return result;
    }
    private static int bytes(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }
}
