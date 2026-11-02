package top.zainzh.rearscreen;

import android.content.Context;

/** The transport is independent of the remote process UID (Shizuku can also run as root). */
public enum PrivilegeMode {
    SHIZUKU, ROOT;
    static final String PREFERENCES = "mrss_privilege";
    public static PrivilegeMode read(Context context) {
        String value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString("mode", SHIZUKU.name());
        return ROOT.name().equals(value) ? ROOT : SHIZUKU;
    }
    void save(Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString("mode", name()).apply();
    }
}
