package jp.tonekeep.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Best-effort enrichment after audio publication. A metadata failure never fails a saved song. */
final class MetadataEnricher {
    static void afterDownload(Context context,DownloadStore.Entry original,File staging) {
        DownloadStore downloads=new DownloadStore(context);DownloadStore.Entry entry=downloads.find(original.id());if(entry==null||!entry.status().equals("complete"))return;
        TrackMetadata.Store store=new TrackMetadata.Store(context);
        if(entry.pitchJob()) {
            TrackMetadata parent=store.forUri(entry.url());if(parent!=null)store.save(new TrackMetadata(entry.id(),entry.contentUri(),parent.title(),parent.artist(),parent.album(),parent.duration(),parent.provider(),parent.url(),parent.artwork(),parent.imageLabel(),"キー変更元の曲情報"));return;
        }
        try {
            File info=new File(staging,"audio.info.json");JSONObject source=info.isFile()&&info.length()<=4*1024*1024 ? new JSONObject(new String(Files.readAllBytes(info.toPath()),StandardCharsets.UTF_8)) : new JSONObject();
            String title=source.optString("track");if(title.isBlank())title=entry.title();String artist=source.optString("artist"),album=source.optString("album");long duration=(long)(source.optDouble("duration",0)*1000);
            TrackMetadata base=new TrackMetadata(entry.id(),entry.contentUri(),title,artist,album,duration,"配信元","", "","","配信元の曲情報");store.save(base);
            if(!downloads.prefs.getBoolean("auto_metadata",true) || (entry.wifiOnly()&&!DownloadService.onWifi(context)))return;
            if(!artist.isBlank()) {
                try { List<MusicCatalog.Match> candidates=MusicCatalog.search(context,title,artist);List<MusicCatalog.Match> exact=candidates.stream().filter(m->m.exact(base.title(),base.artist(),base.duration())).collect(java.util.stream.Collectors.toList());if(exact.size()==1){MusicCatalog.apply(context,entry,exact.get(0));return;} } catch(Exception ignored){ }
            }
            String thumbnail=source.optString("thumbnail");String art="";
            if(!thumbnail.isBlank())try{art=MusicCatalog.artwork(context,thumbnail);}catch(Exception ignored){ }
            store.save(new TrackMetadata(entry.id(),entry.contentUri(),title,artist,album,duration,"配信元","",art,art.isBlank()?"":"配信元画像（アルバム画像は未確認）","曲情報を検索・確認できます"));
        } catch(Exception ignored) { /* The original completed download and source remain intact. */ }
    }
}
