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

package com.tgwgroup.MiRearScreenSwitcher;

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
import rikka.shizuku.Shizuku;

/**
 * V2.6: URI命令处理服务
 * 在后台静默执行URI命令，不显示UI
 * 复用现有的TileService切换逻辑
 */
public class UriCommandService extends IntentService {
    private static final String TAG = "UriCommandService";

    private ITaskService taskService;
    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("task_service")
            .debuggable(false)
            .version(1);

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
        bindTaskService();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null)
            return;

        Uri uri = intent.getData();
        if (uri == null || !"mrss".equals(uri.getScheme())) {
            return;
        }

        Log.d(TAG, "🔗 处理URI: " + uri.toString());

        // 确保TaskService已连接
        if (!ensureTaskServiceConnected()) {
            Log.e(TAG, "❌ TaskService未连接");
            return;
        }

        String host = uri.getHost();
        if (host == null)
            return;

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

            // 等待连接（最多3秒）
            int attempts = 0;
            while (taskService == null && attempts < 30) {
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
        if (taskService != null)
            return;

        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku不可用");
                return;
            }

            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "无Shizuku权限");
                return;
            }

            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "绑定TaskService失败", e);
        }
    }

    /**
     * 处理切换命令 - 复用TileService逻辑
     */
    private void handleSwitch(Uri uri) {
        Log.d(TAG, "════════════════════════════════════════");
        Log.d(TAG, "🔄 处理SWITCH命令");
        Log.d(TAG, "URI: " + uri.toString());

        try {
            // 0. 检查背屏是否已有应用在运行（拒绝重复投放）
            try {
                String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);
                Log.d(TAG, "背屏前台应用: " + rearForegroundApp);

                if (rearForegroundApp != null && !rearForegroundApp.isEmpty()) {
                    // 排除允许的进程：
                    // 1. MRSS自己的Activity（充电动画、通知动画、唤醒等）
                    // 2. 小米官方Launcher（com.xiaomi.subscreencenter.SubScreenLauncher）
                    if (!rearForegroundApp.contains("RearScreenChargingActivity") &&
                            !rearForegroundApp.contains("RearScreenNotificationActivity") &&
                            !rearForegroundApp.contains("RearScreenWakeupActivity") &&
                            !rearForegroundApp.contains("com.xiaomi.subscreencenter")) {
                        Log.w(TAG, "❌ 背屏已有应用在运行: " + rearForegroundApp);
                        Log.d(TAG, "════════════════════════════════════════");
                        return;
                    } else {
                        Log.d(TAG, "✓ 背屏空闲或仅有官方Launcher/MRSS临时Activity");
                    }
                } else {
                    Log.d(TAG, "✓ 背屏空闲");
                }
            } catch (Exception e) {
                Log.w(TAG, "检查背屏占用失败: " + e.getMessage());
            }

            // 1. 确定目标
            String currentParam = uri.getQueryParameter("current");
            String packageName = uri.getQueryParameter("packageName");
            String activity = uri.getQueryParameter("activity");

            Log.d(TAG, "参数 - current: " + currentParam + ", packageName: " + packageName + ", activity: " + activity);

            if ("true".equalsIgnoreCase(currentParam) || "1".equals(currentParam)) {
                // 切换当前应用 - 完全复用TileService逻辑
                Log.d(TAG, "→ 模式：切换当前应用");
                // 先应用配置参数，再切换
                applyConfigParams(uri);
                switchCurrentAppToRear();
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
    private void switchCurrentAppToRear() {
        try {
            // 步骤0: 检查背屏是否已有应用在运行（复用TileService逻辑）
            String lastMovedTask = SwitchToRearTileService.getLastMovedTask();
            if (lastMovedTask != null && lastMovedTask.contains(":")) {
                try {
                    String[] oldParts = lastMovedTask.split(":");
                    String oldPackageName = oldParts[0];

                    // 检查旧应用是否还在背屏
                    String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);
                    if (rearForegroundApp != null && rearForegroundApp.equals(lastMovedTask)) {
                        // 背屏已有应用在运行，禁止操作
                        String oldAppName = getAppName(oldPackageName);
                        Log.w(TAG, "❌ 背屏已被占用: " + oldAppName);
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "检查旧应用失败: " + e.getMessage());
                }
            }

            // 额外检查：确保背屏没有其他用户应用
            try {
                String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);
                if (rearForegroundApp != null && !rearForegroundApp.isEmpty()) {
                    // 排除允许的进程
                    if (!rearForegroundApp.contains("RearScreenChargingActivity") &&
                            !rearForegroundApp.contains("RearScreenNotificationActivity") &&
                            !rearForegroundApp.contains("RearScreenWakeupActivity") &&
                            !rearForegroundApp.contains("com.xiaomi.subscreencenter")) {
                        Log.w(TAG, "❌ 背屏已有其他应用: " + rearForegroundApp);
                        return;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "背屏占用检查失败: " + e.getMessage());
            }

            // 步骤1: 禁用系统背屏Launcher（关键！防止挤占）
            try {
                taskService.disableSubScreenLauncher();
            } catch (Exception e) {
                Log.w(TAG, "Failed to disable SubScreenLauncher", e);
            }

            // 步骤2: 获取当前前台应用
            String currentApp = taskService.getCurrentForegroundApp();

            // 步骤3: 立即启动前台Service（让通知快速出现）
            Intent serviceIntent = new Intent(this, RearScreenKeeperService.class);
            serviceIntent.putExtra("lastMovedTask", currentApp);

            // V2.5: 传递背屏常亮开关状态
            try {
                android.content.SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences",
                        MODE_PRIVATE);
                boolean keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                serviceIntent.putExtra("keepScreenOnEnabled", keepScreenOnEnabled);
            } catch (Exception e) {
                // 默认为开启
                serviceIntent.putExtra("keepScreenOnEnabled", true);
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            if (currentApp != null && currentApp.contains(":")) {
                String[] parts = currentApp.split(":");
                String packageName = parts[0];
                int taskId = Integer.parseInt(parts[1]);

                // 步骤4: 切换到display 1 (背屏)
                boolean success = taskService.moveTaskToDisplay(taskId, 1);

                if (success) {
                    Log.d(TAG, "✅ Task已移动到背屏 (taskId=" + taskId + ")");

                    // 步骤5: 主动点亮背屏 (通过TaskService启动Activity，绕过BAL限制) - 关键步骤！
                    try {
                        if (taskService != null) {
                            try {
                                boolean launchResult = taskService.launchWakeActivity(1);
                                if (!launchResult) {
                                    Log.w(TAG, "TaskService launch failed, fallback to shell");
                                    // Fallback: shell命令启动
                                    String cmd = "am start --display 1 -n com.tgwgroup.MiRearScreenSwitcher/"
                                            + RearScreenWakeupActivity.class.getName();
                                    taskService.executeShellCommand(cmd);
                                }
                            } catch (NoSuchMethodError e) {
                                // 旧版本TaskService没有launchWakeActivity，使用shell命令
                                String cmd = "am start --display 1 -n com.tgwgroup.MiRearScreenSwitcher/"
                                        + RearScreenWakeupActivity.class.getName();
                                taskService.executeShellCommand(cmd);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to launch wake activity: " + e.getMessage());
                    }

                    Log.d(TAG, "✅ " + packageName + " 已切换到背屏");

                    // Toast提示
                    String appName = getAppName(packageName);
                    showToast(appName + " " + getString(R.string.toast_cast_to_rear));
                } else {
                    Log.e(TAG, "❌ 切换失败");
                    showToast(getString(R.string.toast_switch_failed));
                }
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
        try {
            // 步骤0: 先设置DPI和旋转（在启动应用前设置好背屏参数）
            applyConfigParams(uri);

            // 步骤1: 禁用系统背屏Launcher
            taskService.disableSubScreenLauncher();
            Thread.sleep(100);

            // 步骤1.5: 清理目标应用的旧task（如果存在）- 防止获取到旧task
            String targetPackageName = packageName;
            if (targetPackageName == null && activity != null) {
                // 从activity中提取包名
                if (activity.contains("/")) {
                    targetPackageName = activity.substring(0, activity.indexOf("/"));
                }
            }

            if (targetPackageName != null) {
                try {
                    Log.d(TAG, "→ 检查并清理旧task: " + targetPackageName);
                    // 尝试强制停止应用（清理所有task）
                    taskService.executeShellCommand("am force-stop " + targetPackageName);
                    Thread.sleep(300);
                    Log.d(TAG, "✓ 已清理旧task");
                } catch (Exception e) {
                    Log.w(TAG, "清理旧task失败: " + e.getMessage());
                }
            }

            // 步骤2: 在主屏启动应用（先在主屏启动，才能获取taskId）
            String launchCmd;
            if (activity != null) {
                launchCmd = "am start -n " + activity;
                Log.d(TAG, "→ 使用指定Activity启动: " + activity);
            } else {
                // 使用pm命令获取主Activity，比monkey更可靠
                launchCmd = "cmd package resolve-activity --brief " + packageName + " | tail -n 1";
                String mainActivity = taskService.executeShellCommandWithResult(launchCmd);

                if (mainActivity != null && !mainActivity.trim().isEmpty()
                        && !mainActivity.contains("No activity found")) {
                    mainActivity = mainActivity.trim();
                    launchCmd = "am start -n " + mainActivity;
                    Log.d(TAG, "→ 解析到主Activity: " + mainActivity);
                } else {
                    // Fallback: 使用pm dump获取主Activity
                    launchCmd = "pm dump " + packageName + " | grep -A 1 'android.intent.action.MAIN' | grep -o '"
                            + packageName + "[^\\s]*' | head -n 1";
                    mainActivity = taskService.executeShellCommandWithResult(launchCmd);

                    if (mainActivity != null && !mainActivity.trim().isEmpty()) {
                        mainActivity = mainActivity.trim();
                        launchCmd = "am start -n " + mainActivity;
                        Log.d(TAG, "→ 通过pm dump解析到主Activity: " + mainActivity);
                    } else {
                        // 最后的Fallback: 使用Intent方式启动
                        launchCmd = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
                                + packageName;
                        Log.w(TAG, "→ 使用Intent方式启动（未能解析主Activity）");
                    }
                }
            }

            Log.d(TAG, "→ 执行启动命令: " + launchCmd);
            taskService.executeShellCommand(launchCmd);
            Log.d(TAG, "✓ 启动命令已执行");

            // 步骤3: 等待应用启动并验证，最多重试3次
            String targetApp = null;
            String actualPackage = null;
            int taskId = -1;
            int maxRetries = 3;

            for (int retry = 0; retry < maxRetries; retry++) {
                Thread.sleep(500 + retry * 200); // 首次500ms，之后递增

                targetApp = taskService.getCurrentForegroundApp();
                Log.d(TAG, "  尝试 " + (retry + 1) + "/" + maxRetries + " 获取前台应用: " + targetApp);

                if (targetApp == null || !targetApp.contains(":")) {
                    Log.w(TAG, "  未能获取应用，继续重试...");
                    continue;
                }

                String[] parts = targetApp.split(":");
                actualPackage = parts[0];

                // 验证是否是目标应用（支持packageName和activity两种验证）
                boolean isTargetApp = false;
                if (packageName != null) {
                    isTargetApp = actualPackage.equals(packageName);
                } else if (activity != null) {
                    // 从activity提取包名进行验证
                    String activityPackage = activity.contains("/") ? activity.substring(0, activity.indexOf("/"))
                            : activity;
                    isTargetApp = actualPackage.equals(activityPackage);
                } else {
                    // 无验证条件，接受任何应用（不应该到这里）
                    isTargetApp = true;
                }

                if (!isTargetApp) {
                    String expectedPkg = packageName != null ? packageName
                            : (activity != null ? activity.substring(0, activity.indexOf("/")) : "unknown");
                    Log.w(TAG, "  应用不匹配: " + actualPackage + " vs " + expectedPkg);

                    // 最后一次重试前，强制启动目标应用
                    if (retry < maxRetries - 1) {
                        Log.w(TAG, "  强制停止当前应用并重新启动目标应用");
                        // 先停止错误的应用
                        taskService.executeShellCommand("am force-stop " + actualPackage);
                        Thread.sleep(200);
                        // 重新执行启动命令
                        taskService.executeShellCommand(launchCmd);
                        continue;
                    } else {
                        Log.e(TAG, "  ❌ 多次重试后仍然无法启动目标应用");
                        return;
                    }
                } else {
                    // 成功启动目标应用
                    taskId = Integer.parseInt(parts[1]);
                    Log.d(TAG, "✓ 成功启动目标应用，taskId: " + taskId);
                    break;
                }
            }

            if (taskId == -1) {
                Log.e(TAG, "❌ 未能获取启动的应用taskId");
                return;
            }

            // 步骤4: 启动RearScreenKeeperService
            Intent serviceIntent = new Intent(this, RearScreenKeeperService.class);
            serviceIntent.putExtra("lastMovedTask", targetApp);

            // 传递背屏常亮开关状态
            try {
                android.content.SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences",
                        MODE_PRIVATE);
                boolean keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                serviceIntent.putExtra("keepScreenOnEnabled", keepScreenOnEnabled);
            } catch (Exception e) {
                serviceIntent.putExtra("keepScreenOnEnabled", true);
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            // 步骤5: 移动到背屏
            Log.d(TAG, "→ 步骤5: 移动Task到背屏 (taskId=" + taskId + ")");
            boolean success = taskService.moveTaskToDisplay(taskId, 1);

            if (success) {
                Log.d(TAG, "✅ Task已移动到背屏 (taskId=" + taskId + ")");

                // 步骤5.5: 等待应用在背屏稳定显示
                Thread.sleep(300);
                Log.d(TAG, "→ 等待应用稳定");

                // 步骤5.6: 移动到背屏后再次验证并应用DPI（确保生效）
                String dpiStr = uri.getQueryParameter("dpi");
                if (dpiStr != null) {
                    try {
                        int dpi = Integer.parseInt(dpiStr);
                        Log.d(TAG, "→ 再次验证DPI并应用: " + dpi);
                        // 验证当前DPI
                        int currentDpi = taskService.getCurrentRearDpi();
                        Log.d(TAG, "  当前背屏DPI: " + currentDpi);
                        if (currentDpi != dpi) {
                            Log.w(TAG, "  DPI不匹配，重新设置");
                            taskService.setRearDpi(dpi);
                            Thread.sleep(200);
                        } else {
                            Log.d(TAG, "  ✓ DPI已生效");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "DPI验证失败: " + e.getMessage());
                    }
                }

                // 步骤6: 主动点亮背屏（关键步骤！）
                Log.d(TAG, "→ 步骤6: 点亮背屏");
                try {
                    boolean launchResult = taskService.launchWakeActivity(1);
                    if (!launchResult) {
                        Log.w(TAG, "TaskService launch failed, fallback to shell");
                        String cmd = "am start --display 1 -n com.tgwgroup.MiRearScreenSwitcher/"
                                + RearScreenWakeupActivity.class.getName();
                        taskService.executeShellCommand(cmd);
                    }
                    Log.d(TAG, "✓ 背屏已点亮");
                } catch (NoSuchMethodError e) {
                    // 旧版本兼容
                    String cmd = "am start --display 1 -n com.tgwgroup.MiRearScreenSwitcher/"
                            + RearScreenWakeupActivity.class.getName();
                    taskService.executeShellCommand(cmd);
                    Log.d(TAG, "✓ 背屏已点亮（旧版本fallback）");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to launch wake activity: " + e.getMessage());
                }

                // 步骤7: 如果设置了旋转，验证并检查应用状态
                String rotationStr = uri.getQueryParameter("rotation");
                if (rotationStr != null) {
                    Log.d(TAG, "→ 步骤7: 验证旋转并检查应用状态");
                    try {
                        int targetRotation = Integer.parseInt(rotationStr);

                        // 等待旋转生效
                        Thread.sleep(500);

                        // 验证旋转是否生效
                        int currentRotation = taskService.getDisplayRotation(1);
                        Log.d(TAG, "  目标旋转: " + targetRotation + ", 当前旋转: " + currentRotation);

                        if (currentRotation != targetRotation) {
                            Log.w(TAG, "  ⚠ 旋转不匹配，重新设置");
                            taskService.setDisplayRotation(1, targetRotation);
                            Thread.sleep(500); // 等待重新设置生效
                        } else {
                            Log.d(TAG, "  ✓ 旋转已生效");
                        }

                        // 检查应用是否还在背屏（可能被旋转杀死）
                        boolean stillOnRear = taskService.isTaskOnDisplay(taskId, 1);
                        Log.d(TAG, "  应用是否还在背屏: " + stillOnRear);

                        if (!stillOnRear) {
                            // 应用被旋转杀死了，重新投放
                            Log.w(TAG, "  ⚠ 应用因旋转被杀，重新投放");
                            taskService.moveTaskToDisplay(taskId, 1);
                            Thread.sleep(200);
                            Log.d(TAG, "  ✓ 应用已复活");
                        } else {
                            Log.d(TAG, "  ✓ 应用正常运行");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "旋转验证/检查失败: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    Log.d(TAG, "→ 步骤7: 跳过（无旋转参数）");
                }

                Log.d(TAG, "✅ " + actualPackage + " 已切换到背屏");

                // Toast提示
                String appName = getAppName(actualPackage);
                showToast(appName + " " + getString(R.string.toast_cast_to_rear));
            } else {
                Log.e(TAG, "❌ 移动到背屏失败");
                showToast(getString(R.string.toast_switch_failed));
            }

        } catch (Exception e) {
            Log.e(TAG, "切换指定应用失败", e);
            showToast("切换失败: " + e.getMessage());
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
                String rearApp = taskService.getForegroundAppOnDisplay(1);
                if (rearApp != null && rearApp.contains(":")) {
                    String[] parts = rearApp.split(":");
                    targetPackage = parts[0];
                    targetTaskId = Integer.parseInt(parts[1]);
                }
            } else if (taskIdStr != null) {
                targetTaskId = Integer.parseInt(taskIdStr);
                // 尝试从背屏前台应用获取包名
                String rearApp = taskService.getForegroundAppOnDisplay(1);
                if (rearApp != null && rearApp.contains(":")) {
                    targetPackage = rearApp.split(":")[0];
                }
            } else if (packageName != null) {
                String rearApp = taskService.getForegroundAppOnDisplay(1);
                if (rearApp != null && rearApp.startsWith(packageName + ":")) {
                    targetPackage = packageName;
                    targetTaskId = Integer.parseInt(rearApp.split(":")[1]);
                }
            }

            if (targetTaskId != -1) {
                // 检查任务是否真的在背屏
                boolean onRear = taskService.isTaskOnDisplay(targetTaskId, 1);

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
            boolean success = taskService.takeRearScreenshot();

            // 无论成功失败都显示成功Toast
            Log.d(TAG, "✅ 截图命令已执行");
            showToast(getString(R.string.toast_screenshot_saved));
        } catch (Exception e) {
            Log.e(TAG, "截图命令失败", e);
            // 即使异常也显示成功Toast
            showToast(getString(R.string.toast_screenshot_saved));
        }
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
        Log.d(TAG, "URI: " + uri.toString());

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
                Log.d(TAG, "→ 调用 taskService.setDisplayRotation(1, " + rotation + ")");

                // 直接调用TaskService.setDisplayRotation - 完全照抄MainActivity逻辑
                // TaskService内部会自动处理：等待500ms + 检查应用 + 复活
                boolean success = taskService.setDisplayRotation(1, rotation);

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
        super.onDestroy();

        if (taskService != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
            } catch (Exception e) {
                Log.e(TAG, "解绑TaskService失败", e);
            }
            taskService = null;
        }
    }
}
