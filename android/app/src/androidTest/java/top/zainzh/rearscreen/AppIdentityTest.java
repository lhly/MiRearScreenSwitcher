package top.zainzh.rearscreen;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppIdentityTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void applicationComponentsProvidersAndAidlUseTheNewIdentity() throws Exception {
        String id = "top.zainzh.rearscreen.debug";
        assertEquals(id, context.getPackageName());
        assertEquals("ReMRSS 调试版", context.getApplicationInfo().loadLabel(context.getPackageManager()).toString());
        assertEquals("top.zainzh.rearscreen.MyApplication", context.getApplicationInfo().className);
        assertEquals("top.zainzh.rearscreen.ITaskService", ITaskService.class.getName());
        assertEquals("top.zainzh.rearscreen.ITaskService$Stub", ITaskService.Stub.class.getName());
        PackageManager manager = context.getPackageManager();
        PackageInfo info = manager.getPackageInfo(id, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
        for (ComponentInfo[] components : new ComponentInfo[][]{info.activities, info.services, info.receivers, info.providers}) {
            if (components != null) for (ComponentInfo component : components) {
                assertEquals(id, component.packageName);
                assertFalse(component.name, component.name.startsWith("com.tgwgroup."));
            }
        }
        assertEquals("top.zainzh.rearscreen.UpdateFileProvider", manager.resolveContentProvider(id + ".updates", 0).name);
        assertFalse(manager.resolveContentProvider(id + ".updates", 0).exported);
        assertEquals(id, manager.resolveContentProvider(id + ".shizuku", 0).packageName);
    }

    @Test public void automationKeepsTheExistingSchemeAfterPackageMigration() {
        PackageManager manager = context.getPackageManager();
        assertEquals("mrss-debug", BuildConfig.URI_SCHEME);
        Intent command = new Intent(Intent.ACTION_VIEW, Uri.parse("mrss-debug://switch?current=1"))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.getPackageName());
        ResolveInfo resolved = manager.resolveActivity(command, PackageManager.MATCH_DEFAULT_ONLY);
        assertNotNull(resolved);
        assertEquals(context.getPackageName(), resolved.activityInfo.packageName);
        assertEquals(UriReceiverActivity.class.getName(), resolved.activityInfo.name);
    }

    @Test public void updatesUseIsolatedFeedAndRejectLegacyPackageMetadata() {
        assertEquals("top.zainzh.rearscreen", ProjectInfo.RELEASE_PACKAGE);
        assertEquals("https://raw.githubusercontent.com/lhly/MiRearScreenSwitcher/main/updates/remrss/version.json", ProjectInfo.VERSION_MANIFEST);
        String legacy = ReleaseUpdateCheckerTest.release("v3.4.0")
                .replace("\"packageName\":\"" + ProjectInfo.RELEASE_PACKAGE + "\"",
                        "\"packageName\":\"com.tgwgroup.MiRearScreenSwitcher.personal\"");
        assertEquals(ReleaseUpdateChecker.Status.INVALID_RESPONSE, ReleaseUpdateChecker.evaluate(
                new ReleaseUpdateChecker.Response(200, legacy), BuildConfig.SOURCE_TAG, BuildConfig.VERSION_CODE).status);
    }
}
