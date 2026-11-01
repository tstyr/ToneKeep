package jp.tonekeep.app;

import android.content.Context;
import android.graphics.Color;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.view.MotionEvent;
import android.widget.*;
import java.util.*;
import java.util.function.*;

/** A trace gesture may cross wrapped text and multiple lyric rows. Scrolling is an explicit mode. */
final class LyricsTraceView extends ScrollView {
    private final LinearLayout content;private final List<TextView> rows=new ArrayList<>();
    private final LyricsTraceSession session;private final LongSupplier position;private final BooleanSupplier playing;private final Runnable changed;private final Consumer<String> notice;
    private boolean tracing=true,touching;
    private long lastNotice;
    LyricsTraceView(Context context,LyricsTraceSession session,LongSupplier position,BooleanSupplier playing,Runnable changed,Consumer<String> notice){super(context);this.session=session;this.position=position;this.playing=playing;this.changed=changed;this.notice=notice;content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);addView(content);setContentDescription("歌詞を順になぞって時刻を記録");
        for(String text:session.rows){TextView row=new TextView(context);row.setText(text);row.setTextSize(23);row.setTextColor(Color.WHITE);row.setPadding(dp(12),dp(18),dp(12),dp(18));rows.add(row);content.addView(row);}paint();
    }
    boolean tracing(){return tracing;}
    void tracing(boolean value){finish();tracing=value;setContentDescription(value?"歌詞を順になぞって時刻を記録":"歌詞をスクロール");}
    void finish(){if(touching||session.active()>=0){session.finish(Math.min(session.duration,position.getAsLong()));touching=false;paint();changed.run();}}
    void revealNext(){int next=session.next();if(next<session.units.size()){TextView row=rows.get(session.units.get(next).row());post(()->smoothScrollTo(0,Math.max(0,row.getTop()-getHeight()/3)));}}
    void paint(){
        for(int row=0;row<rows.size();row++){SpannableString text=new SpannableString(session.rows.get(row));for(int i=0;i<session.units.size();i++){LyricsTraceSession.Unit u=session.units.get(i);if(u.row()!=row)continue;int color=session.end(i)>=0?Color.rgb(183,245,207):i==session.active()?Color.rgb(255,217,140):Color.rgb(160,179,168);text.setSpan(new ForegroundColorSpan(color),u.from(),u.to(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}rows.get(row).setText(text);}
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event){return tracing||super.onInterceptTouchEvent(event);}
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!tracing)return super.onTouchEvent(event);int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){getParent().requestDisallowInterceptTouchEvent(true);if(!playing.getAsBoolean()){notice("再生してから歌詞をなぞってください");return true;}touching=true;hit(event);return true;}
        if(action==MotionEvent.ACTION_MOVE&&touching){if(!playing.getAsBoolean()){finish();return true;}hit(event);if(event.getY()>getHeight()-dp(48))smoothScrollBy(0,dp(20));else if(event.getY()<dp(48))smoothScrollBy(0,-dp(20));return true;}
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){finish();getParent().requestDisallowInterceptTouchEvent(false);if(action==MotionEvent.ACTION_UP)performClick();return true;}return true;
    }
    private void hit(MotionEvent event){int rowIndex=-1;float y=event.getY()+getScrollY();for(int i=0;i<rows.size();i++){TextView row=rows.get(i);if(y>=row.getTop()&&y<row.getBottom()){rowIndex=i;break;}}if(rowIndex<0)return;TextView row=rows.get(rowIndex);Layout layout=row.getLayout();if(layout==null)return;float localY=y-row.getTop()-row.getTotalPaddingTop();if(localY<0||localY>layout.getHeight())return;int line=layout.getLineForVertical((int)localY);float x=event.getX()-row.getLeft()-row.getTotalPaddingLeft();if(x<layout.getLineLeft(line)||x>layout.getLineRight(line))return;int offset=layout.getOffsetForHorizontal(line,x);if(offset>layout.getLineStart(line)){float previous=layout.getPrimaryHorizontal(offset-1),caret=layout.getPrimaryHorizontal(offset);if(caret>previous&&x<caret||caret<previous&&x>caret)offset--;}int unit=-1;for(int i=0;i<session.units.size();i++){LyricsTraceSession.Unit u=session.units.get(i);if(u.row()==rowIndex&&offset>=u.from()&&offset<u.to()){unit=i;break;}}if(unit<0||unit==session.active())return;long ms=position.getAsLong();if(session.active()>=0)session.finish(ms);if(!session.begin(unit,ms))notice("次の行・単語を順になぞってください。戻る場合は「1つ戻す」を使います。");paint();changed.run();}
    private void notice(String text){long now=android.os.SystemClock.elapsedRealtime();if(now-lastNotice>900){lastNotice=now;notice.accept(text);}}
    @Override public boolean performClick(){super.performClick();return true;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
