package top.zainzh.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;

/** Per-App presentation; save confirmed menu results, never in-flight requests. */
public final class RearPresentationPreferences {
    private static final String PREFS = "rear_presentation";
    public static final class Selection {
        public final int rotation;
        public final boolean full;
        Selection(int rotation, boolean full) { this.rotation = rotation; this.full = full; }
    }
    static SharedPreferences preferences(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static String key(String field, String packageName) { return field + ":" + VirtualCastRequest.packageName(packageName); }
    public static Selection read(Context context, String packageName) {
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) return new Selection(0, false);
        SharedPreferences p = preferences(context);
        int rotation = p.getInt(key("rotation", packageName), 0);
        // Discard an obsolete/invalid value rather than preventing the App from being cast.
        if (rotation < 0 || rotation > 3) rotation = 0;
        return new Selection(rotation, p.getBoolean(key("full", packageName), false));
    }
    public static void saveRotation(Context context, String packageName, int rotation) {
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) return;
        RearCastViewport.validateContentRotation(rotation);
        preferences(context).edit().putInt(key("rotation", packageName), rotation).apply();
    }
    public static void saveViewport(Context context, String packageName, boolean full) {
        if (BuildConfig.VIRTUAL_CAST_ENABLED) preferences(context).edit().putBoolean(key("full", packageName), full).apply();
    }
}
