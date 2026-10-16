package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Instrumentation;
import android.content.Intent;
import androidx.lifecycle.ViewModelProvider;
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
        Intent intent = new Intent(instrumentation.getTargetContext(), FixtureUpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(FixtureUpdateActivity.EXTRA_MANIFEST, data.toJson())
                .putExtra("fixture_file", fixture.getAbsolutePath()).putExtra("fixture_slow", slow)
                .putExtra("fixture_permission", permissionAllowed).putExtra("fixture_fail_first", failFirst);
        FixtureUpdateActivity activity = (FixtureUpdateActivity) instrumentation.startActivitySync(intent);
        waitText(R.string.ui_download_update);
        return activity;
    }

    void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(30);
        assertTrue("Timed out waiting for update UI", condition.getAsBoolean());
    }

    void waitText(int resource) throws Exception {
        waitFor(() -> node(instrumentation.getUiAutomation().getRootInActiveWindow(), instrumentation.getTargetContext().getString(resource)) != null);
    }

    void click(int resource) {
        android.view.accessibility.AccessibilityNodeInfo view = node(instrumentation.getUiAutomation().getRootInActiveWindow(), instrumentation.getTargetContext().getString(resource));
        assertNotNull(view); assertTrue(view.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
    }

    android.view.accessibility.AccessibilityNodeInfo node(android.view.accessibility.AccessibilityNodeInfo root, String value) {
        if (root == null) return null;
        if (root.getText() != null && value.contentEquals(root.getText())) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = node(root.getChild(i), value); if (found != null) return found;
        }
        return null;
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
        int window = instrumentation.getUiAutomation().getRootInActiveWindow().getWindowId();
        click(R.string.ui_download_update);
        waitText(R.string.ui_downloading_update);
        assertEquals("Download must stay in the same dialog window", window, instrumentation.getUiAutomation().getRootInActiveWindow().getWindowId());
        Thread.sleep(250); screenshot("update-progress.png");
        instrumentation.runOnMainSync(activity::recreate);
        waitFor(() -> FixtureUpdateActivity.current != null && FixtureUpdateActivity.current != activity);
        waitFor(() -> installer.get() != null);
        assertEquals(1, FixtureUpdateActivity.tasks.get()); assertEquals(1, installs.get());
        assertEquals("content", installer.get().getData().getScheme());
        assertEquals(instrumentation.getTargetContext().getPackageName() + ".updates", installer.get().getData().getAuthority());
        waitText(R.string.ui_install_update);
        assertTrue((installer.get().getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        instrumentation.runOnMainSync(() -> { instrumentation.callActivityOnPause(FixtureUpdateActivity.current); instrumentation.callActivityOnResume(FixtureUpdateActivity.current); });
        assertEquals("Returning from a cancelled installer must not reopen it", 1, installs.get());
        click(R.string.ui_install_update); waitFor(() -> installs.get() == 2);
    }

    @Test public void installPermissionDenialKeepsVerifiedFileAndGrantReturnContinues() throws Exception {
        launch(false, false, false, false); click(R.string.ui_download_update); waitText(R.string.ui_allow_install);
        screenshot("update-install-permission.png");
        assertNull(installer.get()); click(R.string.ui_allow_install);
        waitFor(() -> permission.get() != null);
        assertEquals("package:" + instrumentation.getTargetContext().getPackageName(), permission.get().getDataString());
        FixtureUpdateActivity old = FixtureUpdateActivity.current;
        instrumentation.runOnMainSync(() -> { old.getIntent().putExtra("fixture_permission", true); old.recreate(); });
        waitFor(() -> installer.get() != null); assertEquals(1, FixtureUpdateActivity.tasks.get());
    }

    @Test public void networkFailureCanRetryToSuccessfulVerifiedInstallation() throws Exception {
        launch(false, true, true, false); click(R.string.ui_download_update); waitText(R.string.ui_download_failed); assertNull(installer.get());
        click(R.string.ui_retry); waitFor(() -> installer.get() != null); assertEquals(2, FixtureUpdateActivity.tasks.get());
    }

    @Test public void hashFailureNeverLaunchesInstallerAndShowsRetry() throws Exception {
        launch(false, true, false, true); click(R.string.ui_download_update); waitText(R.string.ui_download_integrity_error);
        assertNull(installer.get()); waitText(R.string.ui_retry);
    }

    @Test public void dismissingDialogCancelsDownloadAndKeepsAboutPage() throws Exception {
        FixtureUpdateActivity activity = launch(true, true, false, false); click(R.string.ui_download_update); waitText(R.string.ui_downloading_update);
        click(R.string.ui_cancel);
        waitFor(() -> node(instrumentation.getUiAutomation().getRootInActiveWindow(), activity.getString(R.string.ui_downloading_update)) == null);
        Thread.sleep(1200); assertFalse(activity.isDestroyed()); assertNull(installer.get());
        assertNotNull(node(instrumentation.getUiAutomation().getRootInActiveWindow(), activity.getString(R.string.ui_check_updates)));
    }

    @Test public void offerSurvivesRecreationWithoutStartingDownload() throws Exception {
        FixtureUpdateActivity activity = launch(false, false, false, false);
        assertEquals(0, FixtureUpdateActivity.tasks.get()); screenshot("update-offer.png");
        instrumentation.runOnMainSync(activity::recreate);
        waitFor(() -> FixtureUpdateActivity.current != null && FixtureUpdateActivity.current != activity);
        waitText(R.string.ui_download_update); assertEquals(0, FixtureUpdateActivity.tasks.get());
        instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        waitFor(() -> node(instrumentation.getUiAutomation().getRootInActiveWindow(), activity.getString(R.string.ui_download_update)) == null);
        assertFalse(FixtureUpdateActivity.current.isDestroyed());
    }

    UpdateDialogController.Session session(FixtureUpdateActivity activity) {
        return new ViewModelProvider(activity, new ViewModelProvider.NewInstanceFactory()).get(UpdateDialogController.Session.class);
    }

    @Test public void backgroundCompletionWaitsForResumeBeforeOpeningInstaller() throws Exception {
        FixtureUpdateActivity activity = launch(true, true, false, false); click(R.string.ui_download_update);
        waitText(R.string.ui_downloading_update);
        AtomicReference<UpdateDialogController.Session> retained = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { retained.set(session(activity)); instrumentation.callActivityOnPause(activity); });
        waitFor(() -> retained.get().task.state().phase == UpdateDownloadTask.Phase.READY);
        assertNull(installer.get());
        instrumentation.runOnMainSync(() -> instrumentation.callActivityOnResume(activity));
        waitFor(() -> installer.get() != null); assertEquals(1, installs.get()); assertEquals(1, FixtureUpdateActivity.tasks.get());
    }

    @Test public void permissionReturnRevalidatesFileBeforeInstallation() throws Exception {
        FixtureUpdateActivity activity = launch(false, false, false, false); click(R.string.ui_download_update); waitText(R.string.ui_allow_install);
        AtomicReference<File> apk = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> apk.set(session(activity).task.state().file));
        Files.write(apk.get().toPath(), new byte[]{0});
        click(R.string.ui_allow_install); waitFor(() -> permission.get() != null);
        instrumentation.runOnMainSync(() -> { activity.getIntent().putExtra("fixture_permission", true); activity.recreate(); });
        waitText(R.string.ui_download_integrity_error); assertNull(installer.get()); assertEquals(1, FixtureUpdateActivity.tasks.get());
    }
}
