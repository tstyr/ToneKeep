package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class AudioSelectionTest {
    private JSONObject file(String name, String parent, int bitrate) throws Exception {
        return new JSONObject().put("name", name).put("original", parent).put("bitrate", bitrate).put("size", 123456);
    }

    @Test public void sameRecordingPrefersLosslessAndKeepsOtherRecordingsSeparate() throws Exception {
        JSONArray files = new JSONArray()
                .put(file("disc/song.flac", "", 0))
                .put(file("disc/song.mp3", "disc/song.flac", 320))
                .put(file("disc/song.ogg", "disc/song.mp3", 128))
                .put(file("live/song.flac", "", 0));
        List<List<AudioFile>> tracks = AudioFile.tracks(files, false);
        assertEquals(2, tracks.size());
        List<AudioFile> studio = tracks.stream().filter(t -> t.size() == 3).findFirst().orElseThrow();
        assertEquals("FLAC", studio.get(0).format);
        assertEquals("MP3", studio.get(1).format);
        assertEquals(1, AudioFile.tracks(files, true).get(0).size());
    }

    @Test public void higherResolutionLosslessComesBeforeLowerResolution() throws Exception {
        JSONArray files = new JSONArray().put(file("song.wav", "", 0).put("samplerate", "96000").put("bits_per_sample", "24"))
                .put(file("song.flac", "song.wav", 0).put("samplerate", "44100").put("bits_per_sample", "16"));
        assertEquals("WAV", AudioFile.tracks(files, false).get(0).get(0).format);
    }

    @Test public void privateAndNonAudioFilesNeverAppear() throws Exception {
        JSONArray files = new JSONArray().put(file("secret.flac", "", 0).put("private", "true"))
                .put(file("playlist.m3u", "", 0)).put(file("song.mp3", "", 192));
        assertEquals(1, AudioFile.tracks(files, false).size());
        assertTrue(AudioFile.tracks(files, true).isEmpty());
    }

    @Test public void higherBitrateWinsForLossyVersions() throws Exception {
        JSONArray files = new JSONArray().put(file("song.mp3", "master.wav", 128))
                .put(file("song-high.mp3", "master.wav", 320));
        assertEquals(320, AudioFile.tracks(files, false).get(0).get(0).bitrate);
    }

    @Test public void licenseValidationRejectsSpoofedHostsAndUnknownLicenses() {
        assertEquals("CC BY-NC-ND", AudioFile.licenseLabel("https://creativecommons.org/licenses/by-nc-nd/4.0/"));
        assertEquals("CC0", AudioFile.licenseLabel("http://creativecommons.org/publicdomain/zero/1.0/"));
        assertEquals("Public Domain", AudioFile.licenseLabel("https://creativecommons.org/publicdomain/mark/1.0/"));
        assertEquals("", AudioFile.licenseLabel("https://creativecommons.org.evil.test/licenses/by/4.0/"));
        assertEquals("", AudioFile.licenseLabel("https://creativecommons.org@evil.test/licenses/by/4.0/"));
        assertEquals("", AudioFile.licenseLabel("https://creativecommons.org/licenses/unknown/4.0/"));
        assertEquals("", AudioFile.licenseLabel("javascript:alert(1)"));
        assertEquals("", AudioFile.licenseLabel(""));
    }

    @Test public void urlsEncodeJapaneseAndReservedCharactersWithoutBreakingNestedPaths() throws Exception {
        AudioFile file = new AudioFile(file("disc 1/音楽 #1+mix.flac", "", 0));
        assertEquals("https://archive.org/download/album/disc%201/%E9%9F%B3%E6%A5%BD%20%231%2Bmix.flac", file.url("album"));
        assertFalse(file.safeFilename("album").contains("/"));
        AudioFile discOne = new AudioFile(file("disc1/song.flac", "", 0));
        AudioFile discTwo = new AudioFile(file("disc2/song.flac", "", 0));
        assertNotEquals(discOne.safeFilename("album"), discTwo.safeFilename("album"));
    }

    @Test public void userQueryCannotRemoveLicenseConstraints() {
        String query = ArchiveClient.query("Moonlight\" OR mediatype:movies");
        assertTrue(query.startsWith("mediatype:audio AND licenseurl:(*creativecommons.org*) AND ("));
        assertTrue(query.contains("title:\"Moonlight\""));
        assertFalse(query.contains("mediatype:movies"));
    }

    @Test(expected = IllegalArgumentException.class) public void punctuationOnlyQueryIsRejected() {
        ArchiveClient.query("*:()");
    }

    @Test public void metadataArraysSelectAnActualRecognizedLicense() throws Exception {
        JSONArray licenses = new JSONArray().put("all rights reserved").put("https://creativecommons.org/licenses/by/4.0/");
        assertEquals("https://creativecommons.org/licenses/by/4.0/", ArchiveClient.firstLicense(licenses));
        assertEquals("", ArchiveClient.firstLicense(new JSONArray().put("unlicensed")));
    }
}
