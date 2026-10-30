package top.zainzh.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;

/** One snapshot per capture, shared by the tile, URI and settings page. */
public final class RearScreenshotPreferences {
    private RearScreenshotPreferences() {}
    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("rear_screenshot", Context.MODE_PRIVATE);
    }
    public static final class Options {
        public final boolean shell, keepOriginal;
        public final String style, color;
        Options(boolean shell, boolean keepOriginal, String style, String color) {
            this.shell = shell; this.keepOriginal = keepOriginal;
            this.style = "half".equals(style) ? "half" : "full";
            this.color = "WH".equals(color) || "PU".equals(color) || "LG".equals(color) ? color : "BK";
        }
    }
    public static Options read(Context context) {
        SharedPreferences p = preferences(context);
        return new Options(p.getBoolean("shell", false), p.getBoolean("keep_original", false),
                p.getString("style", "full"), p.getString("color", "BK"));
    }
}
