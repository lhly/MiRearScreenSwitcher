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

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

/**
 * Quick Settings Tile - 切换至背屏
 * 点击后将当前前台应用切换到背屏
 */
public class SwitchToRearTileService extends TileService {
    private static final String TAG = "SwitchToRearTile";

    // 静态变量：保存最后移动到背屏的任务信息（用于接近传感器恢复）
    private static String lastMovedTask = null; // 格式: "packageName:taskId"

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
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
            scheduleReconnectTaskService();
        }
    };

    /**
     * TaskService重连任务
     */
    private final Runnable reconnectTaskServiceRunnable = new Runnable() {
        @Override
        public void run() {
            if (taskService == null) {
                bindTaskService();
                // 如果重连失败，1秒后再次尝试
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 1000);
            }
        }
    };

    /**
     * 安排TaskService重连
     */
    private void scheduleReconnectTaskService() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(reconnectTaskServiceRunnable, 200);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();

        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(null);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.setStateDescription("");
            }
            tile.updateTile();
        }

        bindTaskService();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        unbindTaskService();
    }

    /**
     * 静态辅助方法：恢复指定任务到背屏
     * 由 RearScreenBroadcastReceiver 调用
     */
    public static void restoreTaskToRearDisplay(int taskId) {
        // 这个方法留空，实际恢复逻辑由广播接收器直接启动Activity来触发
        // Activity会自动应用FLAG_KEEP_SCREEN_ON
    }

    /**
     * 获取最后移动到背屏的任务信息
     * 
     * @return 格式: "packageName:taskId"，如果没有则返回null
     */
    public static String getLastMovedTask() {
        return lastMovedTask;
    }

    @Override
    public void onClick() {
        super.onClick();
        switchCurrentAppToRearDisplay();
    }

    private void bindTaskService() {
        if (taskService != null) {
            return;
        }

        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku not available");
                return;
            }

            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "No Shizuku permission");
                return;
            }

            Shizuku.bindUserService(serviceArgs, taskServiceConnection);

        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }

    private void unbindTaskService() {
        if (taskService != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding TaskService", e);
            }
            taskService = null;
        }
    }

    private void switchCurrentAppToRearDisplay() {
        if (taskService == null) {
            Log.w(TAG, "TaskService not available!");
            showTemporaryFeedback("服务未就绪");

            // 尝试重新绑定
            bindTaskService();

            // 延迟重试
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (taskService != null) {
                    performSwitch();
                } else {
                    showTemporaryFeedback("请先打开应用授权");
                }
            }, 1000);
            return;
        }

        performSwitch();
    }

    private void performSwitch() {
        // 显示执行中状态 - 保持按钮外观，只改变副标题
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE); // 保持熄灭状态
            tile.setSubtitle("切换中...");
            // 不显示"已开启"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.setStateDescription("");
            }
            tile.updateTile();
        }

        try {
            // 步骤0: 检查背屏是否已有应用在运行
            if (lastMovedTask != null && lastMovedTask.contains(":")) {
                try {
                    String[] oldParts = lastMovedTask.split(":");
                    String oldPackageName = oldParts[0];
                    int oldTaskId = Integer.parseInt(oldParts[1]);

                    // 检查旧应用是否还在背屏
                    String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);
                    if (rearForegroundApp != null && rearForegroundApp.equals(lastMovedTask)) {
                        // 背屏已有应用在运行，禁止操作
                        String oldAppName = getAppName(oldPackageName);

                        // 先收起控制中心，Toast才能显示
                        try {
                            taskService.collapseStatusBar();
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to collapse for toast: " + e.getMessage());
                        }

                        // 延迟显示Toast，确保控制中心已收起
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            Toast.makeText(this, getString(R.string.toast_please_switch_back, oldAppName),
                                    Toast.LENGTH_LONG).show();
                        }, 300);

                        showTemporaryFeedback("✗ 背屏已占用");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to check previous app: " + e.getMessage());
                }
            }

            // 步骤1: 禁用系统背屏Launcher（关键！防止挤占）
            try {
                taskService.disableSubScreenLauncher();
            } catch (Exception e) {
                Log.w(TAG, "Failed to disable SubScreenLauncher", e);
            }

            // 步骤2: 获取当前前台应用
            String currentApp = taskService.getCurrentForegroundApp();

            // 步骤3: 立即启动前台Service（不延迟，让通知快速出现）
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

                // 获取应用名
                String appName = getAppName(packageName);

                // 步骤4: 切换到display 1 (背屏)
                boolean success = taskService.moveTaskToDisplay(taskId, 1);

                if (success) {
                    // 保存最后移动的任务信息（用于接近传感器恢复）
                    lastMovedTask = currentApp;

                    // 自动收回控制中心（提升用户体验）
                    try {
                        new Thread(() -> {
                            try {
                                if (taskService != null) {
                                    taskService.collapseStatusBar();
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to collapse: " + e.getMessage());
                            }
                        }).start();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to start collapse thread: " + e.getMessage());
                    }

                    // 延迟显示Toast，确保控制中心已收起
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        Toast.makeText(this, appName + " " + getString(R.string.toast_cast_to_rear), Toast.LENGTH_SHORT)
                                .show();
                    }, 300);

                    // 步骤5: 主动点亮背屏 (通过TaskService启动Activity，绕过BAL限制)
                    try {
                        if (taskService != null) {
                            try {
                                boolean launchResult = taskService.launchWakeActivity(1);
                                if (!launchResult) {
                                    Log.w(TAG, "TaskService launch failed");
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "launchWakeActivity exception: " + e.getMessage());
                            }
                        } else {
                            Log.w(TAG, "TaskService not available");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to launch wakeup activity", e);
                    }

                    showTemporaryFeedback("✓ 已切换");
                } else {
                    // 先收起控制中心
                    try {
                        taskService.collapseStatusBar();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to collapse: " + e.getMessage());
                    }

                    // 延迟显示Toast
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        Toast.makeText(this, getString(R.string.toast_switch_failed), Toast.LENGTH_SHORT).show();
                    }, 300);

                    showTemporaryFeedback("✗ 失败");
                }
            } else {
                Log.w(TAG, "No foreground app found");
                showTemporaryFeedback("✗ 未找到应用");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error switching app", e);
            showTemporaryFeedback("✗ 操作失败");
        }
    }

    private void showTemporaryFeedback(String message) {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(message);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.setStateDescription("");
            }
            tile.updateTile();
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Tile resetTile = getQsTile();
            if (resetTile != null) {
                resetTile.setState(Tile.STATE_INACTIVE);
                resetTile.setSubtitle(null);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    resetTile.setStateDescription("");
                }
                resetTile.updateTile();
            }
        }, 1500);
    }

    /**
     * 获取应用名称
     */
    private String getAppName(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(appInfo);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get app name: " + e.getMessage());
        }
        return packageName; // 失败时返回包名
    }
}
