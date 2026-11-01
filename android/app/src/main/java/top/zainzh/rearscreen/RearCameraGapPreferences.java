package top.zainzh.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;

public final class RearCameraGapPreferences {
    public static final String ENABLED = "camera_gap_battery_enabled";
    public static final String STATUS_ENABLED = "camera_status_enabled";
    private RearCameraGapPreferences() {}
    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
    }
    public static boolean enabled(Context context) { return preferences(context).getBoolean(ENABLED, false); }
    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply();
    }
    public static boolean statusEnabled(Context context) { return preferences(context).getBoolean(STATUS_ENABLED, false); }
    public static void setStatusEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(STATUS_ENABLED, enabled).apply();
    }
}
