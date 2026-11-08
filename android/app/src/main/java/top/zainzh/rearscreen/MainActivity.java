/*
 * Author: AntiOblivionis
 * Maintainer: 冷火凉烟 (GitHub: lhly), independent fork
 * Modified: 2026-10-13; see NOTICE for maintenance and contribution history.
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

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.app.Activity;

import rikka.shizuku.Shizuku;

public class MainActivity extends androidx.activity.ComponentActivity {
    static final String EXTRA_NAVIGATION_TARGET = "native_navigation_target";
    private static final String TAG = "MainActivity";
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1001;
    
    // 静态实例，供其他类访问
    private static MainActivity currentInstance;
    
    public static MainActivity getCurrentInstance() {
        return currentInstance;
    }
    
    private volatile boolean taskServiceReleased;
    private volatile ITaskService taskService;
    private SettingsController nativeUi;
    ReleaseUpdateChecker createUpdateChecker() { return new ReleaseUpdateChecker(getApplicationContext()); }
    boolean automaticUpdateChecksEnabled() {
        if (!BuildConfig.DEBUG) return true;
        try {
            for (android.content.pm.InstrumentationInfo info : getPackageManager().queryInstrumentation(getPackageName(), 0)) {
                if (getPackageName().equals(info.targetPackage)) return false;
            }
        } catch (RuntimeException ignored) { }
        return true;
    }
    UpdateDownloadTask createUpdateTask(UpdatePackage update) { return new UpdateDownloadTask(getApplicationContext(), update); }
    boolean canInstallUpdates() { return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls(); }
    void checkForUpdates() { if (nativeUi != null) nativeUi.checkForUpdates(); }
    private final java.util.concurrent.ExecutorService commandExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private final Shizuku.OnRequestPermissionResultListener requestPermissionResultListener =
        (requestCode, grantResult) -> {
            boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                bindTaskService();
            }
            if (nativeUi != null) runOnUiThread(nativeUi::refreshStatus);
        };
    
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            if (nativeUi != null) nativeUi.refreshStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
            if (nativeUi != null) nativeUi.refreshStatus();
        }
    };
    
    private void bindTaskService() {
        if (!taskServiceReleased) TaskServiceManager.acquire(this, taskServiceConnection);
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nativeUi = new SettingsController(this, savedInstanceState, automaticUpdateChecksEnabled());
        setContentView(nativeUi.getView());
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!nativeUi.navigateBack()) finish();
            }
        });
        bindTaskService();

        // 保存实例
        currentInstance = this;

        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);
        
        // 自动检查并请求Shizuku权限
        checkAndRequestShizukuPermission();
        
        // 处理通知Intent
        handleIncomingIntent(getIntent());
        handleNavigationIntent(getIntent());
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
        handleNavigationIntent(intent);
    }

    private void handleNavigationIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_NAVIGATION_TARGET) || nativeUi == null) return;
        nativeUi.openNavigationTarget(intent.getIntExtra(EXTRA_NAVIGATION_TARGET, 0));
        intent.removeExtra(EXTRA_NAVIGATION_TARGET);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (nativeUi != null) nativeUi.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * 处理来自Service的通知Intent
     */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        
        String action = intent.getAction();
        
        // 处理通知Intent
        if ("SHOW_NOTIFICATION_ON_REAR_SCREEN".equals(action)) {
            String packageName = intent.getStringExtra("packageName");
            String title = intent.getStringExtra("title");
            String text = intent.getStringExtra("text");
            long when = intent.getLongExtra("when", System.currentTimeMillis());
            
            Log.d(TAG, "Received notification intent for: " + packageName);
            startNotificationOnRearScreen(packageName, title, text, when);
        }
    }
    
    /**
     * 在背屏启动通知显示Activity
     */
    private void startNotificationOnRearScreen(String packageName, String title, String text, long when) {
        if (taskService == null) {
            Log.w(TAG, "TaskService not available for notification");
            return;
        }
        
        new Thread(() -> {
            try {
                // 步骤1: 禁用官方Launcher
                taskService.disableSubScreenLauncher();
                
                // 步骤2: 唤醒背屏
                taskService.executeShellCommand("input -d " + RearDisplay.requireId() + " keyevent KEYCODE_WAKEUP");
                Thread.sleep(50);
                
                // 步骤3: 在主屏启动Activity
                String componentName = getPackageName() + "/" + RearScreenNotificationActivity.class.getName();
                String mainCmd = PrivilegedShellArguments.notification(componentName, packageName, title, text, when);
                taskService.executeShellCommand(mainCmd);
                
                // 步骤4: 轮询获取taskId
                String notifTaskId = null;
                int attempts = 0;
                int maxAttempts = 20;
                
                while (notifTaskId == null && attempts < maxAttempts) {
                    Thread.sleep(30);
                    String result = taskService.executeShellCommandWithResult("am stack list | grep RearScreenNotificationActivity");
                    if (result != null && !result.trim().isEmpty()) {
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("taskId=(\\d+)");
                        java.util.regex.Matcher matcher = pattern.matcher(result);
                        if (matcher.find()) {
                            notifTaskId = matcher.group(1);
                            Log.d(TAG, "Found notification taskId=" + notifTaskId);
                            break;
                        }
                    }
                    attempts++;
                }
                
                if (notifTaskId != null) {
                    // 步骤5: 移动到背屏
                    taskService.moveTaskToDisplay(Integer.parseInt(notifTaskId), RearDisplay.requireId());
                    Thread.sleep(40);
                    
                    // 步骤6: 检查是否锁屏，决定是否关闭主屏
                    android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && km.isKeyguardLocked()) {
                        // 主屏休眠功能已移除
                        Log.d(TAG, "🔒 锁屏状态，主屏已关闭");
                    }
                    
                    Log.d(TAG, "✅ Notification animation started on rear screen");
                } else {
                    Log.e(TAG, "❌ Failed to find notification taskId");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to show notification on rear screen", e);
            }
        }).start();
    }
    
    private void checkAndRequestShizukuPermission() {
        if (TaskServiceManager.isRoot(this)) {
            bindTaskService(); TaskServiceManager.startSelected(this); return;
        }
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
        taskServiceReleased = true;
        if (nativeUi != null) nativeUi.destroy();
        commandExecutor.shutdownNow();
        super.onDestroy();
        
        // 清除静态实例
        currentInstance = null;
        
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener);
        TaskServiceManager.release(taskServiceConnection);
        taskService = null;
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (nativeUi != null) nativeUi.onResume();
    }

    @Override
    protected void onPause() {
        if (nativeUi != null) nativeUi.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (nativeUi != null) nativeUi.saveState(state);
        super.onSaveInstanceState(state);
    }

    boolean isTaskServiceReady() {
        ITaskService service = taskService;
        return service != null && service.asBinder().isBinderAlive();
    }

    void runAction(String method, Object arguments, NativeAction.Callback callback) {
        NativeAction.Result result = new NativeAction.Result() {
            @Override public void success(Object value) { deliver(value, null); }
            @Override public void error(String code, String message, Object details) { deliver(null, message); }
            private void deliver(Object value, String error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && callback != null) callback.complete(value, error);
                });
            }
        };
        boolean remote = method.equals("getCurrentRearDpi") || method.equals("setRearDpi")
                || method.equals("resetRearDpi") || method.equals("getDisplayRotation")
                || method.equals("setDisplayRotation") || method.equals("returnRearAppAndRestart")
                || method.equals("getCompatibilityDiagnostics");
        Runnable action = () -> {
            boolean entered = false;
            try {
                if (remote) {
                    PrivilegeOperations.begin(); entered = true;
                    bindTaskService();
                    long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                    while (!isTaskServiceReady() && !taskServiceReleased
                            && android.os.SystemClock.elapsedRealtime() < deadline) {
                        if (!TaskServiceManager.canConnect(this)) break;
                        Thread.sleep(50);
                    }
                    if (taskServiceReleased) return;
                }
                handleAction(new NativeAction(method, arguments), result);
            } catch (Exception error) {
                Log.e(TAG, "Native action failed: " + method, error);
                result.error("ERROR", error.getMessage(), null);
            } finally { if (entered) PrivilegeOperations.end(); }
        };
        if (remote) commandExecutor.execute(action);
        else action.run();
    }

    private void handleAction(NativeAction call, NativeAction.Result result) {
                switch (call.method) {
                    case "requestShizukuPermission": {
                        try {
                            Shizuku.requestPermission(0);
                            result.success(null);
                        } catch (Exception e) {
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "getCompatibilityDiagnostics": {
                        try {
                        org.json.JSONObject report = new org.json.JSONObject();
                        boolean running = Shizuku.pingBinder();
                        report.put("package", getPackageName()).put("version", BuildConfig.VERSION_NAME)
                                .put("device", Build.DEVICE).put("model", Build.MODEL)
                                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                                .put("incremental", Build.VERSION.INCREMENTAL)
                                .put("virtualCasting", RearCastMode.useVirtual(this))
                                .put("privilegeMode", PrivilegeMode.read(this).name()).put("privilegeState", TaskServiceManager.state(this).name())
                                .put("shizukuRunning", running).put("shizukuPermission", running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                                .put("shizukuUid", running ? Shizuku.getUid() : -1).put("shizukuVersion", running ? Shizuku.getVersion() : -1)
                                .put("connection", TaskServiceManager.diagnostics()).put("taskServiceReady", isTaskServiceReady());
                        if (taskService != null) {
                            try { report.put("capabilities", new org.json.JSONObject(taskService.getCompatibilityDiagnostics())); }
                            catch (Exception e) { report.put("serviceError", e.toString()); }
                        } else report.put("serviceError", "Control service not connected");
                        result.success(report.toString(2));
                        } catch (Exception e) { result.error("DIAGNOSTICS", e.toString(), null); }
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
                    
                    case "openTutorial": {
                        startActivity(new Intent(this, TutorialActivity.class));
                        result.success(null);
                        break;
                    }

                    case "openProject":
                    case "openFeedback":
                    case "openReleases":
                    case "openTutorialSource":
                    case "openVersionSource":
                    case "openOriginalProject":
                    case "openTesterProfile": {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse(ProjectInfo.urlForAction(call.method)));
                            startActivity(intent);
                            result.success(null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open project link", e);
                            result.error("ERROR", NativeStrings.get(this, "operation_failed"), null);
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
                                    boolean onRear = taskService.isTaskOnDisplay(taskId, RearDisplay.requireId());
                                    
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
                    
                    case "setProximitySensorEnabled": {
                        // V2.2: 设置接近传感器开关
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        // 通知RearScreenKeeperService更新状态
                        Intent intent = new Intent(this, RearScreenKeeperService.class);
                        intent.setAction("ACTION_SET_PROXIMITY_ENABLED");
                        intent.putExtra("enabled", enabled);
                        startService(intent);
                        
                        result.success(true);
                        break;
                    }
                    
                    case "setKeepScreenOnEnabled": {
                        // V2.5: 设置背屏常亮开关
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        // 通知RearScreenKeeperService更新状态
                        Intent intent = new Intent(this, RearScreenKeeperService.class);
                        intent.setAction("ACTION_SET_KEEP_SCREEN_ON_ENABLED");
                        intent.putExtra("enabled", enabled);
                        startService(intent);
                        
                        result.success(true);
                        break;
                    }
                    
                    case "setAlwaysWakeUpEnabled": {
                        // V3.5: 设置未投放应用时常亮开关
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                        prefs.edit().putBoolean("always_wakeup_enabled", enabled).apply();
                        
                        Intent intent = new Intent(this, AlwaysWakeUpService.class);
                        if (enabled) {
                            startService(intent);
                            Log.d(TAG, "AlwaysWakeUpService started");
                        } else {
                            stopService(intent);
                            Log.d(TAG, "AlwaysWakeUpService stopped");
                        }
                        
                        result.success(true);
                        break;
                    }
                    
                    case "setChargingAlwaysOnEnabled": {
                        // V3.5: 设置充电动画常亮开关
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                        prefs.edit().putBoolean("charging_always_on_enabled", enabled).apply();
                        
                        // 通知ChargingService重新加载设置
                        sendBroadcast(new Intent(BuildConfig.APPLICATION_ID + ".RELOAD_CHARGING_SETTINGS").setPackage(getPackageName()));
                        
                        Log.d(TAG, "Charging always on set to: " + enabled);
                        result.success(true);
                        break;
                    }
                    
                    case "toggleChargingService": {
                        // V2.3: 切换充电动画服务
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        Intent intent = new Intent(this, ChargingService.class);
                        if (enabled) {
                            startService(intent);
                            Log.d(TAG, "ChargingService started");
                        } else {
                            stopService(intent);
                            Log.d(TAG, "ChargingService stopped");
                        }
                        
                        result.success(true);
                        break;
                    }
                    
                    case "startNotificationService": {
                        // V2.4: 启动通知服务
                        Intent intent = new Intent(this, NotificationService.class);
                        startService(intent);
                        Log.d(TAG, "NotificationService started");
                        result.success(true);
                        break;
                    }
                    
                    case "toggleNotificationService": {
                        // V2.4: 切换通知服务
                        boolean enabled = (boolean) call.argument("enabled");
                        
                        SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                        prefs.edit()
                            .putBoolean("notification_service_enabled", enabled)
                            .apply();
                        
                        if (enabled) {
                            // 开启时启动服务
                            NotificationService.enableForUser(this);
                            Intent intent = new Intent(this, NotificationService.class);
                            startService(intent);
                            Log.d(TAG, "NotificationService started");
                        } else {
                            // 关闭时停止服务
                            NotificationService.disableForUser(this);
                            Intent intent = new Intent(this, NotificationService.class);
                            stopService(intent);
                            Log.d(TAG, "NotificationService stopped");
                        }
                        
                        Log.d(TAG, "Notification service enabled: " + enabled);
                        result.success(true);
                        break;
                    }
                    
                    case "openNotificationListenerSettings": {
                        // V2.4: 打开通知监听设置
                        try {
                            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open notification settings", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "requestQueryAllPackagesPermission": {
                        // V2.4: 请求QUERY_ALL_PACKAGES权限（跳转到应用详情页）
                        try {
                            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open app settings", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setNotificationPrivacyHideTitle": {
                        // V3.2: 设置隐藏通知标题
                        try {
                            boolean enabled = (boolean) call.argument("enabled");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putBoolean("notification_privacy_hide_title", enabled)
                                .apply();
                            
                            // 通知NotificationService重新加载设置
                            sendBroadcast(new Intent(BuildConfig.APPLICATION_ID + ".RELOAD_NOTIFICATION_SETTINGS").setPackage(getPackageName()));
                            
                            Log.d(TAG, "Privacy hide title set to: " + enabled);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set privacy hide title", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setNotificationPrivacyHideContent": {
                        // V3.2: 设置隐藏通知内容
                        try {
                            boolean enabled = (boolean) call.argument("enabled");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putBoolean("notification_privacy_hide_content", enabled)
                                .apply();
                            
                            // 通知NotificationService重新加载设置
                            sendBroadcast(new Intent(BuildConfig.APPLICATION_ID + ".RELOAD_NOTIFICATION_SETTINGS").setPackage(getPackageName()));
                            
                            Log.d(TAG, "Privacy hide content set to: " + enabled);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set privacy hide content", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setFollowDndMode": {
                        // V3.0: 设置跟随系统勿扰模式
                        try {
                            boolean enabled = (boolean) call.argument("enabled");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putBoolean("notification_follow_dnd_mode", enabled)
                                .apply();
                            Log.d(TAG, "Follow DND mode set to: " + enabled);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set follow DND mode", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setOnlyWhenLocked": {
                        // V3.0: 设置仅倒扣手机时通知
                        try {
                            boolean enabled = (boolean) call.argument("enabled");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putBoolean("notification_only_when_locked", enabled)
                                .apply();
                            // 立即同步通知监听服务的传感器注册状态。
                            sendBroadcast(new Intent(BuildConfig.APPLICATION_ID + ".RELOAD_NOTIFICATION_SETTINGS").setPackage(getPackageName()));
                            Log.d(TAG, "Only when upside down mode set to: " + enabled);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set only when upside down mode", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setNotificationDarkMode": {
                        // V3.1: 设置通知暗夜模式
                        try {
                            boolean enabled = (boolean) call.argument("enabled");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putBoolean("notification_dark_mode", enabled)
                                .apply();
                            Log.d(TAG, "Notification dark mode set to: " + enabled);
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set notification dark mode", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    case "setNotificationDuration": {
                        // V3.4: 设置通知自动销毁时间
                        try {
                            int duration = (int) call.argument("duration");
                            SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                            prefs.edit()
                                .putInt("notification_duration", duration)
                                .apply();
                            Log.d(TAG, "Notification duration set to: " + duration + " seconds");
                            result.success(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set notification duration", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }
                    
                    default:
                        result.notImplemented();
                }
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
    
    /**
     * V2.4: 检查通知监听服务是否已启用
     */
    private boolean isNotificationListenerEnabled() {
        String packageName = getPackageName();
        String flat = android.provider.Settings.Secure.getString(
            getContentResolver(),
            "enabled_notification_listeners"
        );
        
        if (flat == null || flat.isEmpty()) {
            return false;
        }
        
        String[] names = flat.split(":");
        for (String name : names) {
            android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(name);
            if (cn != null && packageName.equals(cn.getPackageName())) {
                return true;
            }
        }
        return false;
    }
}
