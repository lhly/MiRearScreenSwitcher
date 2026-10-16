package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 26)
public class UpdateDownloadUiTest {
    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    final AtomicReference<Intent> installer = new AtomicReference<>(), permission = new AtomicReference<>();
    final AtomicInteger installs = new AtomicInteger();
    File fixture;
    Instrumentation.ActivityMonitor monitor;

    @Before public void prepare() throws Exception {
        byte[] bytes = new UpdateDownloadTest().fixture("candidate.apk");
        fixture = File.createTempFile("ui-update-", ".apk", instrumentation.getTargetContext().getCacheDir()); Files.write(fixture.toPath(), bytes);
        FixtureUpdateActivity.tasks.set(0);
        monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if ("application/vnd.android.package-archive".equals(intent.getType())) { installer.set(intent); installs.incrementAndGet(); }
                else if (android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES.equals(intent.getAction())) permission.set(intent);
                else return null;
                return new Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
    }

    @After public void cleanup() {
        instrumentation.removeMonitor(monitor);
        instrumentation.runOnMainSync(() -> { if (FixtureUpdateActivity.current != null) FixtureUpdateActivity.current.finish(); });
        instrumentation.waitForIdleSync(); fixture.delete();
    }

    FixtureUpdateActivity launch(boolean slow, boolean permissionAllowed, boolean failFirst, boolean badHash) throws Exception {
        byte[] bytes = Files.readAllBytes(fixture.toPath());
        UpdatePackage data = new UpdatePackage("v3.4.0", "3.4.0", BuildConfig.VERSION_CODE + 1,
                ProjectInfo.RELEASES + "/download/v3.4.0/MRSS-v3.4.0.apk",
                badHash ? new String(new char[64]).replace('\0', 'f') : UpdateApkVerifier.hex(UpdateApkVerifier.sha256().digest(bytes)),
                bytes.length, ProjectInfo.RELEASE_PACKAGE);
        Intent intent = UpdateDownloadActivity.intent(instrumentation.getTargetContext(), data)
                .setClass(instrumentation.getTargetContext(), FixtureUpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("fixture_file", fixture.getAbsolutePath()).putExtra("fixture_slow", slow)
                .putExtra("fixture_permission", permissionAllowed).putExtra("fixture_fail_first", failFirst);
        return (FixtureUpdateActivity) instrumentation.startActivitySync(intent);
    }

    View text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = text(((ViewGroup) view).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }

    void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(30);
        assertTrue("Timed out waiting for update UI", condition.getAsBoolean());
    }

    void waitText(int resource) throws Exception {
        AtomicReference<View> found = new AtomicReference<>();
        waitFor(() -> { instrumentation.runOnMainSync(() -> {
            FixtureUpdateActivity activity = FixtureUpdateActivity.current;
            found.set(activity == null ? null : text(activity.getWindow().getDecorView(), activity.getString(resource)));
        }); return found.get() != null; });
    }

    void click(int resource) {
        instrumentation.runOnMainSync(() -> {
            FixtureUpdateActivity activity = FixtureUpdateActivity.current;
            View view = text(activity.getWindow().getDecorView(), activity.getString(resource)); assertNotNull(view); assertTrue(view.performClick());
        });
    }

    void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync();
        android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(), name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
    }

    @Test public void progressSurvivesRecreationAndVerifiedApkOpensInstallerOnce() throws Exception {
        FixtureUpdateActivity activity = launch(true, true, false, false);
        waitText(R.string.ui_downloading_update);
        Thread.sleep(250); screenshot("update-progress.png");
        instrumentation.runOnMainSync(activity::recreate);
        waitFor(() -> FixtureUpdateActivity.current != null && FixtureUpdateActivity.current != activity);
        waitFor(() -> installer.get() != null);
        assertEquals(1, FixtureUpdateActivity.tasks.get()); assertEquals(1, installs.get());
        assertEquals("content", installer.get().getData().getScheme());
        assertEquals(instrumentation.getTargetContext().getPackageName() + ".updates", installer.get().getData().getAuthority());
        waitText(R.string.ui_install_update);
        assertTrue((installer.get().getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test public void installPermissionDenialKeepsVerifiedFileAndGrantReturnContinues() throws Exception {
        launch(false, false, false, false); waitText(R.string.ui_allow_install);
        screenshot("update-install-permission.png");
        assertNull(installer.get()); click(R.string.ui_allow_install);
        waitFor(() -> permission.get() != null);
        assertEquals("package:" + instrumentation.getTargetContext().getPackageName(), permission.get().getDataString());
        FixtureUpdateActivity old = FixtureUpdateActivity.current;
        instrumentation.runOnMainSync(() -> { old.getIntent().putExtra("fixture_permission", true); old.recreate(); });
        waitFor(() -> installer.get() != null); assertEquals(1, FixtureUpdateActivity.tasks.get());
    }

    @Test public void networkFailureCanRetryToSuccessfulVerifiedInstallation() throws Exception {
        launch(false, true, true, false); waitText(R.string.ui_download_failed); assertNull(installer.get());
        click(R.string.ui_retry); waitFor(() -> installer.get() != null); assertEquals(2, FixtureUpdateActivity.tasks.get());
    }

    @Test public void hashFailureNeverLaunchesInstallerAndShowsRetry() throws Exception {
        launch(false, true, false, true); waitText(R.string.ui_download_integrity_error);
        assertNull(installer.get()); waitText(R.string.ui_retry);
    }

    @Test public void leavingDownloadPageCancelsWithoutOpeningInstaller() throws Exception {
        FixtureUpdateActivity activity = launch(true, true, false, false); waitText(R.string.ui_downloading_update);
        click(R.string.ui_cancel); waitFor(() -> activity.isDestroyed()); assertNull(installer.get());
    }
}
