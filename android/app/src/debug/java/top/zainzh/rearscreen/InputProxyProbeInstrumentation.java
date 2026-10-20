package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.MotionEvent;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Explicit local device probe, absent from release. */
public final class InputProxyProbeInstrumentation extends Instrumentation {
    private Bundle args;
    private String shellFixture(String kind, String reset) throws Exception {
        String apk = getTargetContext().getApplicationInfo().sourceDir;
        return remote.executeShellCommandWithResult("CLASSPATH='" + apk.replace("'", "'\"'\"'")
            + "' app_process / top.zainzh.rearscreen.RearTouchShellFixture " + kind + " " + reset + " 2>&1");
    }
    private ITaskService remote;
    private android.content.ServiceConnection connection;
    private JSONObject metrics(FixtureInputActivity a) throws Exception {
        JSONObject data = new JSONObject();
        runOnMainSync(() -> {
            try { data.put("ups", a.ups).put("moves", a.moves).put("doubleTaps", a.doubleTaps)
                .put("cancels", a.cancels).put("maxPointers", a.maxPointers).put("scales", a.scales)
                .put("scaleFactor", a.scaleFactor).put("genericEvents", a.genericEvents).put("focused", a.focused); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        data.put("power", power()); return data;
    }
    private JSONObject relay(FixtureInputActivity a, String mode) throws Exception {
        java.util.concurrent.CountDownLatch connected = new java.util.concurrent.CountDownLatch(1);
        connection = new android.content.ServiceConnection() {
            public void onServiceConnected(android.content.ComponentName n, android.os.IBinder b) {
                remote = ITaskService.Stub.asInterface(b); connected.countDown();
            }
            public void onServiceDisconnected(android.content.ComponentName n) { remote = null; }
        };
        TaskServiceManager.acquire(getTargetContext(), connection);
        if (!connected.await(8, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Authorize debug app in Shizuku first");
        String path = getTargetContext().getApplicationInfo().nativeLibraryDir + "/libremrss_input_proxy.so";
        JSONObject result = new JSONObject();
        String start;
        if (mode.equals("relay-keeper")) {
            RearInputProxySession.setEnabled(getTargetContext(), true);
            android.content.Intent keeper = new android.content.Intent(getTargetContext(), RearScreenKeeperService.class);
            keeper.putExtra("lastMovedTask", getTargetContext().getPackageName() + ":" + a.getTaskId());
            keeper.putExtra("keepScreenOnEnabled", false);
            getTargetContext().startForegroundService(keeper);
            long deadline = SystemClock.uptimeMillis() + 7000;
            do { start = remote.getRearInputProxyStatus();
                if (new JSONObject(start).optString("state").equals("running")) break;
                SystemClock.sleep(100);
            } while (SystemClock.uptimeMillis() < deadline);
            if (!new JSONObject(start).optString("state").equals("running")) throw new IllegalStateException("Keeper failed: " + start);
        } else start = remote.startRearInputProxy(path, a.getTaskId(), new android.os.Binder());
        result.put("start", new JSONObject(start));
        String id = new JSONObject(start).getString("id");
        String node = new JSONObject(start).getString("node");
        if (!node.matches("/dev/input/event[0-9]+")) throw new IllegalStateException("Invalid rear node");
        if (mode.equals("relay-manual")) {
            runOnMainSync(a::resetCounters);
            long until = SystemClock.uptimeMillis() + 120000;
            while (SystemClock.uptimeMillis() < until && remote.renewRearInputProxyLease(id)) {
                SystemClock.sleep(1000);
                Bundle update = new Bundle(); update.putString("manual", metrics(a).toString()); sendStatus(1, update);
            }
            result.put("manual", metrics(a));
            result.put("running", new JSONObject(remote.getRearInputProxyStatus()));
            remote.stopRearInputProxy(id);
            result.put("stopped", new JSONObject(remote.getRearInputProxyStatus()));
            return result;
        }
        for (String kind : new String[]{"tap", "swipe", "double", "pinch"}) {
            runOnMainSync(a::resetCounters);
            remote.renewRearInputProxyLease(id);
            String output = shellFixture(kind, "scroll");
            SystemClock.sleep(250);
            result.put(kind, metrics(a).put("softwareFixture", output));
            SystemClock.sleep(500);
        }
        result.put("running", new JSONObject(remote.getRearInputProxyStatus()));
        if (mode.equals("relay-owner")) {
            Bundle update = new Bundle(); update.putString("ownerReady", result.toString()); sendStatus(2, update);
            // Host kills only this debug app; no finally/onDestroy release is involved.
            SystemClock.sleep(15000);
        }
        if (mode.equals("relay-lease")) {
            SystemClock.sleep(4800);
        } else if (mode.equals("relay-keeper")) {
            getTargetContext().stopService(new android.content.Intent(getTargetContext(), RearScreenKeeperService.class));
            SystemClock.sleep(1000);
            result.put("stopped", new JSONObject(remote.getRearInputProxyStatus()));
            return result;
        } else remote.stopRearInputProxy(id);
        result.put("stopped", new JSONObject(remote.getRearInputProxyStatus()));
        runOnMainSync(a::resetCounters);
        shellFixture("double", "none");
        SystemClock.sleep(350);
        result.put("restored", metrics(a));
        return result;
    }
    @Override public void onCreate(Bundle arguments) { args = arguments; start(); }
    private String shell(String command) throws Exception {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }
    private String power() throws Exception {
        String dump = shell("dumpsys power");
        java.util.regex.Matcher group = java.util.regex.Pattern.compile("groupId: 1\\n(.*?)(?=\\ngroupId:|\\n\\s*Display Power|\\n\\n)", java.util.regex.Pattern.DOTALL).matcher(dump);
        if (!group.find()) return "missing";
        StringBuilder out = new StringBuilder();
        for (String line : group.group(1).split("\\n")) if (line.contains("mWakefulness") || line.contains("mLastSleepReason")) out.append(line.trim()).append(' ');
        return out.toString();
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); FixtureInputActivity activity = null;
        String mode = args.getString("mode", "none");
        try {
            shell("input -d 1 keyevent KEYCODE_WAKEUP");
            String component = getTargetContext().getPackageName() + "/top.zainzh.rearscreen.FixtureInputActivity";
            shell("am start --display 1 -n " + component);
            long deadline = SystemClock.uptimeMillis() + 6000;
            while ((FixtureInputActivity.instance == null || !FixtureInputActivity.instance.focused) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            activity = FixtureInputActivity.instance;
            if (activity == null || !activity.focused) throw new IllegalStateException("Probe not focused; check rear proximity cover and unlock");
            FixtureInputActivity target = activity;
            RearInputInjector injector = new RearInputInjector(1, event -> getUiAutomation().injectInputEvent(event, false));
            injector.resetSystemGesture("scroll", 400, 350);
            SystemClock.sleep(500);
            if (mode.startsWith("relay")) {
                result.putString("data", relay(target, mode).toString());
                return;
            }
            runOnMainSync(target::resetCounters);
            JSONObject data = new JSONObject(); data.put("mode", mode); data.put("before", power());
            float x = 400, y = 350;
            org.json.JSONArray times = new org.json.JSONArray();
            for (int i = 0; i < 2; i++) {
                long now = SystemClock.uptimeMillis();
                times.put(now);
                injector.touch(MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{x}, new float[]{y}, now, now);
                SystemClock.sleep(35);
                injector.touch(MotionEvent.ACTION_UP, new int[]{0}, new float[]{x}, new float[]{y}, now, SystemClock.uptimeMillis());
                injector.resetSystemGesture(mode, x, y);
                times.put(SystemClock.uptimeMillis());
                SystemClock.sleep(65);
            }
            SystemClock.sleep(250);
            data.put("after", power()); data.put("ups", activity.ups); data.put("doubleTaps", activity.doubleTaps);
            data.put("times", times);
            data.put("cancels", activity.cancels); data.put("genericEvents", activity.genericEvents); data.put("focused", activity.focused);
            result.putString("data", data.toString());
        } catch (Throwable e) { result.putString("error", e.toString()); }
        finally {
            getTargetContext().stopService(new android.content.Intent(getTargetContext(), RearScreenKeeperService.class));
            getTargetContext().getSharedPreferences(RearInputProxySession.PREFS, 0).edit().putBoolean("enabled", false).commit();
            if (remote != null) try { remote.stopRearInputProxy(null); } catch (Exception ignored) { }
            if (connection != null) TaskServiceManager.release(connection);
            if (activity != null) { FixtureInputActivity target = activity; runOnMainSync(target::finish); }
            try { shell("am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher"); }
            catch (Exception e) { result.putString("cleanupError", e.toString()); }
            finish(0, result);
        }
    }
}
