package com.tgwgroup.MiRearScreenSwitcher;

import android.Manifest;
import android.animation.ValueAnimator;
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
import rikka.shizuku.Shizuku;

/** All settings are Android widgets backed by the existing service and Shizuku actions. */
final class NativeSettingsUi {
    private final MainActivity activity;
    final GlassUi ui;
    final NativePreferences preferences;
    private final GlassUi.Background root;
    private final FrameLayout stage;
    private final LinearLayout navigation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Switch> switches = new HashMap<>();
    private int page;
    private boolean destroyed, resumed, settingControls, restoringServices, permissionPrompted;
    private boolean checkingDisplay, displayRead, remoteBusy;
    private String lastStatus = "";
    private int currentDpi, rotation;
    private TextView statusTitle, statusSummary, dpiValue, rotationValue;
    private Button connectButton;
    private final ArrayList<Button> rotationButtons = new ArrayList<>();
    private ScrollView scroll;
    private NativeAppPicker picker;
    private Bundle pickerState;
    private final ArrayList<AlertDialog> dialogs = new ArrayList<>();
    private AlertDialog numberModal;
    private EditText numberInput;
    private String numberKind;
    private int pendingScroll;
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            refreshStatus();
            handler.postDelayed(this, 2500);
        }
    };

    NativeSettingsUi(MainActivity activity, Bundle state) {
        this.activity = activity;
        ui = new GlassUi(activity);
        preferences = new NativePreferences(activity);
        page = state == null ? 0 : state.getInt("native_page", 0);
        pendingScroll = state == null ? 0 : state.getInt("native_scroll", 0);
        currentDpi = state == null ? 0 : state.getInt("native_dpi", 0);
        rotation = state == null ? 0 : state.getInt("native_rotation", 0);
        pickerState = state;
        root = ui.new Background();
        stage = new FrameLayout(activity);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        navigation = ui.row();
        navigation.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        navigation.setBackground(ui.glass(32, true));
        navigation.setElevation(ui.dp(8));
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(ui.dp(320), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        navParams.bottomMargin = ui.dp(12);
        root.addView(navigation, navParams);
        configureWindow();
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) ViewCompat.requestApplyInsets(root);
        });
        renderPage(false);
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
            int widthDp = Math.round((root.getWidth() - bars.left - bars.right)
                    / activity.getResources().getDisplayMetrics().density);
            int outer = ui.dp(Math.max(0, (widthDp - 680) / 2));
            FrameLayout.LayoutParams stageParams = (FrameLayout.LayoutParams) stage.getLayoutParams();
            stageParams.leftMargin = bars.left + outer;
            stageParams.rightMargin = bars.right + outer;
            stageParams.topMargin = bars.top;
            stageParams.bottomMargin = Math.max(bars.bottom, keyboard.bottom);
            stage.setLayoutParams(stageParams);
            FrameLayout.LayoutParams nav = (FrameLayout.LayoutParams) navigation.getLayoutParams();
            nav.width = Math.min(ui.dp(340), Math.max(ui.dp(180), root.getWidth() - bars.left - bars.right - ui.dp(40)));
            nav.bottomMargin = bars.bottom + ui.dp(12);
            navigation.setLayoutParams(nav);
            navigation.setVisibility(page == 3 || keyboard.bottom > bars.bottom ? View.GONE : View.VISIBLE);
            if (scroll != null && scroll.getChildCount() > 0) {
                View content = scroll.getChildAt(0);
                content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(),
                        Math.max(ui.dp(112), navigation.getMeasuredHeight() + ui.dp(32)));
            }
            return insets;
        });
    }

    void onResume() {
        resumed = true;
        handler.removeCallbacks(statusTick);
        handler.post(statusTick);
        if (picker != null) picker.onResume();
        else if (page == 1) rebuildKeepingPosition();
    }

    void onPause() { resumed = false; handler.removeCallbacks(statusTick); }
    void destroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (picker != null) picker.destroy();
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
    }

    void saveState(Bundle state) {
        state.putInt("native_page", page);
        state.putInt("native_scroll", scroll == null ? 0 : scroll.getScrollY());
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
        if (page == target) return;
        page = target;
        pendingScroll = 0;
        hideKeyboard();
        renderPage(true);
    }

    private void renderPage(boolean animate) {
        if (destroyed) return;
        int restoreY = pendingScroll;
        pendingScroll = 0;
        if (picker != null) { pickerState = new Bundle(); picker.saveState(pickerState); picker.destroy(); picker = null; }
        stage.removeAllViews(); switches.clear(); rotationButtons.clear();
        statusTitle = statusSummary = dpiValue = rotationValue = null;
        connectButton = null;
        navigation.setVisibility(page == 3 ? View.GONE : View.VISIBLE);
        buildNavigation();
        if (page == 3) {
            scroll = null;
            picker = new NativeAppPicker(this, activity, pickerState);
            stage.addView(picker.getView(), new FrameLayout.LayoutParams(-1, -1));
            if (resumed) picker.onResume();
        } else {
            scroll = new ScrollView(activity);
            scroll.setFillViewport(true);
            scroll.setClipToPadding(false);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout content = ui.column();
            content.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(112));
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            stage.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            if (page == 0) buildScreen(content);
            else if (page == 1) buildNotifications(content);
            else buildAbout(content);
            scroll.post(() -> { if (!destroyed && scroll != null) scroll.scrollTo(0, restoreY); });
        }
        ViewCompat.requestApplyInsets(root);
        if (animate && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) {
            stage.setAlpha(.65f);
            stage.animate().alpha(1).setDuration(180).start();
        } else stage.setAlpha(1);
        refreshStatus();
    }

    private void rebuildKeepingPosition() {
        pendingScroll = scroll == null ? 0 : scroll.getScrollY();
        renderPage(false);
    }

    private void buildNavigation() {
        navigation.removeAllViews();
        String[] labels = {"nav_screen", "nav_notifications", "about"};
        String[] icons = {"screen", "bell", "info"};
        for (int i = 0; i < labels.length; i++) {
            final int target = i;
            LinearLayout tab = ui.column();
            tab.setGravity(Gravity.CENTER);
            tab.setMinimumHeight(ui.dp(56));
            tab.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
            boolean selected = page == i || (page == 3 && i == 1);
            if (selected) tab.setBackground(ui.pill(ui.dark ? 0x553D5994 : 0xFFDFE9FD, 26));
            tab.addView(ui.icon(icons[i], selected ? ui.accent : ui.secondary), new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
            TextView label = ui.text(s(labels[i]), 12, selected ? ui.accent : ui.secondary, selected);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = ui.dp(4); tab.addView(label, lp);
            tab.setContentDescription(s(i == 0 ? "screen_title" : i == 1 ? "notifications_title" : "about"));
            tab.setSelected(selected);
            tab.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
                }
            });
            ui.clickable(tab, () -> navigate(target), 26);
            navigation.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
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

    private void buildScreen(LinearLayout content) {
        title(content, "screen_title", "screen_subtitle");
        LinearLayout hero = ui.card();
        hero.setPadding(ui.dp(20), ui.dp(20), ui.dp(16), ui.dp(20));
        LinearLayout heroRow = ui.row();
        LinearLayout info = ui.column();
        TextView label = ui.text("SHIZUKU", 11, ui.secondary, true); label.setLetterSpacing(.1f);
        info.addView(label);
        statusTitle = ui.text(s("check_shizuku"), 21, ui.text, true);
        statusTitle.setPadding(0, ui.dp(8), 0, ui.dp(8)); info.addView(statusTitle);
        statusSummary = ui.text(s("connection_help"), 13, ui.secondary, false); info.addView(statusSummary);
        heroRow.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        GlassUi.DeviceArt device = ui.new DeviceArt();
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(ui.dp(86), ui.dp(112));
        deviceParams.leftMargin = ui.dp(8);
        boolean largeText = activity.getResources().getConfiguration().fontScale > 1.25f;
        if (!largeText) heroRow.addView(device, deviceParams);
        hero.addView(heroRow);
        boolean stacked = largeText || activity.getResources().getConfiguration().screenWidthDp < 360;
        LinearLayout actions = stacked ? ui.column() : ui.row();
        connectButton = ui.button(s("connect"), true, this::connect);
        actions.addView(connectButton, stacked ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1));
        Button details = ui.button(s("connection_details"), false, this::showDetails);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(stacked ? -1 : -2, -2);
        if (stacked) detailParams.topMargin = ui.dp(8); else detailParams.leftMargin = ui.dp(8);
        actions.addView(details, detailParams);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2); actionParams.topMargin = ui.dp(16);
        hero.addView(actions, actionParams); content.addView(hero);

        content.addView(ui.heading(s("screen_size")));
        LinearLayout display = ui.card();
        dpiValue = ui.text(currentDpi > 0 ? String.valueOf(currentDpi) : "—", 25, ui.accent, true);
        display.addView(actionRow("density", s("dpi_caption"), s("dpi_help"), dpiValue, this::showDpi));
        ui.divider(display);
        rotationValue = ui.text((rotation * 90) + "°", 16, ui.accent, true);
        display.addView(actionRow("rotate", s("rotation_caption"), null, rotationValue, null));
        LinearLayout rotations = ui.row();
        rotations.setPadding(ui.dp(2), 0, ui.dp(2), ui.dp(14));
        for (int i = 0; i < 4; i++) {
            final int target = i;
            Button button = ui.button((i * 90) + "°", i == rotation, () -> setRotation(target));
            button.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10));
            button.setContentDescription(s("rotation_caption") + " " + i * 90 + "°");
            button.setSelected(i == rotation);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = ui.dp(8);
            rotations.addView(button, lp); rotationButtons.add(button);
        }
        display.addView(rotations); content.addView(display);
        content.addView(ui.heading(s("display_wake")));
        LinearLayout wake = ui.card();
        addSwitch(wake, "eye", "proximity_sensor", "proximity_help", "proximity_sensor_enabled", "setProximitySensorEnabled");
        ui.divider(wake);
        addSwitch(wake, "sun", "keep_screen_on", "keep_help", "keep_screen_on_enabled", "setKeepScreenOnEnabled");
        ui.divider(wake);
        addSwitch(wake, "sun", "always_wake_up", "always_help", "always_wakeup_enabled", "setAlwaysWakeUpEnabled");
        content.addView(wake);
        TextView burnIn = ui.text(s("warning_burn_in"), 12, ui.secondary, false);
        burnIn.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); content.addView(burnIn);
        content.addView(ui.heading(s("charging_section")));
        LinearLayout charging = ui.card();
        addSwitch(charging, "charge", "charging_animation", "charging_help", "charging_animation_enabled", "toggleChargingService");
        ui.divider(charging);
        addSwitch(charging, "sun", "charging_always_on", "charging_always_help", "charging_always_on_enabled", "setChargingAlwaysOnEnabled");
        content.addView(charging);
        content.addView(ui.heading(s("quick_tiles")));
        LinearLayout shortcut = ui.card();
        shortcut.addView(actionRow("apps", s("quick_tiles"), s("quick_tiles_help"), null, () -> action("openTutorial")));
        content.addView(shortcut);
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
        title(content, "about", "about_subtitle");
        LinearLayout brand = ui.card(); brand.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(24));
        LinearLayout line = ui.row();
        line.addView(ui.new DeviceArt(), new LinearLayout.LayoutParams(ui.dp(64), ui.dp(84)));
        LinearLayout names = ui.column(); names.setPadding(ui.dp(16), 0, 0, 0);
        names.addView(ui.text("MRSS", 27, ui.text, true));
        TextView version = ui.text(count("version_label", BuildConfig.VERSION_NAME), 13, ui.secondary, false);
        version.setPadding(0, ui.dp(6), 0, ui.dp(8)); names.addView(version);
        names.addView(ui.text(s("app_description"), 13, ui.secondary, false));
        line.addView(names, new LinearLayout.LayoutParams(0, -2, 1)); brand.addView(line); content.addView(brand);
        content.addView(ui.heading(s("help_support")));
        LinearLayout support = ui.card();
        support.addView(actionRow("info", s("tutorial"), null, null, () -> action("openTutorial"))); ui.divider(support);
        support.addView(actionRow("bell", s("qq_group_title"), null, null, () -> action("openQQGroup"))); ui.divider(support);
        support.addView(actionRow("charge", s("buy_coffee"), null, null, () -> action("openDonationPage")));
        content.addView(support);
        content.addView(ui.heading(s("credits")));
        LinearLayout authors = ui.card();
        authors.addView(actionRow("info", s("author_anti"), null, null, () -> action("openCoolApkProfile"))); ui.divider(authors);
        authors.addView(actionRow("info", s("author_xmz"), null, null, () -> action("openCoolApkProfileXmz")));
        content.addView(authors);
        content.addView(ui.heading(s("uri_title")));
        LinearLayout uri = ui.card();
        uri.addView(actionRow("screen", s("uri_title"), s("uri_help").replace("mrss://", BuildConfig.URI_SCHEME + "://"), null, this::showUri));
        content.addView(uri);
        content.addView(ui.heading(s("diagnostics")));
        LinearLayout diagnostics = ui.card();
        diagnostics.addView(actionRow("shield", s("connection_details"), null, null, this::showDetails)); ui.divider(diagnostics);
        diagnostics.addView(actionRow("rotate", s("restart_app"), s("restart_help"), null, this::restart)); content.addView(diagnostics);
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
        if (statusTitle != null) statusTitle.setText(s(status));
        if (statusSummary != null) statusSummary.setText(s(ready ? "connection_ok_help" : "connection_help"));
        if (connectButton != null) connectButton.setText(s(ready ? "refresh" : running ? "grant_permission" : "open_shizuku"));
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
            if (dpiValue != null) dpiValue.setText(currentDpi > 0 ? String.valueOf(currentDpi) : "—");
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
            dialog(new AlertDialog.Builder(activity).setTitle(s("connection_details")).setMessage(message)
                    .setPositiveButton(s("confirm"), null).create());
        });
    }

    private void showDpi() {
        numberDialog("dpi", s("dpi_settings"), s("dpi_range"), currentDpi > 0 ? currentDpi : 300, 1, Integer.MAX_VALUE, value -> {
            if (remoteBusy) return;
            remoteBusy = true; toast(s("saving"));
            activity.runAction("setRearDpi", argument("dpi", value), (result, error) -> {
                remoteBusy = false;
                if (destroyed) return;
                if (error == null && Boolean.TRUE.equals(result)) {
                    currentDpi = value; displayRead = false; toast(s("dpi_set_success").replace("%d", String.valueOf(value)));
                    refreshStatus();
                } else toast(error == null ? s("dpi_set_failed") : error);
            });
        }, () -> {
            if (remoteBusy) return;
            remoteBusy = true; toast(s("saving"));
            activity.runAction("resetRearDpi", null, (result, error) -> {
                remoteBusy = false;
                if (destroyed) return;
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

    private void updateRotationControls() {
        if (rotationValue != null) rotationValue.setText(count("angle_value", rotation * 90));
        for (int i = 0; i < rotationButtons.size(); i++) {
            Button button = rotationButtons.get(i);
            boolean selected = i == rotation;
            button.setSelected(selected); button.setEnabled(!remoteBusy);
            button.setTextColor(selected ? (ui.dark ? ui.background : Color.WHITE) : ui.accent);
            button.setBackground(ui.ripple(ui.pill(selected ? ui.accent : (ui.dark ? 0x33245DE3 : 0x0D245DE3), 18), 18));
            button.setAlpha(remoteBusy ? .5f : 1);
        }
    }

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
