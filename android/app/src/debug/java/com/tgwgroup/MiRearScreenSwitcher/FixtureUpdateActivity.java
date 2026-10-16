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
public final class FixtureUpdateActivity extends UpdateDownloadActivity {
    public static volatile FixtureUpdateActivity current;
    public static final AtomicInteger tasks = new AtomicInteger();
    private int attempts;

    @Override protected void onCreate(android.os.Bundle state) { super.onCreate(state); current = this; }
    @Override protected void onDestroy() { if (current == this) current = null; super.onDestroy(); }
    @Override boolean canInstall() { return getIntent().getBooleanExtra("fixture_permission", true); }

    @Override UpdateDownloadTask createTask(UpdatePackage update) {
        tasks.incrementAndGet(); attempts++;
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
            boolean fail = getIntent().getBooleanExtra("fixture_fail_first", false) && attempts == 1;
            boolean slow = getIntent().getBooleanExtra("fixture_slow", false);
            return new UpdateDownloadTask(getApplicationContext(), local, url -> {
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
            }, (file, data) -> UpdateApkVerifier.verifyPackage(getApplicationContext(), file, data));
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
