package top.zainzh.rearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Local integration checks of the debug magnet URI and notification/stop lifecycles. */
public final class VirtualMagnetProbeInstrumentation extends Instrumentation {
    private ITaskService remote;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) { remote = ITaskService.Stub.asInterface(b); connected.countDown(); }
        public void onServiceDisconnected(ComponentName n) { remote = null; }
    };
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private String shell(String cmd) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(cmd));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
    private void uri(String path) throws Exception {
        // UiAutomation passes argv directly, so shell quotes would become literal URI bytes.
        String output = shell("am start -a android.intent.action.VIEW -d " + BuildConfig.URI_SCHEME + "://" + path + " -p " + getTargetContext().getPackageName());
        if (output.contains("Error")) throw new AssertionError(output);
    }
    private JSONObject waitRunning() throws Exception {
        return waitRunning(true);
    }
    private JSONObject waitRunning(boolean keeper) throws Exception {
        long end = SystemClock.uptimeMillis() + 12000;
        do {
            JSONObject s = new JSONObject(remote.getRearVirtualCastStatus());
            if (s.optString("state").equals("running") && RearScreenKeeperService.isRunning() == keeper) return s;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Virtual URI startup timed out");
    }
    private void waitStopped(JSONObject prior) throws Exception {
        long end = SystemClock.uptimeMillis() + 8000;
        do {
            JSONObject s = new JSONObject(remote.getRearVirtualCastStatus());
            if (s.optString("state").equals("stopped") && !RearScreenKeeperService.isRunning()
                    && remote.isTaskOnDisplay(prior.getInt("task"), 0) && !remote.isTaskOnDisplay(prior.getInt("host"), 1)) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Virtual URI recovery timed out");
    }
    private void notification(String id) {
        runOnMainSync(() -> getTargetContext().startService(new Intent(getTargetContext(), RearScreenKeeperService.class)
                .setAction("ACTION_RETURN_TO_MAIN").putExtra(VirtualCastUriFlow.EXTRA_SESSION, id)));
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); JSONArray checks = new JSONArray(); int code = 0;
        try {
            runOnMainSync(() -> TaskServiceManager.acquire(getTargetContext(), connection));
            if (!connected.await(5, TimeUnit.SECONDS)) throw new AssertionError("Shizuku unavailable");
            uri("switch?packageName=tv.danmaku.bili"); JSONObject first = waitRunning();
            notification(first.getString("id")); waitStopped(first);
            checks.put("notification_return_restored");
            // A manual debug host has no Keeper; an old notification must not stop that new owner.
            shell("am start --display 1 --windowingMode 1 -n " + getTargetContext().getPackageName()
                    + "/top.zainzh.rearscreen.VirtualRearHostActivity --ei task " + first.getInt("task"));
            JSONObject manual = waitRunning(false);
            notification(first.getString("id")); SystemClock.sleep(600);
            JSONObject cold = new JSONObject(remote.getRearVirtualCastStatus());
            if (!cold.optString("state").equals("running") || !cold.optString("id").equals(manual.getString("id")))
                throw new AssertionError("Cold stale notification stopped another owner");
            checks.put("stale_cold_notification_ignored");
            remote.stopRearVirtualCast(manual.getString("id")); waitStopped(manual);
            uri("switch?packageName=tv.danmaku.bili"); JSONObject second = waitRunning();
            notification(first.getString("id")); SystemClock.sleep(600);
            JSONObject after = new JSONObject(remote.getRearVirtualCastStatus());
            if (!after.optString("state").equals("running") || !after.optString("id").equals(second.getString("id")))
                throw new AssertionError("Stale notification stopped a newer session");
            checks.put("stale_notification_ignored");
            runOnMainSync(() -> getTargetContext().stopService(new Intent(getTargetContext(), RearScreenKeeperService.class)));
            waitStopped(second); checks.put("stop_service_restored");
            // Simulate magnet removal immediately after attachment, using the actual serial URI service.
            uri("switch?packageName=tv.danmaku.bili"); uri("return?current=1");
            long end = SystemClock.uptimeMillis() + 14000;
            do {
                JSONObject s = new JSONObject(remote.getRearVirtualCastStatus());
                if (s.optString("state").equals("stopped") && !s.optString("id").equals(second.getString("id"))
                        && !RearScreenKeeperService.isRunning() && remote.isTaskOnDisplay(second.getInt("task"), 0)) {
                    checks.put("immediate_switch_return_restored"); break;
                }
                SystemClock.sleep(100);
            } while (SystemClock.uptimeMillis() < end);
            if (checks.length() != 5) throw new AssertionError("Immediate return did not complete");
            result.putString("result", new JSONObject().put("checks", checks).put("state", new JSONObject(remote.getRearVirtualCastStatus())).toString());
        } catch (Throwable e) {
            code = 1; result.putString("error", e.toString()); result.putString("completed", checks.toString());
        } finally {
            try { if (remote != null) remote.stopRearVirtualCast(null); } catch (Exception ignored) { }
            runOnMainSync(() -> { getTargetContext().stopService(new Intent(getTargetContext(), RearScreenKeeperService.class)); TaskServiceManager.release(connection); });
        }
        finish(code, result);
    }
}
