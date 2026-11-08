package top.zainzh.rearscreen;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.graphics.Point;
import android.view.Display;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Shared physical-panel identity. No guessed display number is returned on discovery failure. */
public final class RearDisplay {
    private static volatile Context app;
    private static volatile ITaskService remote;
    private static volatile boolean shell;
    private static volatile int trustedId = -1;
    private static volatile String trustedUnique = "";
    private static volatile long lastRead;
    private static volatile int selected = -1;
    private static volatile String reason = "not_detected";
    private RearDisplay() {}
    static void initialize(Context context) {
        app = context.getApplicationContext();
        DisplayManager manager = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        if (manager != null) manager.registerDisplayListener(new DisplayManager.DisplayListener() {
            public void onDisplayAdded(int id) { lastRead = 0; }
            public void onDisplayChanged(int id) { lastRead = 0; }
            public void onDisplayRemoved(int id) { if (id == trustedId) { trustedId = -1; trustedUnique = ""; } lastRead = 0; }
        }, new android.os.Handler(android.os.Looper.getMainLooper()));
    }
    static void initializeShell() { shell = true; lastRead = 0; }
    static void attach(ITaskService service) {
        remote = service;
        try {
            JSONObject state = new JSONObject(service.getRearDisplayState());
            trustedId = state.optInt("selectedId", -1);
            trustedUnique = state.optString("selectedUnique", "");
            lastRead = 0;
        } catch (Exception e) { trustedId = -1; trustedUnique = ""; lastRead = 0; }
    }
    public static boolean isRear(int display) { return display > 0 && display == id(); }
    public static int requireId() {
        int id = id();
        if (id < 1) throw new IllegalStateException("REAR_DISPLAY_UNAVAILABLE: " + reason);
        return id;
    }
    public static synchronized int id() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastRead != 0 && now - lastRead < 500) return selected;
        selected = -1;
        try {
            if (!shell && remote != null && remote.asBinder().isBinderAlive()) {
                // Only the privileged service can enumerate private panels on some ROMs.
                // Its selection already uses the same physical-panel policy; app visibility is not a second requirement.
                JSONObject state = new JSONObject(remote.getRearDisplayState());
                trustedId = state.optInt("selectedId", -1); trustedUnique = state.optString("selectedUnique", "");
                selected = trustedId > 0 ? trustedId : -1;
                reason = "service_identity: " + state.optString("reason", "unknown");
                lastRead = now;
                return selected;
            }
            List<RearDisplayPolicy.Candidate> all = candidates();
            if (selected < 0) {
                RearDisplayPolicy.Candidate c = RearDisplayPolicy.select(all);
                if (c != null) { selected = c.id; reason = "secondary_internal_panel"; }
                else reason = "missing_or_ambiguous_internal_panel";
            }
        } catch (Exception e) { reason = e.getClass().getSimpleName() + ": " + e.getMessage(); }
        lastRead = now;
        return selected;
    }
    static Object global() throws Exception {
        return Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
    }
    static Object info(int id) throws Exception {
        if (id < 0) throw new IllegalArgumentException("Invalid display");
        Object global = global();
        Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, id);
        if (info == null) throw new IllegalStateException("Display disappeared: " + id);
        return info;
    }
    static List<RearDisplayPolicy.Candidate> candidates() throws Exception {
        List<RearDisplayPolicy.Candidate> all = new ArrayList<>();
        if (shell) {
            Object manager = global();
            int[] ids;
            try { ids = (int[]) manager.getClass().getMethod("getDisplayIds", boolean.class).invoke(manager, true); }
            catch (NoSuchMethodException e) { ids = (int[]) manager.getClass().getMethod("getDisplayIds").invoke(manager); }
            for (int id : ids) {
                Object info = info(id); Class<?> type = info.getClass();
                all.add(new RearDisplayPolicy.Candidate(id, type.getField("type").getInt(info),
                        String.valueOf(type.getField("name").get(info)), String.valueOf(type.getField("uniqueId").get(info)),
                        type.getField("logicalWidth").getInt(info), type.getField("logicalHeight").getInt(info)));
            }
        } else if (app != null) {
            DisplayManager manager = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            if (manager != null) for (Display display : manager.getDisplays()) {
                if (!display.isValid()) continue;
                Point size = new Point(); display.getRealSize(size);
                int type = 0; String unique = "";
                try { type = (Integer) Display.class.getMethod("getType").invoke(display); } catch (Exception ignored) { }
                try { unique = (String) Display.class.getMethod("getUniqueId").invoke(display); } catch (Exception ignored) { }
                all.add(new RearDisplayPolicy.Candidate(display.getDisplayId(), type, display.getName(), unique, size.x, size.y));
            }
        }
        return all;
    }
    static String state() {
        JSONObject result = new JSONObject(); JSONArray displays = new JSONArray();
        try {
            List<RearDisplayPolicy.Candidate> all = candidates();
            RearDisplayPolicy.Candidate panel = shell ? RearDisplayPolicy.select(all) : null;
            int id = shell ? panel == null ? -1 : panel.id : id();
            if (shell) {
                selected = id; reason = panel == null ? "missing_or_ambiguous_internal_panel" : "secondary_internal_panel";
                lastRead = android.os.SystemClock.elapsedRealtime();
            }
            result.put("selectedId", id).put("selectedUnique", panel == null ? "" : panel.unique).put("reason", reason);
            for (RearDisplayPolicy.Candidate c : all) {
                JSONObject d = new JSONObject().put("id", c.id).put("type", c.type).put("name", c.name)
                        .put("unique", c.unique).put("width", c.width).put("height", c.height);
                if (shell) try {
                    Object info = info(c.id); Class<?> type = info.getClass();
                    d.put("rotation", type.getField("rotation").getInt(info)).put("density", type.getField("logicalDensityDpi").getInt(info))
                            .put("state", type.getField("state").getInt(info)).put("flags", type.getField("flags").getInt(info))
                            .put("cutout", String.valueOf(type.getField("displayCutout").get(info)));
                } catch (Exception e) { d.put("metadataError", e.toString()); }
                displays.put(d);
            }
        } catch (Exception e) { try { result.put("selectedId", -1).put("error", e.toString()); } catch (Exception ignored) { } }
        try { result.put("displays", displays); } catch (Exception ignored) { }
        return result.toString();
    }
    public static String identity() {
        try {
            int id = requireId(); Object info = info(id);
            return id + ":" + info.getClass().getField("uniqueId").get(info);
        } catch (Exception e) { throw new IllegalStateException("Rear identity unavailable", e); }
    }
    static String physicalId() throws Exception {
        Object info = info(requireId()); Object address = info.getClass().getField("address").get(info);
        if (address == null) throw new IllegalStateException("Rear physical address unavailable");
        long id = (Long) address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
        return Long.toUnsignedString(id);
    }
}
