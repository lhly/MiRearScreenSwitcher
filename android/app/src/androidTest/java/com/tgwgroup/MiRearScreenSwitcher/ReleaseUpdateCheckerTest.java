package com.tgwgroup.MiRearScreenSwitcher;

import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReleaseUpdateCheckerTest {
    static String release(String tag) {
        return manifest(tag, BuildConfig.VERSION_CODE + 1);
    }
    static String manifest(String tag, int code) {
        return "{\"schemaVersion\":1,\"versionName\":\"" + tag.replaceFirst("^[vV]", "")
                + "\",\"versionCode\":" + code + ",\"tag\":\"" + tag + "\",\"apk\":{\"url\":\""
                + ProjectInfo.RELEASES + "/download/" + tag + "/MRSS-" + tag + ".apk\",\"size\":128,\"sha256\":\""
                + "0000000000000000000000000000000000000000000000000000000000000000"
                + "\",\"packageName\":\"" + ProjectInfo.RELEASE_PACKAGE + "\"},\"html_url\":\"https://example.invalid\"}";
    }
    private ReleaseUpdateChecker.Result result(int code, String body, String current) {
        return ReleaseUpdateChecker.evaluate(new ReleaseUpdateChecker.Response(code, body), current, BuildConfig.VERSION_CODE);
    }

    @Test public void stableVersionsCompareNumericallyAndNeverSuggestDowngrading() {
        assertEquals(ReleaseUpdateChecker.Status.AVAILABLE, result(200, release("v3.4.10"), "v3.4.9").status);
        assertEquals(ReleaseUpdateChecker.Status.CURRENT, result(200, manifest("v3.3.2", BuildConfig.VERSION_CODE), "3.3.2+local.7").status);
        assertEquals(ReleaseUpdateChecker.Status.LOCAL_NEWER, result(200, release("v3.3.0"), "v3.3.2").status);
        assertEquals(ReleaseUpdateChecker.Status.AVAILABLE, result(200, release("v3.4.0"), "v3.4.0-rc.1").status);
    }

    @Test public void prereleaseOrderFollowsSemanticVersionRules() {
        String[] ordered = {"1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
                "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0"};
        for (int i = 0; i < ordered.length - 1; i++) {
            assertTrue(ReleaseUpdateChecker.Version.parse(ordered[i]).compareTo(ReleaseUpdateChecker.Version.parse(ordered[i + 1])) < 0);
        }
        assertTrue(ReleaseUpdateChecker.Version.parse("999999999999999999.0.0")
                .compareTo(ReleaseUpdateChecker.Version.parse("999999999999999998.0.0")) > 0);
    }

    @Test public void onlyStableConsistentTypedManifestsAreAccepted() {
        String valid = release("v3.4.0");
        for (String body : new String[]{release("v3.4.0-rc.1"), release("v3.4.0+local"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                valid.replace("\"versionName\":\"3.4.0\"", "\"versionName\":\"3.5.0\""),
                valid.replace("\"versionName\":\"3.4.0\"", "\"versionName\":null"),
                valid.replace("\"versionCode\":" + (BuildConfig.VERSION_CODE + 1), "\"versionCode\":0"),
                valid.replace("\"versionCode\":" + (BuildConfig.VERSION_CODE + 1), "\"versionCode\":2100000001"),
                valid.replace("\"versionCode\":" + (BuildConfig.VERSION_CODE + 1), "\"versionCode\":\"2014\""),
                valid.replace("\"versionCode\":" + (BuildConfig.VERSION_CODE + 1), "\"versionCode\":2014.5"),
                "{\"tag_name\":\"v3.4.0\",\"draft\":false,\"prerelease\":false}"}) {
            assertEquals(body, ReleaseUpdateChecker.Status.INVALID_RESPONSE, result(200, body, "v3.3.2").status);
        }
    }

    @Test public void androidVersionCodePreventsIncompatibleDowngrades() {
        assertEquals(ReleaseUpdateChecker.Status.LOCAL_NEWER,
                result(200, manifest("v3.4.0", BuildConfig.VERSION_CODE - 1), "v3.3.2").status);
        assertEquals(ReleaseUpdateChecker.Status.INVALID_RESPONSE,
                result(200, manifest("v3.4.0", BuildConfig.VERSION_CODE), "v3.3.2").status);
        assertEquals(ReleaseUpdateChecker.Status.AVAILABLE,
                result(200, release("v3.3.2"), "v3.3.2").status);
    }

    @Test public void missingMalformedAndUntrustedTagsCannotBecomeDownloadLinks() {
        for (String tag : new String[]{"", "v03.3.2", "v3.3", "v3.4.0-01", "https://example.invalid", "../../other", "v3.4.0\n"}) {
            ReleaseUpdateChecker.Result result = result(200, release(tag), "v3.3.2");
            assertEquals(tag, ReleaseUpdateChecker.Status.INVALID_RESPONSE, result.status);
            assertEquals(ProjectInfo.RELEASES, result.releaseUrl());
        }
        assertEquals(ReleaseUpdateChecker.Status.INVALID_RESPONSE, result(200, "{}", "v3.3.2").status);
        assertEquals(ReleaseUpdateChecker.Status.INVALID_RESPONSE, result(200, "not JSON", "v3.3.2").status);
        assertEquals(ReleaseUpdateChecker.Status.INVALID_RESPONSE, result(200, release("v3.4.0"), "unknown").status);
        assertEquals(ProjectInfo.RELEASES + "/tag/v3.4.0", result(200, release("v3.4.0"), "v3.3.2").releaseUrl());
    }

    @Test public void unavailableAndRestrictedResponsesNeverReportCurrent() {
        assertEquals(ReleaseUpdateChecker.Status.MANIFEST_UNAVAILABLE, result(404, "", "v3.3.2").status);
        for (int code : new int[]{403, 429}) assertEquals(ReleaseUpdateChecker.Status.RESTRICTED, result(code, "", "v3.3.2").status);
        for (int code : new int[]{301, 304, 500, 503}) assertEquals(ReleaseUpdateChecker.Status.NETWORK_ERROR, result(code, "", "v3.3.2").status);
    }

    @Test public void fetchRunsOffMainAndDuplicateChecksAreIgnored() throws Exception {
        CountDownLatch started = new CountDownLatch(1), unblock = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicReference<ReleaseUpdateChecker.Result> completed = new AtomicReference<>();
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
            started.countDown();
            try { assertTrue(unblock.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new IOException(error); }
            return new ReleaseUpdateChecker.Response(200, release("v3.4.0"));
        });
        try {
            assertTrue(checker.check("v3.3.2", result -> {
                assertEquals(Looper.getMainLooper(), Looper.myLooper());
                completed.set(result); finished.countDown();
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(checker.check("v3.3.2", result -> fail("Duplicate callback")));
            unblock.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(ReleaseUpdateChecker.Status.AVAILABLE, completed.get().status);
        } finally { unblock.countDown(); checker.close(); }
    }

    @Test public void networkFailureCanBeRetried() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> {
            if (attempts.incrementAndGet() == 1) throw new IOException("offline");
            return new ReleaseUpdateChecker.Response(200, manifest("v3.3.2", BuildConfig.VERSION_CODE));
        });
        try {
            for (ReleaseUpdateChecker.Status expected : new ReleaseUpdateChecker.Status[]{ReleaseUpdateChecker.Status.NETWORK_ERROR, ReleaseUpdateChecker.Status.CURRENT}) {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<ReleaseUpdateChecker.Status> status = new AtomicReference<>();
                assertTrue(checker.check("v3.3.2", result -> { status.set(result.status); done.countDown(); }));
                assertTrue(done.await(5, TimeUnit.SECONDS));
                assertEquals(expected, status.get());
            }
        } finally { checker.close(); }
    }

    @Test public void closingCancelsFetchAndDiscardsLateResults() throws Exception {
        CountDownLatch started = new CountDownLatch(1), unblock = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicBoolean callback = new AtomicBoolean(), cancelled = new AtomicBoolean();
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(new ReleaseUpdateChecker.Fetcher() {
            @Override public ReleaseUpdateChecker.Response fetch() {
                started.countDown();
                try { unblock.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                returned.countDown();
                return new ReleaseUpdateChecker.Response(200, release("v3.4.0"));
            }
            @Override public void cancel() { cancelled.set(true); unblock.countDown(); }
        });
        try {
            checker.check("v3.3.2", result -> callback.set(true));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            checker.close();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(cancelled.get());
            assertFalse(callback.get());
            assertFalse(checker.check("v3.3.2", result -> fail("Closed checker")));
        } finally { checker.close(); }
    }

    static final class FakeConnection extends java.net.HttpURLConnection {
        final int status;
        final byte[] body;
        boolean disconnected;
        FakeConnection(int status, byte[] body) throws Exception {
            super(new java.net.URL(ProjectInfo.VERSION_MANIFEST));
            this.status = status; this.body = body;
        }
        @Override public int getResponseCode() { return status; }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(body); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }

    @Test public void transportReadsRawWithoutTokenAndRetainsNetworkBounds() throws Exception {
        FakeConnection connection = new FakeConnection(200, release("v3.4.0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ReleaseUpdateChecker.RawManifestFetcher fetcher = new ReleaseUpdateChecker.RawManifestFetcher(url -> {
            assertEquals("https://raw.githubusercontent.com/lhly/MiRearScreenSwitcher/main/version.json", url.toString());
            return connection;
        });
        ReleaseUpdateChecker.Response response = fetcher.fetch();
        assertEquals(ReleaseUpdateChecker.Status.AVAILABLE, result(response.code, response.body, "v3.3.2").status);
        assertNull(connection.getRequestProperty("Authorization"));
        assertNull(connection.getRequestProperty("X-GitHub-Api-Version"));
        assertEquals("application/json, text/plain", connection.getRequestProperty("Accept"));
        assertEquals(10000, connection.getConnectTimeout());
        assertEquals(10000, connection.getReadTimeout());
        assertFalse(connection.getUseCaches());
        assertFalse(connection.getInstanceFollowRedirects());
        assertTrue(connection.disconnected);
    }

    @Test public void oversizedManifestDisconnectsAndCancellationPreventsReading() throws Exception {
        FakeConnection large = new FakeConnection(200, new byte[256 * 1024 + 1]);
        try {
            new ReleaseUpdateChecker.RawManifestFetcher(url -> large).fetch();
            fail("Oversized manifest accepted");
        } catch (IOException expected) { assertTrue(large.disconnected); }
        FakeConnection cancelled = new FakeConnection(200, new byte[0]);
        ReleaseUpdateChecker.RawManifestFetcher fetcher = new ReleaseUpdateChecker.RawManifestFetcher(url -> cancelled);
        fetcher.cancel();
        try { fetcher.fetch(); fail("Cancelled fetch read the network"); }
        catch (IOException expected) { assertTrue(cancelled.disconnected); }
    }

    @Test public void proxyPreferenceIsReadForEveryCheckAndFailuresNeverFallbackToDirect() throws Exception {
        AtomicBoolean proxy = new AtomicBoolean(true);
        java.util.List<String> requests = new java.util.ArrayList<>();
        ReleaseUpdateChecker.RawManifestFetcher fetcher = new ReleaseUpdateChecker.RawManifestFetcher(proxy::get, url -> {
            requests.add(url.toString());
            try { return new FakeConnection(200, manifest("v3.3.2", BuildConfig.VERSION_CODE).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            catch (Exception error) { throw new IOException(error); }
        });
        assertEquals(200, fetcher.fetch().code);
        proxy.set(false);
        assertEquals(200, fetcher.fetch().code);
        assertEquals(java.util.Arrays.asList(UpdateNetwork.PROXY + ProjectInfo.VERSION_MANIFEST, ProjectInfo.VERSION_MANIFEST), requests);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        try {
            new ReleaseUpdateChecker.RawManifestFetcher(() -> true, url -> {
                attempts.incrementAndGet(); assertEquals("gh-proxy.com", url.getHost()); throw new IOException("Proxy offline");
            }).fetch();
            fail("Proxy failure must be reported");
        } catch (IOException expected) { assertEquals(1, attempts.get()); }
    }
}
