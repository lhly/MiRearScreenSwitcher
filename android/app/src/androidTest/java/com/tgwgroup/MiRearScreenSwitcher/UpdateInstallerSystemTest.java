package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Intent;
import android.graphics.Bitmap;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in smoke check opens Android's real installer and cancels without installing. */
public class UpdateInstallerSystemTest {
    @Test public void realSystemInstallerAcceptsVerifiedContentUriWithoutInstalling() throws Exception {
        Assume.assumeTrue("Only run explicitly on the isolated test emulator",
                "true".equals(InstrumentationRegistry.getArguments().getString("verifyInstallerUi")));
        UpdateDownloadTest helpers = new UpdateDownloadTest();
        byte[] bytes = helpers.fixture("candidate.apk");
        UpdatePackage update = helpers.data(bytes);
        UpdateDownloadTask task = new UpdateDownloadTask(helpers.context, update,
                url -> new UpdateDownloadTest.Connection(url, bytes),
                (file, metadata) -> UpdateApkVerifier.verifyPackage(helpers.context, file, metadata));
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Activity host = (android.app.Activity) instrumentation.startActivitySync(
                new Intent(helpers.context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            UpdateDownloadTask.State state = helpers.done(task);
            assertEquals(UpdateDownloadTask.Phase.READY, state.phase);
            Intent intent = UpdateDownloadActivity.installerIntent(helpers.context, state.file);
            instrumentation.runOnMainSync(() -> host.startActivity(intent));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            long stable = 0;
            String foreground = "";
            while (System.nanoTime() < deadline) {
                android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                foreground = root == null ? "" : String.valueOf(root.getPackageName());
                if ((foreground.contains("packageinstaller") || foreground.contains("permissioncontroller")) && hasButton(root)) {
                    if (stable == 0) stable = System.nanoTime();
                    if (System.nanoTime() - stable > TimeUnit.MILLISECONDS.toNanos(600)) break;
                } else stable = 0;
                Thread.sleep(100);
            }
            android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            assertTrue("System installer was not interactive: " + foreground,
                    (foreground.contains("packageinstaller") || foreground.contains("permissioncontroller")) && hasButton(root));
            System.out.println("Verified APK opened by system component: " + foreground);
            instrumentation.waitForIdleSync();
            Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
            try (FileOutputStream output = new FileOutputStream(new File(helpers.context.getCacheDir(), "update-system-installer.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            // Never approve unknown-source permissions or click Install in this smoke check.
            instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            instrumentation.waitForIdleSync();
            assertEquals(BuildConfig.VERSION_CODE, helpers.context.getPackageManager().getPackageInfo(helpers.context.getPackageName(), 0).versionCode);
        } finally { task.close(); instrumentation.runOnMainSync(host::finish); }
    }

    private boolean hasButton(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return false;
        if ("android.widget.Button".contentEquals(node.getClassName()) && node.getText() != null) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (hasButton(node.getChild(i))) return true;
        return false;
    }
}
