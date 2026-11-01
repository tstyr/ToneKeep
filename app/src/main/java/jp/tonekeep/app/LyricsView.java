package jp.tonekeep.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** All highlighting follows MediaPlayer's clock, including pauses and backward seeks. */
final class LyricsView extends ScrollView {
    private static final int MINT = Color.rgb(183,245,207), MUTED = Color.rgb(130,151,140), WHITE = Color.rgb(240,244,237);
    private final LinearLayout content;
    private final List<TextView> rows = new ArrayList<>();
    private Lyrics lyrics;
    private int active = -2;
    LyricsView(Context context) {
        super(context); content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL); addView(content);
        setContentDescription("同期歌詞。行をタップすると再生位置を移動");
    }
    void document(Lyrics value, IntConsumer seek) {
        lyrics = value; active = -2; content.removeAllViews(); rows.clear();
        if (value == null) { TextView hint = new TextView(getContext()); hint.setText("歌詞はまだ保存されていません。「歌詞を探す」から取得できます。"); hint.setTextColor(MUTED); content.addView(hint); return; }
        TextView hint = new TextView(getContext()); hint.setText(value.origin().label() + (value.lines().isEmpty() ? " · 未同期" : value.unmatchedCount()>0?" · 未同期 "+value.unmatchedCount()+"行":"")); hint.setTextSize(11); hint.setTextColor(MUTED); content.addView(hint);
        if (value.lines().isEmpty()) { TextView text = new TextView(getContext()); text.setText(value.plainText()); text.setTextColor(WHITE); text.setTextSize(20); text.setPadding(dp(12),dp(18),dp(12),dp(18)); content.addView(text); return; }
        for (Lyrics.Line line : value.lines()) {
            TextView row = new TextView(getContext()); row.setText(line.text()); row.setTextSize(22); row.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            row.setPadding(dp(12),dp(18),dp(12),dp(18)); row.setMinHeight(dp(48)); row.setTextColor(line.startMs()<0?Color.rgb(240,185,130):MUTED);if(line.startMs()>=0)row.setOnClickListener(v -> seek.accept((int)line.startMs()));
            row.setContentDescription(line.text() + (line.startMs()<0?" · 未同期":" · " + AudioPlayer.clock((int)line.startMs()) + "へ移動")); rows.add(row); content.addView(row);
        }
    }
    void position(long ms) {
        if (lyrics == null || getVisibility() != VISIBLE) return;
        int next = -1;
        for (int i = 0; i < lyrics.lines().size(); i++) { Lyrics.Line l = lyrics.lines().get(i); if (ms >= l.startMs() && ms < l.endMs()) { next = i; break; } }
        if (active != next) {
            for (int i = 0; i < rows.size(); i++) { rows.get(i).setText(lyrics.lines().get(i).text()); rows.get(i).setTextColor(lyrics.lines().get(i).startMs()<0?Color.rgb(240,185,130):i == next ? WHITE : MUTED); }
            active = next;
            if (next >= 0) { TextView row = rows.get(next); post(() -> smoothScrollTo(0,Math.max(0,row.getTop() - getHeight()/3))); }
        }
        if (next < 0) return;
        Lyrics.Line line = lyrics.lines().get(next); SpannableString text = new SpannableString(line.text());
        if (line.words().isEmpty()) text.setSpan(new ForegroundColorSpan(MINT),0,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        else {
            int offset = 0;
            for (Lyrics.Word word : line.words()) { int end = offset + word.text().length(); text.setSpan(new ForegroundColorSpan(ms >= word.startMs() ? MINT : WHITE),offset,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); offset = end; }
        }
        rows.get(next).setText(text);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
