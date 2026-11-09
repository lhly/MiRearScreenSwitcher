/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Chief Tester: 汐木泽
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package top.zainzh.rearscreen;

import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 运行在Shizuku进程中的服务，具有shell权限
 */
public class TaskService extends ITaskService.Stub {
    private static final String TAG = "TaskService";
    static final int USER_SERVICE_CONTRACT_VERSION = 59;
    @Override public String getRearDisplayState() { return RearDisplay.state(); }
    @Override public String getCompatibilityDiagnostics() {
        try { return new org.json.JSONObject(CompatibilityDiagnostics.report())
                .put("virtualCast", new org.json.JSONObject(virtualCast.status()))
                .put("inputProxy", new org.json.JSONObject(inputProxy.status())).toString(); }
        catch (Exception e) { return CompatibilityDiagnostics.report(); }
    }
    @Override public String getRearPhysicalDisplayId() {
        try { return RearDisplay.physicalId(); }
        catch (Exception e) { throw new IllegalStateException("Rear physical display: " + e, e); }
    }
    private final SystemMetricsReader systemMetrics = new SystemMetricsReader();
    @Override public String readSystemMetrics() { return systemMetrics.read(); }
    @Override public String readSystemWeather() {
        return android.os.Build.VERSION.SDK_INT >= 26 ? SystemWeatherReader.read() : "{}";
    }
    private final RearInputProxy inputProxy = new RearInputProxy(this::isTaskOnDisplay);
    private final RearVirtualCast virtualCast = new RearVirtualCast(this, inputProxy);

    // Transactions 52-56 remain reserved for wire compatibility; theme operations are not part of RC1.
    @Override public String getOfficialThemeStatus() { return "{\"availability\":\"UNSUPPORTED\",\"reason\":\"not_in_rc1\"}"; }
    @Override public String installOfficialTheme(String id, int systemId, String title, byte[] compiledResource) { throw new UnsupportedOperationException("E_UNSUPPORTED: official themes are not part of RC1"); }
    @Override public String removeOfficialTheme(String id) { throw new UnsupportedOperationException("E_UNSUPPORTED: official themes are not part of RC1"); }
    @Override public void prepareOfficialThemeHost(int task, String component, android.os.IBinder owner) { throw new UnsupportedOperationException("E_UNSUPPORTED: official themes are not part of RC1"); }
    @Override public void cancelOfficialThemeHost(android.os.IBinder owner) { throw new UnsupportedOperationException("E_UNSUPPORTED: official themes are not part of RC1"); }

