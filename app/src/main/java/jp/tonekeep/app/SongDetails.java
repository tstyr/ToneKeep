package jp.tonekeep.app;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.*;
import java.security.MessageDigest;

record SongDetails(String title,String artist,String album,long duration,String hash) {
    static SongDetails read(Context context, DownloadStore.Entry entry) throws Exception {
        Uri uri=Uri.parse(entry.contentUri()); String title=entry.title(),artist="",album=""; long duration;
        try (MediaMetadataRetriever r=new MediaMetadataRetriever()) {
            r.setDataSource(context,uri); String t=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE); if (t!=null&&!t.isBlank()) title=t;
            String a=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST); if (a!=null) artist=a;
            String al=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM); if (al!=null) album=al;
            duration=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        }
        if (duration<=0) throw new IOException("音源の長さを取得できません");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try (InputStream in=context.getContentResolver().openInputStream(uri)) { if (in==null) throw new IOException("音源を開けません"); byte[] b=new byte[65536]; int n; while((n=in.read(b))!=-1) { if(Thread.currentThread().isInterrupted()) throw new InterruptedException(); digest.update(b,0,n); } }
        StringBuilder hash=new StringBuilder(); for(byte b:digest.digest()) hash.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
        TrackMetadata saved=new TrackMetadata.Store(context).find(entry.id(),entry.contentUri());
        return new SongDetails(saved!=null&&!saved.title().isBlank() ? saved.title() : title,saved!=null&&!saved.artist().isBlank()?saved.artist():artist,saved!=null&&!saved.album().isBlank()?saved.album():album,duration,hash.toString());
    }
}
