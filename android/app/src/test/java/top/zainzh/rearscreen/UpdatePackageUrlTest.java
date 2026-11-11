package top.zainzh.rearscreen;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class UpdatePackageUrlTest {
    private static final String TAG = "v3.6.2";
    private static final String PATH = "/download/v3.6.2/ReMRSS-v3.6.2.apk";
    private static final String CURRENT = "https://github.com/lhly/ReMRSS/releases";

    @Test public void currentProjectLinksUseTheRenamedRepository() {
        assertEquals("https://github.com/lhly/ReMRSS", ProjectInfo.urlForAction("openProject"));
        assertEquals("https://github.com/lhly/ReMRSS/issues/new", ProjectInfo.urlForAction("openFeedback"));
        assertEquals(CURRENT, ProjectInfo.urlForAction("openReleases"));
        assertEquals("https://raw.githubusercontent.com/lhly/ReMRSS/main/updates/remrss/version.json",
                ProjectInfo.VERSION_MANIFEST);
        assertEquals("https://github.com/AntiOblivionis/MiRearScreenSwitcher",
                ProjectInfo.urlForAction("openOriginalProject"));
    }

    @Test public void installedClientFeedRemainsUsableButDownloadsUseTheCurrentRepository() throws Exception {
        String legacyUrl = ProjectInfo.LEGACY_RELEASES + PATH;
        JSONObject apk = new JSONObject().put("url", legacyUrl).put("size", 128)
                .put("sha256", "a".repeat(64)).put("packageName", ProjectInfo.RELEASE_PACKAGE);
        UpdatePackage update = UpdatePackage.parse(new JSONObject().put("apk", apk), TAG, 2021);
        assertEquals(legacyUrl, update.url);
        assertEquals(CURRENT + PATH, UpdatePackage.canonicalDownloadUrl(update.url, update.tag));
        assertEquals(CURRENT + PATH, UpdatePackage.canonicalDownloadUrl(CURRENT + PATH, TAG));
    }

    @Test public void migrationDoesNotAcceptOtherRepositoriesOrInvalidPaths() {
        assertNull(UpdatePackage.canonicalDownloadUrl(
                "https://github.com/AntiOblivionis/MiRearScreenSwitcher/releases" + PATH, TAG));
        assertNull(UpdatePackage.canonicalDownloadUrl(CURRENT + PATH, "v3.6.1"));
        assertNull(UpdatePackage.canonicalDownloadUrl(ProjectInfo.LEGACY_RELEASES + PATH + "?x=1", TAG));
        assertNull(UpdatePackage.canonicalDownloadUrl(CURRENT + "/download/" + TAG + "/../app.apk", TAG));
    }
}
