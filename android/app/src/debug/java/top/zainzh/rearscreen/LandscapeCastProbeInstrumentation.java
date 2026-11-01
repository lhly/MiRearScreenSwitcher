package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Physical-device regression for an existing fixed-landscape/non-resizable task; debug only. */
public final class LandscapeCastProbeInstrumentation extends Instrumentation {
    private ITaskService remote;
    private boolean ownSession;
    private boolean nativeReplay;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final JSONArray checks = new JSONArray();
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { remote = ITaskService.Stub.asInterface(binder); connected.countDown(); }
        public void onServiceDisconnected(ComponentName name) { remote = null; }
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
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
    private JSONObject state() throws Exception { return new JSONObject(remote.getRearVirtualCastStatus()); }
    private void stop() throws Exception {
        if (!ownSession) return;
        remote.stopRearVirtualCast(null);
        await("Cast/input released", () -> !"running".equals(state().optString("state"))
                && !"running".equals(new JSONObject(remote.getRearInputProxyStatus()).optString("state")));
        ownSession = false;
    }
    private void restore(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> item : values.entrySet()) {
            Object value = item.getValue();
            if (value instanceof Boolean) editor.putBoolean(item.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(item.getKey(), (Integer) value);
            else if (value instanceof String) editor.putString(item.getKey(), (String) value);
        }
        editor.commit();
    }
    private void nativeTap(JSONObject current) throws Exception {
        JSONObject inventory = new JSONObject(remote.getCompatibilityDiagnostics()).getJSONObject("input");
        String node = inventory.getString("selected");
        if (!node.matches("/dev/input/event[0-9]+") || !node.equals(current.getJSONObject("input").getString("node")))
            throw new AssertionError("Input identity mismatch");
        JSONObject device = null;
        JSONArray devices = inventory.getJSONArray("devices");
        for (int i = 0; i < devices.length(); i++) if (node.equals(devices.getJSONObject(i).getString("path"))) device = devices.getJSONObject(i);
        if (device == null || device.getInt("associatedDisplay") != RearDisplay.requireId())
            throw new AssertionError("Explicit rear input association required for native replay");
        JSONObject viewport = current.getJSONObject("viewport");
        int physicalX = viewport.getInt("left") + viewport.getInt("width") / 2;
        int physicalY = viewport.getInt("top") + viewport.getInt("height") / 2;
        int x = device.getInt("xmin") + (int) ((long) (device.getInt("xmax") - device.getInt("xmin")) * physicalX / viewport.getInt("physicalWidth"));
        int y = device.getInt("ymin") + (int) ((long) (device.getInt("ymax") - device.getInt("ymin")) * physicalY / viewport.getInt("physicalHeight"));
        String command = "sendevent " + node + " ";
        String release = command + "3 47 0\n" + command + "3 57 -1\n" + command + "1 330 0\n" + command + "0 0 0";
        try {
            remote.executeShellCommandWithResult(command + "3 47 0\n" + command + "3 57 23000\n" + command + "3 53 " + x
                    + "\n" + command + "3 54 " + y + "\n" + command + "1 330 1\n" + command + "0 0 0");
            SystemClock.sleep(100);
        } finally { remote.executeShellCommandWithResult(release); }
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); nativeReplay = args != null && Boolean.parseBoolean(args.getString("nativeReplay", "false")); start();
    }
    @Override public void onStart() {
        Context context = getTargetContext(); String pkg = context.getPackageName();
        SharedPreferences density = RearDensityPreferences.preferences(context), presentation = RearPresentationPreferences.preferences(context);
        Map<String, ?> oldDensity = new HashMap<>(density.getAll()), oldPresentation = new HashMap<>(presentation.getAll());
        Bundle result = new Bundle(); int code = 0;
        try {
            runOnMainSync(() -> TaskServiceManager.acquire(context, connection));
            if (!connected.await(8, TimeUnit.SECONDS)) throw new AssertionError("Shizuku unavailable");
            if ("running".equals(state().optString("state"))) throw new AssertionError("Existing debug cast must be ended first");
            int rear = new JSONObject(remote.getRearDisplayState()).getInt("selectedId");
            if (rear <= 0) throw new AssertionError("Physical rear missing");
            for (boolean compatibility : new boolean[]{false, true}) for (boolean full : new boolean[]{false, true}) {
                RearDensityPreferences.setEnabled(context, pkg, compatibility);
                RearDensityPreferences.setLayoutDensity(context, pkg, 200);
                RearPresentationPreferences.saveRotation(context, pkg, 0);
                RearPresentationPreferences.saveViewport(context, pkg, full);
                shell("am start --display 0 -n " + pkg + "/top.zainzh.rearscreen.FixtureLandscapeMigrationActivity");
                await("Landscape source ready", () -> FixtureLandscapeMigrationActivity.instance != null
                        && FixtureLandscapeMigrationActivity.instance.getDisplay().getDisplayId() == 0
                        && FixtureLandscapeMigrationActivity.instance.getResources().getConfiguration().orientation == 2);
                int task = FixtureLandscapeMigrationActivity.instance.getTaskId();
                ownSession = true;
                shell("input -d " + rear + " keyevent KEYCODE_WAKEUP");
                shell("am start --display " + rear + " --windowingMode 1 -n " + pkg
                        + "/top.zainzh.rearscreen.VirtualRearHostActivity --ei task " + task + " --ei dpi 200");
                await("Existing landscape task cast", () -> "running".equals(state().optString("state")));
                JSONObject current = state(); int virtual = current.getInt("display");
                if (current.getInt("task") != task || current.getBoolean("fullViewport") != full
                        || !current.getString("compatibility").equals(compatibility ? "source_density" : "none")
                        || !"running".equals(current.getJSONObject("input").getString("state"))
                        || !remote.isTaskOnDisplay(task, virtual)) throw new AssertionError(current.toString());
                await("Landscape configuration preserved", () -> FixtureLandscapeMigrationActivity.instance != null
                        && FixtureLandscapeMigrationActivity.instance.getDisplay().getDisplayId() == virtual
                        && FixtureLandscapeMigrationActivity.instance.getResources().getConfiguration().orientation == 2);
                SystemClock.sleep(700);
                int before = FixtureLandscapeMigrationActivity.instance.ups;
                if (nativeReplay) nativeTap(current);
                else {
                    String replay = remote.executeShellCommandWithResult("CLASSPATH='" + context.getApplicationInfo().sourceDir
                            + "' app_process / top.zainzh.rearscreen.RearViewportShellFixture " + virtual + " tap 500 300 2>&1");
                    if (!replay.contains("completed tap")) throw new AssertionError(replay);
                }
                await("Landscape mapped tap delivered", () -> FixtureLandscapeMigrationActivity.instance.ups == before + 1);
                if (nativeReplay && state().getJSONObject("input").getLong("events") < 2)
                    throw new AssertionError("Native reader did not inject events");
                String label = "landscape_existing_task_compat_" + compatibility + "_full_" + full + (nativeReplay ? "_native" : "");
                shell("screencap -p -d " + remote.getRearPhysicalDisplayId() + " /data/local/tmp/" + label + ".png");
                stop();
                if (!remote.isTaskOnDisplay(task, 0)) throw new AssertionError("Landscape task not restored to main");
                runOnMainSync(() -> FixtureLandscapeMigrationActivity.instance.finish());
                await("Fixture closed", () -> FixtureLandscapeMigrationActivity.instance == null);
                checks.put(label); Bundle update = new Bundle(); update.putString("check", label); sendStatus(1, update);
            }
            result.putString("result", checks.toString());
        } catch (Throwable error) {
            code = 1; result.putString("error", error.toString()); result.putString("completed", checks.toString());
        } finally {
            try { if (remote != null) stop(); } catch (Exception ignored) { }
            if (FixtureLandscapeMigrationActivity.instance != null) runOnMainSync(() -> FixtureLandscapeMigrationActivity.instance.finish());
            restore(density, oldDensity); restore(presentation, oldPresentation);
            runOnMainSync(() -> TaskServiceManager.release(connection));
        }
        finish(code, result);
    }
}
