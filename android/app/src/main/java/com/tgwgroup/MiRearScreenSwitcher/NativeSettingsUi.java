package com.tgwgroup.MiRearScreenSwitcher;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import rikka.shizuku.Shizuku;

/** Compose home with existing Android settings pages and unchanged service actions. */
final class NativeSettingsUi {
    private final MainActivity activity;
    final GlassUi ui;
    final NativePreferences preferences;
    private final GlassUi.Background root;
    private final FrameLayout stage;
    private final FrameLayout notificationPage, aboutPage;
    private final ScrollView[] pageScrolls = new ScrollView[3];
    private final MiuixHomeUi composeUi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Switch> switches = new HashMap<>();
    private final ReleaseUpdateChecker updateChecker;
    private final UpdateDialogController updateDialog;
    private LinearLayout updateRow;
    private TextView updateSummary;
    private Switch updateProxy;
    private LinearLayout updateProxyRow;
    private boolean checkingUpdates;
    private ReleaseUpdateChecker.Result updateResult;
    private int page;
    private boolean destroyed, resumed, settingControls, restoringServices, permissionPrompted;
    private boolean checkingDisplay, displayRead, remoteBusy;
    private String lastStatus = "";
    private int currentDpi, rotation;
    private String connectionSummary = "connection_help", connectionAction = "open_shizuku";
    private ScrollView scroll;
    private NativeAppPicker picker;
    private Bundle pickerState;
    private final ArrayList<AlertDialog> dialogs = new ArrayList<>();
    private AlertDialog numberModal;
    private EditText numberInput;
    private String numberKind;
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            refreshStatus();
            handler.postDelayed(this, 2500);
        }
    };

    NativeSettingsUi(MainActivity activity, Bundle state) {
        this(activity, state, activity.createUpdateChecker());
    }

    NativeSettingsUi(MainActivity activity, Bundle state, ReleaseUpdateChecker updateChecker) {
        this.activity = activity;
        this.updateChecker = updateChecker;
        updateDialog = new UpdateDialogController(activity, state, this::checkForUpdates);
        ui = new GlassUi(activity);
        preferences = new NativePreferences(activity);
        page = state == null ? 0 : state.getInt("native_page", 0);
        currentDpi = state == null ? 0 : state.getInt("native_dpi", 0);
        rotation = state == null ? 0 : state.getInt("native_rotation", 0);
        pickerState = state;
        root = ui.new Background();
        stage = new FrameLayout(activity);
        notificationPage = new FrameLayout(activity);
        aboutPage = new FrameLayout(activity);
        composeUi = new MiuixHomeUi(activity, notificationPage, aboutPage, stage, state, new MiuixHomeUi.Actions() {
            @Override public void navigate(int target) { NativeSettingsUi.this.navigate(target); }
            @Override public void settled(int target) { onPageSettled(target); }
            @Override public void connect() { NativeSettingsUi.this.connect(); }
            @Override public void details() { showDetails(); }
            @Override public void dpi() { if (!remoteBusy) showDpi(); }
            @Override public void rotation(int target) { setRotation(target); }
            @Override public void toggle(String key, String method, boolean enabled) { NativeSettingsUi.this.toggle(key, method, enabled); }
            @Override public void tutorial() { action("openTutorial"); }
        });
        root.addView(composeUi.getView(), new FrameLayout.LayoutParams(-1, -1));
        configureWindow();
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) ViewCompat.requestApplyInsets(root);
        });
        for (int target = 1; target <= 2; target++) {
            int fallback = state != null && page == target ? state.getInt("native_scroll", 0) : 0;
            buildNativePage(target, state == null ? 0 : state.getInt("native_scroll_" + target, fallback));
        }
        if (page == 3) navigate(3);
        else { scroll = pageScrolls[page]; composeUi.showPage(page); refreshStatus(); }
        if (state != null && state.containsKey("native_number_kind")) {
            String kind = state.getString("native_number_kind");
            String draft = state.getString("native_number_draft", "");
            root.post(() -> {
                if (destroyed) return;
                if ("dpi".equals(kind)) showDpi(); else if ("duration".equals(kind)) showDuration();
                if (numberInput != null) numberInput.setText(draft);
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
        WindowCompat.getInsetsController(window, decor).setAppearanceLightStatusBars(!ui.dark);
        WindowCompat.getInsetsController(window, decor).setAppearanceLightNavigationBars(!ui.dark);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            composeUi.setInsets(bars.left, bars.top, bars.right, bars.bottom, keyboard.bottom);
            return insets;
        });
    }

    void onResume() {
        resumed = true;
        updateDialog.onResume();
        handler.removeCallbacks(statusTick);
        handler.post(statusTick);
        if (picker != null) picker.onResume();
        else if (page == 1 && scroll != null && ViewCompat.isLaidOut(scroll)) rebuildKeepingPosition();
    }

    void onPause() { resumed = false; updateDialog.onPause(); handler.removeCallbacks(statusTick); }
    void destroy() {
        destroyed = true;
        updateChecker.close();
        updateDialog.destroy();
        handler.removeCallbacksAndMessages(null);
        if (picker != null) picker.destroy();
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
    }

    void saveState(Bundle state) {
        updateDialog.saveState(state);
        composeUi.saveState(state);
        state.putInt("native_page", page);
        state.putInt("native_scroll", scroll == null ? 0 : scroll.getScrollY());
        for (int target = 1; target <= 2; target++) {
            state.putInt("native_scroll_" + target, pageScrolls[target].getScrollY());
        }
        state.putInt("native_dpi", currentDpi);
        state.putInt("native_rotation", rotation);
        if (picker != null) picker.saveState(state);
        if (numberModal != null && numberModal.isShowing() && numberInput != null) {
            state.putString("native_number_kind", numberKind);
            state.putString("native_number_draft", numberInput.getText().toString());
        }
    }

    boolean navigateBack() {
        if (page == 3) { navigate(1); return true; }
        if (page != 0) { navigate(0); return true; }
        return false;
    }

    private void navigate(int target) {
        if (destroyed) return;
        int previous = page;
        if (picker != null && target != 3) {
            pickerState = new Bundle(); picker.saveState(pickerState); picker.destroy(); picker = null;
            stage.removeAllViews();
        }
        page = target;
        hideKeyboard();
        if (target == 3) {
            scroll = null;
            if (picker == null) {
                picker = new NativeAppPicker(this, activity, pickerState);
                stage.addView(picker.getView(), new FrameLayout.LayoutParams(-1, -1));
                if (resumed) picker.onResume();
            }
        } else {
            scroll = pageScrolls[target];
            if (target == 1 && previous != 1) rebuildKeepingPosition();
        }
        composeUi.showPage(target);
        ViewCompat.requestApplyInsets(root);
        refreshStatus();
    }

    private void onPageSettled(int target) {
        if (destroyed || page == 3 || page == target) return;
        page = target;
        scroll = pageScrolls[target];
        if (target == 1) rebuildKeepingPosition();
        refreshStatus();
    }

    private void buildNativePage(int target, int restoreY) {
        FrameLayout host = target == 1 ? notificationPage : aboutPage;
        host.removeAllViews();
        if (target == 1) switches.clear();
        else { updateRow = null; updateSummary = null; }
        ScrollView next = new ScrollView(activity);
        next.setTag("native_scroll_" + target);
        next.setFillViewport(true);
        next.setClipToPadding(false);
        next.setVerticalScrollBarEnabled(false);
        ViewCompat.setNestedScrollingEnabled(next, true);
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(112));
        next.addView(content, new ScrollView.LayoutParams(-1, -2));
        host.addView(next, new FrameLayout.LayoutParams(-1, -1));
        pageScrolls[target] = next;
        if (page == target) scroll = next;
        if (target == 1) buildNotifications(content); else buildAbout(content);
        next.post(() -> { if (!destroyed) next.scrollTo(0, restoreY); });
    }

    private void rebuildKeepingPosition() {
        if (page == 1 || page == 2) buildNativePage(page, scroll == null ? 0 : scroll.getScrollY());
    }

    private void title(LinearLayout content, String title, String subtitle) {
        TextView eyebrow = ui.text("MRSS", 12, ui.secondary, true);
        eyebrow.setLetterSpacing(.12f);
        content.addView(eyebrow);
        TextView heading = ui.text(s(title), 34, ui.text, true);
        heading.setPadding(0, ui.dp(10), 0, ui.dp(8));
        ViewCompat.setAccessibilityHeading(heading, true);
        content.addView(heading);
        TextView caption = ui.text(s(subtitle), 14, ui.secondary, false);
        caption.setPadding(0, 0, 0, ui.dp(24));
        content.addView(caption);
    }

    private void buildNotifications(LinearLayout content) {
        title(content, "notifications_title", "notifications_subtitle");
        LinearLayout delivery = ui.card();
        addSwitch(delivery, "bell", "notification_service", "notification_help", "notification_service_enabled", "toggleNotificationService");
        ui.divider(delivery);
        int selected = activity.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE)
                .getStringSet("notification_selected_apps", Collections.emptySet()).size();
        delivery.addView(actionRow("apps", s("notification_apps"), count("selected_count", selected), null, () -> navigate(3)));
        ui.divider(delivery);
        boolean listener = listenerGranted();
        delivery.addView(actionRow("shield", s("notification_access"), s("notification_access_help"),
                ui.text(s(listener ? "access_granted" : "access_pending"), 13, listener ? ui.success : ui.accent, true),
                () -> action("openNotificationListenerSettings")));
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ui.divider(delivery);
            delivery.addView(actionRow("bell", s("notification_permission"), s("notification_permission_help"), null,
                    () -> action("requestNotificationPermission")));
        }
        content.addView(delivery);
        content.addView(ui.heading(s("privacy_section")));
        LinearLayout privacy = ui.card();
        addSwitch(privacy, "eye", "hide_notification_title", "hide_title_help", "notification_privacy_hide_title", "setNotificationPrivacyHideTitle");
        ui.divider(privacy);
        addSwitch(privacy, "eye", "hide_notification_content", "hide_content_help", "notification_privacy_hide_content", "setNotificationPrivacyHideContent");
        ui.divider(privacy);
        addSwitch(privacy, "screen", "notification_dark_mode", "dark_notification_help", "notification_dark_mode", "setNotificationDarkMode");
        content.addView(privacy);
        content.addView(ui.heading(s("notification_rules")));
        LinearLayout rules = ui.card();
        addSwitch(rules, "bell", "follow_system_dnd", "dnd_help", "notification_follow_dnd_mode", "setFollowDndMode");
        ui.divider(rules);
        addSwitch(rules, "screen", "only_when_locked", "upside_help", "notification_only_when_locked", "setOnlyWhenLocked");
        ui.divider(rules);
        rules.addView(actionRow("rotate", s("auto_destroy_time"), s("duration_help"),
                ui.text(count("seconds_value", preferences.getDuration()), 15, ui.accent, true), this::showDuration));
        content.addView(rules);
    }

    private void buildAbout(LinearLayout content) {
        TextView heading = ui.text(s("about"), 34, ui.text, true);
        heading.setPadding(0, 0, 0, ui.dp(24));
        ViewCompat.setAccessibilityHeading(heading, true);
        content.addView(heading);
        LinearLayout brand = ui.row();
        brand.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(28));
        ImageView appIcon = new ImageView(activity);
        appIcon.setImageResource(R.drawable.ic_app_logo);
        appIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(appIcon, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        LinearLayout names = ui.column(); names.setPadding(ui.dp(16), 0, 0, 0);
        names.addView(ui.text("MRSS", 24, ui.text, true));
        TextView version = ui.text(count("version_label", BuildConfig.VERSION_NAME), 14, ui.secondary, false);
        version.setPadding(0, ui.dp(4), 0, 0); names.addView(version);
        brand.addView(names, new LinearLayout.LayoutParams(0, -2, 1)); content.addView(brand);
        LinearLayout support = ui.card();
        support.addView(actionRow("info", s("project_repository"), "GitHub · lhly/MiRearScreenSwitcher\n"
                + count("project_maintainer", ProjectInfo.MAINTAINER), null, () -> action("openProject"))); ui.divider(support);
        updateRow = actionRow("rotate", s("check_updates"), updateSummaryText(), null, this::checkForUpdates);
        updateSummary = (TextView) ((LinearLayout) updateRow.getChildAt(1)).getChildAt(1);
        updateSummary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        updateRow.setEnabled(!checkingUpdates);
        support.addView(updateRow); ui.divider(support);
        updateProxy = new Switch(activity);
        updateProxy.setShowText(false);
        updateProxy.setMinimumWidth(ui.dp(52)); updateProxy.setMinimumHeight(ui.dp(48));
        ui.styleSwitch(updateProxy);
        updateProxy.setContentDescription(s("update_proxy") + ". " + s("update_proxy_help"));
        updateProxy.setChecked(UpdateNetwork.proxyEnabled(activity));
        updateProxy.setOnCheckedChangeListener((button, enabled) -> {
            UpdateNetwork.setProxyEnabled(activity, enabled);
            updateResult = null; refreshUpdateRow();
        });
        updateProxyRow = actionRow("shield", s("update_proxy"), s("update_proxy_help"), updateProxy, null);
        ui.clickable(updateProxyRow, updateProxy::toggle, 18);
        updateProxyRow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        support.addView(updateProxyRow); ui.divider(support);
        refreshUpdateRow();
        support.addView(actionRow("bell", s("issue_feedback"), "GitHub Issues", null, () -> action("openFeedback")));
        content.addView(support);
        LinearLayout details = ui.card();
        details.addView(actionRow("info", s("author_anti"), null, null, () -> action("openOriginalProject"))); ui.divider(details);
        details.addView(actionRow("screen", s("uri_title"), s("uri_help").replace("mrss://", BuildConfig.URI_SCHEME + "://"), null, this::showUri)); ui.divider(details);
        details.addView(actionRow("shield", s("diagnostics"), null, null, this::showDetails));
        LinearLayout.LayoutParams detailsLayout = new LinearLayout.LayoutParams(-1, -2);
        detailsLayout.topMargin = ui.dp(20);
        content.addView(details, detailsLayout);
    }

    private String updateSummaryText() {
        if (checkingUpdates) return s("checking_updates");
        if (updateResult == null) return s("updates_help");
        return UpdateDialogController.summary(activity, updateResult);
    }

    private void refreshUpdateRow() {
        if (updateRow == null) return;
        updateRow.setEnabled(!checkingUpdates);
        if (updateProxy != null) updateProxy.setEnabled(!checkingUpdates);
        if (updateProxyRow != null) updateProxyRow.setEnabled(!checkingUpdates);
        updateSummary.setText(updateSummaryText());
        updateRow.setContentDescription(s("check_updates") + ", " + updateSummaryText());
    }

    void checkForUpdates() {
        if (destroyed || checkingUpdates) return;
        checkingUpdates = true;
        refreshUpdateRow();
        updateChecker.check(BuildConfig.SOURCE_TAG, result -> {
            if (destroyed) return;
            checkingUpdates = false;
            updateResult = result;
            refreshUpdateRow();
            // A completed background check must not interrupt a different page/app.
            if (page != 2 || !resumed) return;
            updateDialog.show(result);
        });
    }

    LinearLayout actionRow(String icon, String title, String summary, View trailing, Runnable action) {
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(64));
        row.setPadding(0, ui.dp(16), 0, ui.dp(16));
        row.addView(ui.icon(icon, ui.accent), new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        LinearLayout labels = ui.column(); labels.setPadding(ui.dp(14), 0, ui.dp(12), 0);
        labels.addView(ui.text(title, 16, ui.text, true));
        if (summary != null && !summary.isEmpty()) {
            TextView description = ui.text(summary, 13, ui.secondary, false);
            description.setPadding(0, ui.dp(5), 0, 0); labels.addView(description);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) {
            row.addView(trailing, new LinearLayout.LayoutParams(-2, -2));
            if (trailing instanceof TextView) ((TextView) trailing).setMaxWidth(ui.dp(100));
        } else if (action != null) row.addView(ui.icon("chevron", ui.secondary), new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        if (action != null) {
            ui.clickable(row, action, 18);
            row.setContentDescription(title + (summary == null ? "" : ", " + summary));
        }
        return row;
    }

    private void addSwitch(LinearLayout parent, String icon, String titleKey, String helpKey, String key, String method) {
        Switch control = new Switch(activity);
        control.setShowText(false);
        control.setMinimumWidth(ui.dp(52)); control.setMinimumHeight(ui.dp(48));
        control.setContentDescription(s(titleKey) + ". " + s(helpKey));
        ui.styleSwitch(control);
        control.setChecked(preferences.getBoolean(key)); switches.put(key, control);
        LinearLayout row = actionRow(icon, s(titleKey), s(helpKey), control, null);
        ui.clickable(row, control::toggle, 18);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        control.setOnCheckedChangeListener((button, enabled) -> {
            if (!settingControls) toggle(key, method, enabled);
        });
        parent.addView(row);
    }

    private void syncSwitches() {
        settingControls = true;
        for (Map.Entry<String, Switch> entry : switches.entrySet()) entry.getValue().setChecked(preferences.getBoolean(entry.getKey()));
        settingControls = false;
        updateHome();
    }

    private void updateHome() {
        Map<String, Boolean> values = new HashMap<>();
        for (String key : new String[]{"proximity_sensor_enabled", "keep_screen_on_enabled", "always_wakeup_enabled",
                "charging_animation_enabled", "charging_always_on_enabled"}) values.put(key, preferences.getBoolean(key));
        composeUi.updateHome(lastStatus.isEmpty() ? "check_shizuku" : lastStatus, connectionSummary, connectionAction,
                currentDpi, rotation, remoteBusy, values);
    }

    private void toggle(String key, String method, boolean enabled) {
        if (enabled && key.equals("notification_service_enabled") && !listenerGranted()) {
            syncSwitches();
            dialog(new AlertDialog.Builder(activity).setTitle(s("permission_required"))
                    .setMessage(s("notification_access_help")).setNegativeButton(s("cancel"), null)
                    .setPositiveButton(s("go_to_settings"), (d, w) -> action("openNotificationListenerSettings")).create());
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
        syncSwitches();
        activity.runAction(method, argument("enabled", enabled), (value, error) -> {
            if (destroyed) return;
            if (error != null || Boolean.FALSE.equals(value)) {
                preferences.putBoolean(key, previous);
                if (otherPrevious) {
                    preferences.putBoolean(other, true);
                    activity.runAction(other.equals("always_wakeup_enabled") ? "setAlwaysWakeUpEnabled" : "setKeepScreenOnEnabled",
                            argument("enabled", true), null);
                }
                syncSwitches(); toast(error == null ? s("operation_failed") : error);
            }
        });
    }

    boolean listenerGranted() {
        String listeners = android.provider.Settings.Secure.getString(activity.getContentResolver(), "enabled_notification_listeners");
        if (listeners == null) return false;
        for (String component : listeners.split(":")) {
            android.content.ComponentName name = android.content.ComponentName.unflattenFromString(component);
            if (name != null && name.getPackageName().equals(activity.getPackageName())) return true;
        }
        return false;
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
            if (!ready) displayRead = false;
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
        syncSwitches();
    }

    private void readDisplay() {
        checkingDisplay = true;
        activity.runAction("getCurrentRearDpi", null, (value, error) -> {
            if (destroyed) return;
            if (value instanceof Number) currentDpi = ((Number) value).intValue();
            updateHome();
            activity.runAction("getDisplayRotation", argument("displayId", 1), (angle, failure) -> {
                if (destroyed) return;
                checkingDisplay = false;
                displayRead = error == null && failure == null;
                if (angle instanceof Number) rotation = Math.max(0, Math.min(3, ((Number) angle).intValue()));
                updateRotationControls();
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
                else dialog(new AlertDialog.Builder(activity).setTitle("Shizuku").setMessage(s("connection_help"))
                        .setPositiveButton(s("confirm"), null).create());
            }
        } catch (Exception error) { toast(error.getMessage()); }
    }

    private void showDetails() {
        activity.runAction("getShizukuInfo", null, (value, error) -> {
            if (destroyed) return;
            String message = String.valueOf(error == null ? value : error) + "\nTaskService: " + activity.isTaskServiceReady()
                    + "\nPackage: " + activity.getPackageName() + "\nVersion: " + BuildConfig.VERSION_NAME;
            dialog(new AlertDialog.Builder(activity).setTitle(s("diagnostics")).setMessage(message)
                    .setNeutralButton(s("license"), (d, w) -> showLicense())
                    .setNegativeButton(s("restart_app"), (d, w) -> restart())
                    .setPositiveButton(s("confirm"), null).create());
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
        if (remoteBusy) return;
        remoteBusy = true; updateRotationControls();
        Map<String, Object> arguments = argument("displayId", 1); arguments.put("rotation", target);
        activity.runAction("setDisplayRotation", arguments, (value, error) -> {
            remoteBusy = false;
            if (destroyed) return;
            if (error == null && Boolean.TRUE.equals(value)) rotation = target;
            else toast(error == null ? s("toast_rotation_failed") : error);
            updateRotationControls();
        });
    }

    private void updateRotationControls() { updateHome(); }

    private void showDuration() {
        numberDialog("duration", s("auto_destroy_time"), s("duration_range"), preferences.getDuration(), 1, Integer.MAX_VALUE, seconds -> {
            int old = preferences.getDuration(); preferences.putDuration(seconds);
            activity.runAction("setNotificationDuration", argument("duration", seconds), (value, error) -> {
                if (destroyed) return;
                if (error != null) { preferences.putDuration(old); toast(error); }
                else toast(count("toast_duration_set", seconds));
                rebuildKeepingPosition();
            });
        }, null);
    }

    private interface NumberAction { void apply(int value); }
    private void numberDialog(String kind, String title, String help, int value, int min, int max, NumberAction save, Runnable reset) {
        LinearLayout body = ui.column(); body.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8));
        TextView label = ui.text(help, 14, ui.secondary, false); body.addView(label);
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(String.valueOf(value)); field.setTextColor(ui.text); field.setTextSize(20);
        field.setSingleLine(true); field.setSelectAllOnFocus(true); field.setMinimumHeight(ui.dp(56));
        field.setContentDescription(title); field.setHint(help);
        field.setId(View.generateViewId()); label.setLabelFor(field.getId());
        body.addView(field, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(title).setView(body)
                .setNegativeButton(s("cancel"), null).setPositiveButton(s("apply"), null);
        if (reset != null) builder.setNeutralButton(s("reset_dpi"), (d, w) -> reset.run());
        AlertDialog dialog = dialog(builder.create());
        numberKind = kind; numberInput = field; numberModal = dialog;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int number = Integer.parseInt(field.getText().toString().trim());
                if (number < min || number > max) throw new NumberFormatException();
                hideKeyboard(); dialog.dismiss(); save.apply(number);
            } catch (NumberFormatException error) { field.setError(help); field.requestFocus(); }
        });
    }

    AlertDialog dialog(AlertDialog dialog) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> {
            dialogs.remove(dialog);
            if (numberModal == dialog) { numberModal = null; numberInput = null; numberKind = null; }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(ui.glass(28, true));
            WindowManager.LayoutParams params = window.getAttributes(); params.dimAmount = ui.dark ? .5f : .28f;
            window.setAttributes(params);
        }
        for (int which : new int[]{-1, -2, -3}) {
            Button button = dialog.getButton(which);
            if (button != null) { button.setTextColor(ui.accent); button.setAllCaps(false); button.setMinHeight(ui.dp(48)); }
        }
        return dialog;
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
            ScrollView body = new ScrollView(activity);
            TextView document = ui.text(text, 14, ui.text, false);
            document.setTextIsSelectable(true);
            document.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(20));
            body.addView(document, new ScrollView.LayoutParams(-1, -2));
            dialog(new AlertDialog.Builder(activity).setTitle(s("license")).setView(body)
                    .setPositiveButton(s("confirm"), null).create());
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
        String uri = BuildConfig.URI_SCHEME + "://switch?current=1";
        dialog(new AlertDialog.Builder(activity).setTitle(s("uri_title"))
                .setMessage(uri + "\n\n" + BuildConfig.URI_SCHEME + "://return?current=1\n"
                        + BuildConfig.URI_SCHEME + "://screenshot\n" + BuildConfig.URI_SCHEME + "://config?dpi=300&rotation=0")
                .setNegativeButton(s("cancel"), null).setPositiveButton(s("uri_copy"), (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("MRSS URI", uri)); toast(s("copied"));
                }).create());
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
