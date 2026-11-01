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

    @Test public void lyricsKeepSeparateSourceIdentitiesAndCancelledResultsCannotWin() throws Exception {
        DownloadStore downloads = new DownloadStore(context); LyricsStore store = new LyricsStore(context);
        DownloadStore.Entry song = downloads.enqueue(track(1),"","best"); downloads.update(song.id(),"complete","done",100,"content://saved/1","song.wav");
        LyricsStore.Task task = store.enqueue(song.id(),"content://saved/1","small",true,"lyrics");
        assertEquals(task.id(),store.enqueue(song.id(),"content://saved/1","medium",true,"lyrics").id());
        assertTrue(store.save(task,LyricsTest.sample())); assertEquals(LyricsTest.sample(),store.load(song.id(),"content://saved/1"));
        assertNull(store.load(song.id(),"content://saved/2")); assertNull(store.load("different-job-same-title","content://saved/1"));
        LyricsStore.Task retry = store.enqueue(song.id(),"content://saved/1","small",true,"lyrics");
        store.update(retry.id(),"cancelled","cancelled",-1); assertFalse(store.save(retry,LyricsTest.sample()));
        assertEquals(LyricsTest.sample(),store.load(song.id(),"content://saved/1"));
        assertNotEquals(retry.id(),store.enqueue(song.id(),"content://saved/1","small",true,"lyrics").id());
        assertEquals("complete",downloads.find(song.id()).status());
    }
    @Test public void lyricsRecoveryFailsRunningAndRetainsQueuedTasks() {
        LyricsStore store = new LyricsStore(context); LyricsStore.Task running = store.enqueue("a","content://a","small",true,"lyrics");
        store.update(running.id(),"running","decoding",-1); LyricsStore.Task queued = store.enqueue("b","content://b","small",true,"lyrics");
        store.recover(); assertEquals("failed",store.find(running.id()).status()); assertEquals("queued",store.find(queued.id()).status());
    }
    @Test public void externalLyricsAndCatalogDataKeepSourceIdentityAndActiveJobsSafe() throws Exception {
        DownloadStore downloads=new DownloadStore(context);var entry=downloads.enqueue(track(1),"","best");downloads.update(entry.id(),"complete","done",100,"content://saved/1","song.mp3");
        LyricsStore store=new LyricsStore(context);var active=store.enqueue(entry.id(),"content://saved/1","small",true,"lyrics");Lyrics plain=LrcParser.plain("青い空",5000,"hash",new Lyrics.Origin("lrclib","LRCLIB","https://lrclib.net/api/get/42"));
        assertThrows(IllegalStateException.class,()->store.saveExternal(entry.id(),"content://saved/1",plain));assertEquals("queued",store.find(active.id()).status());
        store.update(active.id(),"cancelled","cancel",-1);store.saveExternal(entry.id(),"content://saved/1",plain);assertEquals(plain,store.load(entry.id(),"content://saved/1"));assertNull(store.load(entry.id(),"content://saved/2"));
        store.saveExternal(entry.id(),"content://saved/1",LyricsTest.sample());assertEquals(2,store.revisions(entry.id(),"content://saved/1").size());assertEquals(plain,store.revisions(entry.id(),"content://saved/1").get(1).document());assertTrue(store.revisions(entry.id(),"content://saved/2").isEmpty());
        TrackMetadata.Store metadata=new TrackMetadata.Store(context);metadata.save(new TrackMetadata(entry.id(),"content://saved/1","正式名","自作","アルバム",5000,"MusicBrainz","","","","saved"));assertEquals("正式名",metadata.find(entry.id(),"content://saved/1").title());assertNull(metadata.find(entry.id(),"content://saved/2"));assertEquals(entry.title(),downloads.find(entry.id()).title());
    }
    @Test public void downloadMetadataUsesMusicTagsWithoutRenamingOrFailingTheSavedAudio() throws Exception {
        DownloadStore downloads=new DownloadStore(context);downloads.prefs.edit().putBoolean("auto_metadata",false).commit();var entry=downloads.enqueue(track(1),"","best");downloads.update(entry.id(),"complete","done",100,"content://saved/1","source.mp3");
        java.io.File staging=new java.io.File(context.getCacheDir(),"metadata-test");assertTrue(staging.isDirectory()||staging.mkdirs());
        java.io.File info=new java.io.File(staging,"audio.info.json");java.nio.file.Files.write(info.toPath(),new JSONObject().put("track","正式な自作曲").put("title","動画タイトル MV").put("artist","自作アーティスト").put("album","自作アルバム").put("duration",120).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MetadataEnricher.afterDownload(context,entry,staging);TrackMetadata metadata=new TrackMetadata.Store(context).find(entry.id(),"content://saved/1");assertEquals("正式な自作曲",metadata.title());assertEquals("自作アーティスト",metadata.artist());assertEquals("自作アルバム",metadata.album());assertEquals(120000,metadata.duration());assertEquals("complete",downloads.find(entry.id()).status());assertEquals("source.mp3",downloads.find(entry.id()).filename());assertEquals(entry.title(),downloads.find(entry.id()).title());
        info.delete();staging.delete();
    }
    @Test public void versionThreeMigrationPreservesTheCachedLyricsDocument() throws Exception {
        DownloadStore downloads=new DownloadStore(context);LyricsStore lyrics=new LyricsStore(context);lyrics.saveExternal("audio-id","content://saved/1",LyricsTest.sample());olderTaskSchema(downloads.database());downloads.database().execSQL("DROP TABLE track_metadata");downloads.database().setVersion(3);
        Field field=DownloadStore.class.getDeclaredField("instance");field.setAccessible(true);((SQLiteOpenHelper)field.get(null)).close();field.set(null,null);
        DownloadStore upgraded=new DownloadStore(context);assertEquals(6,upgraded.database().getVersion());assertEquals(LyricsTest.sample(),new LyricsStore(context).load("audio-id","content://saved/1"));assertNull(new TrackMetadata.Store(context).find("audio-id","content://saved/1"));
    }
    @Test public void versionTwoMigrationPreservesSavedAndPitchHistory() throws Exception {
        DownloadStore store = new DownloadStore(context); DownloadStore.Entry original = store.enqueue(track(1),"","best");
        store.update(original.id(),"complete","saved",100,"content://saved/1","song.wav");
        DownloadStore.Entry pitch = store.enqueuePitch("content://saved/1","same title",new PitchSettings(2,true,"wav"));
        var db = store.database(); db.execSQL("DROP TABLE lyrics_documents"); db.execSQL("DROP TABLE lyrics_tasks"); db.execSQL("DROP TABLE track_metadata"); db.execSQL("DROP TABLE lyrics_recognition_cache"); db.setVersion(2);
        Field field = DownloadStore.class.getDeclaredField("instance"); field.setAccessible(true); ((SQLiteOpenHelper)field.get(null)).close(); field.set(null,null);
        DownloadStore upgraded = new DownloadStore(context);
        assertEquals("content://saved/1",upgraded.find(original.id()).contentUri()); assertTrue(upgraded.find(pitch.id()).pitchJob());
        assertEquals(6,upgraded.database().getVersion()); assertNotNull(new LyricsStore(context).enqueue(original.id(),"content://saved/1","small",true,"lyrics"));
    }
    private void olderTaskSchema(android.database.sqlite.SQLiteDatabase db){
        db.execSQL("CREATE TABLE old_tasks(id TEXT PRIMARY KEY,audio_id TEXT NOT NULL,source_uri TEXT NOT NULL,model TEXT NOT NULL,japanese INTEGER NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,progress INTEGER NOT NULL,UNIQUE(audio_id,source_uri))");db.execSQL("INSERT INTO old_tasks SELECT id,audio_id,source_uri,model,japanese,kind,status,message,progress FROM lyrics_tasks");db.execSQL("DROP TABLE lyrics_tasks");db.execSQL("ALTER TABLE old_tasks RENAME TO lyrics_tasks");db.execSQL("DROP TABLE lyrics_recognition_cache");
    }
    @Test public void firstRecognitionQueuesDownloadBeforeWorkAndDoesNotNeedASecondStart() throws Exception {
        LyricsStore store=new LyricsStore(context);var prepared=store.prepare("song","content://song","small",true,"lyrics",null,true);
        assertEquals(List.of(prepared.download().id(),prepared.work().id()),store.queued().stream().map(LyricsStore.Task::id).toList());
        assertEquals("model",prepared.download().kind());assertEquals("lyrics",prepared.work().kind());
        assertThrows(IllegalStateException.class,()->store.prepare("song","content://song","medium",true,"lyrics",null,true));assertNull(store.modelTask("medium"));
        var second=store.prepare("second","content://second","small",true,"align",LyricsTest.sample(),true);assertEquals(prepared.download().id(),second.download().id());assertEquals(3,store.queued().size());assertEquals(LyricsTest.sample(),store.input(second.work().id()));
    }
    @Test public void failedOrCancelledDownloadEndsDependentWorkButKeepsOtherModelsAndHistory() throws Exception {
        LyricsStore store=new LyricsStore(context);store.saveExternal("song","content://song",LyricsTest.sample());var small=store.prepare("song","content://song","small",true,"align",LyricsTest.sample(),true);var medium=store.prepare("other","content://other","medium",true,"lyrics",null,true);
        store.update(small.download().id(),"failed","network",-1);store.failModelDependents("small","failed","取得を再開してください");assertEquals("failed",store.find(small.work().id()).status());assertEquals("queued",store.find(medium.work().id()).status());assertEquals(LyricsTest.sample(),store.load("song","content://song"));assertFalse(store.save(small.work(),LyricsTest.sample()));
        var retry=store.prepare("song","content://song","small",true,"align",store.input(small.work().id()),true);assertNotEquals(small.work().id(),retry.work().id());assertEquals(LyricsTest.sample(),store.input(retry.work().id()));store.failModelDependents("small","cancelled","中止");assertEquals("cancelled",store.find(retry.work().id()).status());
    }
    @Test public void heartbeatKeepsLongInferenceAliveAndExposesDeadProcessesForRetry() throws Exception {
        LyricsStore store=new LyricsStore(context);var work=store.prepare("a","content://a","small",true,"lyrics",null,true);store.update(work.work().id(),"running","recognition",-1);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(90));store.pulse();assertEquals(0,store.recoverStalled());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(61));assertEquals(2,store.recoverStalled());assertEquals("failed",store.find(work.work().id()).status());assertEquals(-1,store.find(work.work().id()).progress());assertFalse(store.save(work.work(),LyricsTest.sample()));
    }
    @Test public void versionFiveMigrationPreservesReferenceCacheAndActiveTask() throws Exception {
        DownloadStore downloads=new DownloadStore(context);LyricsStore lyrics=new LyricsStore(context);lyrics.saveExternal("a","content://a",LyricsTest.sample());var task=lyrics.enqueueAlignment("a","content://a","small",LyricsTest.sample());lyrics.cacheRecognition(task,LyricsTest.sample());var db=downloads.database();
        db.execSQL("CREATE TABLE v5_tasks(id TEXT PRIMARY KEY,audio_id TEXT NOT NULL,source_uri TEXT NOT NULL,model TEXT NOT NULL,japanese INTEGER NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,progress INTEGER NOT NULL,input_document TEXT NOT NULL DEFAULT '',UNIQUE(audio_id,source_uri))");db.execSQL("INSERT INTO v5_tasks SELECT id,audio_id,source_uri,model,japanese,kind,status,message,progress,input_document FROM lyrics_tasks");db.execSQL("DROP TABLE lyrics_tasks");db.execSQL("ALTER TABLE v5_tasks RENAME TO lyrics_tasks");db.setVersion(5);
        Field field=DownloadStore.class.getDeclaredField("instance");field.setAccessible(true);((SQLiteOpenHelper)field.get(null)).close();field.set(null,null);DownloadStore upgraded=new DownloadStore(context);LyricsStore migrated=new LyricsStore(context);
        assertEquals(6,upgraded.database().getVersion());assertEquals("queued",migrated.find(task.id()).status());assertEquals(LyricsTest.sample(),migrated.input(task.id()));assertEquals(LyricsTest.sample(),migrated.load("a","content://a"));assertEquals(LyricsTest.sample(),migrated.recognition("a","content://a","small","sha256"));migrated.pulse();assertEquals(0,migrated.recoverStalled());
    }
    @Test public void versionFourMigrationKeepsMetadataAndSnapshotsTheReferenceForAi() throws Exception {
        DownloadStore downloads=new DownloadStore(context);LyricsStore store=new LyricsStore(context);store.saveExternal("a","content://a",LyricsTest.sample());new TrackMetadata.Store(context).save(new TrackMetadata("a","content://a","自作曲","作者","アルバム",4000,"source","","","","saved"));olderTaskSchema(downloads.database());downloads.database().setVersion(4);
        Field field=DownloadStore.class.getDeclaredField("instance");field.setAccessible(true);((SQLiteOpenHelper)field.get(null)).close();field.set(null,null);new DownloadStore(context);LyricsStore migrated=new LyricsStore(context);assertEquals(LyricsTest.sample(),migrated.load("a","content://a"));assertEquals("自作曲",new TrackMetadata.Store(context).find("a","content://a").title());
        var task=migrated.enqueueAlignment("a","content://a","small",LyricsTest.sample());assertEquals(LyricsTest.sample(),migrated.input(task.id()));assertThrows(IllegalStateException.class,()->migrated.saveExternal("a","content://a",LyricsTest.sample()));assertThrows(IllegalStateException.class,()->migrated.enqueueAlignment("a","content://a","medium",LyricsTest.sample()));
        migrated.cacheRecognition(task,LyricsTest.sample());assertEquals(LyricsTest.sample(),migrated.recognition("a","content://a","small","sha256"));assertNull(migrated.recognition("a","content://a","small","changed-source"));migrated.update(task.id(),"cancelled","cancel",-1);assertFalse(migrated.save(task,LyricsTest.sample()));
    }

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
