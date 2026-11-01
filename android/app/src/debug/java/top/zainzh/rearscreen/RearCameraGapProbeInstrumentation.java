package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.*;
import org.json.*;
import java.util.concurrent.*;

/** Real host/battery/settings tests without Espresso or UiAutomation on HyperOS. */
public final class RearCameraGapProbeInstrumentation extends Instrumentation {
    private ITaskService remote;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { remote = ITaskService.Stub.asInterface(binder); connected.countDown(); }
        public void onServiceDisconnected(ComponentName name) { remote = null; }
    };
    private interface Condition { boolean ready() throws Exception; }
    private void await(String label, Condition condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        do { if (condition.ready()) return; SystemClock.sleep(80); } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError(label);
    }
    private JSONObject state() throws Exception { return new JSONObject(remote.getRearVirtualCastStatus()); }
    private RearCameraGapBatteryView layer;
    private RearCameraStatusView statusLayer;
    private RearCameraStatusController statusController;
    private int dpi;
    private String physicalDisplay;
    private boolean lockMain;
    private RearCameraGapBatteryView find(View view) {
        if (view instanceof RearCameraGapBatteryView) return (RearCameraGapBatteryView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            RearCameraGapBatteryView result = find(((ViewGroup) view).getChildAt(i));
            if (result != null) return result;
        }
        return null;
    }
    private boolean visible() {
        boolean[] value = {false};
        runOnMainSync(() -> value[0] = layer != null && layer.isShown()); return value[0];
    }
    private RearCameraStatusView findStatus(View view) {
        if (view instanceof RearCameraStatusView) return (RearCameraStatusView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            RearCameraStatusView found = findStatus(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private boolean statusVisible() {
        boolean[] value = {false}; runOnMainSync(() -> value[0] = statusLayer != null && statusLayer.isShown()); return value[0];
    }
    private int draws() {
        int[] value = {0}; runOnMainSync(() -> value[0] = layer.getDrawCount()); return value[0];
    }
    private void scope(boolean full, int rear, String host) throws Exception {
        remote.executeShellCommand("am start --display " + rear + " -n " + host + " --ez preview_menu true");
        SystemClock.sleep(320);
        remote.executeShellCommand("am start --display " + rear + " -n " + host + " --ef preview_menu_x 864 --ef preview_menu_y 298");
        await("Range switch confirmation", () -> state().getBoolean("fullViewport") == full && !state().optBoolean("viewportChanging"));
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); dpi = Integer.parseInt(args.getString("dpi", "240"));
        physicalDisplay = args.getString("physicalDisplay", "");
        lockMain = Boolean.parseBoolean(args.getString("lockMain", "false")); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = 0; boolean started = false, mainSlept = false;
        String pkg = getTargetContext().getPackageName();
        SharedPreferences prefs = RearCameraGapPreferences.preferences(getTargetContext());
        boolean had = prefs.contains(RearCameraGapPreferences.ENABLED), old = RearCameraGapPreferences.enabled(getTargetContext());
        boolean hadStatus = prefs.contains(RearCameraGapPreferences.STATUS_ENABLED), oldStatus = RearCameraGapPreferences.statusEnabled(getTargetContext());
        SharedPreferences presentation = RearPresentationPreferences.preferences(getTargetContext());
        String rotationKey = "rotation:" + pkg, fullKey = "full:" + pkg;
        boolean hadRotation = presentation.contains(rotationKey), hadFull = presentation.contains(fullKey);
        int rotation = presentation.getInt(rotationKey, 0); boolean full = presentation.getBoolean(fullKey, false);
        JSONArray checks = new JSONArray();
        try {
            boolean templateOnly = RearCameraGapGeometry.supported(Build.DEVICE) == null;
            if (RearCameraGapGeometry.candidate(Build.DEVICE) == null) throw new AssertionError("No geometry candidate for this device");
            runOnMainSync(() -> TaskServiceManager.acquire(getTargetContext(), connection));
            if (!connected.await(8, TimeUnit.SECONDS)) throw new AssertionError("Shizuku unavailable");
            if ("running".equals(state().optString("state"))) throw new AssertionError("Another cast is active");
            int rear = new JSONObject(remote.getRearDisplayState()).getInt("selectedId");
            String host = pkg + "/top.zainzh.rearscreen.VirtualRearHostActivity";
            runOnMainSync(() -> {
                RearCameraGapPreferences.setEnabled(getTargetContext(), false);
                RearCameraGapPreferences.setStatusEnabled(getTargetContext(), false);
                RearPresentationPreferences.saveRotation(getTargetContext(), pkg, 0);
                RearPresentationPreferences.saveViewport(getTargetContext(), pkg, false);
            });
            remote.executeShellCommand("input -d " + rear + " keyevent KEYCODE_WAKEUP");
            remote.executeShellCommand("am start --display " + rear + " --windowingMode 1 -n " + host
                    + " --ei task -1 --ei dpi " + dpi + " --es component " + pkg + "/top.zainzh.rearscreen.FixtureInputActivity");
            started = true;
            await("Cast start", () -> "running".equals(state().optString("state")) && VirtualRearHostActivity.instance != null);
            runOnMainSync(() -> layer = find(VirtualRearHostActivity.instance.getWindow().getDecorView()));
            runOnMainSync(() -> {
                statusLayer = findStatus(VirtualRearHostActivity.instance.getWindow().getDecorView());
                try {
                    java.lang.reflect.Field field = VirtualRearHostActivity.class.getDeclaredField("cameraStatus");
                    field.setAccessible(true); statusController = (RearCameraStatusController) field.get(VirtualRearHostActivity.instance);
                } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            });
            if (templateOnly) {
                // Exercise the real lifecycle with a candidate without changing release eligibility.
                runOnMainSync(() -> {
                    try {
                        VirtualRearHostActivity hostActivity = VirtualRearHostActivity.instance;
                        java.lang.reflect.Field field = VirtualRearHostActivity.class.getDeclaredField("cameraGapBattery");
                        field.setAccessible(true);
                        ((RearCameraGapBatteryController) field.get(hostActivity)).close();
                        RearCameraGapBatteryController candidate = new RearCameraGapBatteryController(hostActivity, layer,
                                RearCameraGapGeometry.candidate(Build.DEVICE));
                        field.set(hostActivity, candidate); candidate.visible(true);
                        java.lang.reflect.Method update = VirtualRearHostActivity.class.getDeclaredMethod("updateCameraGapBattery");
                        update.setAccessible(true); update.invoke(hostActivity);
                    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                });
                checks.put("candidate_geometry_software_only_optical_pending");
            }
            if (layer == null || visible()) throw new AssertionError("Disabled battery layer is visible");
            if (statusVisible() || statusController.isObserving()) throw new AssertionError("Disabled status reads data");
            checks.put("disabled_on_start");
            runOnMainSync(() -> RearCameraGapPreferences.setEnabled(getTargetContext(), true));
            await("Immediate enable and initial battery", () -> visible() && layer.getPercentage() >= 0);
            checks.put("live_preference_and_real_battery");
            runOnMainSync(() -> RearCameraGapPreferences.setStatusEnabled(getTargetContext(), true));
            await("Status and direct system weather", () -> statusVisible() && !statusLayer.getTimeLabel().isEmpty() && !statusLayer.getWeatherLabel().isEmpty());
            checks.put("system_weather_and_time");
            SystemClock.sleep(400); int initialDraws = draws(); SystemClock.sleep(1000);
            if (draws() != initialDraws) throw new AssertionError("Static battery layer redraws continuously");
            checks.put("static_no_frame_loop");
            int[] statusDraws = {0}; String[] beforeTime = {""};
            runOnMainSync(() -> { statusDraws[0] = statusLayer.getDrawCount(); beforeTime[0] = statusLayer.getTimeLabel(); });
            SystemClock.sleep(1200);
            runOnMainSync(() -> {
                int allowed = beforeTime[0].equals(statusLayer.getTimeLabel()) ? 0 : 1;
                if (statusLayer.getDrawCount() > statusDraws[0] + allowed) throw new AssertionError("Status has a frame loop");
            });
            checks.put("status_static_between_minutes");
            String id = state().getString("id");
            for (int i = 0; i < 4; i++) {
                remote.rotateRearVirtualCast(id, 1);
                if (!visible()) throw new AssertionError("App rotation moved or hid the physical battery");
                if (!statusVisible()) throw new AssertionError("App rotation moved status");
            }
            checks.put("four_content_rotations");
            scope(true, rear, host); await("Full range hides battery", () -> !visible());
            await("Full range stops status", () -> !statusVisible() && !statusController.isObserving());
            scope(false, rear, host); await("Safe range restores battery", this::visible);
            await("Safe range restores status", this::statusVisible);
            checks.put("safe_full_handoff");
            runOnMainSync(() -> RearCameraGapPreferences.setEnabled(getTargetContext(), false));
            await("Immediate disable", () -> !visible());
            if (!statusVisible()) throw new AssertionError("Battery toggle affected status");
            runOnMainSync(() -> RearCameraGapPreferences.setEnabled(getTargetContext(), true));
            await("Immediate re-enable", this::visible); checks.put("repeated_preference_changes");
            runOnMainSync(() -> RearCameraGapPreferences.setStatusEnabled(getTargetContext(), false));
            await("Status independently stops", () -> !statusVisible() && !statusController.isObserving());
            if (!visible()) throw new AssertionError("Status toggle affected battery");
            runOnMainSync(() -> RearCameraGapPreferences.setStatusEnabled(getTargetContext(), true));
            await("Status independently resumes", this::statusVisible); checks.put("independent_status_toggle");
            FixtureInputActivity input = FixtureInputActivity.instance;
            if (input == null) throw new AssertionError("Fixture disappeared");
            runOnMainSync(input::resetCounters);
            remote.executeShellCommand("input -d " + state().getInt("display") + " tap 200 300");
            await("Target still receives software input", () -> input.ups == 1); checks.put("software_input_to_target");
            if (lockMain) {
                mainSlept = true;
                remote.executeShellCommand("input keyevent KEYCODE_SLEEP");
                android.hardware.display.DisplayManager displays = getTargetContext().getSystemService(android.hardware.display.DisplayManager.class);
                await("Main display off", () -> displays.getDisplay(0).getState() == Display.STATE_OFF);
                await("Rear battery recovers while main stays off", () -> visible() && "running".equals(state().optString("state")));
                if (displays.getDisplay(0).getState() != Display.STATE_OFF) throw new AssertionError("Recovery woke the main display");
                checks.put("main_off_rear_battery_restored");
            }
            if (physicalDisplay.matches("[0-9]+")) {
                String physical = Long.toUnsignedString(Long.parseUnsignedLong(physicalDisplay));
                remote.executeShellCommand("screencap -d " + physical + " -p /data/local/tmp/remrss-camera-gap-" + dpi + ".png");
            }
            runOnMainSync(() -> VirtualRearHostActivity.instance.finish());
            await("Session cleanup", () -> !"running".equals(state().optString("state")));
            if (visible()) throw new AssertionError("Battery layer leaked after session exit");
            if (statusVisible() || statusController.isObserving()) throw new AssertionError("Status listener leaked after exit");
            checks.put("exit_cleanup");
            result.putString("checks", checks.toString()); code = -1;
        } catch (Throwable e) { result.putString("checks", checks.toString()); result.putString("error", android.util.Log.getStackTraceString(e)); }
        finally {
            if (mainSlept && remote != null) try { remote.executeShellCommand("input -d 0 keyevent KEYCODE_WAKEUP"); } catch (Exception ignored) { }
            if (started && VirtualRearHostActivity.instance != null) runOnMainSync(() -> VirtualRearHostActivity.instance.finish());
            if (FixtureInputActivity.instance != null) runOnMainSync(() -> FixtureInputActivity.instance.finish());
            runOnMainSync(() -> {
                SharedPreferences.Editor edit = prefs.edit();
                if (had) edit.putBoolean(RearCameraGapPreferences.ENABLED, old); else edit.remove(RearCameraGapPreferences.ENABLED);
                if (hadStatus) edit.putBoolean(RearCameraGapPreferences.STATUS_ENABLED, oldStatus); else edit.remove(RearCameraGapPreferences.STATUS_ENABLED);
                edit.commit();
                SharedPreferences.Editor restore = presentation.edit();
                if (hadRotation) restore.putInt(rotationKey, rotation); else restore.remove(rotationKey);
                if (hadFull) restore.putBoolean(fullKey, full); else restore.remove(fullKey);
                restore.commit();
                TaskServiceManager.release(connection);
            });
        }
        finish(code, result);
    }
}
