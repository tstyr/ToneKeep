package jp.tonekeep.app;

import com.yausername.youtubedl_android.YoutubeDLRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

public class MediaInfoTest {
    @Test public void queryUsesChosenSearchProviderAndKeepsPunctuationLiteral() {
        assertEquals("ytsearch12:夜に駆ける --exec test", MediaInfo.target(" 夜に駆ける --exec test ", false));
        assertEquals("scsearch12:Jazz", MediaInfo.target("Jazz", true));
        assertEquals("https://vimeo.com/1234", MediaInfo.target("https://vimeo.com/1234", true));
    }
    @Test public void unsafeSchemesAndCredentialsAreRejected() {
        for (String input : List.of("file:///sdcard/Music", "javascript:alert(1)", "ftp://example.com/song", "https://name:secret@example.com/song", "https:///song")) {
            try { MediaInfo.target(input, false); fail(input); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test(expected = IllegalArgumentException.class) public void emptySearchIsRejected() { MediaInfo.target("   ", false); }
    @Test public void searchMetadataUsesWebPageUrlsRatherThanExpiringStreamUrls() throws Exception {
        String json = new JSONObject().put("entries", new JSONArray().put(new JSONObject()
                .put("id", "123").put("title", "Piano").put("uploader", "Artist")
                .put("webpage_url", "https://example.com/song/123")
                .put("url", "https://cdn.example.com/expiring?token=secret"))).toString();
        MediaInfo.Track result = MediaInfo.parseSearch(json).get(0);
        assertEquals("https://example.com/song/123", result.url());
        assertEquals("Artist", result.artist());
    }
    @Test public void flatYoutubeEntriesWithIdsBecomePlayablePageUrls() throws Exception {
        JSONObject video = new JSONObject().put("id", "abcdefghijk").put("url", "abcdefghijk").put("ie_key", "Youtube");
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", MediaInfo.parseTrack(video).url());
    }
    @Test public void missingSearchEntriesAreIgnoredWithoutLosingValidEntries() throws Exception {
        JSONArray entries = new JSONArray().put(JSONObject.NULL).put(new JSONObject().put("id", "removed"))
                .put(new JSONObject().put("url", "https://example.com/valid").put("title", "Valid"));
        assertEquals(1, MediaInfo.parseSearch(new JSONObject().put("entries", entries).toString()).size());
    }
    @Test public void formatsExcludeVideoAndDrmAndPreserveExtractorRanking() throws Exception {
        JSONArray formats = new JSONArray()
                .put(format("low", "none", "aac").put("abr", 128))
                .put(format("video", "h264", "aac"))
                .put(format("drm", "none", "aac").put("has_drm", true))
                .put(format("best", "none", "opus").put("abr", 160).put("filesize_approx", 1024));
        List<MediaInfo.Format> result = MediaInfo.formats(formats);
        assertEquals(2, result.size()); assertEquals("best", result.get(0).id());
        assertEquals(1024, result.get(0).size()); assertTrue(result.get(0).label().contains("160kbps"));
    }
    @Test public void unavailableFormatMeasurementsAreNotInvented() throws Exception {
        MediaInfo.Format format = MediaInfo.formats(new JSONArray().put(format("1", "none", "opus"))).get(0);
        assertEquals(0, format.bitrate(), 0); assertEquals(0, format.size());
        assertTrue(format.label().contains("容量不明")); assertFalse(format.label().contains("kbps"));
    }
    @Test public void defaultRequestChoosesBestAudioWithoutForcingLossyConversion() {
        YoutubeDLRequest request = YtDlpEngine.downloadRequest("https://example.com/song", "", new File("stage"));
        assertEquals("bestaudio/best", request.getOption("-f"));
        assertEquals("best", request.getOption("--audio-format"));
        assertTrue(request.hasOption("--no-playlist")); assertTrue(request.hasOption("--ignore-config"));
        assertFalse(request.hasOption("--allow-unplayable-formats")); assertFalse(request.hasOption("--no-check-certificates"));
    }
    @Test public void chosenFormatAndUrlAreSeparateArguments() {
        String url = "https://example.com/song?name=$(test)&x=2";
        YoutubeDLRequest request = YtDlpEngine.downloadRequest(url, "251", new File("stage folder"));
        assertEquals("251", request.getOption("-f"));
        assertTrue(request.buildCommand().contains(url));
        assertEquals(1, request.buildCommand().stream().filter(url::equals).count());
        assertFalse(request.hasOption("--exec"));
    }
    @Test public void filenamesHandleJapaneseEmojiAndDuplicateTitlesWithinFilesystemLimits() {
        String title = "音楽🎵".repeat(80) + "/:*?";
        String a = MediaInfo.filename(title, "12345678-rest", "opus");
        String b = MediaInfo.filename(title, "87654321-rest", "opus");
        assertNotEquals(a, b); assertFalse(a.contains("/")); assertTrue(a.endsWith(".opus"));
        assertTrue(a.getBytes(StandardCharsets.UTF_8).length < 255);
    }
    @Test public void liveStatusSurvivesParsing() throws Exception {
        JSONObject object = new JSONObject().put("url", "https://example.com/live").put("is_live", true);
        assertTrue(MediaInfo.parseTrack(object).live());
    }
    private JSONObject format(String id, String vcodec, String acodec) throws Exception {
        return new JSONObject().put("format_id", id).put("ext", "webm").put("vcodec", vcodec).put("acodec", acodec);
    }
}
