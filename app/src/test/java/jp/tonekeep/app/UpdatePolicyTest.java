package jp.tonekeep.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class UpdatePolicyTest {
    private final String root = "https://github.com/tstyr/ToneKeep/releases/download/test/";
    private JSONObject manifest(long version) throws Exception {
        return new JSONObject().put("packageName", "jp.tonekeep.app.debug").put("versionCode", version).put("versionName", "1.6.0-preview")
                .put("url", root + "ToneKeep-preview.apk").put("sha256", "ab".repeat(32)).put("size", 1024);
    }
    @Test public void onlyNewerMatchingPackagesAndExactSignerSetsAreAccepted() {
        UpdatePolicy.validate("app", 1, Set.of("key"), "app", 2, Set.of("key"));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate("app", 2, Set.of("key"), "app", 2, Set.of("key")));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate("app", 2, Set.of("key"), "app", 1, Set.of("key")));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate("app", 1, Set.of("key"), "other", 2, Set.of("key")));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate("app", 1, Set.of("key"), "app", 2, Set.of("other")));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.validate("app", 1, Set.of(), "app", 2, Set.of()));
    }
    @Test public void manifestsRequireBoundedApkSizeChecksumsAndOfficialHttpsUrls() throws Exception {
        ReleaseUpdate update = ReleaseUpdate.parse(manifest(60002).toString()); assertEquals(60002, update.versionCode());
        for (String url : new String[]{"http://github.com/tstyr/ToneKeep/releases/download/a/app.apk", "https://github.com.evil.test/tstyr/ToneKeep/releases/download/a/app.apk", "https://github.com/other/repo/releases/download/a/app.apk", "https://user@github.com/tstyr/ToneKeep/releases/download/a/app.apk"})
            assertThrows(IllegalArgumentException.class, () -> ReleaseUpdate.parse(manifest(2).put("url", url).toString()));
        assertThrows(IllegalArgumentException.class, () -> ReleaseUpdate.parse(manifest(2).put("sha256", "bad").toString()));
        assertThrows(IllegalArgumentException.class, () -> ReleaseUpdate.parse(manifest(2).put("size", 600L * 1024 * 1024).toString()));
    }
    @Test public void releaseLookupIncludesPreviewsAndSkipsLegacyAssetsAndWrongPackages() throws Exception {
        JSONObject older = new JSONObject().put("prerelease", true).put("assets", new JSONArray().put(new JSONObject().put("name", "update.json").put("browser_download_url", root + "old.json")));
        JSONObject newer = new JSONObject().put("prerelease", true).put("assets", new JSONArray().put(new JSONObject().put("name", "update.json").put("browser_download_url", root + "new.json")));
        JSONArray releases = new JSONArray().put(new JSONObject().put("assets", new JSONArray())).put(older).put(newer);
        ReleaseUpdate update = ReleaseUpdate.find(releases.toString(), "jp.tonekeep.app.debug", 60001,
                url -> manifest(url.endsWith("new.json") ? 60003 : 60002).toString());
        assertEquals(60003, update.versionCode());
        assertNull(ReleaseUpdate.find(releases.toString(), "other.app", 1, url -> manifest(60003).toString()));
        assertNull(ReleaseUpdate.find(releases.toString(), "jp.tonekeep.app.debug", 60003, url -> manifest(60003).toString()));
    }
}
