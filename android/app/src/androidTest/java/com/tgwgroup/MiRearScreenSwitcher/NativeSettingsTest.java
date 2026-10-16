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
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeSettingsTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void compactAboutKeepsCreditsAndReadsEntireLicenseFromDiagnostics() throws Exception {
        MainActivity activity = launch();
        try {
            navigate(activity, "about");
            instrumentation.runOnMainSync(() -> {
                assertNotNull(findText(activity.getWindow().getDecorView(), "MRSS"));
                assertNull(findText(activity.getWindow().getDecorView(), "MRSS 交流群"));
                assertNull(findText(activity.getWindow().getDecorView(), "请作者喝咖啡"));
                assertNotNull(findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "check_updates")));
                assertNotNull(findText(activity.getWindow().getDecorView(), "GitHub · lhly/MiRearScreenSwitcher\n"
                        + NativeStrings.get(activity, "project_maintainer").replace("{0}", ProjectInfo.MAINTAINER)));
                assertNotNull(findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "version_label")
                        .replace("{0}", BuildConfig.VERSION_NAME)));
                assertNotNull(findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "author_anti")));
                for (String removed : new String[]{"version_source", "author_xmz", "tutorial", "license", "restart_app"}) {
                    assertNull(removed, findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, removed)));
                }
                ((View) findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "diagnostics")).getParent().getParent()).performClick();
            });
            android.view.accessibility.AccessibilityNodeInfo diagnostics = waitDialogButton(NativeStrings.get(activity, "license"));
            assertTrue(nodeText(diagnostics), nodeText(diagnostics).contains(activity.getPackageName()));
            assertNotNull(buttonNode(diagnostics, NativeStrings.get(activity, "restart_app")));
            assertTrue(buttonNode(diagnostics, NativeStrings.get(activity, "license"))
                    .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            android.view.accessibility.AccessibilityNodeInfo node = null;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                node = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (node != null && !node.findAccessibilityNodeInfosByText("GNU GENERAL PUBLIC LICENSE").isEmpty()) break;
                Thread.sleep(50);
            }
            assertNotNull("License dialog must become the active window", node);
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> text = node.findAccessibilityNodeInfosByText("GNU GENERAL PUBLIC LICENSE");
            assertFalse("License must be readable without opening a browser or using Shizuku", text.isEmpty());
            String document = String.valueOf(text.get(0).getText());
            assertTrue(document.contains("冷火凉烟"));
            assertTrue(document.contains("Original author: AntiOblivionis"));
            assertTrue(document.contains("Upstream testing contributor: 汐木泽"));
            assertTrue("The complete license, including its final section, must be bundled", document.contains("https://www.gnu.org/licenses/why-not-lgpl.html"));
            assertTrue(document.contains("Apache License"));
            assertTrue(document.contains("MIUIX-LICENSE.txt"));
            if (android.os.Build.VERSION.SDK_INT >= 33) assertTrue(text.get(0).isTextSelectable());
            instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 26)
    @Test public void compactAboutLinksOpenRepositoryFeedbackAndOriginalProject() throws Exception {
        AtomicReference<String> opened = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_VIEW.equals(intent.getAction())) return null;
                opened.set(intent.getDataString());
                return new Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
        MainActivity activity = launch();
        try {
            navigate(activity, "about");
            instrumentation.runOnMainSync(() -> {
                String[][] targets = {
                        {"project_repository", "https://github.com/lhly/MiRearScreenSwitcher"},
                        {"issue_feedback", "https://github.com/lhly/MiRearScreenSwitcher/issues/new"},
                        {"author_anti", "https://github.com/AntiOblivionis/MiRearScreenSwitcher"},
                };
                for (String[] target : targets) {
                    opened.set(null);
                    ((View) findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, target[0])).getParent().getParent()).performClick();
                    assertEquals(target[0], target[1], opened.get());
                }
                ((View) findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "uri_title"))
                        .getParent().getParent()).performClick();
            });
            android.view.accessibility.AccessibilityNodeInfo automation = waitDialogButton(NativeStrings.get(activity, "uri_copy"));
            assertTrue(nodeText(automation), nodeText(automation).contains(BuildConfig.URI_SCHEME + "://switch?current=1"));
            instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        } finally {
            instrumentation.removeMonitor(monitor);
            instrumentation.runOnMainSync(activity::finish);
        }
    }

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
            navigate(activity, "notifications_title");
            instrumentation.waitForIdleSync();
            Switch hide = findSwitch(activity.getWindow().getDecorView(), NativeStrings.get(activity, "hide_notification_title"));
            assertNotNull(hide);
            boolean old = hide.isChecked();
            instrumentation.runOnMainSync(() -> hide.setChecked(!old));
            assertEquals(!old, activity.getSharedPreferences("mrss_settings", 0).getBoolean("notification_privacy_hide_title", old));
            assertEquals(!old, activity.getSharedPreferences("FlutterSharedPreferences", 0).getBoolean("flutter.notification_privacy_hide_title", old));
            instrumentation.runOnMainSync(() -> {
                assertNotNull(findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "auto_destroy_time")));
            });
            navigate(activity, "about");
            instrumentation.runOnMainSync(() -> {
                assertNotNull(findText(activity.getWindow().getDecorView(), "MRSS"));
                activity.onBackPressed();

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
            navigate(activity, "notifications_title");
            instrumentation.runOnMainSync(() -> {
                ((View) findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "notification_apps")).getParent().getParent()).performClick();
            });
            waitForNativeText(activity, "system_apps");
            instrumentation.runOnMainSync(() -> {
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
            navigate(activity, "notifications_title");
            instrumentation.runOnMainSync(() -> {
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

    @Test public void homeDpiDraftSurvivesActivityRecreation() throws Exception {
        MainActivity activity = launch();
        try {
            String label = NativeStrings.get(activity, "dpi_caption");
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes = Collections.emptyList();
            long deadline = System.currentTimeMillis() + 5000;
            while (nodes.isEmpty() && System.currentTimeMillis() < deadline) {
                android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                android.view.accessibility.AccessibilityNodeInfo found = textNode(root, label);
                if (found != null) nodes = Collections.singletonList(found);
                if (nodes.isEmpty()) Thread.sleep(50);
            }
            assertFalse(nodes.isEmpty());
            android.view.accessibility.AccessibilityNodeInfo target = nodes.get(0);
            while (target != null && !target.isClickable()) target = target.getParent();
            assertNotNull(target);
            assertTrue(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            android.view.accessibility.AccessibilityNodeInfo input = activeNumberField();
            assertNotNull(input);
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "420");
            assertTrue(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args));
            instrumentation.runOnMainSync(activity::recreate);
            instrumentation.waitForIdleSync();
            assertEquals("420", String.valueOf(activeNumberField().getText()));
        } finally {
            instrumentation.runOnMainSync(() -> { MainActivity current = MainActivity.getCurrentInstance(); if (current != null) current.finish(); });
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

    private NativeSettingsUi aboutWithChecker(MainActivity activity, ReleaseUpdateChecker.Fetcher fetcher) {
        AtomicReference<NativeSettingsUi> ui = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            android.os.Bundle state = new android.os.Bundle(); state.putInt("native_page", 2);
            NativeSettingsUi settings = new NativeSettingsUi(activity, state, new ReleaseUpdateChecker(fetcher));
            activity.setContentView(settings.getView()); settings.onResume(); ui.set(settings);
        });
        instrumentation.waitForIdleSync();
        waitForNativeText(activity, "check_updates");
        return ui.get();
    }

    private android.view.accessibility.AccessibilityNodeInfo waitDialogButton(String text) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (buttonNode(root, text) != null) return root;
            Thread.sleep(50);
        }
        fail("Dialog not found: " + text); return null;
    }

    private android.view.accessibility.AccessibilityNodeInfo buttonNode(android.view.accessibility.AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if ("android.widget.Button".contentEquals(node.getClassName()) && label.contentEquals(node.getText())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = buttonNode(node.getChild(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private String nodeText(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder text = new StringBuilder(String.valueOf(node.getText()));
        for (int i = 0; i < node.getChildCount(); i++) text.append('\n').append(nodeText(node.getChild(i)));
        return text.toString();
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 26)
    @Test public void availableUpdateOffersThisRepositoryReleaseAndPreventsDuplicateTaps() throws Exception {
        MainActivity activity = launch();
        java.util.concurrent.CountDownLatch unblock = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> opened = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_VIEW.equals(intent.getAction())) return null;
                opened.set(intent.getDataString());
                return new Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
        NativeSettingsUi settings = aboutWithChecker(activity, () -> {
            try { unblock.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException error) { throw new java.io.IOException(error); }
            return new ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.release("v9999.0.0"));
        });
        try {
            instrumentation.runOnMainSync(() -> {
                View row = (View) findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "check_updates")).getParent().getParent();
                row.performClick();
                assertFalse(row.isEnabled());
                assertNotNull(findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "checking_updates")));
            });
            unblock.countDown();
            android.view.accessibility.AccessibilityNodeInfo root = waitDialogButton(NativeStrings.get(activity, "view_release"));
            assertTrue(nodeText(root), nodeText(root).contains("v9999.0.0"));
            assertTrue(buttonNode(root, NativeStrings.get(activity, "view_release"))
                    .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            instrumentation.waitForIdleSync();
            assertEquals(ProjectInfo.RELEASES + "/tag/v9999.0.0", opened.get());
        } finally {
            unblock.countDown(); instrumentation.removeMonitor(monitor);
            instrumentation.runOnMainSync(() -> { settings.destroy(); activity.finish(); });
        }
    }

    @Test public void failedUpdateCheckShowsRetryAndThenAccurateCurrentResult() throws Exception {
        MainActivity activity = launch();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        NativeSettingsUi settings = aboutWithChecker(activity, () -> {
            if (attempts.incrementAndGet() == 1) throw new java.io.IOException("offline");
            return new ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.manifest(BuildConfig.SOURCE_TAG, BuildConfig.VERSION_CODE));
        });
        try {
            instrumentation.runOnMainSync(() -> ((View) findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "check_updates"))
                    .getParent().getParent()).performClick());
            android.view.accessibility.AccessibilityNodeInfo root = waitDialogButton(NativeStrings.get(activity, "retry"));
            assertTrue(nodeText(root), nodeText(root).contains(NativeStrings.get(activity, "update_network_error")));
            assertTrue(buttonNode(root, NativeStrings.get(activity, "retry"))
                    .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            root = waitDialogButton(NativeStrings.get(activity, "view_releases"));
            assertTrue(nodeText(root), nodeText(root).contains(NativeStrings.get(activity, "update_current")));
            assertEquals(2, attempts.get());
        } finally { instrumentation.runOnMainSync(() -> { settings.destroy(); activity.finish(); }); }
    }

    @Test public void missingRawManifestOffersRetryInsteadOfReportingLatestVersion() throws Exception {
        MainActivity activity = launch();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        NativeSettingsUi settings = aboutWithChecker(activity, () -> attempts.incrementAndGet() == 1
                ? new ReleaseUpdateChecker.Response(404, "")
                : new ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.manifest(BuildConfig.SOURCE_TAG, BuildConfig.VERSION_CODE)));
        try {
            instrumentation.runOnMainSync(() -> ((View) findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, "check_updates"))
                    .getParent().getParent()).performClick());
            android.view.accessibility.AccessibilityNodeInfo root = waitDialogButton(NativeStrings.get(activity, "retry"));
            assertTrue(nodeText(root), nodeText(root).contains(NativeStrings.get(activity, "update_none")));
            assertFalse(nodeText(root), nodeText(root).contains(NativeStrings.get(activity, "update_current")));
            assertNotNull(buttonNode(root, NativeStrings.get(activity, "view_releases")));
            assertTrue(buttonNode(root, NativeStrings.get(activity, "retry"))
                    .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            root = waitDialogButton(NativeStrings.get(activity, "view_releases"));
            assertTrue(nodeText(root), nodeText(root).contains(NativeStrings.get(activity, "update_current")));
        } finally { instrumentation.runOnMainSync(() -> { settings.destroy(); activity.finish(); }); }
    }

    @Test public void availableUpdateShowsTargetVersionOnlyOnce() throws Exception {
        MainActivity activity = launch();
        NativeSettingsUi settings = aboutWithChecker(activity, () -> new ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.release("v3.4.0")));
        try {
            instrumentation.runOnMainSync(settings::checkForUpdates);
            android.view.accessibility.AccessibilityNodeInfo root = waitDialogButton(activity.getString(R.string.ui_download_update));
            String contents = nodeText(root);
            assertEquals(contents, 1, contents.split("v3.4.0", -1).length - 1);
            assertTrue(contents, contents.contains(activity.getString(R.string.ui_current_version).replace("{0}", BuildConfig.VERSION_NAME)));
            assertNotNull(buttonNode(root, NativeStrings.get(activity, "view_release")));
        } finally { instrumentation.runOnMainSync(() -> { settings.destroy(); activity.finish(); }); }
    }

    private void navigate(MainActivity activity, String key) throws Exception {
        String label = NativeStrings.get(activity, key);
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            android.view.accessibility.AccessibilityNodeInfo node = descriptionNode(instrumentation.getUiAutomation().getRootInActiveWindow(), label);
            if (node != null && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                while (System.currentTimeMillis() < deadline) {
                    android.view.accessibility.AccessibilityNodeInfo selected = descriptionNode(instrumentation.getUiAutomation().getRootInActiveWindow(), label);
                    if (selected != null && selected.isSelected()) {
                        if (key.equals("about")) waitForNativeText(activity, "check_updates");
                        else if (key.equals("notifications_title")) waitForNativeText(activity, "notification_apps");
                        instrumentation.waitForIdleSync(); return;
                    }
                    Thread.sleep(20);
                }
                break;
            }
            Thread.sleep(50);
        }
        fail("Navigation tab not found: " + label);
    }

    private void waitForNativeText(MainActivity activity, String key) {
        long deadline = System.currentTimeMillis() + 5000;
        AtomicReference<TextView> found = new AtomicReference<>();
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> found.set(findText(activity.getWindow().getDecorView(), NativeStrings.get(activity, key))));
            if (found.get() != null) return;
            try { Thread.sleep(20); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        }
        fail("Native page content not attached: " + key);
    }
    private android.view.accessibility.AccessibilityNodeInfo textNode(android.view.accessibility.AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (String.valueOf(node.getText()).contains(label)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = textNode(node.getChild(i), label);
            if (found != null) return found;
        }
        return null;
    }
    private android.view.accessibility.AccessibilityNodeInfo descriptionNode(android.view.accessibility.AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = descriptionNode(node.getChild(i), label);
            if (found != null) return found;
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
