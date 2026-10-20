package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

/** Local software checks of live viewport changes and interrupted transaction cleanup, absent from release. */
public final class RearCastViewportProbeInstrumentation extends Instrumentation {
    private boolean densityCompatibility;
    private ITaskService remote;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final JSONArray checks = new JSONArray();
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) { remote = ITaskService.Stub.asInterface(b); connected.countDown(); }
        public void onServiceDisconnected(ComponentName n) { remote = null; }
    };
    private interface Check { boolean ready() throws Exception; }
    private void await(String label, Check check) throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        do { if (check.ready()) return; SystemClock.sleep(80); } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError(label);
    }
    private String shell(String command) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
    private JSONObject state() throws Exception { return new JSONObject(remote.getRearVirtualCastStatus()); }
    private void passed(String label, JSONObject data) throws Exception {
        checks.put(new JSONObject().put("check", label).put("data", data));
        Bundle update = new Bundle(); update.putString("check", label); sendStatus(1, update);
    }
    private JSONObject startCast() throws Exception {
        RearPresentationPreferences.saveRotation(getTargetContext(), getTargetContext().getPackageName(), 0);
        RearPresentationPreferences.saveViewport(getTargetContext(), getTargetContext().getPackageName(), false);
        String component = getTargetContext().getPackageName() + "/top.zainzh.rearscreen.FixtureInputActivity";
        shell("input -d 1 keyevent KEYCODE_WAKEUP");
        shell("am start --display 1 --windowingMode 1 -n " + getTargetContext().getPackageName()
                + "/top.zainzh.rearscreen.VirtualRearHostActivity --ei task -1 --ei dpi 240 --es component " + component);
        await("Fixture cast start", () -> "running".equals(state().optString("state")) && FixtureInputActivity.instance != null);
        return state();
    }
    private void stopped(JSONObject before) throws Exception {
        await("Interrupted viewport must release input, display and host", () -> !"running".equals(state().optString("state"))
                && !"running".equals(new JSONObject(remote.getRearInputProxyStatus()).optString("state"))
                && remote.isTaskOnDisplay(before.getInt("task"), 0) && !remote.isTaskOnDisplay(before.getInt("host"), 1));
    }
    private void scope(boolean full) throws Exception {
        if (state().getBoolean("fullViewport") == full) return;
        String host = getTargetContext().getPackageName() + "/top.zainzh.rearscreen.VirtualRearHostActivity";
        shell("am start --display 1 -n " + host + " --ez preview_menu true"); SystemClock.sleep(300);
        shell("am start --display 1 -n " + host + " --ef preview_menu_x 864 --ef preview_menu_y 298");
        await("Actual menu range button", () -> state().getBoolean("fullViewport") == full && !state().getBoolean("viewportChanging"));
        SystemClock.sleep(350);
    }
    private JSONObject input(JSONObject s) throws Exception {
        FixtureInputActivity a = FixtureInputActivity.instance;
        if (a == null || !remote.isTaskOnDisplay(a.getTaskId(), s.getInt("display"))) throw new AssertionError("Fixture target left");
        runOnMainSync(a::resetCounters);
        String apk = getTargetContext().getApplicationInfo().sourceDir;
        for (String mode : new String[]{"tap", "double", "swipe", "multi"}) {
            String output = remote.executeShellCommandWithResult("CLASSPATH='" + apk.replace("'", "'\"'\"'")
                    + "' app_process / top.zainzh.rearscreen.RearViewportShellFixture " + s.getInt("display") + " " + mode + " 500 300 2>&1");
            if (!output.contains("completed " + mode)) throw new AssertionError(output);
            SystemClock.sleep(350);
        }
        JSONObject stats = new JSONObject();
        runOnMainSync(() -> { try { stats.put("ups", a.ups).put("moves", a.moves).put("doubleTaps", a.doubleTaps)
                .put("maxPointers", a.maxPointers).put("scales", a.scales); } catch (Exception e) { throw new RuntimeException(e); } });
        if (stats.getInt("ups") != 5 || stats.getInt("moves") < 7 || stats.getInt("doubleTaps") != 1 || stats.getInt("maxPointers") != 2)
            throw new AssertionError(stats.toString());
        return stats;
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); densityCompatibility = Boolean.parseBoolean(args.getString("densityCompatibility", "false")); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = 0;
        String fixturePackage = getTargetContext().getPackageName();
        android.content.SharedPreferences preferences = RearDensityPreferences.preferences(getTargetContext());
        String key = RearDensityPreferences.key(fixturePackage);
        boolean had = preferences.contains(key), saved = preferences.getBoolean(key, false);
        android.content.SharedPreferences presentation = RearPresentationPreferences.preferences(getTargetContext());
        String rotationKey = "rotation:" + fixturePackage, fullKey = "full:" + fixturePackage;
        boolean hadRotation = presentation.contains(rotationKey), hadFull = presentation.contains(fullKey);
        int savedRotation = presentation.getInt(rotationKey, 0); boolean savedFull = presentation.getBoolean(fullKey, false);
        try {
            if (densityCompatibility) runOnMainSync(() -> RearDensityPreferences.setEnabled(getTargetContext(), fixturePackage, true));
            runOnMainSync(() -> TaskServiceManager.acquire(getTargetContext(), connection));
            if (!connected.await(8, TimeUnit.SECONDS)) throw new AssertionError("Shizuku unavailable");
            JSONObject before = startCast(); String id = before.getString("id"); int task = before.getInt("task"), display = before.getInt("display");
            if (densityCompatibility) {
                android.view.Display mainDisplay = getTargetContext().getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
                int mainDensity = getTargetContext().createDisplayContext(mainDisplay).getResources().getDisplayMetrics().densityDpi;
                if (!"source_density".equals(before.getString("compatibility")) || before.getInt("density") != mainDensity)
                    throw new AssertionError("Per-App compatibility was not passed to backend: " + before);
                passed("per_app_enabled", before);
                runOnMainSync(() -> RearDensityPreferences.setEnabled(getTargetContext(), fixturePackage, false));
                SystemClock.sleep(250);
                if (!id.equals(state().getString("id")) || !"source_density".equals(state().getString("compatibility")))
                    throw new AssertionError("Preference changed the active cast");
                passed("active_session_keeps_snapshot", state());
            }
            for (int r = 0; r < 4; r++) {
                if (r > 0) remote.rotateRearVirtualCast(id, 1);
                for (boolean full : new boolean[]{false, true}) {
                    scope(full); JSONObject current = state();
                    if (!id.equals(current.getString("id")) || task != current.getInt("task") || display != current.getInt("display")
                            || r != current.getInt("contentRotation")) throw new AssertionError(current.toString());
                    passed("software_input_rotation_" + r + "_full_" + full, input(current));
                }
            }
            remote.stopRearVirtualCast(id); stopped(before); passed("normal_full_stop_restored", state());
            before = startCast(); id = before.getString("id");
            if (densityCompatibility) {
                if (!"none".equals(before.getString("compatibility")) || before.getInt("density") != 240)
                    throw new AssertionError("Next cast ignored disabled preference: " + before);
                passed("next_cast_uses_disabled_preference", before);
            }
            remote.beginRearCastViewport(id, true); // Deliberately omit the Surface commit.
            stopped(before); passed("uncommitted_viewport_timeout_restored", state());
            before = startCast(); id = before.getString("id"); remote.beginRearCastViewport(id, true);
            boolean rejected = false; try { remote.finishRearCastViewport(id, null); } catch (Exception expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Null Surface accepted");
            stopped(before); passed("invalid_surface_restored", state());
            before = startCast(); id = before.getString("id"); remote.beginRearCastViewport(id, true);
            remote.stopRearVirtualCast(id); stopped(before); passed("stop_during_viewport_restored", state());
            result.putString("result", checks.toString());
        } catch (Throwable e) {
            code = 1; result.putString("error", e.toString()); result.putString("completed", checks.toString());
        } finally {
            try { if (remote != null) remote.stopRearVirtualCast(null); } catch (Exception ignored) { }
            if (densityCompatibility) {
                android.content.SharedPreferences.Editor editor = preferences.edit();
                if (had) editor.putBoolean(key, saved); else editor.remove(key);
                editor.commit();
            }
            android.content.SharedPreferences.Editor savedPresentation = presentation.edit();
            if (hadRotation) savedPresentation.putInt(rotationKey, savedRotation); else savedPresentation.remove(rotationKey);
            if (hadFull) savedPresentation.putBoolean(fullKey, savedFull); else savedPresentation.remove(fullKey);
            savedPresentation.commit();
            runOnMainSync(() -> TaskServiceManager.release(connection));
        }
        finish(code, result);
    }
}
