package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 26)
public class UpdateDownloadTest {
    final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    UpdatePackage data(byte[] body) {
        return new UpdatePackage("v3.4.0", "3.4.0-debug", BuildConfig.VERSION_CODE + 1,
                ProjectInfo.RELEASES + "/download/v3.4.0/MRSS-v3.4.0.apk",
                UpdateApkVerifier.hex(UpdateApkVerifier.sha256().digest(body)), body.length, context.getPackageName());
    }

    static class Connection extends HttpURLConnection {
        byte[] body; int status = 200; long length; String location;
        volatile boolean disconnected;
        Connection(URL url, byte[] body) { super(url); this.body = body; length = body.length; }
        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public String getHeaderField(String key) { return "Location".equals(key) ? location : null; }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
    }

    UpdateDownloadTask.State done(UpdateDownloadTask task) throws Exception {
        long deadline = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < deadline) {
            UpdateDownloadTask.State state = task.state();
            if (state.phase == UpdateDownloadTask.Phase.READY || state.phase == UpdateDownloadTask.Phase.ERROR) return state;
            Thread.sleep(20);
        }
        throw new AssertionError("Download did not finish: " + task.state().phase);
    }

    byte[] fixture(String name) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    @Test public void rejectsMissingOrUntrustedDownloadMetadataAndRedirects() throws Exception {
        String valid = ReleaseUpdateCheckerTest.release("v3.4.0");
        for (String key : new String[]{"url", "sha256", "size", "packageName"}) {
            JSONObject json = new JSONObject(valid); json.getJSONObject("apk").remove(key);
            assertEquals(ReleaseUpdateChecker.Status.INVALID_RESPONSE,
                    ReleaseUpdateChecker.evaluate(new ReleaseUpdateChecker.Response(200, json.toString()), "v3.3.2", BuildConfig.VERSION_CODE).status);
        }
        for (String url : new String[]{"http://github.com/file.apk", "https://example.invalid/file.apk",
                UpdateNetwork.PROXY + ProjectInfo.RELEASES + "/download/v3.4.0/app.apk",
                ProjectInfo.RELEASES + "/download/v9.0.0/app.apk", ProjectInfo.RELEASES + "/download/v3.4.0/../app.apk",
                ProjectInfo.RELEASES + "/download/v3.4.0/app.apk?token=hidden"}) assertFalse(UpdatePackage.validDownloadUrl(url, "v3.4.0"));
        for (String url : new String[]{"http://release-assets.githubusercontent.com/file", "https://github.com.evil.invalid/file",
                "https://user@github.com/file", "https://github.com:444/file"}) assertFalse(UpdatePackage.allowedRedirect(java.net.URI.create(url)));
        assertTrue(UpdatePackage.allowedRedirect(java.net.URI.create("https://release-assets.githubusercontent.com/file?signature=temporary")));
    }

    @Test public void successfulTransferChecksHashBeforePackageInspectionAndReportsBytes() throws Exception {
        byte[] bytes = new byte[65536]; Arrays.fill(bytes, (byte) 17);
        AtomicBoolean verified = new AtomicBoolean();
        Connection connection = new Connection(new URL(data(bytes).url), bytes);
        UpdateDownloadTask task = new UpdateDownloadTask(context, data(bytes), url -> connection,
                (file, metadata) -> { assertEquals(bytes.length, file.length()); verified.set(true); });
        try {
            UpdateDownloadTask.State state = done(task);
            assertEquals(UpdateDownloadTask.Phase.READY, state.phase); assertEquals(bytes.length, state.bytes);
            assertTrue(verified.get()); assertArrayEquals(bytes, Files.readAllBytes(state.file.toPath()));
            assertNull(connection.getRequestProperty("Authorization")); assertFalse(connection.getInstanceFollowRedirects());
            assertTrue(connection.disconnected); state.file.delete();
        } finally { task.close(); }
    }

    @Test public void truncatedOversizedAndTamperedBodiesAreNeverReady() throws Exception {
        byte[] expected = new byte[32]; Arrays.fill(expected, (byte) 17);
        for (int length : new int[]{31, 32, 33}) {
            byte[] body = new byte[length]; AtomicBoolean packageChecked = new AtomicBoolean();
            Connection connection = new Connection(new URL(data(expected).url), body); connection.length = -1;
            UpdateDownloadTask task = new UpdateDownloadTask(context, data(expected), url -> connection,
                    (file, metadata) -> packageChecked.set(true));
            try {
                UpdateDownloadTask.State state = done(task); assertEquals(UpdateDownloadTask.Phase.ERROR, state.phase);
                assertEquals(length == 32 ? UpdateDownloadTask.Error.HASH : UpdateDownloadTask.Error.SIZE, state.error);
                assertFalse(packageChecked.get()); assertNull(state.file);
            } finally { task.close(); }
        }
    }

    @Test public void contentLengthAndHttpFailuresDoNotReachVerification() throws Exception {
        byte[] body = new byte[32];
        for (int status : new int[]{200, 403, 404, 500}) {
            Connection connection = new Connection(new URL(data(body).url), body); connection.status = status; connection.length = 33;
            UpdateDownloadTask task = new UpdateDownloadTask(context, data(body), url -> connection, (file, metadata) -> fail("Must not verify"));
            try {
                UpdateDownloadTask.State state = done(task); assertEquals(UpdateDownloadTask.Phase.ERROR, state.phase);
                assertEquals(status == 200 ? UpdateDownloadTask.Error.SIZE : UpdateDownloadTask.Error.NETWORK, state.error);
            } finally { task.close(); }
        }
    }

    @Test public void allowedGitHubRedirectWorksButUnsafeRedirectsStopBeforeSecondConnection() throws Exception {
        byte[] body = new byte[32];
        for (String location : new String[]{"https://release-assets.githubusercontent.com/file", "http://github.com/file", "https://example.invalid/file"}) {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            UpdateDownloadTask task = new UpdateDownloadTask(context, data(body), url -> {
                Connection connection = new Connection(url, body);
                if (calls.incrementAndGet() == 1) { connection.status = 302; connection.location = location; }
                return connection;
            }, (file, metadata) -> { });
            try {
                UpdateDownloadTask.State state = done(task);
                assertEquals(location.startsWith("https://release-assets") ? UpdateDownloadTask.Phase.READY : UpdateDownloadTask.Phase.ERROR, state.phase);
                assertEquals(location.startsWith("https://release-assets") ? 2 : 1, calls.get());
                if (state.file != null) state.file.delete();
            } finally { task.close(); }
        }
    }

    @Test public void cancellationDisconnectsAndRemovesPartialFileWithoutCallbacks() throws Exception {
        byte[] body = new byte[32]; CountDownLatch reading = new CountDownLatch(1), released = new CountDownLatch(1);
        AtomicBoolean callback = new AtomicBoolean();
        Connection connection = new Connection(new URL(data(body).url), body) {
            @Override public InputStream getInputStream() {
                return new InputStream() { @Override public int read() throws IOException {
                    reading.countDown();
                    try { released.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    throw new IOException("Disconnected");
                } };
            }
            @Override public void disconnect() { super.disconnect(); released.countDown(); }
        };
        UpdateDownloadTask task = new UpdateDownloadTask(context, data(body), url -> connection, (file, metadata) -> fail("Cancelled APK inspected"));
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            task.attach(state -> { if (state.phase == UpdateDownloadTask.Phase.READY || state.phase == UpdateDownloadTask.Phase.ERROR) callback.set(true); });
            task.close();
        });
        assertTrue(connection.disconnected);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse(callback.get());
    }

    @Test public void realSignedApkPassesEntireDownloadAndInstallerUriContract() throws Exception {
        byte[] bytes = fixture("candidate.apk"); UpdatePackage update = data(bytes);
        UpdateDownloadTask task = new UpdateDownloadTask(context, update, url -> new Connection(url, bytes),
                (file, metadata) -> UpdateApkVerifier.verifyPackage(context, file, metadata));
        try {
            UpdateDownloadTask.State state = done(task);
            assertEquals(String.valueOf(state.error), UpdateDownloadTask.Phase.READY, state.phase);
            Intent intent = UpdateInstaller.intent(context, state.file);
            assertEquals(Intent.ACTION_VIEW, intent.getAction()); assertEquals("content", intent.getData().getScheme());
            assertEquals(context.getPackageName() + ".updates", intent.getData().getAuthority());
            assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            assertEquals("application/vnd.android.package-archive", intent.getType());
            try (InputStream input = context.getContentResolver().openInputStream(intent.getData())) { assertTrue(input.read() >= 0); }
            state.file.delete();
        } finally { task.close(); }
    }

    @Test public void proxiedSignedApkKeepsCanonicalMetadataAndEntireVerification() throws Exception {
        byte[] bytes = fixture("candidate.apk"); UpdatePackage update = data(bytes);
        UpdateDownloadTask task = new UpdateDownloadTask(context, update, true, url -> {
            assertEquals(UpdateNetwork.PROXY + update.url, url.toString());
            return new Connection(url, bytes);
        }, (file, metadata) -> {
            assertEquals(update.url, metadata.url);
            UpdateApkVerifier.verifyPackage(context, file, metadata);
        });
        try {
            UpdateDownloadTask.State state = done(task);
            assertEquals(String.valueOf(state.error), UpdateDownloadTask.Phase.READY, state.phase);
            assertArrayEquals(bytes, Files.readAllBytes(state.file.toPath())); state.file.delete();
        } finally { task.close(); }
    }

    @Test public void proxyRedirectsStayProxiedAndRejectUntrustedUpstreams() throws Exception {
        byte[] body = new byte[32]; String asset = "https://release-assets.githubusercontent.com/file?signature=temporary";
        for (String location : new String[]{asset, UpdateNetwork.PROXY + asset, "/" + asset, "another.apk",
                "http://github.com/file", "https://example.invalid/file", UpdateNetwork.PROXY + "https://example.invalid/file",
                "https://gh-proxy.com.evil.invalid/" + asset, "https://user@gh-proxy.com/" + asset,
                "https://gh-proxy.com:444/" + asset}) {
            boolean allowed = location.equals(asset) || location.equals(UpdateNetwork.PROXY + asset)
                    || location.equals("/" + asset) || location.equals("another.apk");
            java.util.List<String> requests = new java.util.ArrayList<>();
            UpdateDownloadTask task = new UpdateDownloadTask(context, data(body), true, url -> {
                requests.add(url.toString()); assertEquals("gh-proxy.com", url.getHost());
                Connection connection = new Connection(url, body);
                if (requests.size() == 1) { connection.status = 302; connection.location = location; }
                return connection;
            }, (file, metadata) -> { });
            try {
                UpdateDownloadTask.State state = done(task);
                assertEquals(location, allowed ? UpdateDownloadTask.Phase.READY : UpdateDownloadTask.Phase.ERROR, state.phase);
                assertEquals(location, allowed ? 2 : 1, requests.size());
                if (allowed) assertEquals(UpdateNetwork.PROXY + (location.equals("another.apk")
                        ? ProjectInfo.RELEASES + "/download/v3.4.0/another.apk" : asset), requests.get(1));
                if (state.file != null) state.file.delete();
            } finally { task.close(); }
        }
    }

    @Test public void proxyFailureNeverFallsBackAndTamperingNeverReachesPackageVerifier() throws Exception {
        byte[] expected = new byte[32], tampered = new byte[32]; Arrays.fill(tampered, (byte) 17);
        for (boolean offline : new boolean[]{true, false}) {
            java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
            UpdateDownloadTask task = new UpdateDownloadTask(context, data(expected), true, url -> {
                attempts.incrementAndGet(); assertEquals("gh-proxy.com", url.getHost());
                if (offline) throw new IOException("Proxy offline");
                return new Connection(url, tampered);
            }, (file, metadata) -> fail("Unverified APK inspected"));
            try {
                UpdateDownloadTask.State state = done(task);
                assertEquals(UpdateDownloadTask.Phase.ERROR, state.phase);
                assertEquals(offline ? UpdateDownloadTask.Error.NETWORK : UpdateDownloadTask.Error.HASH, state.error);
                assertEquals(1, attempts.get()); assertNull(state.file);
            } finally { task.close(); }
        }
    }

    @Test public void realWrongSignerWrongPackageAndOldApksAreRejected() throws Exception {
        for (String name : new String[]{"wrong-signer.apk", "other-package.apk", "current.apk"}) {
            byte[] bytes = fixture(name); File file = File.createTempFile("identity-", ".apk", context.getCacheDir());
            Files.write(file.toPath(), bytes);
            try {
                UpdateApkVerifier.verify(context, file, data(bytes)); fail("Rejected fixture accepted: " + name);
            } catch (UpdateDownloadTask.Failure error) {
                assertEquals(name.equals("wrong-signer.apk") ? UpdateDownloadTask.Error.SIGNATURE
                        : name.equals("other-package.apk") ? UpdateDownloadTask.Error.PACKAGE : UpdateDownloadTask.Error.VERSION, error.error);
            } finally { file.delete(); }
        }
    }

    @Test public void changedFileFailsRevalidationAndProviderCannotExposeOtherCacheFiles() throws Exception {
        byte[] body = new byte[32];
        UpdateDownloadTask task = new UpdateDownloadTask(context, data(body), url -> new Connection(url, body), (file, metadata) -> { });
        try {
            File file = done(task).file; Files.write(file.toPath(), new byte[31]); task.verifyAgain();
            assertEquals(UpdateDownloadTask.Phase.ERROR, done(task).phase); assertFalse(file.exists());
        } finally { task.close(); }
        File outside = File.createTempFile("private-", ".txt", context.getCacheDir());
        try { UpdateInstaller.intent(context, outside); fail("Unrelated cache exposed"); }
        catch (IllegalArgumentException expected) { } finally { outside.delete(); }
        android.content.pm.ProviderInfo provider = context.getPackageManager().resolveContentProvider(context.getPackageName() + ".updates", 0);
        assertNotNull(provider); assertFalse(provider.exported); assertTrue(provider.grantUriPermissions);
    }
}
