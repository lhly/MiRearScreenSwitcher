package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeSettingsTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    private Context isolatedContext(String suffix) {
        Context base = instrumentation.getTargetContext();
        return new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return base.getSharedPreferences("native_test_" + suffix + "_" + name, mode);
            }
        };
    }

    @Test public void migratesFlutterLongAndKeepsSelectedApps() {
        Context context = isolatedContext("upgrade");
        SharedPreferences legacy = context.getSharedPreferences("FlutterSharedPreferences", 0);
        SharedPreferences nativePrefs = context.getSharedPreferences("mrss_settings", 0);
        legacy.edit().clear().putBoolean("flutter.charging_animation_enabled", false)
                .putBoolean("flutter.always_wakeup_enabled", true).putBoolean("flutter.keep_screen_on_enabled", false)
                .putLong("flutter.notification_duration", 7200).commit();
        nativePrefs.edit().clear().putStringSet("notification_selected_apps", Collections.singleton("example.app")).commit();
        NativePreferences prefs = new NativePreferences(context);
        assertFalse(prefs.getBoolean("charging_animation_enabled"));
        assertTrue(prefs.getBoolean("always_wakeup_enabled"));
        assertFalse(prefs.getBoolean("keep_screen_on_enabled"));
        assertEquals(7200, prefs.getDuration());
        assertEquals(Collections.singleton("example.app"), nativePrefs.getStringSet("notification_selected_apps", null));
        // Reopening must honor new native changes instead of importing a stale Flutter value again.
        nativePrefs.edit().putBoolean("charging_animation_enabled", true).commit();
        assertTrue(new NativePreferences(context).getBoolean("charging_animation_enabled"));
    }

    @Test public void settingsStillReachLegacyTileAndNativeServiceReaders() {
        Context context = isolatedContext("contracts");
        context.getSharedPreferences("mrss_settings", 0).edit().clear().commit();
        context.getSharedPreferences("FlutterSharedPreferences", 0).edit().clear().commit();
        NativePreferences prefs = new NativePreferences(context);
        prefs.putBoolean("proximity_sensor_enabled", false);
        prefs.putBoolean("notification_service_enabled", true);
        prefs.putDuration(25);
        SharedPreferences legacy = context.getSharedPreferences("FlutterSharedPreferences", 0);
        SharedPreferences services = context.getSharedPreferences("mrss_settings", 0);
        assertFalse(legacy.getBoolean("flutter.proximity_sensor_enabled", true));
        assertTrue(legacy.getBoolean("flutter.notification_service_enabled", false));
        assertTrue(services.getBoolean("notification_service_enabled", false));
        assertEquals(25L, legacy.getLong("flutter.notification_duration", 0));
        assertEquals(25, services.getInt("notification_duration", 0));
    }

    @Test public void allPagesWorkAndPrivacyToggleSurvivesRecreation() throws Exception {
        MainActivity activity = launch();
        try {
            String title = NativeStrings.get(activity, "notifications_title");
            instrumentation.runOnMainSync(() -> findDescription(activity.getWindow().getDecorView(), title).performClick());
            instrumentation.waitForIdleSync();
            Switch hide = findSwitch(activity.getWindow().getDecorView(), NativeStrings.get(activity, "hide_notification_title"));
            assertNotNull(hide);
            boolean old = hide.isChecked();
            instrumentation.runOnMainSync(() -> hide.setChecked(!old));
            assertEquals(!old, activity.getSharedPreferences("mrss_settings", 0).getBoolean("notification_privacy_hide_title", old));
            assertEquals(!old, activity.getSharedPreferences("FlutterSharedPreferences", 0).getBoolean("flutter.notification_privacy_hide_title", old));
            instrumentation.runOnMainSync(() -> {
                assertNotNull(findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "auto_destroy_time")));
                findDescription(activity.getWindow().getDecorView(), NativeStrings.get(activity, "about")).performClick();
                assertNotNull(findText(activity.getWindow().getDecorView(), "MRSS"));
                activity.onBackPressed();
                assertNotNull(findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "proximity_sensor")));
                activity.recreate();
            });
            instrumentation.waitForIdleSync();
            assertEquals(!old, new NativePreferences(instrumentation.getTargetContext()).getBoolean("notification_privacy_hide_title"));
        } finally {
            instrumentation.runOnMainSync(() -> {
                MainActivity current = MainActivity.getCurrentInstance();
                if (current != null && !current.isDestroyed()) current.finish();
            });
        }
    }

    @Test public void appSearchSystemFilterAndVisibleBulkSelection() throws Exception {
        MainActivity activity = launch();
        try {
            activity.getSharedPreferences("mrss_settings", 0).edit()
                    .putStringSet("notification_selected_apps", Collections.singleton("outside.app")).commit();
            instrumentation.runOnMainSync(() -> {
                findDescription(activity.getWindow().getDecorView(), NativeStrings.get(activity, "notifications_title")).performClick();
                ((View) findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "notification_apps")).getParent().getParent()).performClick();
                Switch system = findSwitch(activity.getWindow().getDecorView(), NativeStrings.get(activity, "system_apps"));
                system.setChecked(true);
                EditText search = findType(activity.getWindow().getDecorView(), EditText.class);
                search.setText("com.android.settings");
            });
            long deadline = System.currentTimeMillis() + 5000;
            ListView list = findType(activity.getWindow().getDecorView(), ListView.class);
            while (list.getAdapter().getCount() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50);
            int matching = list.getAdapter().getCount();
            assertTrue(matching > 0);
            instrumentation.runOnMainSync(() -> findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "select_all")).performClick());
            Set<String> selected = activity.getSharedPreferences("mrss_settings", 0).getStringSet("notification_selected_apps", Collections.emptySet());
            assertTrue(selected.contains("com.android.settings"));
            assertTrue(selected.contains("outside.app"));
            assertEquals(matching + 1, selected.size());
            instrumentation.runOnMainSync(() -> findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "deselect_all")).performClick());
            assertFalse(activity.getSharedPreferences("mrss_settings", 0).getStringSet("notification_selected_apps", Collections.emptySet()).contains("com.android.settings"));
            assertEquals(Collections.singleton("outside.app"), activity.getSharedPreferences("mrss_settings", 0)
                    .getStringSet("notification_selected_apps", Collections.emptySet()));
            instrumentation.runOnMainSync(activity::onBackPressed);
            assertNotNull(findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "auto_destroy_time")));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void numericDraftSurvivesActivityRecreation() throws Exception {
        MainActivity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                findDescription(activity.getWindow().getDecorView(), NativeStrings.get(activity, "notifications_title")).performClick();
                ((View) findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "auto_destroy_time")).getParent().getParent()).performClick();
            });
            instrumentation.waitForIdleSync();
            android.view.accessibility.AccessibilityNodeInfo input = activeNumberField();
            assertNotNull(input);
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "137");
            assertTrue(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args));
            instrumentation.runOnMainSync(activity::recreate);
            instrumentation.waitForIdleSync();
            android.view.accessibility.AccessibilityNodeInfo restored = activeNumberField();
            assertNotNull(restored);
            assertEquals("137", String.valueOf(restored.getText()));
        } finally {
            instrumentation.runOnMainSync(() -> {
                MainActivity current = MainActivity.getCurrentInstance();
                if (current != null) current.finish();
            });
        }
    }

    private android.view.accessibility.AccessibilityNodeInfo activeNumberField() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        do {
            android.view.accessibility.AccessibilityNodeInfo field = numberField(instrumentation.getUiAutomation().getRootInActiveWindow());
            if (field != null) return field;
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private static android.view.accessibility.AccessibilityNodeInfo numberField(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ("android.widget.EditText".contentEquals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = numberField(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private MainActivity launch() {
        Context context = instrumentation.getTargetContext();
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync(); return activity;
    }

    private static View findDescription(View view, String text) {
        if (text.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View match = findDescription(((ViewGroup) view).getChildAt(i), text); if (match != null) return match;
        }
        return null;
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView match = findText(((ViewGroup) view).getChildAt(i), text); if (match != null) return match;
        }
        return null;
    }
    private static Switch findSwitch(View view, String label) {
        if (view instanceof Switch && String.valueOf(view.getContentDescription()).startsWith(label)) return (Switch) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Switch match = findSwitch(((ViewGroup) view).getChildAt(i), label); if (match != null) return match;
        }
        return null;
    }
    private static <T> T findType(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T match = findType(((ViewGroup) view).getChildAt(i), type); if (match != null) return match;
        }
        return null;
    }
}
