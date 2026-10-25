package jp.tonekeep.app;

import android.content.Context;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
public class DownloadStoreTest {
    private Context context;
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        reset();
        context.getSharedPreferences("tonekeep", Context.MODE_PRIVATE).edit().clear().commit();
    }
    @After public void cleanup() throws Exception { reset(); }
    private void reset() throws Exception {
        Field instance = DownloadStore.class.getDeclaredField("instance"); instance.setAccessible(true);
        SQLiteOpenHelper helper = (SQLiteOpenHelper) instance.get(null);
        if (helper != null) helper.close(); instance.set(null, null); context.deleteDatabase("downloads.db");
    }
    private MediaInfo.Track track(int index) { return new MediaInfo.Track("" + index, "Track " + index, "Artist", "https://example.com/song/" + index, "Test", 120, false, List.of()); }

    @Test public void batchSkipsCompletedAndDuplicateTracksAndKeepsPlaylistOrder() {
        DownloadStore store = new DownloadStore(context);
        DownloadStore.Entry old = store.enqueue(track(1), "", "best");
        store.update(old.id(), "complete", "done", 100, "content://old", "old.opus");
        DownloadStore.Added batch = store.enqueueBatch(List.of(track(1), track(2), track(2), track(3)), "Playlist");
        assertEquals(2, batch.added()); assertEquals(2, batch.skipped());
        assertEquals(List.of("Track 2", "Track 3"), store.queued().stream().map(DownloadStore.Entry::title).toList());
    }
    @Test public void cancellationCannotBeOverwrittenByLateProgressOrCompletion() {
        DownloadStore store = new DownloadStore(context);
        DownloadStore.Added batch = store.enqueueBatch(List.of(track(1), track(2)), "Playlist");
        DownloadStore.Entry running = store.queued().get(0);
        store.update(running.id(), "running", "running", 20, null, null);
        store.cancelBatch(batch.batchId());
        assertFalse(store.update(running.id(), "complete", "late", 100, "content://late", "late.opus"));
        assertFalse(store.active()); assertEquals(2, store.batches().get(0).cancelled());
    }
    @Test public void failedTrackDoesNotPreventRemainingTracksAndCanBeRetriedAlone() {
        DownloadStore store = new DownloadStore(context);
        DownloadStore.Added batch = store.enqueueBatch(List.of(track(1), track(2), track(3)), "Playlist");
        String failed = store.queued().get(0).id();
        store.update(failed, "failed", "not available", 0, null, null);
        assertEquals(2, store.queued().size()); assertEquals(1, store.failed(batch.batchId()).size());
        DownloadStore.Added retry = store.enqueueBatch(store.failed(batch.batchId()), "Retry");
        assertEquals(1, retry.added()); assertEquals(3, store.queued().size());
    }
    @Test public void serviceAndScreenInstancesShareAtomicState() {
        DownloadStore screen = new DownloadStore(context), service = new DownloadStore(context);
        DownloadStore.Entry entry = screen.enqueue(track(1), "251", "Opus");
        service.update(entry.id(), "running", "running", 45, null, null);
        assertEquals(45, screen.find(entry.id()).progress());
        screen.update(entry.id(), "cancelled", "stop", 0, null, null);
        assertFalse(service.update(entry.id(), "running", "late", 60, null, null));
    }
    @Test public void previousVersionHistoryIsMigratedOnceWithSavedUriAndOrder() throws Exception {
        JSONObject old = new JSONObject().put("id", "old-id").put("title", "Old song").put("url", track(1).url())
                .put("formatId", "").put("quality", "best").put("status", "complete")
                .put("contentUri", "content://downloads/old").put("filename", "old.opus");
        context.getSharedPreferences("tonekeep", Context.MODE_PRIVATE).edit().putString("ytDownloads", new JSONArray().put(old).toString()).commit();
        DownloadStore store = new DownloadStore(context);
        assertEquals(1, store.count()); assertEquals("content://downloads/old", store.find("old-id").contentUri());
        assertEquals(1, new DownloadStore(context).count());
    }
    @Test public void interruptedRecoveryKeepsPendingQueueAndTerminalStates() {
        DownloadStore store = new DownloadStore(context);
        store.enqueueBatch(List.of(track(1), track(2), track(3)), "Playlist");
        List<DownloadStore.Entry> queue = store.queued();
        store.update(queue.get(0).id(), "publishing", "saving", 99, "content://pending", "partial.opus");
        store.update(queue.get(2).id(), "cancelled", "cancel", 0, null, null);
        store.recoverInterrupted();
        assertEquals("failed", store.find(queue.get(0).id()).status());
        assertEquals("", store.find(queue.get(0).id()).contentUri());
        assertEquals("queued", store.find(queue.get(1).id()).status());
        assertEquals("cancelled", store.find(queue.get(2).id()).status());
    }
    @Test public void thousandTrackBatchIsPersistentAndHistoryRemainsPaged() {
        DownloadStore store = new DownloadStore(context);
        List<MediaInfo.Track> tracks = new ArrayList<>(); for (int i = 0; i < 1000; i++) tracks.add(track(i));
        assertEquals(1000, store.enqueueBatch(tracks, "Large playlist").added());
        assertEquals(1000, new DownloadStore(context).pending());
        assertEquals(80, store.recent(80).size()); assertEquals("Track 0", store.queued().get(0).title());
    }
    @Test public void pitchRetryPreservesSourceAndSettingsAndWorksWithoutWifi() {
        DownloadStore store = new DownloadStore(context);
        store.prefs.edit().putBoolean("wifi", true).commit();
        DownloadStore.Entry original = store.enqueuePitch("content://audio/original", "Song.wav", new PitchSettings(-5, false, "wav"));
        assertThrows(IllegalStateException.class, () -> store.enqueuePitch(original.url(), "Song.wav", new PitchSettings(-5, false, "wav")));
        assertTrue(store.batches().isEmpty());
        store.update(original.id(), "cancelled", "cancel", 0, null, null);
        assertFalse(store.update(original.id(), "complete", "late", 100, "content://late", "late.wav"));
        DownloadStore.Entry retry = new DownloadStore(context).retry(store.find(original.id()));
        assertTrue(retry.pitchJob()); assertFalse(retry.wifiOnly());
        assertEquals(-5, retry.semitones()); assertFalse(retry.preserveFormants()); assertEquals("wav", retry.outputFormat());
        assertEquals(original.url(), retry.url()); assertEquals(original.title(), retry.title()); assertNotEquals(original.id(), retry.id());
        assertThrows(IllegalArgumentException.class, () -> store.enqueuePitch("https://example.com/file.wav", "Remote", new PitchSettings(1, true, "flac")));
    }
    @Test public void runningPitchRemainsVisibleAheadOfNewerHistory() {
        DownloadStore store = new DownloadStore(context);
        DownloadStore.Entry running = store.enqueuePitch("content://audio/first", "First", new PitchSettings(3, true, "flac"));
        store.update(running.id(), "running", "processing", 20, null, null);
        for (int i = 0; i < 15; i++) {
            DownloadStore.Entry e = store.enqueuePitch("content://audio/" + i, "Song", new PitchSettings(3, true, "flac"));
            store.update(e.id(), "complete", "done", 100, "content://output/" + i, "song.flac");
        }
        assertEquals(12, store.pitchRecent().size()); assertEquals(running.id(), store.pitchRecent().get(0).id());
    }
    @Test public void databaseUpgradePreservesExistingDownloadsAndAddsPitchSettings() {
        try (android.database.sqlite.SQLiteDatabase old = context.openOrCreateDatabase("downloads.db", Context.MODE_PRIVATE, null)) {
            old.execSQL("CREATE TABLE jobs (seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,title TEXT NOT NULL,url TEXT NOT NULL,format_id TEXT NOT NULL,quality TEXT NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,progress INTEGER NOT NULL,content_uri TEXT NOT NULL,filename TEXT NOT NULL,wifi INTEGER NOT NULL,batch_id TEXT NOT NULL,batch_title TEXT NOT NULL)");
            old.execSQL("INSERT INTO jobs (id,title,url,format_id,quality,status,message,progress,content_uri,filename,wifi,batch_id,batch_title) VALUES ('old','Existing','https://example.com/old','251','best','complete','done',100,'content://existing','old.opus',1,'batch','Old batch')");
            old.setVersion(1);
        }
        DownloadStore store = new DownloadStore(context);
        DownloadStore.Entry old = store.find("old");
        assertEquals("complete", old.status()); assertEquals("content://existing", old.contentUri());
        assertEquals("251", old.formatId()); assertTrue(old.wifiOnly()); assertFalse(old.pitchJob());
        assertEquals(0, old.semitones());
        store.enqueuePitch(old.contentUri(), old.title(), new PitchSettings(2, true, "flac"));
        assertEquals(2, store.count());
    }
}
