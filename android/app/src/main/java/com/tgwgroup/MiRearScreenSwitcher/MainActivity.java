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

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

import rikka.shizuku.Shizuku;

public class MainActivity extends FlutterActivity {
    private static final String CHANNEL = "com.display.switcher/task";
    private static final String TAG = "MainActivity";
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1001;
    
    private ITaskService taskService;
    private MethodChannel methodChannel;
    private final Shizuku.UserServiceArgs serviceArgs = 
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("task_service")
            .debuggable(false)
            .version(1);
    
    // Shizuku监听器（关键！）
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = 
        () -> {
            bindTaskService();
        };
    
    private final Shizuku.OnBinderDeadListener binderDeadListener = 
        () -> {
            taskService = null;
            
            // 启动重连任务
            scheduleReconnectTaskService();
        };
    
    /**
     * TaskService重连任务
     */
    private final Runnable reconnectTaskServiceRunnable = new Runnable() {
        @Override
        public void run() {
            if (taskService == null) {
                bindTaskService();
                
                // 如果重连失败，2秒后再次尝试
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 30);
            } else {
            }
        }
    };
    
    /**
     * 安排TaskService重连
     */
    private void scheduleReconnectTaskService() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(reconnectTaskServiceRunnable, 30);
    };
    
    private final Shizuku.OnRequestPermissionResultListener requestPermissionResultListener = 
        (requestCode, grantResult) -> {
            boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                bindTaskService();
            }
            // 通知Flutter刷新状态
            if (methodChannel != null) {
                runOnUiThread(() -> {
                    methodChannel.invokeMethod("onShizukuPermissionChanged", granted);
                });
            }
        };
    
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            
            // 测试Service
            try {
                String test = taskService.getCurrentForegroundApp();
            } catch (Exception e) {
                Log.e(TAG, "TaskService test failed", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };
    
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
            e.printStackTrace();
        }
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 添加Shizuku监听器（关键！使用Sticky版本）
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);
        
        // 自动检查并请求Shizuku权限
        checkAndRequestShizukuPermission();
    }
    
    private void checkAndRequestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(0);
                } else {
                    bindTaskService();
                }
            } else {
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to check Shizuku permission", e);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener);
    }
    
    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        
        methodChannel = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        methodChannel.setMethodCallHandler((call, result) -> {
                switch (call.method) {
                    case "checkShizuku": {
                        try {
                            boolean isRunning = Shizuku.pingBinder();
                            boolean hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                            result.success(isRunning && hasPermission);
                        } catch (Exception e) {
                            result.success(false);
                        }
                        break;
                    }
                    
                    case "requestShizukuPermission": {
                        try {
                            Shizuku.requestPermission(0);
                            result.success(null);
                        } catch (Exception e) {
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "getShizukuInfo": {
                        try {
                            boolean isRunning = Shizuku.pingBinder();
                            boolean hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                            int uid = Shizuku.getUid();
                            int version = Shizuku.getVersion();
                            String info = "Running: " + isRunning + "\n" +
                                         "Permission: " + hasPermission + "\n" +
                                         "UID: " + uid + "\n" +
                                         "Version: " + version;
                            result.success(info);
                        } catch (Exception e) {
                            result.success("Error: " + e.getMessage());
                        }
                        break;
                    }
                    
                    case "getCurrentApp": {
                        if (taskService != null) {
                            try {
                                String currentApp = taskService.getCurrentForegroundApp();
                                result.success(currentApp);
                            } catch (Exception e) {
                                Log.e(TAG, "TaskService error: " + e.getMessage(), e);
                                result.success(null);
                            }
                        } else {
                            result.success(null);
                        }
                        break;
                    }
                    
                    case "requestNotificationPermission": {
                        // Android 13+ 需要请求通知权限
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                                != PackageManager.PERMISSION_GRANTED) {
                                ActivityCompat.requestPermissions(this, 
                                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 
                                    NOTIFICATION_PERMISSION_REQUEST_CODE);
                                result.success(null);
                            } else {
                                result.success(null);
                            }
                        } else {
                            // Android 12及以下不需要请求通知权限
                            result.success(null);
                        }
                        break;
                    }
                    
                    case "getCurrentRearDpi": {
                        if (taskService != null) {
                            try {
                                int dpi = taskService.getCurrentRearDpi();
                                result.success(dpi);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to get rear DPI", e);
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "setRearDpi": {
                        if (taskService != null) {
                            try {
                                int dpi = (int) call.argument("dpi");
                                boolean success = taskService.setRearDpi(dpi);
                                result.success(success);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to set rear DPI", e);
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "resetRearDpi": {
                        if (taskService != null) {
                            try {
                                boolean success = taskService.resetRearDpi();
                                result.success(success);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to reset rear DPI", e);
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "openCoolApkProfile": {
                        try {
                            Intent intent = new Intent();
                            intent.setClassName("com.coolapk.market", "com.coolapk.market.view.AppLinkActivity");
                            intent.setAction(Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse("coolmarket://u/8158212"));
                            startActivity(intent);
                            result.success(null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open CoolApk profile", e);
                            result.error("ERROR", "请先安装酷安应用", null);
                        }
                        break;
                    }
                    
                    case "openCoolApkProfileXmz": {
                        try {
                            Intent intent = new Intent();
                            intent.setClassName("com.coolapk.market", "com.coolapk.market.view.AppLinkActivity");
                            intent.setAction(Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse("coolmarket://u/4279097"));
                            startActivity(intent);
                            result.success(null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open CoolApk profile", e);
                            result.error("ERROR", "请先安装酷安应用", null);
                        }
                        break;
                    }
                    
                    case "openCoolApkTutorial": {
                        try {
                            Intent intent = new Intent();
                            intent.setClassName("com.coolapk.market", "com.coolapk.market.view.AppLinkActivity");
                            intent.setAction(Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse("coolmarket://feed/67979666"));
                            startActivity(intent);
                            result.success(null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open CoolApk tutorial", e);
                            result.error("ERROR", "请先安装酷安应用", null);
                        }
                        break;
                    }
                    
                    case "ensureTaskServiceConnected": {
                        // 确保TaskService连接正常
                        try {
                            if (taskService == null) {
                                // 尝试重新绑定
                                bindTaskService();
                            }
                            result.success(taskService != null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to ensure TaskService connection", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setDisplayRotation": {
                        if (taskService != null) {
                            try {
                                int displayId = (int) call.argument("displayId");
                                int rotation = (int) call.argument("rotation");
                                boolean success = taskService.setDisplayRotation(displayId, rotation);
                                result.success(success);
                            } catch (Exception e) {
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "getDisplayRotation": {
                        if (taskService != null) {
                            try {
                                int displayId = (int) call.argument("displayId");
                                int rotation = taskService.getDisplayRotation(displayId);
                                result.success(rotation);
                            } catch (Exception e) {
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    case "returnRearAppAndRestart": {
                        // 重启前先拉回背屏应用
                        if (taskService != null) {
                            try {
                                // 获取最后移动的任务信息
                                String lastTask = SwitchToRearTileService.getLastMovedTask();
                                
                                if (lastTask != null && lastTask.contains(":")) {
                                    String[] parts = lastTask.split(":");
                                    int taskId = Integer.parseInt(parts[1]);
                                    
                                    // 检查任务是否还在背屏
                                    boolean onRear = taskService.isTaskOnDisplay(taskId, 1);
                                    
                                    if (onRear) {
                                        // 拉回主屏
                                        taskService.moveTaskToDisplay(taskId, 0);
                                        
                                        // 恢复官方Launcher
                                        taskService.enableSubScreenLauncher();
                                        
                                        result.success(true);
                                    } else {
                                        // 没有应用在背屏
                                        result.success(false);
                                    }
                                } else {
                                    // 没有记录
                                    result.success(false);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to return rear app", e);
                                result.error("ERROR", e.getMessage(), null);
                            }
                        } else {
                            result.error("ERROR", "TaskService not available", null);
                        }
                        break;
                    }
                    
                    default:
                        result.notImplemented();
                }
            });
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            } else {
            }
        }
    }
}
