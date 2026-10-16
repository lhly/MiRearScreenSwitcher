package top.zainzh.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps the existing service preference contract and upgrades Flutter settings in place. */
final class NativePreferences {
    private final SharedPreferences settings;
    private final SharedPreferences legacy;

    static final Map<String, Boolean> BOOLEAN_DEFAULTS = new LinkedHashMap<>();
    static {
        BOOLEAN_DEFAULTS.put("proximity_sensor_enabled", true);
        BOOLEAN_DEFAULTS.put("charging_animation_enabled", true);
        BOOLEAN_DEFAULTS.put("keep_screen_on_enabled", true);
        BOOLEAN_DEFAULTS.put("always_wakeup_enabled", false);
        BOOLEAN_DEFAULTS.put("charging_always_on_enabled", false);
        BOOLEAN_DEFAULTS.put("notification_service_enabled", false);
        BOOLEAN_DEFAULTS.put("notification_privacy_hide_title", false);
        BOOLEAN_DEFAULTS.put("notification_privacy_hide_content", false);
        BOOLEAN_DEFAULTS.put("notification_follow_dnd_mode", true);
        BOOLEAN_DEFAULTS.put("notification_only_when_locked", false);
        BOOLEAN_DEFAULTS.put("notification_dark_mode", false);
    }

    NativePreferences(Context context) {
        settings = context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        legacy = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE);
        // Flutter was the source of truth for these two settings; native services read the mirror.
        if (!settings.getBoolean("native_ui_migrated", false)) {
            for (Map.Entry<String, Boolean> entry : BOOLEAN_DEFAULTS.entrySet()) {
                String key = entry.getKey();
                Object old = legacy.getAll().get("flutter." + key);
                putBoolean(key, old instanceof Boolean ? (Boolean) old : getBoolean(key));
            }
            Object oldDuration = legacy.getAll().get("flutter.notification_duration");
            putDuration(oldDuration instanceof Number ? ((Number) oldDuration).intValue() : getDuration());
            if (getBoolean("always_wakeup_enabled")) putBoolean("keep_screen_on_enabled", false);
            settings.edit().putBoolean("native_ui_migrated", true).apply();
        }
    }

    boolean getBoolean(String key) {
        boolean fallback = Boolean.TRUE.equals(BOOLEAN_DEFAULTS.get(key));
        if (settings.contains(key)) return settings.getBoolean(key, fallback);
        return legacy.getBoolean("flutter." + key, fallback);
    }

    void putBoolean(String key, boolean enabled) {
        settings.edit().putBoolean(key, enabled).apply();
        // Tiles, URI commands, charging and keeper intentionally retain their existing keys.
        legacy.edit().putBoolean("flutter." + key, enabled).apply();
    }

    int getDuration() {
        Object value = settings.getAll().get("notification_duration");
        if (!(value instanceof Number)) value = legacy.getAll().get("flutter.notification_duration");
        int duration = value instanceof Number ? ((Number) value).intValue() : 10;
        return Math.max(1, duration);
    }

    void putDuration(int seconds) {
        settings.edit().putInt("notification_duration", seconds).apply();
        legacy.edit().putLong("flutter.notification_duration", seconds).apply();
    }
}
