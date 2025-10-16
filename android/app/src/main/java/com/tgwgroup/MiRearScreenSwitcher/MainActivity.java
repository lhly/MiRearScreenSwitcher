package com.tgwgroup.MiRearScreenSwitcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

import rikka.shizuku.Shizuku;

public class MainActivity extends FlutterActivity {
    private static final String CHANNEL = "com.display.switcher/task";
    private static final String TAG = "MainActivity";
    
    private ITaskService taskService;
    private MethodChannel methodChannel;
    private final Shizuku.UserServiceArgs serviceArgs = 
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("task_service")
            .debuggable(true)
            .version(1);
    
    // Shizuku监听器（关键！）
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = 
        () -> {
            Log.d(TAG, "Shizuku binder received");
            bindTaskService();
        };
    
    private final Shizuku.OnBinderDeadListener binderDeadListener = 
        () -> {
            Log.d(TAG, "Shizuku binder dead");
            taskService = null;
        };
    
    private final Shizuku.OnRequestPermissionResultListener requestPermissionResultListener = 
        (requestCode, grantResult) -> {
            boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
            Log.d(TAG, "Shizuku permission result: " + granted);
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
            Log.d(TAG, "✅ TaskService connected!");
            taskService = ITaskService.Stub.asInterface(binder);
            
            // 测试Service
            try {
                String test = taskService.getCurrentForegroundApp();
                Log.d(TAG, "TaskService test successful: " + test);
            } catch (Exception e) {
                Log.e(TAG, "TaskService test failed", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "❌ TaskService disconnected");
            taskService = null;
        }
    };
    
    private void bindTaskService() {
        if (taskService != null) {
            Log.d(TAG, "TaskService already bound");
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
            
            Log.d(TAG, "Binding TaskService... (Shizuku version=" + Shizuku.getVersion() + ")");
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
            Log.d(TAG, "bindUserService call completed, waiting for onServiceConnected...");
            
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
                    Log.d(TAG, "Requesting Shizuku permission...");
                    Shizuku.requestPermission(0);
                } else {
                    Log.d(TAG, "Shizuku permission already granted");
                    bindTaskService();
                }
            } else {
                Log.w(TAG, "Shizuku not running");
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
                Log.d(TAG, "Method called: " + call.method);
                
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
                                Log.d(TAG, "Using TaskService to get current app");
                                String currentApp = taskService.getCurrentForegroundApp();
                                Log.d(TAG, "TaskService returned: " + currentApp);
                                result.success(currentApp);
                            } catch (Exception e) {
                                Log.e(TAG, "TaskService error: " + e.getMessage(), e);
                                result.success(null);
                            }
                        } else {
                            Log.w(TAG, "TaskService not available");
                            result.success(null);
                        }
                        break;
                    }
                    
                    case "showFloatingBubble": {
                        // 检查悬浮窗权限
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                            Log.w(TAG, "No overlay permission, requesting...");
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                            result.success("need_permission");
                        } else {
                            Intent serviceIntent = new Intent(this, FloatingBubbleService.class);
                            startService(serviceIntent);
                            Log.d(TAG, "FloatingBubbleService started");
                            result.success("success");
                        }
                        break;
                    }
                    
                    case "checkOverlayPermission": {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            boolean hasPermission = Settings.canDrawOverlays(this);
                            result.success(hasPermission);
                        } else {
                            result.success(true);
                        }
                        break;
                    }
                    
                    case "hideFloatingBubble": {
                        Intent serviceIntent = new Intent(this, FloatingBubbleService.class);
                        stopService(serviceIntent);
                        Log.d(TAG, "FloatingBubbleService stopped");
                        result.success(true);
                        break;
                    }
                    
                    case "minimizeApp": {
                        // 最小化应用到后台
                        moveTaskToBack(true);
                        result.success(true);
                        break;
                    }
                    
                    default:
                        result.notImplemented();
                }
            });
    }
}
