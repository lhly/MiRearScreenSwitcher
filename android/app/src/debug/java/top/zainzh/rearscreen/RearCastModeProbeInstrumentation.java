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
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = 0, task = -1;
        Context context = getTargetContext(); boolean saved = RearCastMode.useVirtual(context);
        try {
            runOnMainSync(() -> TaskServiceManager.acquire(context, connection));
            if (!connected.await(6, TimeUnit.SECONDS)) throw new AssertionError("Shizuku unavailable");
            RearCastMode.setVirtual(context, true);
            uri("switch?packageName=tv.danmaku.bili");
            await("Virtual cast start", () -> "running".equals(virtual().optString("state")) && RearScreenKeeperService.isRunning());
            JSONObject first = virtual(); task = first.getInt("task");
            passed("virtual_uri_selected");
            RearCastMode.setVirtual(context, false);
            uri("switch?current=1"); SystemClock.sleep(400);
            if (!first.getString("id").equals(virtual().optString("id")) || !remote.isTaskOnDisplay(task, first.getInt("display")))
                throw new AssertionError("Mode flip interrupted an active virtual cast");
            uri("return?current=1"); stopped(task); passed("virtual_stop_after_switch_off");

            RearInputProxySession.setEnabled(context, true); // Old prototype preference must not affect direct casting.
            focus(task); uri("switch?current=1"); direct(task); passed("direct_uri_without_input_grab");
            RearCastMode.setVirtual(context, true);
            uri("switch?current=1"); SystemClock.sleep(400); direct(task);
            uri("return?current=1"); stopped(task); passed("direct_stop_after_switch_on");

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
            RearInputProxySession.setEnabled(context, false); RearCastMode.setVirtual(context, saved);
            runOnMainSync(() -> { context.stopService(new Intent(context, RearScreenKeeperService.class)); TaskServiceManager.release(connection); });
        }
        finish(code, result);
    }
}
