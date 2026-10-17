package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PermissionChecksTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void vendorPoliciesStayManualInsteadOfClaimingAuthorization() {
        assertEquals(PermissionChecks.State.MANUAL, PermissionChecks.read(context, PermissionChecks.Item.AUTOSTART));
        assertEquals(PermissionChecks.State.MANUAL, PermissionChecks.read(context, PermissionChecks.Item.BACKGROUND));
    }

    @Test public void unreadablePermissionStaysUnknown() {
        Context unavailable = new ContextWrapper(context) {
            @Override public Object getSystemService(String name) { throw new SecurityException("test restriction"); }
        };
        assertEquals(PermissionChecks.State.UNKNOWN, PermissionChecks.read(unavailable, PermissionChecks.Item.NOTIFICATIONS));
        assertEquals(PermissionChecks.State.UNKNOWN, PermissionChecks.read(unavailable, PermissionChecks.Item.BATTERY));
    }

    @Test public void settingsTargetCurrentPackageAndExactListener() {
        Intent overlay = PermissionChecks.settingsIntents(context, PermissionChecks.Item.OVERLAY).get(0);
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay.getAction());
        assertEquals("package:" + context.getPackageName(), overlay.getDataString());
        if (Build.VERSION.SDK_INT >= 30) {
            Intent listener = PermissionChecks.settingsIntents(context, PermissionChecks.Item.LISTENER).get(0);
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, listener.getAction());
            assertEquals(new android.content.ComponentName(context, NotificationService.class).flattenToString(),
                    listener.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME));
        }
        if (Build.VERSION.SDK_INT >= 26) {
            Intent notification = PermissionChecks.settingsIntents(context, PermissionChecks.Item.NOTIFICATIONS).get(0);
            assertEquals(context.getPackageName(), notification.getStringExtra(Settings.EXTRA_APP_PACKAGE));
            Intent install = PermissionChecks.settingsIntents(context, PermissionChecks.Item.INSTALL).get(0);
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, install.getAction());
            assertEquals("package:" + context.getPackageName(), install.getDataString());
        }
    }

    @Test public void unavailableAndPrivateVendorPagesFallBackWithoutCrashing() {
        List<Intent> opened = new ArrayList<>();
        Context restricted = new ContextWrapper(context) {
            @Override public void startActivity(Intent intent) {
                opened.add(intent);
                if (opened.size() == 1) throw new SecurityException("private ROM activity");
                if (opened.size() == 2) throw new ActivityNotFoundException("no app info");
            }
        };
        assertTrue(PermissionChecks.openSettings(restricted, PermissionChecks.Item.AUTOSTART));
        assertEquals(3, opened.size());
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.get(1).getAction());
        assertEquals(Settings.ACTION_SETTINGS, opened.get(2).getAction());
    }

    @Test public void allSettingsUnavailableReturnsFailure() {
        Context unavailable = new ContextWrapper(context) {
            @Override public void startActivity(Intent intent) { throw new ActivityNotFoundException(); }
        };
        assertFalse(PermissionChecks.openSettings(unavailable, PermissionChecks.Item.OVERLAY));
    }

    @Test public void batteryWhitelistChangesAreDetected() throws Exception {
        boolean original = PermissionChecks.read(context, PermissionChecks.Item.BATTERY) == PermissionChecks.State.GRANTED;
        try {
            shell("cmd deviceidle whitelist +" + context.getPackageName());
            assertEquals(PermissionChecks.State.GRANTED, PermissionChecks.read(context, PermissionChecks.Item.BATTERY));
            shell("cmd deviceidle whitelist -" + context.getPackageName());
            assertEquals(PermissionChecks.State.DENIED, PermissionChecks.read(context, PermissionChecks.Item.BATTERY));
        } finally { shell("cmd deviceidle whitelist " + (original ? "+" : "-") + context.getPackageName()); }
    }

    @Test @SdkSuppress(minSdkVersion = 27) public void exactNotificationListenerGrantAndRevokeAreDetected() throws Exception {
        String component = new android.content.ComponentName(context, NotificationService.class).flattenToString();
        boolean original = PermissionChecks.listenerGranted(context);
        try {
            shell("cmd notification allow_listener " + component);
            assertEquals(PermissionChecks.State.GRANTED, PermissionChecks.read(context, PermissionChecks.Item.LISTENER));
            shell("cmd notification disallow_listener " + component);
            assertEquals(PermissionChecks.State.DENIED, PermissionChecks.read(context, PermissionChecks.Item.LISTENER));
        } finally { shell("cmd notification " + (original ? "allow_listener " : "disallow_listener ") + component); }
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

}
