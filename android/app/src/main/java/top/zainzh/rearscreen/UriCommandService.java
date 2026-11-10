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

import android.app.IntentService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import top.zainzh.rearscreen.platform.LegacyDisplayOccupancy;

/**
 * V2.6: URI命令处理服务
 * 在后台静默执行URI命令，不显示UI
 * 复用现有的TileService切换逻辑
 */
public class UriCommandService extends IntentService {
    private static final String TAG = "UriCommandService";

    private volatile boolean taskServiceReleased;
    private volatile ITaskService taskService;
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            Log.d(TAG, "✓ TaskService已连接");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };

    public UriCommandService() {
        super("UriCommandService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.VIRTUAL_CAST_ENABLED) {
            startForeground(10003, RearScreenKeeperService.createServiceNotification(this));
        }
        bindTaskService();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        try { PrivilegeOperations.begin(); }
        catch (IllegalStateException switching) { return; }
        try {
            Uri uri = intent == null ? null : intent.getData();
            if (uri != null && "switch".equals(uri.getHost())) {
                RearAnimationManager.runDisplayAction(() -> handlePrivilegedIntent(intent));
            } else handlePrivilegedIntent(intent);
        } finally { PrivilegeOperations.end(); }
    }

    private void handlePrivilegedIntent(Intent intent) {
        if (intent == null)
            return;

        Uri uri = intent.getData();
        if (uri == null || !uri.isHierarchical() || !BuildConfig.URI_SCHEME.equals(uri.getScheme())) {
            return;
        }

        try {
            PrivilegedShellArguments.validateTargets(uri.getQueryParameter("packageName"), uri.getQueryParameter("activity"));
        } catch (IllegalArgumentException invalid) {
            Log.w(TAG, "Rejected invalid automation target", invalid);
            return;
        }

        // The URI can contain an app/component supplied by the caller. Keep diagnostics to the
        // command class; attribution below records bounded stages without echoing that input.
        Log.d(TAG, "uri_dispatch action=validated");

        // 确保TaskService已连接
        if (!ensureTaskServiceConnected()) {
            Log.e(TAG, "❌ TaskService未连接");
            if ("screenshot".equals(uri.getHost())) showToast(getString(R.string.rear_screenshot_service_unavailable));
            return;
        }
        if (BuildConfig.VIRTUAL_CAST_ENABLED && intent.getBooleanExtra("fromQuickSettings", false)) {
            try { taskService.collapseStatusBar(); }
            catch (Exception e) { Log.w(TAG, "Prototype tile panel collapse", e); }
        }

        String host = uri.getHost();
        if (host == null)
            return;

        if (VirtualCastUriFlow.handle(this, taskService, uri)) return;

        switch (host) {
            case "switch":
                handleSwitch(uri);
                break;
            case "return":
                handleReturn(uri);
                break;
            case "screenshot":
                handleScreenshot();
                break;
            case "config":
                handleConfig(uri);
                break;
        }
    }

    private boolean ensureTaskServiceConnected() {
        if (taskService != null)
            return true;

        try {
            bindTaskService();

            // Include the manager's five-second first-bind window.
            int attempts = 0;
            while (taskService == null && attempts < 60) {
                Thread.sleep(100);
                attempts++;
            }

            return taskService != null;
        } catch (Exception e) {
            Log.e(TAG, "TaskService重连失败", e);
            return false;
        }
    }

    private void bindTaskService() {
        if (!taskServiceReleased) TaskServiceManager.acquire(this, taskServiceConnection);
    }

    /**
     * 处理切换命令 - 复用TileService逻辑
     */
    private void handleSwitch(Uri uri) {
        Log.d(TAG, "════════════════════════════════════════");
        Log.d(TAG, "🔄 处理SWITCH命令");
        Log.d(TAG, "cast_dispatch mode=physical action=switch");

        try {
            // 0. 检查背屏是否已有应用在运行（拒绝重复投放）
            try {
                RearDisplayCastOccupancy.Result occupancy = RearDisplayCastOccupancy.query(
                        taskService, getPackageName(), RearDisplay.requireId());
                Log.i(TAG, "cast_gate mode=physical stage=entry disposition=" + occupancy.disposition
                        + " hidden_tasks=" + occupancy.hiddenTasks + " result=" + (occupancy.allowed() ? "allow" : "busy"));
                if (!occupancy.allowed()) {
                    Log.w(TAG, "Rear display is occupied by an unmanaged task; cast remains blocked");
                    Log.d(TAG, "════════════════════════════════════════");
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "cast_gate mode=physical stage=entry result=query_error error=" + e.getClass().getSimpleName());
                showToast("无法确认背屏占用状态，请稍后重试");
                return;
            }

            // 1. 确定目标
            String currentParam = uri.getQueryParameter("current");
            String packageName = uri.getQueryParameter("packageName");
            String activity = uri.getQueryParameter("activity");

            Log.d(TAG, "cast_target mode=physical kind=" + ("true".equalsIgnoreCase(currentParam) || "1".equals(currentParam)
                    ? "main_foreground" : activity != null ? "explicit_activity" : packageName != null ? "package_launch" : "missing"));

            if ("true".equalsIgnoreCase(currentParam) || "1".equals(currentParam)) {
                // 切换当前应用 - 完全复用TileService逻辑
                Log.d(TAG, "→ 模式：切换当前应用");
                switchCurrentAppToRear(uri);
            } else if (activity != null) {
                // 启动指定Activity到背屏
                Log.d(TAG, "→ 模式：启动指定Activity");
                switchSpecificAppToRear(activity, null, uri);
            } else if (packageName != null) {
                // 启动指定包名到背屏
                Log.d(TAG, "→ 模式：启动指定包名");
                switchSpecificAppToRear(null, packageName, uri);
            } else {
                Log.w(TAG, "⚠ 未指定切换目标");
            }

            Log.d(TAG, "════════════════════════════════════════");
        } catch (Exception e) {
            Log.e(TAG, "❌ 切换命令失败", e);
            e.printStackTrace();
            Log.d(TAG, "════════════════════════════════════════");
        }
    }

    /**
     * 切换当前应用到背屏 - 完全复用TileService的逻辑
     */
    private void switchCurrentAppToRear(Uri uri) {
        try {
            RearDisplayCastOccupancy.Result occupancy = RearDisplayCastOccupancy.query(
                    taskService, getPackageName(), RearDisplay.requireId());
            Log.i(TAG, "cast_gate mode=physical stage=current disposition=" + occupancy.disposition
                    + " hidden_tasks=" + occupancy.hiddenTasks + " result=" + (occupancy.allowed() ? "allow" : "busy"));
            if (!occupancy.allowed()) return;

            CurrentMainTaskSelector.Result currentTarget = CurrentMainTaskSelector.await(
                    displayId -> taskService.getDisplayCastOccupancy(displayId), getPackageName(), 4000, 100);
            String currentApp = currentTarget.target == null ? null : currentTarget.target.legacyForegroundValue();
            if (currentApp == null || !currentApp.contains(":")) {
                Log.i(TAG, "cast_target mode=physical kind=main_foreground result=unresolved reason=" + currentTarget.reason);
                showToast("无法确认当前主屏应用，请回到目标应用后重试");
                return;
            }
            String[] parts = currentApp.split(":", 2);
            String packageName = VirtualCastRequest.packageName(parts[0]);
            int taskId = Integer.parseInt(parts[1]);
            PhysicalCastRequestFlow.Result result = PhysicalCastRequestFlow.prepareAndHandoff(
                    new PhysicalCastRequestFlow.Target(packageName, taskId), createPhysicalCastPort(uri, null));
            if (result.committed) {
                Log.i(TAG, "cast_stage mode=physical target=main_foreground status=move_success");
                wakeRearDisplayAfterCommit();
                showToast(result.keeperStarted
                        ? getAppName(packageName) + " " + getString(R.string.toast_cast_to_rear)
                        : "已投放；后台守护未启动，仍可使用原返回入口结束投放");
            } else {
                Log.i(TAG, "cast_stage mode=physical target=main_foreground status=" + result.failure + " result=not_committed");
                showToast(getString(R.string.toast_switch_failed));
            }
        } catch (Exception e) {
            Log.e(TAG, "切换失败", e);
            showToast(getString(R.string.toast_switch_failed));
        }
    }

    /**
     * 切换指定应用到背屏（packageName或activity）
     */
    private void switchSpecificAppToRear(String activity, String packageName, Uri uri) {
        final boolean isSpecificActivity = activity != null;
        try {
            final String targetPackage;
            final String launchCmd;
            if (isSpecificActivity) {
                String component = VirtualCastRequest.component(activity);
                targetPackage = component.substring(0, component.indexOf('/'));
                launchCmd = "am start -n " + component;
            } else {
                targetPackage = VirtualCastRequest.packageName(packageName);
                String resolved = taskService.executeShellCommandWithResult(
                        "cmd package resolve-activity --brief " + targetPackage + " | tail -n 1");
                String component = resolved == null ? "" : resolved.trim();
                if (!component.isEmpty() && !component.contains("No activity found")
                        && component.startsWith(targetPackage + "/")) {
                    launchCmd = "am start -n " + VirtualCastRequest.component(component);
                } else {
                    String dumped = taskService.executeShellCommandWithResult("pm dump " + targetPackage
                            + " | grep -A 1 'android.intent.action.MAIN' | grep -o '" + targetPackage
                            + "[^\\s]*' | head -n 1");
                    component = dumped == null ? "" : dumped.trim();
                    if (!component.isEmpty() && component.startsWith(targetPackage + "/")) {
                        launchCmd = "am start -n " + VirtualCastRequest.component(component);
                    } else {
                        launchCmd = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
                                + targetPackage;
                    }
                }
            }

            PhysicalCastRequestFlow.Result result = PhysicalCastRequestFlow.launchAndHandoff(
                    targetPackage, 3, createPhysicalCastPort(uri, launchCmd));
            if (!result.committed) {
                Log.i(TAG, "cast_stage mode=physical target=" + (isSpecificActivity ? "explicit_activity" : "package_task")
                        + " status=" + result.failure + " result=not_committed");
                showToast(getString(R.string.toast_switch_failed));
                return;
            }

            int taskId = result.target.taskId;
            String actualPackage = result.target.packageName;
            Log.i(TAG, "cast_stage mode=physical target=" + (isSpecificActivity ? "explicit_activity" : "package_task")
                    + " status=move_success");
            try {
                Thread.sleep(300);
                String dpiValue = uri.getQueryParameter("dpi");
                if (dpiValue != null && taskService.getCurrentRearDpi() != Integer.parseInt(dpiValue))
                    taskService.setRearDpi(Integer.parseInt(dpiValue));
                wakeRearDisplayAfterCommit();
            } catch (Exception e) { Log.w(TAG, "Post-commit display presentation adjustment failed", e); }
            String rotationValue = uri.getQueryParameter("rotation");
            if (rotationValue != null) {
                try {
                    int rotation = Integer.parseInt(rotationValue);
                    if (taskService.getDisplayRotation(RearDisplay.requireId()) != rotation)
                        taskService.setDisplayRotation(RearDisplay.requireId(), rotation);
                    if (!taskService.isTaskOnDisplay(taskId, RearDisplay.requireId()))
                        taskService.moveTaskToDisplay(taskId, RearDisplay.requireId());
                } catch (Exception e) { Log.w(TAG, "Post-commit rotation check failed", e); }
            }
            showToast(result.keeperStarted
                    ? getAppName(actualPackage) + " " + getString(R.string.toast_cast_to_rear)
                    : "已投放；后台守护未启动，仍可使用原返回入口结束投放");
        } catch (Exception e) {
            Log.e(TAG, "切换指定应用失败", e);
            showToast("切换失败: " + e.getMessage());
        }
    }

    private PhysicalCastRequestFlow.Port createPhysicalCastPort(Uri uri, String launchCmd) {
        return new PhysicalCastRequestFlow.Port() {
            private CastDisplayConfigSnapshot config;
            @Override public void prepare() throws Exception {
                config = CastDisplayConfigSnapshot.capture(taskService, uri);
                applyConfigParams(uri);
            }
            @Override public String launchAndObserve(int attempt) throws Exception {
                if (launchCmd == null) throw new IllegalStateException("No launch command for current-task request");
                if (attempt > 0) Thread.sleep(200);
                if (!taskService.executeShellCommand(launchCmd))
                    throw new IllegalStateException("Target launch command was rejected");
                Thread.sleep(500L + attempt * 200L);
                return taskService.getCurrentForegroundApp();
            }
            @Override public boolean moveToRear(int id) throws Exception {
                RearDisplayCastOccupancy.Result occupancy = RearDisplayCastOccupancy.query(
                        taskService, getPackageName(), RearDisplay.requireId());
                if (!occupancy.allowed()) {
                    Log.i(TAG, "cast_gate mode=physical stage=commit result=busy disposition=" + occupancy.disposition);
                    return false;
                }
                return taskService.isTaskOnDisplay(id, 0) && taskService.moveTaskToDisplay(id, RearDisplay.requireId());
            }
            @Override public void projectionCommitted(int id, String pkg) {
                RearAnimationManager.projectionStarted(UriCommandService.this, id, pkg);
            }
            @Override public void disableLauncher(PhysicalCastRequestFlow.Target target) throws Exception {
                if (!taskService.disableSubScreenLauncher())
                    throw new IllegalStateException("SubScreenLauncher disable failed");
                if (!LegacyDisplayOccupancy.markPhysicalLauncherDisabled(
                        UriCommandService.this, target.taskId, RearDisplay.requireId()))
                    throw new IllegalStateException("Physical launcher ownership record changed");
            }
            @Override public boolean startKeeper(String packageTask) {
                Intent serviceIntent = new Intent(UriCommandService.this, RearScreenKeeperService.class)
                        .putExtra("lastMovedTask", packageTask);
                String[] owner = packageTask.split(":", 2);
                if (owner.length == 2) {
                    try {
                        serviceIntent.putExtra("physicalCastOwnerGeneration",
                                LegacyDisplayOccupancy.physicalProjectionGeneration(
                                        UriCommandService.this, Integer.parseInt(owner[1]), RearDisplay.requireId()));
                    } catch (NumberFormatException ignored) { }
                }
                try {
                    boolean keepOn = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
                            .getBoolean("flutter.keep_screen_on_enabled", true);
                    serviceIntent.putExtra("keepScreenOnEnabled", keepOn);
                } catch (Exception ignored) { serviceIntent.putExtra("keepScreenOnEnabled", true); }
                ComponentName started = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                        ? startForegroundService(serviceIntent) : startService(serviceIntent);
                if (started == null) throw new IllegalStateException("Keeper start was not accepted");
                return true;
            }
            @Override public void rollbackPreparation() {
                if (config != null) config.restore(taskService);
            }
            @Override public void postCommitFailure(Exception failure) {
                Log.w(TAG, "Physical cast committed; post-commit maintenance failed", failure);
            }
        };
    }

    private void wakeRearDisplayAfterCommit() {
        try {
            if (taskService.launchWakeActivity(RearDisplay.requireId())) return;
            Log.w(TAG, "TaskService launch failed, fallback to shell");
            taskService.executeShellCommand("am start --display " + RearDisplay.requireId() + " -n "
                    + getPackageName() + "/" + RearScreenWakeupActivity.class.getName());
        } catch (NoSuchMethodError olderService) {
            try {
                taskService.executeShellCommand("am start --display " + RearDisplay.requireId() + " -n "
                        + getPackageName() + "/" + RearScreenWakeupActivity.class.getName());
            } catch (Exception e) { Log.w(TAG, "Failed to wake rear display after cast commit", e); }
        } catch (Exception e) { Log.w(TAG, "Failed to wake rear display after cast commit", e); }
    }

    private static final class CastDisplayConfigSnapshot {
        private final CastDisplayConfigRollback snapshot;
        private CastDisplayConfigSnapshot(CastDisplayConfigRollback snapshot) { this.snapshot = snapshot; }
        static CastDisplayConfigSnapshot capture(ITaskService service, Uri uri) throws Exception {
            boolean dpi = uri.getQueryParameter("dpi") != null;
            boolean rotation = uri.getQueryParameter("rotation") != null;
            CastDisplayConfigRollback.Port port = displayConfigPort(service);
            return new CastDisplayConfigSnapshot(CastDisplayConfigRollback.capture(port, dpi, rotation));
        }
        void restore(ITaskService service) {
            try { snapshot.restore(displayConfigPort(service)); }
            catch (Exception e) { Log.w(TAG, "Failed to restore request display configuration", e); }
        }
        private static CastDisplayConfigRollback.Port displayConfigPort(ITaskService service) {
            return new CastDisplayConfigRollback.Port() {
                @Override public int displayId() { return RearDisplay.requireId(); }
                @Override public String displayIdentity() { return RearDisplay.identity(); }
                @Override public Integer dpi() throws Exception { return service.getCurrentRearDpi(); }
                @Override public Integer rotation(int id) throws Exception { return service.getDisplayRotation(id); }
                @Override public void setDpi(int value) throws Exception { service.setRearDpi(value); }
                @Override public void setRotation(int id, int value) throws Exception { service.setDisplayRotation(id, value); }
            };
        }
    }

    /**
     * 处理拉回命令 - 完全复用现有逻辑
     */
    private void handleReturn(Uri uri) {
        try {
            String currentParam = uri.getQueryParameter("current");
            String taskIdStr = uri.getQueryParameter("taskId");
            String packageName = uri.getQueryParameter("packageName");

            int targetTaskId = -1;
            String targetPackage = null;

            if ("true".equalsIgnoreCase(currentParam) || "1".equals(currentParam)) {
                String rearApp = taskService.getForegroundAppOnDisplay(RearDisplay.requireId());
                if (rearApp != null && rearApp.contains(":")) {
                    String[] parts = rearApp.split(":");
                    targetPackage = parts[0];
                    targetTaskId = Integer.parseInt(parts[1]);
                }
            } else if (taskIdStr != null) {
                targetTaskId = Integer.parseInt(taskIdStr);
                // 尝试从背屏前台应用获取包名
                String rearApp = taskService.getForegroundAppOnDisplay(RearDisplay.requireId());
                if (rearApp != null && rearApp.contains(":")) {
                    targetPackage = rearApp.split(":")[0];
                }
            } else if (packageName != null) {
                String rearApp = taskService.getForegroundAppOnDisplay(RearDisplay.requireId());
                if (rearApp != null && rearApp.startsWith(packageName + ":")) {
                    targetPackage = packageName;
                    targetTaskId = Integer.parseInt(rearApp.split(":")[1]);
                }
            }

            if (targetTaskId != -1) {
                // 检查任务是否真的在背屏
                boolean onRear = taskService.isTaskOnDisplay(targetTaskId, RearDisplay.requireId());

                if (onRear) {
                    String appName = getAppName(targetPackage != null ? targetPackage : String.valueOf(targetTaskId));

                    // 步骤1: 拉回主屏
                    taskService.moveTaskToDisplay(targetTaskId, 0);
                    Log.d(TAG, "✅ 已拉回主屏 (taskId=" + targetTaskId + ")");

                    // 步骤2: 恢复官方Launcher（关键！）
                    try {
                        taskService.enableSubScreenLauncher();
                        Log.d(TAG, "✓ Launcher已恢复");
                    } catch (Exception e) {
                        Log.w(TAG, "恢复Launcher失败: " + e.getMessage());
                    }

                    // 步骤3: 停止RearScreenKeeperService（如果在运行）
                    try {
                        stopService(new Intent(this, RearScreenKeeperService.class));
                        Log.d(TAG, "✓ KeeperService已停止");
                    } catch (Exception e) {
                        Log.w(TAG, "停止KeeperService失败: " + e.getMessage());
                    }

                    // Toast提示
                    showToast(appName + " " + getString(R.string.toast_return_to_main));
                } else {
                    Log.w(TAG, "⚠ 任务不在背屏");
                    showToast(getString(R.string.toast_not_on_rear));
                }
            } else {
                Log.w(TAG, "⚠ 未找到要拉回的任务");
                showToast(getString(R.string.toast_app_not_found));
            }
        } catch (Exception e) {
            Log.e(TAG, "拉回命令失败", e);
        }
    }

    /**
     * 处理截图命令
     */
    private void handleScreenshot() {
        try {
            screenshotStatus(R.string.rear_screenshot_saving);
            RearScreenshotPipeline.Result result = RearScreenshotPipeline.capture(this, taskService,
                    () -> {
                        screenshotStatus(R.string.rear_screenshot_preparing);
                        showToast(getString(R.string.rear_screenshot_preparing));
                    });
            int message;
            switch (result) {
                case SHELL: message = R.string.rear_screenshot_saved_shell; break;
                case SHELL_AND_ORIGINAL: message = R.string.rear_screenshot_saved_both; break;
                case ORIGINAL_FALLBACK: message = R.string.rear_screenshot_saved_fallback; break;
                case SHELL_ORIGINAL_FAILED: message = R.string.rear_screenshot_saved_shell_only; break;
                default: message = R.string.toast_screenshot_saved;
            }
            showToast(getString(message));
        } catch (Exception | OutOfMemoryError e) {
            Log.e(TAG, "截图命令失败", e);
            showToast(getString(R.string.rear_screenshot_failed));
        }
    }

    private void screenshotStatus(int text) {
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (manager != null) manager.notify(10003, new androidx.core.app.NotificationCompat.Builder(this, "rear_screen_keeper")
                .setSmallIcon(R.drawable.ic_stat_mrss).setContentTitle(getString(R.string.rear_screenshot_title))
                .setContentText(getString(text)).setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true).build());
    }

    /**
     * 处理配置命令
     */
    private void handleConfig(Uri uri) {
        try {
            applyConfigParams(uri);
        } catch (Exception e) {
            Log.e(TAG, "配置命令失败", e);
        }
    }

    /**
     * 应用配置参数 - 直接调用TaskService方法（照抄MainActivity逻辑）
     * DPI和旋转都直接设置，TaskService内部会处理等待和复活
     */
    private void applyConfigParams(Uri uri) {
        Log.d(TAG, "────────────────────────────");
        Log.d(TAG, "🔧 开始应用配置参数");
        Log.d(TAG, "cast_config action=config");

        try {
            String dpiStr = uri.getQueryParameter("dpi");
            Log.d(TAG, "DPI参数: " + dpiStr);

            if (dpiStr != null) {
                int dpi = Integer.parseInt(dpiStr);
                Log.d(TAG, "→ 调用 taskService.setRearDpi(" + dpi + ")");

                // 直接调用TaskService.setRearDpi - 完全照抄MainActivity逻辑
                boolean success = taskService.setRearDpi(dpi);

                if (success) {
                    Log.d(TAG, "✅ DPI设置成功: " + dpi);
                } else {
                    Log.e(TAG, "❌ DPI设置失败（TaskService返回false）");
                }
            } else {
                Log.d(TAG, "→ 跳过DPI设置（无参数）");
            }

            String rotationStr = uri.getQueryParameter("rotation");
            Log.d(TAG, "旋转参数: " + rotationStr);

            if (rotationStr != null) {
                int rotation = Integer.parseInt(rotationStr);
                Log.d(TAG, "→ 调用 taskService.setDisplayRotation(RearDisplay.requireId(), " + rotation + ")");

                // 直接调用TaskService.setDisplayRotation - 完全照抄MainActivity逻辑
                // TaskService内部会自动处理：等待500ms + 检查应用 + 复活
                boolean success = taskService.setDisplayRotation(RearDisplay.requireId(), rotation);

                if (success) {
                    Log.d(TAG, "✅ 旋转设置成功: " + rotation);
                } else {
                    Log.e(TAG, "❌ 旋转设置失败（TaskService返回false）");
                }
            } else {
                Log.d(TAG, "→ 跳过旋转设置（无参数）");
            }

            Log.d(TAG, "🔧 配置参数应用完成");
            Log.d(TAG, "────────────────────────────");
        } catch (Exception e) {
            Log.e(TAG, "❌ 应用配置参数异常", e);
            e.printStackTrace();
        }
    }

    /**
     * 获取应用名称
     */
    private String getAppName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    /**
     * 显示Toast提示（主线程）
     */
    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDestroy() {
        taskServiceReleased = true;
        if (BuildConfig.VIRTUAL_CAST_ENABLED) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();

        TaskServiceManager.release(taskServiceConnection);
        taskService = null;
    }
}
