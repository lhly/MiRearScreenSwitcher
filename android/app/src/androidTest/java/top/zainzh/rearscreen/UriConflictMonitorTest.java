package top.zainzh.rearscreen;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UriConflictMonitorTest {
    private ResolveInfo handler(String name, boolean exported, boolean enabled) {
        ResolveInfo resolved = new ResolveInfo(); resolved.activityInfo = new ActivityInfo();
        resolved.activityInfo.packageName = name;
        resolved.activityInfo.name = name + ".Receiver";
        resolved.activityInfo.exported = exported; resolved.activityInfo.enabled = enabled;
        return resolved;
    }

    @Test public void excludesOwnPrivateAndDisabledActivitiesAndDeduplicatesOtherApps() {
        List<ResolveInfo> found = UriConflictMonitor.findOtherHandlers("top.zainzh.rearscreen", "mrss", intent -> Arrays.asList(
                handler("top.zainzh.rearscreen", true, true), handler("example.z", true, true),
                handler("example.z", true, true), handler("example.a", true, true),
                handler("example.private", false, true), handler("example.disabled", true, false), new ResolveInfo()));
        assertEquals(2, found.size());
        assertEquals("example.a", found.get(0).activityInfo.packageName);
        assertEquals("example.z", found.get(1).activityInfo.packageName);
    }

    @Test public void detectsHostSpecificFiltersAcrossAllSupportedCommandsWithoutLaunchingAnything() {
        AtomicInteger queries = new AtomicInteger();
        List<ResolveInfo> found = UriConflictMonitor.findOtherHandlers("top.zainzh.rearscreen.debug", "mrss-debug", intent -> {
            queries.incrementAndGet();
            assertEquals(Intent.ACTION_VIEW, intent.getAction()); assertNull(intent.getComponent());
            assertNull(intent.getPackage()); assertEquals("mrss-debug", intent.getData().getScheme());
            return "screenshot".equals(intent.getData().getHost())
                    ? Collections.singletonList(handler("example.screenshot.only", true, true)) : Collections.emptyList();
        });
        assertEquals(4, queries.get()); assertEquals(1, found.size());
        assertEquals("example.screenshot.only", found.get(0).activityInfo.packageName);
    }

    @Test public void noOtherHandlersProducesNoConflict() {
        assertTrue(UriConflictMonitor.findOtherHandlers("top.zainzh.rearscreen", "mrss", intent ->
                Collections.singletonList(handler("top.zainzh.rearscreen", true, true))).isEmpty());
    }
}