    @Override public String getRearCastViewport() {
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) throw new SecurityException("Virtual casting is unavailable");
        try { return RearCastGeometry.encode(RearCastGeometry.read()); }
        catch (Exception e) { throw new IllegalStateException("Rear safe area: " + e, e); }
    }
    @Override public String getRearCastRenderPlan(int task, String component, int density, boolean compatibility, boolean full) {
        try { return virtualCast.renderPlan(task, component, density, compatibility, full); }
        catch (Exception e) { throw new IllegalStateException("Rear render plan: " + e, e); }
    }
    @Override public String startRearVirtualCast(android.view.Surface surface, String path, int task, int host, int density, String component, android.os.IBinder owner, IRearGestureListener gestures, String viewport, boolean compatibility, int rotation, boolean full) {
        try { return virtualCast.start(surface, path, task, host, density, component, owner, gestures, viewport, compatibility, rotation, full); }
        catch (Exception e) { throw new IllegalStateException("Rear virtual cast: " + e, e); }
    }
    @Override public void dismissRearCastMenu(String id) {
        try { virtualCast.dismissMenu(id); }
        catch (Exception e) { throw new IllegalStateException("Rear menu: " + e, e); }
    }
    @Override public String rotateRearVirtualCast(String id, int direction) {
        try { return virtualCast.rotate(id, direction); }
        catch (Exception e) { throw new IllegalStateException("Rear content rotation: " + e, e); }
    }
    @Override public String beginRearCastViewport(String id, boolean full) {
        try { return virtualCast.beginViewport(id, full); }
        catch (Exception e) { throw new IllegalStateException("Rear viewport begin: " + e, e); }
    }
    @Override public String finishRearCastViewport(String id, android.view.Surface surface) {
        try { return virtualCast.finishViewport(id, surface); }
        catch (Exception e) { throw new IllegalStateException("Rear viewport finish: " + e, e); }
    }
    @Override public String getRearVirtualCastStatus() { return virtualCast.status(); }
    @Override public boolean renewRearVirtualCastLease(String id) { return virtualCast.renew(id); }
    @Override public void stopRearVirtualCast(String id) { virtualCast.stop(id); }
    @Override public boolean setRearCastNotificationBounds(String id, long token, int left, int top, int right, int bottom) {
        return virtualCast.notification(id, token, left, top, right, bottom);
    }
    @Override public boolean updateRearVirtualCastSurface(String id, android.view.Surface surface) {
        try { return virtualCast.updateSurface(id, surface); }
        catch (Exception e) { throw new IllegalStateException("Rear output reconnect: " + e, e); }
    }
    @Override public String setRearVirtualCastOutputAvailable(String id, boolean available, android.view.Surface replacement,
                                                               ISceneTaskActionGate actionGate) {
        try { return virtualCast.setOutputAvailable(id, available, replacement, actionGate); }
        catch (Exception e) { throw new IllegalStateException("Rear output power state: " + e, e); }
    }
    @Override public String recoverRearVirtualCastHost(String id, int host, int rearDisplayId, int action,
                                                       ISceneTaskActionGate actionGate) {
        try { return virtualCast.recoverHost(id, host, rearDisplayId, action, actionGate); }
        catch (Exception e) { throw new IllegalStateException("Rear virtual host recovery: " + e, e); }
    }

    @Override public boolean reportOwnedNotificationActivity(int displayId, long notificationSessionId,
                                                              long expiresAtElapsedRealtimeMs,
                                                              long drawEventUptime,
                                                              ISceneTaskActionGate currentGate) {
        String result = "unavailable";
        try {
            if (notificationSessionId <= 0 || displayId < 1 || expiresAtElapsedRealtimeMs <= 0 ||
                    drawEventUptime < 0 || currentGate == null ||
                    android.os.SystemClock.elapsedRealtime() >= expiresAtElapsedRealtimeMs) {
                result = "invalid_request";
                return false;
            }
            RearDisplayUserActivity.Result report = RearDisplayUserActivity.reportOwnedNotification(
                    this::rearUserActivitySink,
                    targetDisplayId -> {
                        org.json.JSONObject rear = new org.json.JSONObject(RearDisplay.state());
                        int selectedRearId = rear.optInt("selectedId", -1);
                        String selectedRearUniqueId = rear.optString("selectedUnique", "");
                        Object info = RearDisplay.info(targetDisplayId);
                        String actualDisplayUniqueId = String.valueOf(info.getClass().getField("uniqueId").get(info));
                        boolean displayOn = info.getClass().getField("state").getInt(info) == android.view.Display.STATE_ON;
                        return new RearDisplayUserActivity.DisplaySnapshot(
                                selectedRearId, selectedRearUniqueId, actualDisplayUniqueId, displayOn);
                    },
                    currentGate::isCurrent, android.os.SystemClock::elapsedRealtime,
                    displayId, notificationSessionId, expiresAtElapsedRealtimeMs, drawEventUptime);
            result = report.name().toLowerCase(java.util.Locale.ROOT);
            return report == RearDisplayUserActivity.Result.REPORTED;
        } catch (Exception failure) {
            result = "failed_" + failure.getClass().getSimpleName();
            synchronized (rearUserActivitySinkLock) { rearUserActivitySink = null; }
            return false;
        } finally {
            CompatibilityDiagnostics.record("owned_notification_activity",
                    "session=" + notificationSessionId + " display=" + displayId
                            + " draw_uptime=" + drawEventUptime + " result=" + result);
            Log.i(TAG, "owned_notification_activity session=" + notificationSessionId +
                    " display=" + displayId + " draw_uptime=" + drawEventUptime + " result=" + result);
        }
    }

    private RearDisplayUserActivity.Sink rearUserActivitySink() throws Exception {
        RearDisplayUserActivity.Sink sink = rearUserActivitySink;
        if (sink != null) return sink;
        synchronized (rearUserActivitySinkLock) {
            if (rearUserActivitySink == null) rearUserActivitySink = RearDisplayUserActivity.shell();
            return rearUserActivitySink;
        }
    }

    @Override public String startRearInputProxy(String path, int task, android.os.IBinder owner) {
        if (!BuildConfig.DEBUG || !BuildConfig.INPUT_PROXY_PROTOTYPE) throw new SecurityException("Direct input probe is debug-only");
        try { return inputProxy.start(path, task, owner); }
        catch (Exception e) { throw new IllegalStateException("Rear input proxy: " + e, e); }
    }
    @Override public String getRearInputProxyStatus() { return inputProxy.status(); }
    @Override public boolean renewRearInputProxyLease(String id) { return inputProxy.renew(id); }
    @Override public void stopRearInputProxy(String id) { inputProxy.stop(id); }
    private final DisplayRotationController rotationController = new DisplayRotationController(
            DisplayRotationController::runCommand, Thread::sleep, message -> { Log.i(TAG, message); CompatibilityDiagnostics.record("rotation", message); });

    private final int ownerUid;
    private volatile RearDisplayUserActivity.Sink rearUserActivitySink;
    private final Object rearUserActivitySinkLock = new Object();
    @Keep
    public TaskService() { this(-1); }
    TaskService(int ownerUid) {
        this.ownerUid = ownerUid;
        RearDisplay.initializeShell();
        if (ownerUid < 0) RearAppFreezeGuard.recoverStale();
        CompatibilityDiagnostics.record("task_service_start", "user_service_contract=" + USER_SERVICE_CONTRACT_VERSION);
    }

    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws RemoteException {
        int caller = android.os.Binder.getCallingUid();
        if (ownerUid >= 0 && caller != ownerUid && caller != android.os.Process.myUid())
            throw new SecurityException("Caller is not the Root service owner");
        long startedAt = android.os.SystemClock.elapsedRealtime();
        try {
            return super.onTransact(code, data, reply, flags);
        } finally {
            long elapsed = android.os.SystemClock.elapsedRealtime() - startedAt;
            if (elapsed >= 1000L) Log.w(TAG, "binder_slow method=" + binderMethodName(code)
                    + " code=" + code + " elapsed_ms=" + elapsed);
        }
    }

    /** Uses generated AIDL transaction constants; Android's runtime code includes FIRST_CALL_TRANSACTION. */
    private static String binderMethodName(int code) {
        if (code == ITaskService.Stub.TRANSACTION_launchSceneActivity) return "launchSceneActivity";
        if (code == ITaskService.Stub.TRANSACTION_verifySceneActivity) return "verifySceneActivity";
        if (code == ITaskService.Stub.TRANSACTION_inspectSceneActivityTask) return "inspectSceneActivityTask";
        if (code == ITaskService.Stub.TRANSACTION_finishSceneActivityTask) return "finishSceneActivityTask";
        if (code == ITaskService.Stub.TRANSACTION_presentSceneActivityTask) return "presentSceneActivityTask";
        return "unknown";
    }

    void closeSessions() {
        try { virtualCast.stop(null); } finally { inputProxy.stop(null); }
    }
    @Override
    public void destroy() { closeSessions(); System.exit(0); }

    @Override
    public String getCurrentForegroundApp() throws RemoteException {
        try {

            // 执行am stack list，在Shizuku进程中具有shell权限
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            boolean inDisplayZero = false;
            String line;
            while ((line = reader.readLine()) != null) {
                // RootTask行：判断displayId
                if (line.startsWith("RootTask")) {
                    inDisplayZero = line.contains("displayId=0");
                    continue;
                }
                
                // taskId行（缩进的子行）
                if (inDisplayZero && line.contains("taskId=") && line.contains("/")) {
                    // 解析:   taskId=1471: com.example.display_switcher/com.example.display_switcher.MainActivity
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    String taskId = line.substring(tidStart, tidEnd).trim();
                    
                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    String packageName = line.substring(pkgStart, pkgEnd).trim();
                    
                    // 跳过Launcher和应用自己
                    if (packageName.contains("launcher") || 
                        packageName.contains("miui.home") ||
                        packageName.equals(BuildConfig.APPLICATION_ID)) {
                        continue;
                    }
                    
                    reader.close();
                    process.destroy();
                    
                    String result = packageName + ":" + taskId;

                    return result;
                }
            }
            
            int exitCode = process.waitFor();
            reader.close();

            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting current app", e);
            return null;
        }
    }

    @Override
    public int getTaskIdByPackage(String packageName) throws RemoteException {
        try {

            // 执行am stack list，在Shizuku进程中具有shell权限
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("taskId=") && line.contains(packageName + "/")) {
                    // 解析: taskId=1434: com.android.camera/...
                    int start = line.indexOf("taskId=") + 7;
                    int end = line.indexOf(':', start);
                    String taskId = line.substring(start, end).trim();
                    int tid = Integer.parseInt(taskId);
                    
                    reader.close();
                    process.destroy();

                    return tid;
                }
            }
            
            reader.close();
            process.waitFor();

            return -1;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting taskId", e);
            return -1;
        }
    }

    @Override
    public boolean moveTaskToDisplay(int taskId, int displayId) throws RemoteException {
        if (!RearDisplay.isRear(displayId)) inputProxy.taskLeaving(taskId);
        try {
            long startTime = System.currentTimeMillis();

            // 先获取包名
            String packageName = getPackageNameFromTaskId(taskId);

            boolean invoked = RearSystemBridge.moveTask(taskId, displayId);
            boolean success = false;
            for (int attempt = 0; invoked && attempt < 10; attempt++) {
                if (isTaskOnDisplay(taskId, displayId)) { success = true; break; }
                try { Thread.sleep(50); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            }
            CompatibilityDiagnostics.record("move_result", "task_id=" + taskId + " display_id=" + displayId
                    + " verified=" + success + " elapsed_ms=" + (System.currentTimeMillis() - startTime));
            Log.i(TAG, "move_result task_id=" + taskId + " display_id=" + displayId + " verified=" + success);

            // 如果成功移动到当前背屏，保存任务信息
            if (success && RearDisplay.isRear(displayId)) {
                try {
                    if (packageName != null) {
                        // 保存到广播接收器，以便系统事件后恢复
                        RearScreenBroadcastReceiver.saveLastTask(packageName, taskId);

                    } else {

                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ Failed to save task info", e);
                }
            }

            return success;
            
        } catch (Exception e) {
            CompatibilityDiagnostics.record("task_move_failed", e.toString());
            Log.e(TAG, "❌ EXCEPTION in moveTaskToDisplay", e);
            return false;
        }
    }

    /**
     * 根据taskId获取包名（辅助方法）
     */
    @Override public String getPackageNameFromTaskId(int taskId) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("taskId=" + taskId + ":") && line.contains("/")) {
                    // 解析: taskId=1471: com.example.app/...
                    int pkgStart = line.indexOf(':') + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    if (pkgEnd > pkgStart) {
                        String packageName = line.substring(pkgStart, pkgEnd).trim();
                        reader.close();
                        process.destroy();
                        return packageName;
                    }
                }
            }
            
            reader.close();
            process.waitFor();
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting package name from taskId", e);
            return null;
        }
    }

    @Override
    public boolean launchWakeActivity(int displayId) throws RemoteException {
        try {
            long startTime = System.currentTimeMillis();

            // 使用am start命令在指定display上启动RearScreenWakeupActivity
            // --display参数指定目标display
            // 注意：RearScreenWakeupActivity使用FLAG_TURN_SCREEN_ON点亮屏幕
            String cmd = "am start --display " + displayId + 
                        " -n " + BuildConfig.APPLICATION_ID + "/" + RearScreenWakeupActivity.class.getName();

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // 读取输出
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

            }
            reader.close();
            
            int exitCode = process.waitFor();
            boolean success = (exitCode == 0);
            
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            return success;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in launchWakeActivity", e);
            return false;
        }
    }
    
    @Override
    public boolean disableSubScreenLauncher() throws RemoteException {
        try {

            // 强制停止进程（进程可能会自动重启，需要持续杀死）
            String killCmd = "am force-stop com.xiaomi.subscreencenter";

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", killCmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in disableSubScreenLauncher", e);
            return false;
        }
    }
    
    /**
     * V12杀进程法：检查Launcher进程是否在运行
     */
    @Override
    public boolean isLauncherProcessRunning() throws RemoteException {
        try {
            // 检查进程是否在运行
            String cmd = "ps -A | grep com.xiaomi.subscreencenter";
            
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line = reader.readLine();
            reader.close();
            process.waitFor();
            
            // 如果有输�?�?进程在运�?�?返回true（需要杀�?
            // 如果无输�?�?进程不在运行 �?返回false（不需要处理）
            boolean isRunning = (line != null && !line.isEmpty());
            
            if (isRunning) {

            }
            
            return isRunning;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in isLauncherProcessRunning", e);
            return false;
        }
    }
    
    /**
     * V12杀进程法：尝试杀掉Launcher进程
     * 返回true = 成功杀掉（说明进程在运行）
     * 返回false = 失败（说明进程不在运行）
     */
    @Override
    public boolean killLauncherProcess() throws RemoteException {
        try {
            // 强制停止进程
            String cmd = "am force-stop com.xiaomi.subscreencenter";
            
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            // force-stop 总是返回0，所以需要检查进程是否真的被杀死
            // 简单起见，如果命令成功就返回true
            return (exitCode == 0);
            
        } catch (Exception e) {
            // 异常也返回false（静默）
            return false;
        }
    }
    
    @Override
    public boolean enableSubScreenLauncher() throws RemoteException {
        virtualCast.stop(null);
        inputProxy.stop(null);
        try {

            return RearSystemBridge.restoreLauncher();
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in enableSubScreenLauncher", e);
            return false;
        }
    }
    
    // 删除未使用的wakeUpDisplay方法
    
    @Override
    public boolean forceStatusBarToMainDisplay() throws RemoteException {
        try {

            // 新策略：直接展开主屏状态栏，而不是移动或重启SystemUI
            // 这会强制主屏显示SystemUI，从而保持焦点在主屏
            
            // 方法1: 展开主屏状态栏（不完全展开，只是激活）
            String expandCmd = "cmd statusbar expand-settings";

            ProcessBuilder pb1 = new ProcessBuilder("sh", "-c", expandCmd);
            Process process1 = pb1.start();
            int exitCode1 = process1.waitFor();
            
            if (exitCode1 == 0) {

                Thread.sleep(30);  // 短暂延迟
                
                // 立即收起
                String collapseCmd = "cmd statusbar collapse";
                ProcessBuilder pb2 = new ProcessBuilder("sh", "-c", collapseCmd);
                Process process2 = pb2.start();
                int exitCode2 = process2.waitFor();
                
                if (exitCode2 == 0) {

                } else {

                }
            } else {

            }
            
            // 方法2: 强制主屏SystemUI可见（通过wm命令�?
            // 设置主屏display为默�?
            String wmCmd = "wm set-display-type 0 home";

            ProcessBuilder pb3 = new ProcessBuilder("sh", "-c", wmCmd);
            Process process3 = pb3.start();
            
            BufferedReader reader3 = new BufferedReader(
                new InputStreamReader(process3.getInputStream()), 8192
            );
            String line;
            while ((line = reader3.readLine()) != null) {

            }
            reader3.close();
            
            int exitCode3 = process3.waitFor();
            if (exitCode3 == 0) {

            } else {

            }
            
            // 方法3: 检查当前状态栏位置

            ProcessBuilder pb4 = new ProcessBuilder("sh", "-c", "dumpsys window displays | grep -A20 'Display: 0'");
            Process process4 = pb4.start();
            
            BufferedReader reader4 = new BufferedReader(
                new InputStreamReader(process4.getInputStream()), 8192
            );
            while ((line = reader4.readLine()) != null) {
                if (line.contains("StatusBar") || line.contains("systemui")) {

                }
            }
            reader4.close();
            process4.waitFor();

            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in forceStatusBarToMainDisplay", e);
            return false;
        }
    }
    
    /**
     * 收回状态栏/控制中心
     * @return 是否成功
     */
    @Override
    public boolean collapseStatusBar() throws RemoteException {
        try {

            // 使用 cmd statusbar collapse 命令
            String cmd = "cmd statusbar collapse";

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in collapseStatusBar", e);
            return false;
        }
    }
    
    /**
     * 获取当前背屏DPI
     * @return DPI值
     */
    @Override
    public int getCurrentRearDpi() throws RemoteException {
        try {

            // 使用 wm density 命令获取当前背屏的DPI
            String cmd = "wm density -d " + RearDisplay.requireId();

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            int dpi = 0;
            while ((line = reader.readLine()) != null) {

                // 解析输出: "Physical density: 450" 或 "Override density: 300"
                if (line.contains("density:")) {
                    String[] parts = line.split(":");
                    if (parts.length > 1) {
                        try {
                            String dpiStr = parts[1].trim();
                            // 如果是 "Override density: 300"，优先使用
                            if (line.contains("Override density")) {
                                dpi = Integer.parseInt(dpiStr);

                                break; // 找到override就不继续找了
                            } else if (dpi == 0) {
                                // 如果还没找到override，先记录physical
                                dpi = Integer.parseInt(dpiStr);

                            }
                        } catch (NumberFormatException e) {

                        }
                    }
                }
            }
            reader.close();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0 && dpi > 0) {

            } else {

            }

            return dpi;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in getCurrentRearDpi", e);
            return 0;
        }
    }
    
    /**
     * 设置背屏DPI
     * @param dpi DPI值
     * @return 是否成功
     */
    @Override
    public boolean setRearDpi(int dpi) throws RemoteException {
        try {

            // 使用 wm density 命令设置display 1的DPI
            String cmd = "wm density " + dpi + " -d " + RearDisplay.requireId();

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in setRearDpi", e);
            return false;
        }
    }
    
    /**
     * 还原背屏DPI到默认值
     * @return 是否成功
     */
    @Override
    public boolean resetRearDpi() throws RemoteException {
        try {

            // 使用 wm density reset 命令还原display 1的DPI
            String cmd = "wm density reset -d " + RearDisplay.requireId();

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in resetRearDpi", e);
            return false;
        }
    }
    
    /**
     * 截取背屏画面
     * @return 是否成功
     */
    @Override
    public boolean takeRearScreenshot() throws RemoteException {
        try {
            return RearScreenshotShell.saveLegacy(RearScreenshotShell.capture(new int[3]));
        } catch (Exception e) {
            Log.e(TAG, "Rear screenshot failed", e);
            return false;
        }
    }

    @Override public android.os.ParcelFileDescriptor captureRearScreenshot(int[] metadata) {
        try { return RearScreenshotShell.capture(metadata); }
        catch (Exception e) { CompatibilityDiagnostics.record("screenshot_failed", e.toString()); throw new IllegalStateException("Rear screenshot capture failed", e); }
    }

    @Override public boolean saveRearScreenshotLegacy(android.os.ParcelFileDescriptor source) {
        return source != null && RearScreenshotShell.saveLegacy(source);
    }

    @Override
    public int launchSceneActivity(String component, int displayId, String launchTicket) throws RemoteException {
        String validated = VirtualCastRequest.component(component);
        if (displayId < 1 || !RearDisplay.isRear(displayId)) throw new IllegalArgumentException("Scene display is not the selected rear display");
        if (launchTicket == null || !launchTicket.matches("[A-Za-z0-9_-]{24,64}")) throw new IllegalArgumentException("Invalid scene ticket");
        SceneTaskLaunchFlow.Result result = SceneTaskLaunchFlow.launch(validated, displayId, launchTicket,
                SceneTaskSnapshotReader::read, SceneTaskShellRunner::run,
                (taskId, sceneComponent, actualDisplayId) -> {
                    SceneTaskTaskRemover.Result removal = SceneTaskTaskRemover.remove(taskId, sceneComponent, actualDisplayId);
                    return new SceneTaskLaunchFlow.CleanupResult(removal.removed, removal.reason);
                }, new SceneTaskLaunchFlow.Time() {
                    @Override public long elapsedRealtime() { return android.os.SystemClock.elapsedRealtime(); }
                    @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
                });
        CompatibilityDiagnostics.record("scene_launch_attempt", "component=" + validated + " display=" + displayId + " " + result.diagnostic());
        if (result.taskId < 0) CompatibilityDiagnostics.record("scene_launch_failed", "reason=" + result.failure);
        CompatibilityDiagnostics.record("scene_launch", "component=" + validated + " task=" + result.taskId + " display=" + displayId);
        return result.taskId;
    }

    @Override
    public boolean wakeRearDisplayForScene(int displayId) throws RemoteException {
        if (displayId < 1 || !RearDisplay.isRear(displayId)) return false;
        SceneDisplayWakeup.Result result = SceneDisplayWakeup.wakeIfNeeded(displayId, id -> {
            Object info = RearDisplay.info(id);
            return info.getClass().getField("state").getInt(info);
        }, id -> SceneTaskShellRunner.run("input -d " + id + " keyevent KEYCODE_WAKEUP", 1_500L),
                new SceneDisplayWakeup.Time() {
                    @Override public long elapsedRealtime() { return android.os.SystemClock.elapsedRealtime(); }
                    @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
                });
        CompatibilityDiagnostics.record("scene_display_wake", "display=" + displayId + " status=" + result.status
                + " command_exit=" + result.commandExit + " awake=" + result.awake);
        return result.awake;
    }

    @Override
    public String presentSceneActivityTask(int taskId, String component, int displayId,
            ISceneTaskActionGate actionGate) throws RemoteException {
        final String expected;
        try {
            expected = android.content.ComponentName.unflattenFromString(VirtualCastRequest.component(component)).flattenToString();
        } catch (Exception invalid) {
            return new SceneTaskPresentationFlow.Result("invalid_component", "input", taskId, displayId,
                    null, null, -1).toJson();
        }
        if (taskId < 0 || displayId < 1 || !RearDisplay.isRear(displayId)) {
            return new SceneTaskPresentationFlow.Result("invalid_task_or_display", "input", taskId, displayId,
                    null, null, -1).toJson();
        }
        SceneRecoveryPresentationAuthority.RearStateReader rearState = RearDisplay::state;
        SceneRecoveryPresentationAuthority.CurrentGate currentGate = () ->
                SceneTaskPresentationFlow.invokeRemoteActionGate(actionGate == null ? null : actionGate::isCurrent);
        String rearPreflight = SceneRecoveryPresentationAuthority.preflightRear(displayId, rearState);
        if (rearPreflight != null) {
            SceneTaskPresentationFlow.Result rejected = new SceneTaskPresentationFlow.Result(
                    rearPreflight, "physical_rear", taskId, displayId, expected, null, -1);
            CompatibilityDiagnostics.record("scene_task_present", rejected.diagnostic());
            return rejected.toJson();
        }
        SceneTaskPresentationFlow.Result result = SceneTaskPresentationFlow.present(taskId, expected, displayId,
                SceneTaskSnapshotReader::read,
                id -> {
                    try {
                        DisplayRotationController.Result command = DisplayRotationController.runCommand(
                                "am", "task", "focus", Integer.toString(id));
                        return new SceneTaskPresentationFlow.FocusResult(
                                RearSystemBridge.commandSucceeded(command), command.exitCode, null);
                    } catch (Exception failure) {
                        return new SceneTaskPresentationFlow.FocusResult(false, -1,
                                failure.getClass().getSimpleName());
                    }
                }, top -> SceneTaskPresentationFlow.matchesKnownRearHome(RearSystemBridge.launcherCandidates(), top),
                () -> SceneTaskPresentationFlow.invokeRemoteActionGate(
                        actionGate == null ? null : actionGate::isCurrent),
                presentation -> SceneRecoveryPresentationAuthority.confirm(taskId, expected, displayId,
                        rearState, SceneTaskSnapshotReader::read, currentGate));
        CompatibilityDiagnostics.record("scene_task_present", result.diagnostic());
        return result.toJson();
    }

    /** Returns only a single task created by this launch attempt; never guesses among existing/user tasks. */
    public static int uniqueFreshSceneTaskId(java.util.Map<Integer, Integer> before,
            java.util.Map<Integer, Integer> observed, int expectedDisplayId) {
        int candidate = -1;
        for (java.util.Map.Entry<Integer, Integer> entry : observed.entrySet()) {
            int task = entry.getKey();
            if (before.containsKey(task)) continue;
            if (candidate >= 0) return -1;
            candidate = task;
            if (entry.getValue() != expectedDisplayId) return -1;
        }
        return candidate;
    }

    @Override
    public boolean verifySceneActivity(int taskId, String component, int displayId) throws RemoteException {
        try { return new org.json.JSONObject(inspectSceneTaskIdentity(taskId, component, displayId, false)).optString("status").equals("VERIFIED"); }
        catch (Exception e) { return false; }
    }

    @Override
    public String inspectSceneActivityTask(int taskId, String component, int displayId) throws RemoteException {
        return inspectSceneTaskIdentity(taskId, component, displayId, true);
    }

    private String inspectSceneTaskIdentity(int taskId, String component, int displayId, boolean readyReport) {
        if (taskId < 0 || displayId < 1) return new SceneTaskIdentity.Result(
                SceneTaskIdentity.Status.PARSE_ERROR, "input", "invalid_task_or_display", null).toJson();
        final String expected;
        try { expected = android.content.ComponentName.unflattenFromString(VirtualCastRequest.component(component)).flattenToString(); }
        catch (Exception e) { return new SceneTaskIdentity.Result(SceneTaskIdentity.Status.PARSE_ERROR, "input", "invalid_component", null).toJson(); }

        try {
            java.util.List<SceneTaskIdentity.Record> records = RearSystemBridge.runningTaskSnapshot();
            SceneTaskIdentity.Result result = readyReport
                    ? SceneTaskIdentity.verifyReady(records, taskId, expected, displayId, "activity_task")
                    : SceneTaskIdentity.verifyOwner(records, taskId, expected, displayId, "activity_task");
            CompatibilityDiagnostics.record("scene_verify", "task=" + taskId + " expected_display=" + displayId + " purpose=" + (readyReport ? "ready" : "owner") + " source=activity_task status=" + result.status
                    + (result.detail == null ? "" : " detail=" + result.detail));
            return result.toJson();
        } catch (Exception binderError) {
            // Reflection/API/permission failures are distinguished from an authoritative task
            // mismatch. Only this unavailable-query path falls back to the bounded legacy shell.
            CompatibilityDiagnostics.record("scene_verify_query_unavailable", "type=" + binderError.getClass().getSimpleName());
        }

        SceneTaskShellRunner.Result shell = SceneTaskShellRunner.run("am stack list", 3000);
        SceneTaskIdentity.Result result = SceneTaskIdentity.verifyShellSnapshot(shell.output, shell.exitCode,
                shell.timedOut, shell.truncated, taskId, expected, displayId, readyReport);
        CompatibilityDiagnostics.record("scene_verify", "task=" + taskId + " expected_display=" + displayId
                + " purpose=" + (readyReport ? "ready" : "owner") + " source=am_stack_list status=" + result.status
                + (result.detail == null ? "" : " detail=" + result.detail));
        return result.toJson();
    }

    @Override
    public boolean finishSceneActivityTask(int taskId, String component, int displayId) throws RemoteException {
        SceneTaskTaskRemover.Result result = SceneTaskTaskRemover.remove(taskId, component, displayId);
        CompatibilityDiagnostics.record("scene_close", "component=" + component + " task=" + taskId
                + " removed=" + result.removed + " reason=" + result.reason);
        return result.removed;
    }

    @Override
    public boolean isTaskOnDisplay(int taskId, int displayId) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            boolean inTargetDisplay = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.matches(".*\\bdisplayId=" + displayId + "\\b.*");
                    continue;
                }
                
                if (inTargetDisplay && line.contains("taskId=" + taskId + ":")) {
                    reader.close();
                    process.destroy();
                    return true;
                }
            }
            
            reader.close();
            process.waitFor();
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking task on display", e);
            return false;
        }
    }
    
    @Override
    public String getForegroundAppOnDisplay(int displayId) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            boolean inTargetDisplay = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.matches(".*\\bdisplayId=" + displayId + "\\b.*");
                    continue;
                }
                
                if (inTargetDisplay && line.contains("taskId=") && line.contains("/")) {
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    String taskId = line.substring(tidStart, tidEnd).trim();
                    
                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    String packageName = line.substring(pkgStart, pkgEnd).trim();
                    
                    reader.close();
                    process.destroy();
                    
                    return packageName + ":" + taskId;
                }
            }
            
            reader.close();
            process.waitFor();
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting foreground app on display", e);
            return null;
        }
    }

    @Override public String getDisplayCastOccupancy(int displayId) {
        return RearDisplayCastOccupancyReader.read(displayId).toJson();
    }

    /**
     * V2.1: 设置显示器旋转方向
     * @param displayId 显示器ID (0=主屏, 1=背屏)
     * @param rotation 旋转角度 (0=0°, 1=90°, 2=180°, 3=270°)
     * @return 是否成功
     */
    @Override
    public boolean setDisplayRotation(int displayId, int rotation) throws RemoteException {
        if (displayId < 0 || rotation < 0 || rotation > 3) return false;
        try {
            // 获取当前背屏前台应用（如果有）
            String currentApp = null;
            int currentTaskId = -1;
            if (RearDisplay.isRear(displayId)) {
                currentApp = getForegroundAppOnDisplay(RearDisplay.requireId());
                if (currentApp != null && currentApp.contains(":")) {
                    String[] parts = currentApp.split(":");
                    try {
                        currentTaskId = Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {}
                }
            }
            
            boolean applied = rotationController.setRotation(displayId, rotation);

            // 如果是背屏且有应用在运行，等待500ms后检查并复活
            if (RearDisplay.isRear(displayId) && applied && currentTaskId > 0) {
                Thread.sleep(500);
                
                // 检查应用是否还在背屏
                boolean stillOnRear = isTaskOnDisplay(currentTaskId, RearDisplay.requireId());
                
                if (!stillOnRear) {
                    // 应用被关闭了，重新投放
                    moveTaskToDisplay(currentTaskId, RearDisplay.requireId());
                }
            }
            
            return applied;
            
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.e(TAG, "设置旋转异常", e);
            return false;
        }
    }
    
    /**
     * V2.1: 获取显示器当前旋转方向
     * @param displayId 显示器ID (0=主屏, 1=背屏)
     * @return 旋转角度 (0-3)，-1表示失败
     */
    @Override
    public int getDisplayRotation(int displayId) throws RemoteException {
        return rotationController.getRotation(displayId);
    }
    
    @Override
    public boolean executeShellCommand(String cmd) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()), 8192
            );
            
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();
            
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
            
            reader.close();
            errorReader.close();
            
            int exitCode = process.waitFor();
            
            if (exitCode != 0 && cmd.startsWith("input -d ") && cmd.endsWith(" keyevent KEYCODE_WAKEUP"))
                CompatibilityDiagnostics.record("wake_failed", "exit=" + exitCode + " response=" + errorOutput);
            // 记录详细输出
            if (output.length() > 0) {
                Log.d(TAG, "Command stdout: " + output.toString().trim());
            }
            if (errorOutput.length() > 0) {
                Log.w(TAG, "Command stderr: " + errorOutput.toString().trim());
            }
            
            return (exitCode == 0);
            
        } catch (Exception e) {
            Log.e(TAG, "执行命令失败: " + cmd, e);
            return false;
        }
    }
    
    @Override
    public String executeShellCommandWithResult(String cmd) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            reader.close();
            process.waitFor();
            
            return output.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "执行命令失败: " + cmd, e);
            return "";
        }
    }
    
}
