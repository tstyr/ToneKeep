package jp.tonekeep.app;

import org.json.*;
import java.util.*;

/** Gesture times come only from the playback clock. Missing touches never get synthetic times. */
final class LyricsTraceSession {
    record Unit(String text,int row,int from,int to) { }
    final List<String> rows; final List<Unit> units; final boolean words; final long duration;
    private final long[] starts,ends; private int active=-1;
    LyricsTraceSession(Lyrics doc,boolean words){
        rows=List.copyOf(ReferenceLyricsAlignment.rows(doc));this.words=words;duration=doc.durationMs();List<Unit> units=new ArrayList<>();
        for(int row=0;row<rows.size();row++){
            String text=rows.get(row);int first=units.size();
            if(!words){units.add(new Unit(text,row,0,text.length()));continue;}
            int cursor=0;
            for(JapaneseWords.Span span:JapaneseWords.split(text)){
                int to=cursor+span.text().length();boolean lexical=span.text().codePoints().anyMatch(Character::isLetterOrDigit);
                if(lexical){int from=units.size()==first?0:cursor;units.add(new Unit(text.substring(from,to),row,from,to));}
                else if(units.size()>first){Unit last=units.remove(units.size()-1);units.add(new Unit(text.substring(last.from,to),row,last.from,to));}
                cursor=to;
            }
            if(units.size()==first)units.add(new Unit(text,row,0,text.length()));
        }
        this.units=List.copyOf(units);starts=new long[units.size()];ends=new long[units.size()];Arrays.fill(starts,-1);Arrays.fill(ends,-1);
        if(units.isEmpty()||units.size()>10000)throw new IllegalArgumentException("歌詞が空、または長すぎます");
        prefill(doc);
    }
    int next(){for(int i=0;i<ends.length;i++)if(ends[i]<0)return i;return units.size();}
    int active(){return active;}
    long start(int i){return starts[i];}long end(int i){return ends[i];}
    boolean complete(){return next()==units.size();}
    int recorded(){int count=0;for(long end:ends)if(end>=0)count++;return count;}
    void clear(){Arrays.fill(starts,-1);Arrays.fill(ends,-1);active=-1;}
    boolean begin(int index,long ms){if(index!=next()||ms<0||ms>=duration||index>0&&ms<ends[index-1]||ms>=futureStart(index))return false;if(active==index)return true;active=index;starts[index]=ms;return true;}
    boolean finish(long ms){if(active<0)return false;int i=active;active=-1;if(ms<=starts[i]||ms>duration||ms>futureStart(i)){starts[i]=-1;return false;}ends[i]=ms;return true;}
    private long futureStart(int i){for(int j=i+1;j<ends.length;j++)if(ends[j]>=0)return starts[j];return duration;}
    private void prefill(Lyrics doc){int row=0;for(Lyrics.Line line:doc.lines()){
        List<String> parts=new ArrayList<>();for(String part:line.text().split("\\R"))if(!part.isBlank())parts.add(part);if(row+parts.size()>rows.size()||!rows.subList(row,row+parts.size()).equals(parts)){clear();return;}
        if(parts.size()==1&&line.startMs()>=0){List<Integer> ids=new ArrayList<>();for(int i=0;i<units.size();i++)if(units.get(i).row==row)ids.add(i);
            if(!words){starts[ids.get(0)]=line.startMs();ends[ids.get(0)]=line.endMs();}
            else if(ids.size()==line.words().size()){boolean same=true;for(int i=0;i<ids.size();i++)if(!units.get(ids.get(i)).text.equals(line.words().get(i).text()))same=false;if(same)for(int i=0;i<ids.size();i++){starts[ids.get(i)]=line.words().get(i).startMs();ends[ids.get(i)]=line.words().get(i).endMs();}}
        }row+=parts.size();
    }}
    void undo(){if(active>=0){starts[active]=-1;active=-1;return;}int i=next()-1;if(i>=0){starts[i]=-1;ends[i]=-1;}}
    String draft(String fingerprint) throws JSONException {JSONArray a=new JSONArray(),b=new JSONArray();for(long t:starts)a.put(t);for(long t:ends)b.put(t);return new JSONObject().put("version",1).put("fingerprint",fingerprint).put("words",words).put("starts",a).put("ends",b).toString();}
    boolean restore(String json,String fingerprint){
        try{JSONObject d=new JSONObject(json);JSONArray a=d.getJSONArray("starts"),b=d.getJSONArray("ends");if(d.getInt("version")!=1||!d.getString("fingerprint").equals(fingerprint)||d.getBoolean("words")!=words||a.length()!=starts.length||b.length()!=ends.length)return false;
            long previous=0;for(int i=0;i<a.length();i++){long start=a.getLong(i),end=b.getLong(i);if(end<0){if(end!=-1||start< -1) return false;continue;}if(start<previous||end<=start||end>duration)return false;previous=end;}
            clear();for(int i=0;i<a.length();i++)if(b.getLong(i)>=0){starts[i]=a.getLong(i);ends[i]=b.getLong(i);}return true;
        }catch(JSONException e){return false;}
    }
    Lyrics document(Lyrics original){
        if(!complete()||active>=0)throw new IllegalStateException("記録していない歌詞があります");List<Lyrics.Line> lines=new ArrayList<>();
        for(int row=0;row<rows.size();row++){List<Lyrics.Word> timed=new ArrayList<>();long start=-1,end=-1;for(int i=0;i<units.size();i++)if(units.get(i).row==row){if(start<0)start=starts[i];end=ends[i];if(words)timed.add(new Lyrics.Word(units.get(i).text,starts[i],ends[i],row,null));}lines.add(new Lyrics.Line(rows.get(row),start,end,null,0,words?"manual-trace-word":"manual-trace-line",timed));}
        return new Lyrics(1,original.sourceHash(),"manual-trace",original.language(),duration,lines,ReferenceLyricsAlignment.text(original),new Lyrics.Origin(original.origin().provider(),original.origin().label().split(" · ")[0]+" · なぞり同期（"+(words?"単語":"行")+"）",original.origin().url()));
    }
}
