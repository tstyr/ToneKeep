package jp.tonekeep.app;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** LRC times are supplied by the file, never estimated from text lengths. */
final class LrcParser {
    private static final String TIME = "(\\d{1,4}):([0-5]\\d)(?:[.:](\\d{1,3}))?";
    private static final Pattern LINE = Pattern.compile("\\["+TIME+"\\]");
    private static final Pattern WORD = Pattern.compile("<"+TIME+">");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\s*\\[offset:([+-]?\\d+)\\]\\s*$");
    private record Row(long start, String text) { }
    static Lyrics parse(String input, long duration, String hash, Lyrics.Origin origin) throws IOException {
        if (input.length() > 1024*1024) throw new IOException("歌詞ファイルが大きすぎます");
        long offset = 0; Matcher off = OFFSET.matcher(input);
        while (off.find()) { try { offset = Long.parseLong(off.group(1)); } catch (NumberFormatException e) { throw new IOException("offsetが不正です",e); } }
        if (Math.abs(offset) > 3_600_000L || offset == Long.MIN_VALUE) throw new IOException("offsetが大きすぎます");
        List<Row> rows = new ArrayList<>();
        for (String line : input.replace("\ufeff", "").split("\\R")) {
            Matcher m = LINE.matcher(line); List<Long> starts = new ArrayList<>(); int end = 0;
            while (m.find() && line.substring(end,m.start()).isBlank()) { starts.add(time(m)+offset); end = m.end(); }
            if (!starts.isEmpty()) for (long start : starts) rows.add(new Row(start,line.substring(end)));
        }
        if (rows.isEmpty()) throw new IOException("時刻付きLRCが見つかりません。テキスト歌詞として読み込んでください。");
        rows.sort(Comparator.comparingLong(Row::start)); List<Lyrics.Line> lines = new ArrayList<>();
        for (int i=0; i<rows.size(); i++) {
            Row r = rows.get(i); long start = r.start, end = i+1<rows.size() ? Math.min(duration,rows.get(i+1).start) : duration;
            String text = WORD.matcher(r.text).replaceAll("");
            if (text.isBlank() && start>=0 && start<=duration+2500) continue;
            if (start < 0 || start >= duration || end > duration || end <= start) throw new IOException("LRCの時刻が曲の範囲外、または同時刻の行が重複しています");
            if (text.isBlank()) continue; // Blank timestamp ends the preceding lyric, including instrumental gaps.
            List<Lyrics.Word> words = words(r.text,offset,start,end,lines.size());
            lines.add(new Lyrics.Line(text,start,end,null,0,words.isEmpty() ? "lrc-line" : "enhanced-lrc",words));
        }
        if (lines.isEmpty()) throw new IOException("歌詞本文がありません");
        return new Lyrics(1,hash,"external","und",duration,lines,"",origin);
    }
    private static List<Lyrics.Word> words(String raw, long offset, long start, long end, int line) {
        Matcher m = WORD.matcher(raw); List<Lyrics.Word> words = new ArrayList<>();
        long previousTime = -1, previousEnd = start; int previousEndIndex = 0;
        while (m.find()) {
            if (previousTime < 0 && !raw.substring(0,m.start()).isEmpty()) return List.of();
            long next = time(m)+offset;
            if (previousTime >= 0) {
                String text = raw.substring(previousEndIndex,m.start());
                if (!text.isEmpty()) {
                    if (previousTime < previousEnd || next <= previousTime || next > end) return List.of();
                    words.add(new Lyrics.Word(text,previousTime,next,line,null)); previousEnd = next;
                } else if (next < previousTime) return List.of();
            }
            previousTime = next; previousEndIndex = m.end();
        }
        String tail = raw.substring(previousEndIndex);
        if (previousTime >= previousEnd && previousTime >= start && previousTime < end && !tail.isEmpty()) words.add(new Lyrics.Word(tail,previousTime,end,line,null));
        else if (!tail.isEmpty()) return List.of();
        return List.copyOf(words);
    }
    private static long time(Matcher m) { String f = m.group(3); long ms = f == null ? 0 : Long.parseLong((f+"000").substring(0,3)); return Long.parseLong(m.group(1))*60000+Long.parseLong(m.group(2))*1000+ms; }
    static Lyrics plain(String text, long duration, String hash, Lyrics.Origin origin) throws IOException {
        if (text.isBlank() || text.length()>1024*1024) throw new IOException("歌詞本文が空、または大きすぎます");
        return new Lyrics(1,hash,"external","und",duration,List.of(),text,origin);
    }
}
