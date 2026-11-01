package jp.tonekeep.app;

import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Monotonic text matching to acoustic ASR segments. This is not a forced-alignment model. */
final class ReferenceLyricsAlignment {
    static String text(Lyrics doc) { return doc.plainText().isBlank()?doc.lines().stream().map(Lyrics.Line::text).collect(java.util.stream.Collectors.joining("\n")):doc.plainText(); }
    static List<String> rows(Lyrics doc) { List<String> rows=new ArrayList<>();for(String line:text(doc).split("\\R"))if(!line.isBlank())rows.add(line);return rows; }
    private record Step(int refs,int heard,boolean matched) { }
    static Lyrics align(Lyrics reference,Lyrics recognition,BooleanSupplier active) throws Exception {
        if(!reference.sourceHash().equals(recognition.sourceHash()) || Math.abs(reference.durationMs()-recognition.durationMs())>500)throw new IOException("歌詞と解析音源が一致しません。歌詞を読み込み直してください。");
        List<String> ref=rows(reference);List<Lyrics.Line> heard=recognition.lines();int n=ref.size(),m=heard.size();
        if(n==0||m==0||n>500||m>1000||(long)n*m>80000)throw new IOException("同期対象の歌詞が空、または行数が多すぎます");
        String[] a=new String[n],b=new String[m];for(int i=0;i<n;i++)a[i]=normalize(ref.get(i));for(int j=0;j<m;j++)b[j]=normalize(heard.get(j).text());
        double[][] cost=new double[n+1][m+1];Step[][] back=new Step[n+1][m+1];for(double[] r:cost)Arrays.fill(r,Double.POSITIVE_INFINITY);cost[0][0]=0;
        for(int i=0;i<=n;i++)for(int j=0;j<=m;j++) {
            ModelManager.require(active);double current=cost[i][j];if(!Double.isFinite(current))continue;
            if(i<n)relax(cost,back,i+1,j,current+Math.max(4,a[i].length())*.85,new Step(1,0,false));
            if(j<m)relax(cost,back,i,j+1,current+Math.max(4,b[j].length())*.65,new Step(0,1,false));
            for(int x=1;x<=3&&i+x<=n;x++)for(int y=1;y<=3&&j+y<=m;y++) {
                if(x>1&&y>1)continue;
                Lyrics.Line first=heard.get(j),last=heard.get(j+y-1);boolean continuous=true;
                for(int k=j;k<j+y;k++){Lyrics.Line line=heard.get(k);if(line.startMs()<0||line.confidence()!=null&&line.confidence()<.35||k>j&&line.startMs()-heard.get(k-1).endMs()>3500)continuous=false;}
                if(!continuous||last.endMs()-first.startMs()>40000)continue;
                String ra=join(a,i,x),hb=join(b,j,y);if(ra.isEmpty()||hb.isEmpty()||ra.length()>500||hb.length()>500||Math.min(ra.length(),hb.length())/(double)Math.max(ra.length(),hb.length())<.55)continue;
                int distance=distance(ra,hb);double similarity=1-distance/(double)Math.max(ra.length(),hb.length());
                if(similarity<.68||(Math.min(ra.length(),hb.length())<5&&distance!=0))continue;
                relax(cost,back,i+x,j+y,current+distance+(x+y-2)*.7,new Step(x,y,true));
            }
        }
        List<Step> path=new ArrayList<>();int i=n,j=m;
        while(i>0||j>0){Step step=back[i][j];if(step==null)throw new IOException("同期候補を作成できません");path.add(step);i-=step.refs;j-=step.heard;}Collections.reverse(path);
        List<Lyrics.Line> lines=new ArrayList<>();i=0;j=0;int matched=0;
        for(Step step:path){
            if(step.matched){String body=String.join("\n",ref.subList(i,i+step.refs));Lyrics.Line first=heard.get(j),last=heard.get(j+step.heard-1);double similarity=1-distance(join(a,i,step.refs),join(b,j,step.heard))/(double)Math.max(join(a,i,step.refs).length(),join(b,j,step.heard).length());
                lines.add(new Lyrics.Line(body,first.startMs(),last.endMs(),similarity,0,"ai-reference-match",List.of()));matched+=step.refs;
            }else if(step.refs>0)lines.add(new Lyrics.Line(ref.get(i),-1,-1,null,0,"unmatched",List.of()));
            i+=step.refs;j+=step.heard;
        }
        if(matched==0)throw new IOException("歌詞と音声の十分な一致が見つかりませんでした。なぞり同期を利用してください。");
        return new Lyrics(1,reference.sourceHash(),recognition.model(),reference.language(),recognition.durationMs(),lines,text(reference),new Lyrics.Origin(reference.origin().provider(),reference.origin().label()+" · AI同期候補（要確認）",reference.origin().url()));
    }
    private static void relax(double[][] cost,Step[][] back,int i,int j,double value,Step step){if(value<cost[i][j]){cost[i][j]=value;back[i][j]=step;}}
    private static String join(String[] a,int from,int count){StringBuilder s=new StringBuilder();for(int k=from;k<from+count;k++)s.append(a[k]);return s.toString();}
    static String normalize(String text){String s=Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);StringBuilder out=new StringBuilder();s.codePoints().forEach(c->{if(c>=0x30a1&&c<=0x30f6)c-=0x60;if(Character.isLetterOrDigit(c))out.appendCodePoint(c);});return out.toString();}
    static int distance(String a,String b){int[] previous=new int[b.length()+1],next=new int[b.length()+1];for(int j=0;j<previous.length;j++)previous[j]=j;for(int i=1;i<=a.length();i++){next[0]=i;for(int j=1;j<=b.length();j++)next[j]=Math.min(Math.min(next[j-1]+1,previous[j]+1),previous[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));int[] swap=previous;previous=next;next=swap;}return previous[b.length()];}
}
