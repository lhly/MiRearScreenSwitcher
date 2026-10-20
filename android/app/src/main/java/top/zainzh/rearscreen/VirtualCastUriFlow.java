package top.zainzh.rearscreen;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
import org.json.JSONObject;

/** Shared magnet switch/return flow for the selected virtual/direct mode. */
final class VirtualCastUriFlow {
    private static final String TAG = "VirtualCastUriFlow";
    static final String HOST = "top.zainzh.rearscreen.VirtualRearHostActivity";
    static final String EXTRA_SESSION = "virtualCastSessionId";
    private static final int HOST_START_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    static boolean handle(Context context, ITaskService remote, Uri uri) {
        if (!BuildConfig.VIRTUAL_CAST_ENABLED || !("switch".equals(uri.getHost()) || "return".equals(uri.getHost()))) return false;
        try {
            JSONObject before = new JSONObject(remote.getRearVirtualCastStatus());
            if ("return".equals(uri.getHost())) return stop(context, remote, uri, before);
            if ("running".equals(before.optString("state")) || "starting".equals(before.optString("state"))) {
                Log.i(TAG, "switch_ignored active_session=" + before.optString("id"));
                toast(context, "虚拟背屏已在投放，请先停止当前投放");
                return true;
            }
            if (RearScreenKeeperService.isRunning()) {
                toast(context, "请先停止当前投放，再开始新的投放");
                return true;
            }
            if (!RearCastMode.useVirtual(context)) {
                // The retired physical input comparison must not leak into normal direct casting.
                RearInputProxySession.setEnabled(context, false);
                Log.i(TAG, "switch_direct_selected");
                return false;
            }
            VirtualCastRequest.rotation(uri.getQueryParameter("rotation"));
            int density = VirtualCastRequest.density(uri.getQueryParameter("dpi"));
            finishOldHost(remote, before);
            String rear = remote.getForegroundAppOnDisplay(1);
            if (rear != null && !rear.startsWith("com.xiaomi.subscreencenter:")
                    && !rear.startsWith(context.getPackageName() + ":"))
                throw new IllegalStateException("背屏已有其他应用，请先停止投放");
            if (RearScreenKeeperService.isRunning()) throw new IllegalStateException("请先停止上一轮投放");
            String targetPackage, launchComponent = null;
            int task;
            if (VirtualCastRequest.yes(uri.getQueryParameter("current"))) {
                String[] parts = awaitForeground(context, remote, null).split(":");
                targetPackage = VirtualCastRequest.packageName(parts[0]);
                task = Integer.parseInt(parts[1]);
                if (!remote.isTaskOnDisplay(task, 0)) throw new IllegalStateException("目标任务已离开主屏");
            } else {
                String activity = uri.getQueryParameter("activity"), pkg = uri.getQueryParameter("packageName");
                if (activity != null) {
                    launchComponent = VirtualCastRequest.component(activity);
                    pkg = activity.substring(0, activity.indexOf('/'));
                } else {
                    pkg = VirtualCastRequest.packageName(pkg);
                    android.content.Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch == null || launch.getComponent() == null) throw new IllegalStateException("未找到应用启动入口");
                    launchComponent = VirtualCastRequest.component(launch.getComponent().flattenToString());
                }
                if (pkg.equals(context.getPackageName())) throw new IllegalArgumentException("请选择要投放的其他应用");
                targetPackage = pkg;
                task = remote.getTaskIdByPackage(pkg);
                if (task >= 0 && !remote.isTaskOnDisplay(task, 0)) throw new IllegalStateException("目标应用已在其他显示屏");
                // Existing package casts preserve their page; explicit Activity requests launch on the owned virtual display.
                if (task >= 0 && activity == null) launchComponent = null;
            }
            remote.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
            if (!remote.executeShellCommand("am start -f " + HOST_START_FLAGS + " --display 1 --windowingMode " + WINDOWING_MODE_FULLSCREEN + " -n " + context.getPackageName() + "/" + HOST
                    + " --ei task " + task + " --ei dpi " + density + " --es targetPackage " + targetPackage
                    + (launchComponent == null ? "" : " --es component '" + launchComponent + "'") + " --ez managed true"))
                throw new IllegalStateException("背屏显示宿主未启动");
            long end = SystemClock.uptimeMillis() + 8000;
            do {
                JSONObject state = new JSONObject(remote.getRearVirtualCastStatus());
                if (targetPackage.equals(state.optString("package")) && !state.optString("id").equals(before.optString("id"))) {
                    if ("running".equals(state.optString("state"))) {
                        Log.i(TAG, "switch_running id=" + state.optString("id") + " task=" + state.optInt("task") + " display=" + state.optInt("display"));
                        toast(context, "已投放到虚拟背屏");
                        return true;
                    }
                    if ("stopped".equals(state.optString("state"))) throw new IllegalStateException(state.optString("reason"));
                }
                Thread.sleep(100);
            } while (SystemClock.uptimeMillis() < end);
            // Finish only the host we just requested; its queued start then scoped stop releases resources.
            remote.executeShellCommand("am start --display 1 -n " + context.getPackageName() + "/" + HOST + " --ez stop true");
            throw new IllegalStateException("虚拟背屏启动超时，已取消");
        } catch (Exception e) {
            Log.e(TAG, "Virtual casting URI failed", e);
            toast(context, "虚拟背屏：" + e.getMessage());
            return true; // Never silently fall through into the physical rear path on failure.
        }
    }
    private static String awaitForeground(Context context, ITaskService remote, String expected) throws Exception {
        long end = SystemClock.uptimeMillis() + 4000;
        do {
            String value = remote.getCurrentForegroundApp();
            if (value != null && value.matches("[^:]+:[0-9]+")) {
                String pkg = value.substring(0, value.indexOf(':'));
                if (!pkg.equals(context.getPackageName()) && !pkg.equals("com.miui.home")
                        && !pkg.equals("com.xiaomi.subscreencenter") && (expected == null || expected.equals(pkg))) return value;
            }
            Thread.sleep(100);
        } while (SystemClock.uptimeMillis() < end);
        throw new IllegalStateException("未找到要投放的主屏应用");
    }
    private static boolean stop(Context context, ITaskService remote, Uri uri, JSONObject state) throws Exception {
        if (!("running".equals(state.optString("state")) || "starting".equals(state.optString("state")))) {
            String physicalTask = RearScreenKeeperService.physicalCastTask();
            if (physicalTask != null) return stopDirect(context, remote, uri, physicalTask);
            String rear = remote.getForegroundAppOnDisplay(1);
            if (rear != null && !rear.startsWith("com.xiaomi.subscreencenter:") && !rear.startsWith(context.getPackageName() + ":")) return false;
            Log.i(TAG, "return_noop");
            return true;
        }
        String foreground = remote.getForegroundAppOnDisplay(state.getInt("display"));
        String pkg = state.optString("package"), frontPackage = "";
        int frontTask = -1;
        if (foreground != null && foreground.matches("[^:]+:[0-9]+")) {
            String[] parts = foreground.split(":"); frontPackage = parts[0]; frontTask = Integer.parseInt(parts[1]);
        }
        if (!VirtualCastRequest.matchesReturn(uri.getQueryParameter("current"), uri.getQueryParameter("taskId"),
                uri.getQueryParameter("packageName"), state.getInt("task"), frontTask, pkg, frontPackage)) {
            Log.i(TAG, "return_ignored selector_mismatch id=" + state.getString("id"));
            toast(context, "指定应用不在当前虚拟背屏投放中");
            return true;
        }
        String id = state.getString("id");
        remote.stopRearVirtualCast(id);
        finishOldHost(remote, state);
        // Keeper teardown also uses this ID, so a late callback cannot stop a newer cast.
        RearScreenKeeperService.stopVirtualSession(id);
        Log.i(TAG, "return_stopped id=" + id);
        toast(context, "投放已停止，应用已返回主屏");
        return true;
    }
    private static boolean stopDirect(Context context, ITaskService remote, Uri uri, String physicalTask) throws Exception {
        String[] parts = physicalTask.split(":");
        if (parts.length != 2) throw new IllegalStateException("直接投放任务信息无效");
        String pkg = parts[0]; int task = Integer.parseInt(parts[1]);
        if (!VirtualCastRequest.matchesReturn(uri.getQueryParameter("current"), uri.getQueryParameter("taskId"),
                uri.getQueryParameter("packageName"), task, task, pkg, pkg)) {
            Log.i(TAG, "return_direct_ignored selector_mismatch task=" + task);
            return true;
        }
        // A temporary rear wake Activity can be foreground immediately after start. Restore the cast's task.
        if (remote.isTaskOnDisplay(task, 1) && !remote.moveTaskToDisplay(task, 0))
            throw new IllegalStateException("直接投放任务未能返回主屏");
        remote.enableSubScreenLauncher();
        context.stopService(new android.content.Intent(context, RearScreenKeeperService.class));
        Log.i(TAG, "return_direct_stopped task=" + task);
        toast(context, "投放已停止，应用已返回主屏");
        return true;
    }
    private static void finishOldHost(ITaskService remote, JSONObject state) throws Exception {
        int host = state.optInt("host", -1);
        if (host >= 0 && remote.isTaskOnDisplay(host, 1)) {
            remote.executeShellCommand("am stack remove " + host);
            long end = SystemClock.uptimeMillis() + 2000;
            while (remote.isTaskOnDisplay(host, 1) && SystemClock.uptimeMillis() < end) Thread.sleep(50);
            if (remote.isTaskOnDisplay(host, 1)) throw new IllegalStateException("上一轮背屏宿主正在退出，请重试");
        }
    }
    private static void toast(Context context, String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }
}
