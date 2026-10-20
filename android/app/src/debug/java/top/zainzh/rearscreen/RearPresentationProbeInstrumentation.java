package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;

/** Cold-process menu persistence and initial rendering/input checks. Debug APK only. */
public final class RearPresentationProbeInstrumentation extends Instrumentation {
    private String mode;
    private ITaskService remote;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final JSONArray checks = new JSONArray();
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) { remote = ITaskService.Stub.asInterface(b); connected.countDown(); }
        public void onServiceDisconnected(ComponentName n) { remote = null; }
    };
    private interface Check { boolean ready() throws Exception; }
    private void await(String label, Check check) throws Exception {
        long end = SystemClock.uptimeMillis() + 12000;
        do { if (check.ready()) return; SystemClock.sleep(100); } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError(label);
    }
    private String shell(String command) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n); return out.toString("UTF-8");
        }
    }
    private String pkg() { return getTargetContext().getPackageName(); }
    private JSONObject state() throws Exception { return new JSONObject(remote.getRearVirtualCastStatus()); }
    private RearPresentationPreferences.Selection choice() { return RearPresentationPreferences.read(getTargetContext(), pkg()); }
    private void selected(int r, boolean full) {
        RearPresentationPreferences.Selection p = choice();
        if (p.rotation != r || p.full != full) throw new AssertionError("Saved choice " + p.rotation + "/" + p.full);
    }
    private void passed(String name) { checks.put(name); Bundle b = new Bundle(); b.putString("check", name); sendStatus(1, b); }
    private JSONObject startCast(int rotation, boolean full, int density) throws Exception {
        shell("input -d 1 keyevent KEYCODE_WAKEUP");
        shell("am start --display 1 --windowingMode 1 -n " + pkg() + "/top.zainzh.rearscreen.VirtualRearHostActivity"
                + " --ei task -1 --ei dpi 240 --es component " + pkg() + "/top.zainzh.rearscreen.FixtureInputActivity");
        await("Saved initial cast configuration", () -> "running".equals(state().optString("state")) && FixtureInputActivity.instance != null);
        SystemClock.sleep(700); // Let the live geometry guard run with the restored full/rotated viewport.
        JSONObject s = state();
        if (!"running".equals(s.optString("state")) || s.getInt("contentRotation") != rotation
                || s.getBoolean("fullViewport") != full || s.getInt("density") != density)
            throw new AssertionError(s.toString());
        JSONObject input = new JSONObject(remote.getRearInputProxyStatus());
        if (!"running".equals(input.optString("state")) || input.getInt("contentRotation") != rotation
                || !input.getBoolean("outputAvailable")) throw new AssertionError(input.toString());
        return s;
    }
    private void stopped(JSONObject s) throws Exception {
        await("Stop restores task and releases display, host and input", () -> "stopped".equals(state().optString("state"))
                && !"running".equals(new JSONObject(remote.getRearInputProxyStatus()).optString("state"))
                && remote.isTaskOnDisplay(s.getInt("task"), 0) && !remote.isTaskOnDisplay(s.getInt("host"), 1));
    }
    private void stop(JSONObject s) throws Exception { remote.stopRearVirtualCast(s.getString("id")); stopped(s); }
    private void menu(float x, int rotation, boolean full) throws Exception {
        String host = pkg() + "/top.zainzh.rearscreen.VirtualRearHostActivity";
        shell("am start --display 1 -n " + host + " --ez preview_menu true"); SystemClock.sleep(400);
        shell("am start --display 1 -n " + host + " --ef preview_menu_x " + x + " --ef preview_menu_y 298");
        await("Successful menu choice saved", () -> state().optInt("contentRotation") == rotation
                && state().optBoolean("fullViewport") == full && !state().optBoolean("viewportChanging")
                && choice().rotation == rotation && choice().full == full);
        SystemClock.sleep(300); selected(rotation, full);
    }
    private void input(JSONObject s) throws Exception {
        FixtureInputActivity a = FixtureInputActivity.instance;
        if (a == null || !remote.isTaskOnDisplay(a.getTaskId(), s.getInt("display"))) throw new AssertionError("Restored fixture left display");
        runOnMainSync(a::resetCounters);
        String apk = getTargetContext().getApplicationInfo().sourceDir;
        for (String gesture : new String[]{"tap", "double", "swipe", "multi"}) {
            String output = remote.executeShellCommandWithResult("CLASSPATH='" + apk.replace("'", "'\"'\"'")
                    + "' app_process / top.zainzh.rearscreen.RearViewportShellFixture " + s.getInt("display") + " " + gesture + " 500 300 2>&1");
            if (!output.contains("completed " + gesture)) throw new AssertionError(output);
            SystemClock.sleep(350);
        }
        int[] stats = new int[4];
        runOnMainSync(() -> { stats[0] = a.ups; stats[1] = a.moves; stats[2] = a.doubleTaps; stats[3] = a.maxPointers; });
        if (stats[0] != 5 || stats[1] < 7 || stats[2] != 1 || stats[3] != 2)
            throw new AssertionError("Restored software input " + java.util.Arrays.toString(stats));
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); mode = args.getString("mode", "setup"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = 0;
        try {
            if ("cleanup".equals(mode)) {
                RearPresentationPreferences.preferences(getTargetContext()).edit().remove("rotation:" + pkg()).remove("full:" + pkg()).commit();
                RearDensityPreferences.preferences(getTargetContext()).edit().remove(RearDensityPreferences.key(pkg())).commit();
                passed("fixture_only_preferences_removed");
            } else {
                runOnMainSync(() -> TaskServiceManager.acquire(getTargetContext(), connection));
                if (!connected.await(8, TimeUnit.SECONDS)) throw new AssertionError("Shizuku unavailable");
                if ("setup".equals(mode)) {
                    selected(0, false); RearDensityPreferences.setEnabled(getTargetContext(), pkg(), true);
                    JSONObject s = startCast(0, false, 480); passed("first_cast_default_safe_rotation0");
                    menu(700, 1, false); passed("right_rotation_saved");
                    menu(550, 0, false); passed("explicit_rotation0_saved");
                    menu(550, 3, false); menu(864, 3, true); passed("left_rotation_and_full_saved");
                    stop(s); selected(3, true); passed("normal_stop_keeps_choice");
                } else if ("verify".equals(mode)) {
                    selected(3, true); passed("choice_survives_cold_process");
                    JSONObject s = startCast(3, true, 480); input(s); passed("cold_cast_restores_full_rotation3_and_scaled_input");
                    menu(864, 3, false); passed("safe_mode_saved_without_losing_rotation");
                    menu(700, 0, false); passed("right_wraparound_saved_without_losing_scope"); stop(s);
                    s = startCast(0, false, 480); stop(s); passed("next_cast_restores_explicit_defaults");
                } else if ("matrix".equals(mode)) {
                    for (int r = 0; r < 4; r++) for (boolean full : new boolean[]{false, true}) {
                        RearPresentationPreferences.saveRotation(getTargetContext(), pkg(), r);
                        RearPresentationPreferences.saveViewport(getTargetContext(), pkg(), full);
                        RearDensityPreferences.setEnabled(getTargetContext(), pkg(), full);
                        JSONObject s = startCast(r, full, full ? 480 : 240); input(s); stop(s);
                        passed("restored_rotation_" + r + "_full_" + full + "_input_and_stop");
                    }
                    selected(3, true); JSONObject s = startCast(3, true, 480);
                    remote.beginRearCastViewport(s.getString("id"), false); stopped(s); selected(3, true);
                    passed("failed_viewport_keeps_last_successful_choice");
                } else throw new IllegalArgumentException("Unknown probe mode");
            }
            result.putString("result", checks.toString());
        } catch (Throwable e) { code = 1; result.putString("error", e.toString()); result.putString("completed", checks.toString()); }
        finally {
            try { if (remote != null) remote.stopRearVirtualCast(null); } catch (Exception ignored) { }
            if (!"cleanup".equals(mode)) runOnMainSync(() -> TaskServiceManager.release(connection));
        }
        finish(code, result);
    }
}
