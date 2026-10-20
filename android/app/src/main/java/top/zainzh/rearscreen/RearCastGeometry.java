package top.zainzh.rearscreen;

import android.view.DisplayCutout;
import android.view.RoundedCorner;
import org.json.JSONObject;
import java.io.IOException;

/** Shell-side display query; never infer missing cutout geometry from a fullscreen screenshot. */
final class RearCastGeometry {
    static int displayDensity(int display) throws Exception {
        Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, display);
        if (info == null) throw new IOException("Source display unavailable");
        return info.getClass().getField("logicalDensityDpi").getInt(info);
    }
    static RearCastViewport read() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 31) throw new IOException("Safe rear casting requires Android 12 or later");
        Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, 1);
        if (info == null) throw new IOException("Rear display unavailable");
        Class<?> type = info.getClass();
        DisplayCutout cutout = (DisplayCutout) type.getField("displayCutout").get(info);
        if (cutout == null) throw new IOException("Rear cutout unavailable; safe casting cannot start");
        Object corners = type.getField("roundedCorners").get(info);
        if (corners == null) throw new IOException("Rear rounded corners unavailable");
        int[] radii = new int[4];
        for (int i = 0; i < 4; i++) {
            RoundedCorner corner = (RoundedCorner) corners.getClass().getMethod("getRoundedCorner", int.class).invoke(corners, i);
            radii[i] = corner == null ? 0 : corner.getRadius();
        }
        android.graphics.Insets waterfall = cutout.getWaterfallInsets();
        return RearCastViewport.safe(type.getField("logicalWidth").getInt(info), type.getField("logicalHeight").getInt(info),
                type.getField("rotation").getInt(info), type.getField("logicalDensityDpi").getInt(info),
                Math.max(cutout.getSafeInsetLeft(), waterfall.left), Math.max(cutout.getSafeInsetTop(), waterfall.top),
                Math.max(cutout.getSafeInsetRight(), waterfall.right), Math.max(cutout.getSafeInsetBottom(), waterfall.bottom), radii);
    }
    static String encode(RearCastViewport v) throws Exception {
        return new JSONObject().put("physicalWidth", v.physicalWidth).put("physicalHeight", v.physicalHeight)
                .put("rotation", v.rotation).put("density", v.density).put("left", v.left).put("top", v.top)
                .put("right", v.right).put("bottom", v.bottom).put("width", v.width()).put("height", v.height())
                .put("bufferWidth", v.bufferWidth).put("bufferHeight", v.bufferHeight).toString();
    }
    static RearCastViewport decode(String value) throws Exception {
        JSONObject j = new JSONObject(value);
        RearCastViewport physical = new RearCastViewport(j.getInt("physicalWidth"), j.getInt("physicalHeight"), j.getInt("rotation"),
                j.getInt("density"), j.getInt("left"), j.getInt("top"), j.getInt("right"), j.getInt("bottom"));
        return physical.buffered(j.optInt("bufferWidth", physical.width()), j.optInt("bufferHeight", physical.height()));
    }
}
