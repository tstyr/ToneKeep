package jp.tonekeep.app;

import android.content.Context;
import android.database.sqlite.SQLiteOpenHelper;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import org.robolectric.shadows.util.DataSource;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29) @SQLiteMode(SQLiteMode.Mode.LEGACY)
public class MetadataAcquisitionTest {
    private Context context;private DownloadStore downloads;private DownloadStore.Entry entry;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();reset();downloads=new DownloadStore(context);
        entry=downloads.enqueue(new MediaInfo.Track("own","自作曲","","https://example.invalid/metadata-fixture","self",6,false,List.of()),"","test");downloads.update(entry.id(),"complete","saved",100,"content://own/song","own.wav");entry=downloads.find(entry.id());
        DataSource source=DataSource.toDataSource(context,Uri.parse(entry.contentUri()));ShadowMediaMetadataRetriever.addMetadata(source,MediaMetadataRetriever.METADATA_KEY_DURATION,"6000");ShadowMediaMetadataRetriever.addMetadata(source,MediaMetadataRetriever.METADATA_KEY_TITLE,"自作曲");
    }
    @After public void close() throws Exception {reset();}
    private void reset() throws Exception {var field=DownloadStore.class.getDeclaredField("instance");field.setAccessible(true);SQLiteOpenHelper db=(SQLiteOpenHelper)field.get(null);if(db!=null)db.close();field.set(null,null);context.deleteDatabase("downloads.db");}
    @Test public void searchInspectsTagsWithoutReadingAudioButSavingStillHashesIt() throws Exception {
        byte[] original="owned audio fixture".getBytes(StandardCharsets.UTF_8);AtomicInteger reads=new AtomicInteger();
        Shadows.shadowOf(context.getContentResolver()).registerInputStream(Uri.parse(entry.contentUri()),new ByteArrayInputStream(original){@Override public synchronized int read(byte[] b,int off,int len){reads.incrementAndGet();return super.read(b,off,len);}});
        SongDetails inspected=SongDetails.inspect(context,entry);assertEquals("自作曲",inspected.title());assertEquals(6000,inspected.duration());assertEquals("",inspected.hash());assertEquals(0,reads.get());
        SongDetails saved=SongDetails.read(context,entry);assertTrue(reads.get()>0);byte[] hash=MessageDigest.getInstance("SHA-256").digest(original);StringBuilder expected=new StringBuilder();for(byte b:hash)expected.append(String.format(Locale.ROOT,"%02x",b&255));assertEquals(expected.toString(),saved.hash());
    }
    @Test public void missingSourceJsonTagsDoNotBecomeTheLiteralNullAndNeverChangeAudio() throws Exception {
        downloads.prefs.edit().putBoolean("auto_metadata",false).commit();File directory=new File(context.getCacheDir(),"metadata-null");assertTrue(directory.mkdirs()||directory.isDirectory());
        Files.write(new File(directory,"audio.info.json").toPath(),new JSONObject().put("track",JSONObject.NULL).put("artist",JSONObject.NULL).put("album",JSONObject.NULL).put("duration",6).toString().getBytes(StandardCharsets.UTF_8));byte[] original={1,2,3,4};File audio=new File(directory,"audio.wav");Files.write(audio.toPath(),original);
        MetadataEnricher.afterDownload(context,entry,directory);TrackMetadata result=new TrackMetadata.Store(context).find(entry.id(),entry.contentUri());assertEquals("自作曲",result.title());assertEquals("",result.artist());assertEquals("",result.album());assertArrayEquals(original,Files.readAllBytes(audio.toPath()));
    }
    @Test public void selectedAlbumIsSavedBeforeArtworkAndATimeoutDoesNotStartAnotherDownload(){
        MusicCatalog.Match match=match();AtomicInteger calls=new AtomicInteger();
        TrackMetadata result=MusicCatalog.apply(context,entry,match,(c,url)->{calls.incrementAndGet();TrackMetadata stored=new TrackMetadata.Store(context).find(entry.id(),entry.contentUri());assertEquals("正式な曲名",stored.title());assertEquals("自作アルバム",stored.album());throw new SocketTimeoutException("画像のテストタイムアウト");});
        assertEquals(1,calls.get());assertEquals("自作アルバム",result.album());assertTrue(result.artwork().isBlank());assertTrue(result.status().contains("画像は未取得"));assertEquals(result,new TrackMetadata.Store(context).find(entry.id(),entry.contentUri()));
    }
    @Test public void previouslySavedNullSourceTagsUseTheOriginalFileTitleWithoutRewritingConfirmedValues() throws Exception {
        TrackMetadata.Store store=new TrackMetadata.Store(context);store.save(new TrackMetadata(entry.id(),entry.contentUri(),"null","null","null",6000,"配信元","","","","旧版"));
        SongDetails details=SongDetails.inspect(context,entry);assertEquals("自作曲",details.title());assertEquals("",details.artist());assertEquals("",details.album());
        store.save(new TrackMetadata(entry.id(),entry.contentUri(),"null","null","null",6000,"MusicBrainz","","","","確認済み"));assertEquals("null",store.find(entry.id(),entry.contentUri()).title());
    }
    @Test public void missingLargeCoverFallsBackToSmallAndAlbumEditsAlwaysInvalidateTheList(){
        List<String> urls=new ArrayList<>();TrackMetadata result=MusicCatalog.apply(context,entry,match(),(c,url)->{urls.add(url);if(url.endsWith("1200"))throw new MetadataHttp.HttpFailure("coverartarchive.org",404,0);return "owned-cover.jpg";});
        assertEquals(2,urls.size());assertTrue(urls.get(1).endsWith("500"));assertEquals("owned-cover.jpg",result.artwork());TrackMetadata.Store store=new TrackMetadata.Store(context);String before=store.snapshot();
        store.save(new TrackMetadata(result.audioId(),result.uri(),result.title(),result.artist(),"別のアルバム",result.duration(),result.provider(),result.url(),result.artwork(),result.imageLabel(),result.status()));assertNotEquals(before,new TrackMetadata.Store(context).snapshot());assertEquals("別のアルバム",store.find(entry.id(),entry.contentUri()).album());assertNull(store.find(entry.id(),"content://different/song"));
    }
    private MusicCatalog.Match match(){return new MusicCatalog.Match("00000000-0000-0000-0000-000000000001","正式な曲名","自作","自作アルバム",6000,"00000000-0000-0000-0000-000000000002",100);}
}
