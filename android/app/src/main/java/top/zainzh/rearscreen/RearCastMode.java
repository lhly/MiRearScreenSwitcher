package top.zainzh.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;

/** A choice for the next cast. The running session always owns its own shutdown path. */
public final class RearCastMode {
    public static final String PREFS = "rear_cast_mode";
    public static final String VIRTUAL = "virtual_display";
    public static SharedPreferences preferences(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public static boolean useVirtual(Context context) {
        // Existing production users keep direct casting until they opt into virtual casting.
        return BuildConfig.VIRTUAL_CAST_ENABLED && android.os.Build.VERSION.SDK_INT >= 33
                && preferences(context).getBoolean(VIRTUAL, BuildConfig.DEBUG);
    }
    public static void setVirtual(Context context, boolean enabled) {
        if (BuildConfig.VIRTUAL_CAST_ENABLED) preferences(context).edit().putBoolean(VIRTUAL, enabled).apply();
    }
}
