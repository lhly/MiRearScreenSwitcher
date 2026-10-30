package top.zainzh.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;

/** App-owned preferences; the chosen value is explicitly passed to the shell session. */
public final class RearDensityPreferences {
    private static final String PREFS = "rear_density_compatibility";
    public static SharedPreferences preferences(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static String key(String packageName) { return "enabled:" + VirtualCastRequest.packageName(packageName); }
    public static boolean enabled(Context context, String packageName) {
        return BuildConfig.VIRTUAL_CAST_ENABLED && preferences(context).getBoolean(key(packageName), false);
    }
    public static void setEnabled(Context context, String packageName, boolean enabled) {
        if (BuildConfig.VIRTUAL_CAST_ENABLED) preferences(context).edit().putBoolean(key(packageName), enabled).apply();
    }
    public static int layoutDensity(Context context, String packageName) {
        String key = "dpi:" + VirtualCastRequest.packageName(packageName);
        int value;
        try { value = preferences(context).getInt(key, 0); }
        catch (ClassCastException corrupt) { return 0; }
        return value >= 72 && value <= 640 ? value : 0;
    }
    public static void setLayoutDensity(Context context, String packageName, int value) {
        if (value != 0 && (value < 72 || value > 640)) throw new IllegalArgumentException("Invalid layout density");
        String key = "dpi:" + VirtualCastRequest.packageName(packageName);
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) return;
        SharedPreferences.Editor edit = preferences(context).edit();
        if (value == 0) edit.remove(key); else edit.putInt(key, value);
        edit.apply();
    }
}
