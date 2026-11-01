package jp.tonekeep.app;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;

/** Explicit opt-in only: verifies metadata GETs on the user's retained demo, with no audio upload. */
@RunWith(AndroidJUnit4.class)
public class OnlineSongInfoTest {
    @Test public void refreshOnlyTheRetainedDemoArtworkForTheLargePlayer() throws Exception {
        org.junit.Assume.assumeTrue("Explicit artwork refresh opt-in required","true".equals(InstrumentationRegistry.getArguments().getString("refreshArtwork")));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();DownloadStore.Entry entry=new DownloadStore(context).all().stream().filter(e->e.title().equals("SAYONARA MAYBE")&&e.status().equals("complete")).findFirst().orElseThrow();
        LyricsStore lyrics=new LyricsStore(context);String before=lyrics.load(entry.id(),entry.contentUri()).json();
        var match=new MusicCatalog.Match("339994ff-6f8d-4c32-9deb-e8cd9ee322e2","SAYONARA MAYBE","NOMELON NOLEMON","ルール",244000,"903be4c3-4d17-4983-bcec-1f00baae7881",100);
        TrackMetadata metadata=MusicCatalog.apply(context,entry,match);android.graphics.BitmapFactory.Options size=new android.graphics.BitmapFactory.Options();size.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(metadata.artwork(),size);
        assertTrue("Large player artwork: "+size.outWidth,size.outWidth>=500);assertEquals(before,lyrics.load(entry.id(),entry.contentUri()).json());
        android.os.Bundle report=new android.os.Bundle();report.putString("stream","artwork refresh: "+size.outWidth+"x"+size.outHeight+", lyricsUnchanged=true\n");InstrumentationRegistry.getInstrumentation().sendStatus(0,report);
    }
    @Test public void fetchMetadataArtworkAndLyricsThenReadThemFromLocalStorage() throws Exception {
        org.junit.Assume.assumeTrue("Online demo test requires explicit opt-in","true".equals(InstrumentationRegistry.getArguments().getString("onlineDemo")));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();DownloadStore store=new DownloadStore(context);
        DownloadStore.Entry entry=store.all().stream().filter(e->e.title().equals("SAYONARA MAYBE")&&e.status().equals("complete")).findFirst().orElseThrow(()->new AssertionError("Prepare the retained demo first"));
        SongDetails song=SongDetails.read(context,entry);assertTrue(Math.abs(song.duration()-244390)<=100);
        var matches=MusicCatalog.search(context,"SAYONARA MAYBE","NOMELON NOLEMON");var match=matches.stream().filter(m->m.exact("SAYONARA MAYBE","NOMELON NOLEMON",song.duration())).findFirst().orElseThrow(()->new AssertionError("Expected duration-matched catalog recording"));
        TrackMetadata metadata=MusicCatalog.apply(context,entry,match);assertFalse(metadata.artwork().isBlank());assertTrue(new File(metadata.artwork()).isFile());assertEquals("NOMELON NOLEMON",metadata.artist());assertEquals("MusicBrainz",metadata.provider());
        var candidates=LyricsLookup.search(context,metadata.title(),metadata.artist(),song.duration());var candidate=candidates.stream().filter(c->c.matchesDuration(song.duration())).findFirst().orElseThrow(()->new AssertionError("Expected the full-length version"));
        Lyrics doc=candidate.document(song.duration(),song.hash());LyricsStore lyrics=new LyricsStore(context);lyrics.saveExternal(entry.id(),entry.contentUri(),doc);
        assertEquals(doc,lyrics.load(entry.id(),entry.contentUri()));assertEquals("lrclib",doc.origin().provider());assertTrue(doc.lines().isEmpty() || !doc.export("lrc").isBlank());
        assertEquals(metadata,new TrackMetadata.Store(context).find(entry.id(),entry.contentUri()));assertNull(new TrackMetadata.Store(context).find(entry.id(),"content://different-source"));
        android.util.Log.i("ToneKeepInfoTest","PASS title="+metadata.title()+" artist="+metadata.artist()+" album="+metadata.album()+" artworkBytes="+new File(metadata.artwork()).length()+" lyricsRecord="+candidate.id()+" syncedLines="+doc.lines().size()+" plainCharacters="+doc.plainText().length());
    }
}
