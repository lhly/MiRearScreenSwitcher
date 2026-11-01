package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

/** Real debug URI checks, including changing the next mode while a cast is still active. */
public final class RearCastModeProbeInstrumentation extends Instrumentation {
    private ITaskService remote;
    private String targetPackage = "tv.danmaku.bili";
    private boolean capture;
    private boolean densityCompatibility;
    private boolean virtualOnly;
    private boolean currentTilePath;
    private boolean lockMainAfterStart;
    private boolean addedTestTile;
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
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
    private void uri(String path) throws Exception {
        String result = shell("am start -a android.intent.action.VIEW -d " + BuildConfig.URI_SCHEME + "://" + path + " -p " + getTargetContext().getPackageName());
        if (result.contains("Error")) throw new AssertionError(result);
    }
    private JSONObject virtual() throws Exception { return new JSONObject(remote.getRearVirtualCastStatus()); }
    private void focus(int task) throws Exception { if (!remote.executeShellCommand("am task focus " + task)) throw new AssertionError("Focus failed"); }
    private void passed(String label) { checks.put(label); Bundle update = new Bundle(); update.putString("check", label); sendStatus(1, update); }
    private void stopped(int task) throws Exception {
        await("Stop must restore task and both modes", () -> !"running".equals(virtual().optString("state"))
                && !RearScreenKeeperService.isRunning() && remote.isTaskOnDisplay(task, 0));
    }
    private void direct(int task) throws Exception {
        await("Expected direct cast", () -> remote.isTaskOnDisplay(task, 1) && RearScreenKeeperService.isPhysicalCastRunning());
        if ("running".equals(virtual().optString("state")) || "running".equals(new JSONObject(remote.getRearInputProxyStatus()).optString("state"))
                || RearInputProxySession.enabled(getTargetContext())) throw new AssertionError("Direct mode grabbed input or created VD");
    }
    private void capture(String label) throws Exception {
        if (!capture) return;
        remote.executeShellCommand("input -d " + RearDisplay.requireId() + " keyevent KEYCODE_WAKEUP");
        SystemClock.sleep(700);
        if (!remote.executeShellCommand("screencap -p -d " + remote.getRearPhysicalDisplayId() + " /data/local/tmp/mode_" + label + ".png"))
            throw new AssertionError("Rear capture failed");
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (args != null) {
            targetPackage = VirtualCastRequest.packageName(args.getString("packageName", targetPackage));
            capture = Boolean.parseBoolean(args.getString("capture", "false"));
            densityCompatibility = Boolean.parseBoolean(args.getString("densityCompatibility", "false"));
            virtualOnly = Boolean.parseBoolean(args.getString("virtualOnly", "false"));
            currentTilePath = Boolean.parseBoolean(args.getString("currentTilePath", "false"));
            lockMainAfterStart = Boolean.parseBoolean(args.getString("lockMainAfterStart", "false"));
        }
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = 0, task = -1;
        Context context = getTargetContext(); boolean saved = RearCastMode.useVirtual(context);
        SharedPreferences density = RearDensityPreferences.preferences(context);
        String densityKey = RearDensityPreferences.key(targetPackage);
        boolean hadDensity = density.contains(densityKey), savedDensity = density.getBoolean(densityKey, false);
        try {
            runOnMainSync(() -> TaskServiceManager.acquire(context, connection));
            if (!connected.await(6, TimeUnit.SECONDS)) throw new AssertionError("Shizuku unavailable");
            RearCastMode.setVirtual(context, true);
            if (densityCompatibility || currentTilePath) RearDensityPreferences.setEnabled(context, targetPackage, densityCompatibility);
            if (currentTilePath) {
                String foreground = remote.getCurrentForegroundApp();
                if (foreground == null || !foreground.startsWith(targetPackage + ":")) throw new AssertionError("Expected foreground target: " + foreground);
                String tile = context.getPackageName() + "/" + SwitchToRearTileService.class.getName();
                String savedTiles = shell("settings get secure sysui_qs_tiles");
                try (FileOutputStream out = context.openFileOutput("tile-specs-before.txt", Context.MODE_PRIVATE)) { out.write(savedTiles.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                if (!savedTiles.contains(tile)) { shell("cmd statusbar add-tile " + tile); addedTestTile = true; SystemClock.sleep(1000); }
                shell("cmd statusbar expand-settings"); SystemClock.sleep(300);
                shell("cmd statusbar click-tile " + tile);
            } else uri("switch?packageName=" + targetPackage);
            await("Virtual cast start", () -> "running".equals(virtual().optString("state")) && RearScreenKeeperService.isRunning());
            JSONObject first = virtual(); task = first.getInt("task");
            passed("virtual_uri_selected");
            if (lockMainAfterStart) {
                SystemClock.sleep(400); // Let the host consume the returned session and release its startup flag.
                shell("input -d 0 keyevent KEYCODE_SLEEP"); SystemClock.sleep(1800);
                await("Rear output recovered after main sleep", () -> first.getString("id").equals(virtual().optString("id"))
                        && "running".equals(virtual().optString("state")) && virtual().optBoolean("outputAvailable"));
                String power = remote.executeShellCommandWithResult("dumpsys power");
                java.util.regex.Matcher group = java.util.regex.Pattern.compile("groupId: 0\\s*\\n(?:(?!groupId:).)*?mWakefulness=(\\d+)",
                        java.util.regex.Pattern.DOTALL).matcher(power);
                if (!group.find()) throw new AssertionError("Main power group state unavailable");
                String mainPowerState = group.group(1);
                result.putString("mainPowerStateAfterSleep", mainPowerState);
                // Both asleep and AOD/dozing are noninteractive; an enabled AOD is not a main wake.
                if (!"0".equals(mainPowerState) && !"3".equals(mainPowerState))
                    throw new AssertionError("Main power group remained interactive: " + mainPowerState);
                passed("main_stays_asleep_rear_cast_running");
            }
            capture("virtual");
            if (lockMainAfterStart) shell("input -d 0 keyevent KEYCODE_WAKEUP");
            RearCastMode.setVirtual(context, false);
            uri("switch?current=1"); SystemClock.sleep(400);
            if (!first.getString("id").equals(virtual().optString("id")) || !remote.isTaskOnDisplay(task, first.getInt("display")))
                throw new AssertionError("Mode flip interrupted an active virtual cast");
            uri("return?current=1"); stopped(task); passed("virtual_stop_after_switch_off");

            if (virtualOnly) {
                SystemClock.sleep(3000); stopped(task); passed("virtual_return_keeps_target_task");
            } else {
            RearInputProxySession.setEnabled(context, true); // Old prototype preference must not affect direct casting.
            focus(task); uri("switch?current=1"); direct(task); passed("direct_uri_without_input_grab"); capture("direct");
            RearCastMode.setVirtual(context, true);
            uri("switch?current=1"); SystemClock.sleep(400); direct(task);
            uri("return?current=1"); stopped(task); passed("direct_stop_after_switch_on");
            }

            result.putString("result", new JSONObject().put("checks", checks).put("task", task).toString());
        } catch (Throwable e) {
            code = 1; result.putString("error", e.toString()); result.putString("completed", checks.toString());
        } finally {
            try {
                if (remote != null) {
                    remote.stopRearVirtualCast(null); remote.stopRearInputProxy(null);
                    if (task >= 0 && remote.isTaskOnDisplay(task, 1)) remote.moveTaskToDisplay(task, 0);
                }
                shell("cmd statusbar collapse");
            } catch (Exception ignored) { }
            if (lockMainAfterStart) try { shell("input -d 0 keyevent KEYCODE_WAKEUP"); } catch (Exception ignored) { }
            if (addedTestTile) try { shell("cmd statusbar remove-tile " + context.getPackageName() + "/" + SwitchToRearTileService.class.getName()); } catch (Exception ignored) { }
            RearInputProxySession.setEnabled(context, false); RearCastMode.setVirtual(context, saved);
            if (densityCompatibility || currentTilePath) {
                SharedPreferences.Editor editor = density.edit();
                if (hadDensity) editor.putBoolean(densityKey, savedDensity); else editor.remove(densityKey);
                editor.commit();
            }
            runOnMainSync(() -> { context.stopService(new Intent(context, RearScreenKeeperService.class)); TaskServiceManager.release(connection); });
        }
        finish(code, result);
    }
}
