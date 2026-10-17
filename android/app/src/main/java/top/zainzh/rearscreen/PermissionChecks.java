package top.zainzh.rearscreen;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;
import rikka.shizuku.Shizuku;

/** Checks only permissions used by current features; vendor policy is never guessed. */
final class PermissionChecks {
    enum Item {
        SHIZUKU(R.string.ui_permission_shizuku, R.string.ui_permission_shizuku_help, "shield", false),
        LISTENER(R.string.ui_permission_listener, R.string.ui_permission_listener_help, "bell", false),
        NOTIFICATIONS(R.string.ui_permission_notifications, R.string.ui_permission_notifications_help, "bell", false),
        OVERLAY(R.string.ui_permission_overlay, R.string.ui_permission_overlay_help, "screen", false),
        INSTALL(R.string.ui_permission_install, R.string.ui_permission_install_help, "apps", false),
        BATTERY(R.string.ui_permission_battery, R.string.ui_permission_battery_help, "shield", true),
        BACKGROUND(R.string.ui_permission_background, R.string.ui_permission_background_help, "screen", true),
        AUTOSTART(R.string.ui_permission_autostart, R.string.ui_permission_autostart_help, "rotate", true);

        final int title, help;
        final String icon;
        final boolean background;
        Item(int title, int help, String icon, boolean background) {
            this.title = title; this.help = help; this.icon = icon; this.background = background;
        }
    }

    enum State { GRANTED, DENIED, NOT_RUNNING, UNKNOWN, MANUAL, NOT_REQUIRED }

    static State read(Context context, Item item) {
        try {
            switch (item) {
                case SHIZUKU:
                    if (!Shizuku.pingBinder()) return State.NOT_RUNNING;
                    return granted(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);
                case LISTENER:
                    return granted(listenerGranted(context));
                case NOTIFICATIONS:
                    NotificationManager manager = context.getSystemService(NotificationManager.class);
                    if (manager == null) return State.UNKNOWN;
                    return granted(manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33
                            || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED));
                case OVERLAY:
                    return granted(Settings.canDrawOverlays(context));
                case INSTALL:
                    return Build.VERSION.SDK_INT < 26 ? State.NOT_REQUIRED
                            : granted(context.getPackageManager().canRequestPackageInstalls());
                case BATTERY:
                    PowerManager power = context.getSystemService(PowerManager.class);
                    return power == null ? State.UNKNOWN
                            : granted(power.isIgnoringBatteryOptimizations(context.getPackageName()));
                default:
                    return State.MANUAL;
            }
        } catch (RuntimeException error) {
            // An unreadable setting must not appear as either granted or denied.
            return State.UNKNOWN;
        }
    }

    static boolean listenerGranted(Context context) {
        ComponentName listener = new ComponentName(context, NotificationService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(listener);
        }
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (enabled != null) for (String name : enabled.split(":")) {
            if (listener.equals(ComponentName.unflattenFromString(name))) return true;
        }
        return false;
    }

    private static State granted(boolean granted) { return granted ? State.GRANTED : State.DENIED; }

    static int statusLabel(Item item, State state) {
        switch (state) {
            case GRANTED: return item == Item.BATTERY ? R.string.ui_permission_exempt : R.string.ui_permission_granted;
            case DENIED: return item == Item.BATTERY ? R.string.ui_permission_not_exempt : R.string.ui_permission_denied;
            case NOT_RUNNING: return R.string.ui_permission_not_running;
            case MANUAL: return R.string.ui_permission_manual;
            case NOT_REQUIRED: return R.string.ui_permission_not_required;
            default: return R.string.ui_permission_unknown;
        }
    }

    static int actionLabel(Item item, State state) {
        if (item == Item.SHIZUKU && state == State.NOT_RUNNING) return R.string.ui_permission_open_shizuku;
        return state == State.DENIED && !item.background
                ? R.string.ui_permission_authorize : R.string.ui_permission_settings;
    }

    /** Ordered fallbacks: ROM detail page, public settings page, app info, system settings. */
    static List<Intent> settingsIntents(Context context, Item item) {
        String pkg = context.getPackageName();
        Uri packageUri = Uri.parse("package:" + pkg);
        List<Intent> intents = new ArrayList<>();
        switch (item) {
            case SHIZUKU:
                Intent manager = context.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (manager != null) intents.add(manager);
                intents.add(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")));
                break;
            case LISTENER:
                if (Build.VERSION.SDK_INT >= 30) intents.add(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new ComponentName(context, NotificationService.class).flattenToString()));
                intents.add(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                break;
            case NOTIFICATIONS:
                if (Build.VERSION.SDK_INT >= 26) intents.add(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg));
                break;
            case OVERLAY:
                intents.add(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri));
                break;
            case INSTALL:
                if (Build.VERSION.SDK_INT >= 26) intents.add(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri));
                break;
            case BATTERY:
                intents.add(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                break;
            case BACKGROUND:
                intents.add(new Intent().setComponent(new ComponentName("com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                        .putExtra("package_name", pkg)
                        .putExtra("package_label", context.getApplicationInfo().loadLabel(context.getPackageManager()).toString()));
                break;
            case AUTOSTART:
                intents.add(new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")));
                break;
        }
        intents.add(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri));
        intents.add(new Intent(Settings.ACTION_SETTINGS));
        return intents;
    }

    static boolean openSettings(Context context, Item item) {
        for (Intent intent : settingsIntents(context, item)) {
            try {
                context.startActivity(intent);
                return true;
            } catch (android.content.ActivityNotFoundException | SecurityException | IllegalArgumentException ignored) {
                // Vendor pages can be removed or private, even when resolveActivity reports them.
            }
        }
        return false;
    }
}
