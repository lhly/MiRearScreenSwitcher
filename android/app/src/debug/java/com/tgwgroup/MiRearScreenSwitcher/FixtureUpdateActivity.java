package com.tgwgroup.MiRearScreenSwitcher;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

/** Debug-only local APK transport. Production downloads cannot select this transport. */
public final class FixtureUpdateActivity extends MainActivity {
    public static volatile FixtureUpdateActivity current;
    public static final AtomicInteger tasks = new AtomicInteger();
    static final String EXTRA_MANIFEST = "version_manifest";

    @Override protected void onCreate(android.os.Bundle state) {
        boolean first = state == null;
        if (first) { state = new android.os.Bundle(); state.putInt("native_page", 2); }
        super.onCreate(state); current = this;
        if (first) getWindow().getDecorView().post(this::checkForUpdates);
    }
    @Override ReleaseUpdateChecker createUpdateChecker() {
        return new ReleaseUpdateChecker(() -> new ReleaseUpdateChecker.Response(200, getIntent().getStringExtra(EXTRA_MANIFEST)));
    }
    @Override protected void onDestroy() { if (current == this) current = null; super.onDestroy(); }
    @Override boolean canInstallUpdates() { return getIntent().getBooleanExtra("fixture_permission", true); }

    @Override UpdateDownloadTask createUpdateTask(UpdatePackage update) {
        int attempt = tasks.incrementAndGet();
        try {
            File fixture = new File(getIntent().getStringExtra("fixture_file"));
            byte[] bytes;
            try (InputStream input = new FileInputStream(fixture); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
                bytes = out.toByteArray();
            }
            android.content.pm.PackageInfo apk = getPackageManager().getPackageArchiveInfo(fixture.getAbsolutePath(), 0);
            // A debug APK is inspected against the installed debug app. Production uses the release package.
            UpdatePackage local = new UpdatePackage(update.tag, apk.versionName, update.versionCode, update.url,
                    update.sha256, update.size, getPackageName());
            boolean fail = getIntent().getBooleanExtra("fixture_fail_first", false) && attempt == 1;
            boolean slow = getIntent().getBooleanExtra("fixture_slow", false);
            android.content.Context appContext = getApplicationContext();
            return new UpdateDownloadTask(appContext, local, url -> {
                if (fail) throw new IOException("Fixture offline");
                return new HttpURLConnection(url) {
                    @Override public int getResponseCode() { return 200; }
                    @Override public long getContentLengthLong() { return bytes.length; }
                    @Override public InputStream getInputStream() {
                        return new ByteArrayInputStream(bytes) {
                            @Override public synchronized int read(byte[] b, int off, int len) {
                                if (slow) try { Thread.sleep(20); } catch (InterruptedException ignored) { }
                                return super.read(b, off, Math.min(len, 32768));
                        } };
                    }
                    @Override public void disconnect() { }
                    @Override public void connect() { }
                    @Override public boolean usingProxy() { return false; }
                };
            }, (file, data) -> UpdateApkVerifier.verifyPackage(appContext, file, data));
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
