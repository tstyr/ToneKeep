package jp.tonekeep.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.*;

final class MusicCatalog {
    record Match(String id,String title,String artist,String album,long duration,String release,int score) {
        String description() { return title+" · "+artist+"\n"+album+" · "+MediaInfo.duration(duration/1000)+" · MusicBrainz"; }
        boolean exact(String t,String a,long length) { return score>=95 && !a.isBlank() && normal(title).equals(normal(t)) && normal(artist).equals(normal(a)) && duration>0 && length>0 && Math.abs(duration-length)<=3000; }
    }
    static List<Match> search(Context context,String title,String artist) throws Exception {
        if(title.isBlank()) throw new IllegalArgumentException("曲名を入力してください");
        String query="recording:\""+escape(title.trim())+"\""+(artist.isBlank()?"":" AND artist:\""+escape(artist.trim())+"\"");
        return parse(MetadataHttp.json(context,"https://musicbrainz.org/ws/2/recording/?query="+MetadataHttp.encode(query)+"&fmt=json&limit=12"));
    }
    static List<Match> parse(String json) throws JSONException {
        JSONArray records=new JSONObject(json).optJSONArray("recordings"); List<Match> results=new ArrayList<>(); if(records==null)return results;
        for(int i=0;i<records.length();i++) {
            JSONObject r=records.getJSONObject(i); String id=r.optString("id"); if(!uuid(id))continue;
            StringBuilder artist=new StringBuilder(); JSONArray credits=r.optJSONArray("artist-credit");
            if(credits!=null)for(int j=0;j<credits.length();j++){JSONObject c=credits.optJSONObject(j);if(c!=null)artist.append(c.optString("name",c.optJSONObject("artist")==null?"":c.getJSONObject("artist").optString("name"))).append(c.optString("joinphrase"));}
            JSONObject release=null;JSONArray releases=r.optJSONArray("releases");
            if(releases!=null)for(int j=0;j<releases.length();j++){JSONObject candidate=releases.getJSONObject(j);if(uuid(candidate.optString("id")) && (release==null || (!"Official".equals(release.optString("status")) && "Official".equals(candidate.optString("status")))))release=candidate;}
            results.add(new Match(id,r.optString("title"),artist.toString(),release==null?"":release.optString("title"),r.optLong("length",0),release==null?"":release.optString("id"),r.optInt("score",0)));
        }
        return results;
    }
    static TrackMetadata apply(Context context,DownloadStore.Entry entry,Match match) {
        TrackMetadata.Store store=new TrackMetadata.Store(context);TrackMetadata old=store.find(entry.id(),entry.contentUri());String art="",image="",status="曲情報を保存しました";
        if(old!=null && old.album().equals(match.album)) { art=old.artwork();image=old.imageLabel(); }
        if(!match.release.isBlank())try {
            String front="https://coverartarchive.org/release/"+match.release+"/front-";
            try{art=artwork(context,front+"1200");}catch(Exception unavailable){if(Thread.currentThread().isInterrupted())throw unavailable;art=artwork(context,front+"500");}
            image="Cover Art Archive";
        } catch(Exception e){status=art.isBlank()?"曲情報取得済み · アルバム画像は未取得":"曲情報取得済み · 保存済みの画像を利用";}
        TrackMetadata m=new TrackMetadata(entry.id(),entry.contentUri(),match.title,match.artist,match.album,match.duration,"MusicBrainz","https://musicbrainz.org/recording/"+match.id,art,image,status);store.save(m);return m;
    }
    static String artwork(Context context,String url) throws Exception {
        byte[] bytes=MetadataHttp.get(context,url,4*1024*1024,"image/*");BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(options.outWidth<=0||options.outHeight<=0||options.outWidth>20000||options.outHeight>20000)throw new IOException("画像を読み込めません");
        options.inSampleSize=1;while(options.outWidth/options.inSampleSize>1280 || options.outHeight/options.inSampleSize>1280)options.inSampleSize*=2;options.inJustDecodeBounds=false;
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)throw new IOException("画像形式に対応していません");
        File dir=new File(context.getNoBackupFilesDir(),"catalog-art");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("画像保存先を作成できません");File file=new File(dir,UUID.randomUUID()+".jpg");
        try(OutputStream out=new FileOutputStream(file)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,88,out))throw new IOException("画像を保存できません");}finally{bitmap.recycle();}
        return file.getAbsolutePath();
    }
    static boolean uuid(String s){return s.matches("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}");}
    private static String escape(String s){return s.replace("\\","\\\\").replace("\"","\\\"");}
    private static String normal(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
}
