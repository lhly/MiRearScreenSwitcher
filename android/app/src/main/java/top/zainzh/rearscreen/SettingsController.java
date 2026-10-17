package top.zainzh.rearscreen;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import rikka.shizuku.Shizuku;

/** Settings actions and lifecycle. All visual rendering belongs to Compose + Miuix. */
final class SettingsController {
    private final MainActivity activity;
    final NativePreferences preferences;
    private final FrameLayout root;
    private final MiuixSettingsUi composeUi;
    private final MiuixDialogUi modal;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ReleaseUpdateChecker updateChecker;
    private final UpdateDialogController updateDialog;
    private final UriConflictMonitor uriConflicts;
    private java.util.List<String> uriConflictApps = Collections.emptyList();
    private boolean checkingUpdates;
    private ReleaseUpdateChecker.Result updateResult;
    private int page;
    private boolean destroyed, resumed, restoringServices, permissionPrompted;
    private boolean checkingDisplay, displayRead, remoteBusy;
    private String lastStatus = "";
    private int currentDpi, rotation;
    private String connectionSummary = "connection_help", connectionAction = "open_shizuku";
    private AppPickerModel picker;
    private Bundle pickerState;
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            refreshStatus();
            handler.postDelayed(this, 2500);
        }
    };

    SettingsController(MainActivity activity, Bundle state) {
        this(activity, state, activity.createUpdateChecker());
    }

    SettingsController(MainActivity activity, Bundle state, ReleaseUpdateChecker updateChecker) {
        this.activity = activity;
        this.updateChecker = updateChecker;
        updateDialog = new UpdateDialogController(activity, state, this::checkForUpdates);
        modal = new MiuixDialogUi(activity);
        preferences = new NativePreferences(activity);
        uriConflicts = new UriConflictMonitor(activity, this::updateUriConflicts);
        page = state == null ? 0 : state.getInt("native_page", 0);
        currentDpi = state == null ? 0 : state.getInt("native_dpi", 0);
        rotation = state == null ? -1 : state.getInt("native_rotation", -1);
        pickerState = state;
        root = new FrameLayout(activity);
        composeUi = new MiuixSettingsUi(activity, state, new MiuixSettingsUi.Actions() {
            @Override public void navigate(int target) { SettingsController.this.navigate(target); }
            @Override public void settled(int target) { onPageSettled(target); }
            @Override public void connect() { SettingsController.this.connect(); }
            @Override public void details() { showDetails(); }
            @Override public void dpi() { if (!remoteBusy) showDpi(); }
            @Override public void rotation(int target) { setRotation(target); }
            @Override public void toggle(String key, String method, boolean enabled) { SettingsController.this.toggle(key, method, enabled); }
            @Override public void tutorial() { action("openTutorial"); }
            @Override public void automation() { showUri(); }
            @Override public void apps() { navigate(3); }
            @Override public void duration() { showDuration(); }
            @Override public void action(String method) { SettingsController.this.action(method); }
            @Override public void updates() { checkForUpdates(); }
            @Override public void proxy(boolean enabled) { UpdateNetwork.setProxyEnabled(activity, enabled); updateResult = null; updateHome(); }
            @Override public void permissions() { activity.startActivity(new Intent(activity, PermissionChecksActivity.class)); }
        });
        root.addView(composeUi.getView(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(modal.getView(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(updateDialog.getView(), new FrameLayout.LayoutParams(-1, -1));
        configureWindow();
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) ViewCompat.requestApplyInsets(root);
        });
        if (page == 3) navigate(3);
        else { composeUi.showPage(page); refreshStatus(); }
        if (state != null && state.containsKey("native_number_kind")) {
            String kind = state.getString("native_number_kind");
            String draft = state.getString("native_number_draft", "");
            root.post(() -> {
                if (destroyed) return;
                if ("dpi".equals(kind)) showDpi(); else if ("duration".equals(kind)) showDuration();
                modal.restoreDraft(draft);
            });
        }
    }

    View getView() { return root; }
    String s(String key) { return NativeStrings.get(activity, key); }
    String count(String key, Object value) { return s(key).replace("{0}", String.valueOf(value)); }

    private void configureWindow() {
        Window window = activity.getWindow();
        View decor = window.getDecorView();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        WindowCompat.getInsetsController(window, decor).setAppearanceLightStatusBars((activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES);
        WindowCompat.getInsetsController(window, decor).setAppearanceLightNavigationBars((activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            composeUi.setInsets(bars.left, bars.top, bars.right, bars.bottom, keyboard.bottom);
            return insets;
        });
    }

    void onResume() {
        resumed = true;
        displayRead = false;
        uriConflicts.start();
        updateDialog.onResume();
        handler.removeCallbacks(statusTick);
        handler.post(statusTick);
        if (picker != null) picker.onResume();
        updateHome();
    }

    void onPause() { resumed = false; uriConflicts.stop(); updateDialog.onPause(); handler.removeCallbacks(statusTick); }
    void destroy() {
        destroyed = true;
        updateChecker.close();
        uriConflicts.close();
        updateDialog.destroy();
        handler.removeCallbacksAndMessages(null);
        if (picker != null) picker.destroy();
        modal.dismiss();
    }

    void saveState(Bundle state) {
        updateDialog.saveState(state);
        composeUi.saveState(state);
        state.putInt("native_page", page);
        state.putInt("native_dpi", currentDpi);
        state.putInt("native_rotation", rotation);
        if (picker != null) picker.saveState(state);
        modal.saveState(state);
    }

    boolean navigateBack() {
        if (page == 3) { navigate(1); return true; }
        if (page != 0) { navigate(0); return true; }
        return false;
    }

    private void navigate(int target) {
        if (destroyed) return;
        if (picker != null && target != 3) {
            pickerState = new Bundle(); picker.saveState(pickerState); picker.destroy(); picker = null;
            composeUi.showPicker(null);
        }
        page = target;
        hideKeyboard();
        if (target == 3 && picker == null) {
            picker = new AppPickerModel(activity, pickerState, this::closePicker);
            composeUi.showPicker(picker);
            if (resumed) picker.onResume();
        }
        composeUi.showPage(target);
        ViewCompat.requestApplyInsets(root);
        refreshStatus();
    }

    private void onPageSettled(int target) {
        if (destroyed || page == 3 || page == target) return;
        page = target;
        refreshStatus();
    }

    private String updateSummaryText() {
        if (checkingUpdates) return s("checking_updates");
        if (updateResult == null) return s("updates_help");
        return UpdateDialogController.summary(activity, updateResult);
    }


    void checkForUpdates() {
        if (destroyed || checkingUpdates) return;
        checkingUpdates = true;
        updateHome();
        updateChecker.check(BuildConfig.SOURCE_TAG, result -> {
            if (destroyed) return;
            checkingUpdates = false;
            updateResult = result;
            updateHome();
            // A completed background check must not interrupt a different page/app.
            if (page != 2 || !resumed) return;
            updateDialog.show(result);
        });
    }


    private void updateHome() {
        Map<String, Boolean> values = new HashMap<>();
        for (String key : new String[]{"proximity_sensor_enabled", "keep_screen_on_enabled", "always_wakeup_enabled",
                "charging_animation_enabled", "charging_always_on_enabled", "notification_service_enabled",
                "notification_privacy_hide_title", "notification_privacy_hide_content", "notification_dark_mode",
                "notification_follow_dnd_mode", "notification_only_when_locked"}) values.put(key, preferences.getBoolean(key));
        composeUi.updateHome(lastStatus.isEmpty() ? "check_shizuku" : lastStatus, connectionSummary, connectionAction,
                currentDpi, rotation, remoteBusy || checkingDisplay, values);
        int selected = activity.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE)
                .getStringSet("notification_selected_apps", Collections.emptySet()).size();
        composeUi.updateSettings(selected, listenerGranted(), Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                preferences.getDuration(), updateSummaryText(), checkingUpdates, UpdateNetwork.proxyEnabled(activity));
    }

    private void toggle(String key, String method, boolean enabled) {
        if (enabled && key.equals("notification_service_enabled") && !listenerGranted()) {
            updateHome();
            modal.show(s("permission_required"), s("notification_access_help"),
                    new String[]{s("go_to_settings"), s("cancel")}, new Runnable[]{() -> action("openNotificationListenerSettings"), null});
            return;
        }
        boolean previous = preferences.getBoolean(key);
        String other = enabled && key.equals("keep_screen_on_enabled") ? "always_wakeup_enabled"
                : enabled && key.equals("always_wakeup_enabled") ? "keep_screen_on_enabled" : null;
        boolean otherPrevious = other != null && preferences.getBoolean(other);
        preferences.putBoolean(key, enabled);
        if (otherPrevious) {
            preferences.putBoolean(other, false);
            String otherMethod = other.equals("always_wakeup_enabled") ? "setAlwaysWakeUpEnabled" : "setKeepScreenOnEnabled";
            activity.runAction(otherMethod, argument("enabled", false), (v, error) -> { if (error != null) toast(error); });
        }
        updateHome();
        activity.runAction(method, argument("enabled", enabled), (value, error) -> {
            if (destroyed) return;
            if (error != null || Boolean.FALSE.equals(value)) {
                preferences.putBoolean(key, previous);
                if (otherPrevious) {
                    preferences.putBoolean(other, true);
                    activity.runAction(other.equals("always_wakeup_enabled") ? "setAlwaysWakeUpEnabled" : "setKeepScreenOnEnabled",
                            argument("enabled", true), null);
                }
                updateHome(); toast(error == null ? s("operation_failed") : error);
            }
        });
    }

    boolean listenerGranted() {
        return PermissionChecks.listenerGranted(activity);
    }

    void refreshStatus() {
        if (destroyed) return;
        boolean running = false, authorized = false;
        try {
            running = Shizuku.pingBinder();
            authorized = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception ignored) { }
        boolean ready = authorized && activity.isTaskServiceReady();
        String status = ready ? "connection_ready" : authorized ? "connection_binding"
                : running ? "shizuku_permission_denied" : "shizuku_not_running";
        connectionSummary = ready ? "connection_ok_help" : "connection_help";
        connectionAction = ready ? "refresh" : running ? "grant_permission" : "open_shizuku";
        if (!status.equals(lastStatus)) {
            lastStatus = status;
            if (!ready) { displayRead = false; rotation = -1; }
        }
        if (ready && !displayRead && !checkingDisplay) readDisplay();
        if (ready && !restoringServices) {
            restoringServices = true;
            if (preferences.getBoolean("charging_animation_enabled")) actionQuiet("toggleChargingService", argument("enabled", true));
            if (preferences.getBoolean("always_wakeup_enabled")) actionQuiet("setAlwaysWakeUpEnabled", argument("enabled", true));
            if (preferences.getBoolean("notification_service_enabled") && listenerGranted()) actionQuiet("startNotificationService", null);
        }
        if (authorized && !permissionPrompted) {
            permissionPrompted = true;
            if (!preferences.getBoolean("native_notification_prompted")) {
                preferences.putBoolean("native_notification_prompted", true);
                actionQuiet("requestNotificationPermission", null);
            }
        }
        updateHome();
    }

    private void readDisplay() {
        checkingDisplay = true;
        updateHome();
        activity.runAction("getCurrentRearDpi", null, (value, error) -> {
            if (destroyed) return;
            if (value instanceof Number) currentDpi = ((Number) value).intValue();
            updateHome();
            activity.runAction("getDisplayRotation", argument("displayId", 1), (angle, failure) -> {
                if (destroyed) return;
                checkingDisplay = false;
                // 本次读取已完成；未知状态保持未选中，重新进入/连接时再读取。
                displayRead = true;
                int actual = failure == null && angle instanceof Number ? ((Number) angle).intValue() : -1;
                rotation = actual >= 0 && actual <= 3 ? actual : -1;
                updateHome();
            });
        });
    }

    private void connect() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) action("requestShizukuPermission");
                else { displayRead = false; actionQuiet("ensureTaskServiceConnected", null); refreshStatus(); }
            } else {
                Intent intent = activity.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (intent != null) activity.startActivity(intent);
                else modal.show("Shizuku", s("connection_help"), new String[]{s("confirm")}, new Runnable[]{null});
            }
        } catch (Exception error) { toast(error.getMessage()); }
    }

    private void showDetails() {
        activity.runAction("getShizukuInfo", null, (value, error) -> {
            if (destroyed) return;
            String message = String.valueOf(error == null ? value : error) + "\nTaskService: " + activity.isTaskServiceReady()
                    + "\nPackage: " + activity.getPackageName() + "\nVersion: " + BuildConfig.VERSION_NAME;
            modal.show(s("diagnostics"), message, new String[]{s("confirm"), s("license"), s("restart_app")},
                    new Runnable[]{null, this::showLicense, this::restart});
        });
    }

    private void showDpi() {
        numberDialog("dpi", s("dpi_settings"), s("dpi_range"), currentDpi > 0 ? currentDpi : 300, 1, Integer.MAX_VALUE, value -> {
            if (remoteBusy) return;
            remoteBusy = true; updateHome(); toast(s("saving"));
            activity.runAction("setRearDpi", argument("dpi", value), (result, error) -> {
                remoteBusy = false;
                if (destroyed) return;
                updateHome();
                if (error == null && Boolean.TRUE.equals(result)) {
                    currentDpi = value; displayRead = false; toast(s("dpi_set_success").replace("%d", String.valueOf(value)));
                    refreshStatus();
                } else toast(error == null ? s("dpi_set_failed") : error);
            });
        }, () -> {
            if (remoteBusy) return;
            remoteBusy = true; updateHome(); toast(s("saving"));
            activity.runAction("resetRearDpi", null, (result, error) -> {
                remoteBusy = false;
                if (destroyed) return;
                updateHome();
                if (error == null && Boolean.TRUE.equals(result)) { displayRead = false; toast(s("dpi_reset_success")); refreshStatus(); }
                else toast(error == null ? s("dpi_reset_failed") : error);
            });
        });
    }

    private void setRotation(int target) {
        if (remoteBusy || checkingDisplay) return;
        remoteBusy = true; updateHome();
        Map<String, Object> arguments = argument("displayId", 1); arguments.put("rotation", target);
        activity.runAction("setDisplayRotation", arguments, (value, error) -> {
            remoteBusy = false;
            if (destroyed) return;
            if (error == null && Boolean.TRUE.equals(value)) rotation = target;
            else {
                toast(error == null ? s("toast_rotation_failed") : error);
                // 回滚也可能受系统权限变化影响，重新读取实际状态。
                readDisplay();
            }
            updateHome();
        });
    }


    private void showDuration() {
        numberDialog("duration", s("auto_destroy_time"), s("duration_range"), preferences.getDuration(), 1, Integer.MAX_VALUE, seconds -> {
            int old = preferences.getDuration(); preferences.putDuration(seconds);
            activity.runAction("setNotificationDuration", argument("duration", seconds), (value, error) -> {
                if (destroyed) return;
                if (error != null) { preferences.putDuration(old); toast(error); }
                else toast(count("toast_duration_set", seconds));
                updateHome();
            });
        }, null);
    }

    private void numberDialog(String kind, String title, String help, int value, int min, int max,
            java.util.function.IntConsumer save, Runnable reset) {
        modal.number(kind, title, help, value, min, max, save, reset);
    }

    private void restart() {
        activity.runAction("returnRearAppAndRestart", null, (value, error) -> {
            if (error != null) toast(error);
            else if (!destroyed) activity.recreate();
        });
    }

    private void showLicense() {
        try {
            String text = readLegalAsset("NOTICE") + "\n\n" + readLegalAsset("MIUIX-LICENSE.txt") + "\n\n" + readLegalAsset("MATERIALKOLOR-LICENSE.txt") + "\n\n" + readLegalAsset("LICENSE");
            modal.show(s("license"), text, new String[]{s("confirm")}, new Runnable[]{null});
        } catch (IOException error) {
            toast(s("license_load_failed"));
        }
    }

    private String readLegalAsset(String name) throws IOException {
        try (InputStream input = activity.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void showUri() {
        uriConflicts.refresh();
        String uri = BuildConfig.URI_SCHEME + "://switch?current=1";
        modal.show(s("uri_title"), uriMessage(), new String[]{s("uri_copy"), s("cancel")}, new Runnable[]{() -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("ReMRSS URI", uri)); toast(s("copied"));
        }, null});
    }

    private String uriMessage() {
        String warning = uriConflictApps.isEmpty() ? "" : s("uri_conflict_title") + "\n" + s("uri_conflict_details")
                .replace("{0}", s("uri_conflict_help").replace("{0}", BuildConfig.URI_SCHEME + "://"))
                .replace("{1}", android.text.TextUtils.join("\n", uriConflictApps)).replace("{2}", activity.getPackageName()) + "\n\n";
        return warning + BuildConfig.URI_SCHEME + "://switch?current=1\n" + BuildConfig.URI_SCHEME + "://return?current=1\n"
                + BuildConfig.URI_SCHEME + "://screenshot\n" + BuildConfig.URI_SCHEME + "://config?dpi=300&rotation=0";
    }

    private void updateUriConflicts(java.util.List<String> apps) {
        if (destroyed) return;
        uriConflictApps = apps;
        composeUi.updateUriConflict(!apps.isEmpty());
        modal.updateMessage(s("uri_title"), uriMessage());
    }

    void action(String method) {
        activity.runAction(method, null, (value, error) -> { if (!destroyed && error != null) toast(error); });
    }
    private void actionQuiet(String method, Object arguments) {
        activity.runAction(method, arguments, (value, error) -> { if (!destroyed && error != null) toast(error); });
    }
    void toast(String message) { Toast.makeText(activity, message == null ? s("operation_failed") : message, Toast.LENGTH_SHORT).show(); }
    void closePicker() { navigate(1); }
    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }
    static Map<String, Object> argument(String key, Object value) {
        Map<String, Object> arguments = new HashMap<>(); arguments.put(key, value); return arguments;
    }
}
