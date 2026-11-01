package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Versioned document. Confidence is a method-specific estimate, never calibrated timing accuracy. */
record Lyrics(int version, String sourceHash, String model, String language, long durationMs, List<Lyrics.Line> lines,
              String plainText, Lyrics.Origin origin) {
    record Origin(String provider, String label, String url) { }
    Lyrics(int version, String sourceHash, String model, String language, long durationMs, List<Line> lines) {
        this(version,sourceHash,model,language,durationMs,lines,"",new Origin("whisper","AI認識・同期は推定です。単語同期が難しい行は行同期になります。",""));
    }
    record Word(String text, long startMs, long endMs, int line, Double confidence) { }
    record Line(String text, long startMs, long endMs, Double confidence, double noSpeech, String alignment, List<Word> words) {
        Line { words = List.copyOf(words); }
    }
    Lyrics {
        if (version != 1 || durationMs <= 0) throw new IllegalArgumentException("歌詞形式・音源の長さが不正です");
        lines = List.copyOf(lines); long previous = 0;
        plainText = plainText == null ? "" : plainText;
        if (origin == null) throw new IllegalArgumentException("歌詞の取得元が必要です");
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if(l.alignment.equals("unmatched")&&l.startMs==-1&&l.endMs==-1&&l.words.isEmpty())continue;
            if (l.startMs < previous || l.endMs <= l.startMs || l.endMs > durationMs || !probability(l.confidence) || !probability(l.noSpeech)) throw new IllegalArgumentException("歌詞行の時刻・信頼度が不正です");
            long end = l.startMs; StringBuilder joined = new StringBuilder();
            for (Word w : l.words) {
                if (w.line != i || w.startMs < end || w.endMs <= w.startMs || w.endMs > l.endMs || !probability(w.confidence)) throw new IllegalArgumentException("単語の時刻・所属行が不正です");
                end = w.endMs; joined.append(w.text);
            }
            if (!l.words.isEmpty() && !joined.toString().equals(l.text)) throw new IllegalArgumentException("単語と歌詞行の不一致");
            previous = l.endMs;
        }
    }
    private static boolean probability(Double p) { return p == null || (Double.isFinite(p) && p >= 0 && p <= 1); }
    String json() throws org.json.JSONException {
        JSONArray rows = new JSONArray();
        for (Line l : lines) {
            JSONArray words = new JSONArray();
            for (Word w : l.words) words.put(new JSONObject().put("text", w.text).put("startMs", w.startMs).put("endMs", w.endMs).put("line", w.line).put("confidence", w.confidence));
            rows.put(new JSONObject().put("text", l.text).put("startMs", l.startMs).put("endMs", l.endMs).put("confidence", l.confidence).put("noSpeech", l.noSpeech).put("alignment", l.alignment).put("words", words));
        }
        return new JSONObject().put("version", version).put("sourceHash", sourceHash).put("model", model).put("language", language).put("durationMs", durationMs).put("lines", rows)
                .put("plainText",plainText).put("origin",new JSONObject().put("provider",origin.provider).put("label",origin.label).put("url",origin.url)).toString();
    }
    static Lyrics parse(String json) throws org.json.JSONException {
        JSONObject doc = new JSONObject(json); List<Line> lines = new ArrayList<>(); JSONArray rows = doc.getJSONArray("lines");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject l = rows.getJSONObject(i); List<Word> words = new ArrayList<>(); JSONArray a = l.getJSONArray("words");
            for (int j = 0; j < a.length(); j++) { JSONObject w = a.getJSONObject(j); words.add(new Word(w.getString("text"), w.getLong("startMs"), w.getLong("endMs"), w.getInt("line"), confidence(w))); }
            lines.add(new Line(l.getString("text"), l.getLong("startMs"), l.getLong("endMs"), confidence(l), l.getDouble("noSpeech"), l.getString("alignment"), List.copyOf(words)));
        }
        JSONObject source = doc.optJSONObject("origin");
        Origin origin = source == null ? new Origin("whisper","AI認識・同期は推定です。単語同期が難しい行は行同期になります。","")
                : new Origin(source.getString("provider"),source.getString("label"),source.optString("url"));
        return new Lyrics(doc.getInt("version"), doc.getString("sourceHash"), doc.getString("model"), doc.getString("language"), doc.getLong("durationMs"), lines,doc.optString("plainText"),origin);
    }
    private static Double confidence(JSONObject o) throws org.json.JSONException { return o.has("confidence") && !o.isNull("confidence") ? o.getDouble("confidence") : null; }
    String export(String format) {
        if(format.equals("txt")&&!plainText.isBlank())return plainText;
        if(!format.equals("txt")&&unmatchedCount()>0)throw new IllegalStateException("未同期の行があります。なぞり同期で修正してからLRCへ書き出してください。");
        if (lines.isEmpty()) { if (format.equals("txt")) return plainText; throw new IllegalStateException("時刻がない歌詞はLRCへ書き出せません。先に行同期を作成してください。"); }
        StringBuilder out = new StringBuilder();
        for (Line l : lines) {
            if (format.equals("txt")) out.append(l.text);
            else {
                out.append('[').append(stamp(l.startMs)).append(']');
                if (format.equals("elrc") && !l.words.isEmpty()) {
                    for (Word w : l.words) out.append('<').append(stamp(w.startMs)).append('>').append(safe(w.text)).append('<').append(stamp(w.endMs)).append('>');
                } else out.append(safe(l.text));
            }
            out.append('\n');
        }
        return out.toString();
    }
    int unmatchedCount(){return (int)lines.stream().filter(l->l.startMs()<0).count();}
    boolean fullyTimed(){return !lines.isEmpty()&&unmatchedCount()==0;}
    private static String safe(String text) { return text.replace('[', '［').replace(']', '］').replace('<', '＜').replace('>', '＞').replace('\n', ' '); }
    private static String stamp(long ms) { return String.format(Locale.ROOT, "%02d:%02d.%02d", ms / 60000, ms / 1000 % 60, ms / 10 % 100); }
}
