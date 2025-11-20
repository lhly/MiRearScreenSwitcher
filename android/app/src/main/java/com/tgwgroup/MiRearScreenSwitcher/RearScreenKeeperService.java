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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.List;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import rikka.shizuku.Shizuku;

/**
 * 前台Service - 保持背屏常亮
 * 
 * 为什么用Service而不是Activity：
 * - Activity方案失败了3次（FLAG_NOT_FOCUSABLE、屏幕外、alpha=0都会被onStop）
 * - Service不会被onPause/onStop，系统很难杀死前台Service
 * - 可以直接持有WakeLock保持屏幕常亮
 * 
 * 注意：WakeLock可能会让两个屏幕都保持常亮（无法指定特定display）
 */
public class RearScreenKeeperService extends Service implements SensorEventListener {
    private static final String TAG = "RearScreenKeeperService";
    private static final String CHANNEL_ID = "rear_screen_keeper";
    private static final int NOTIFICATION_ID = 10001;

    private static RearScreenKeeperService instance = null;
    private PowerManager.WakeLock wakeLock;
    private Handler handler;
    private ITaskService taskService = null;

    // V12.3: 初始杀进程策略 - 只杀1次，不持续监控
    private static final int INITIAL_KILL_COUNT = 1; // 初始杀1次
    private static final long KILL_INTERVAL_MS = 200; // 每次间隔200ms

    // V12.1: 接近传感器监听
    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private boolean isProximityCovered = false;
    private long lastProximityTime = 0;
    private static final long PROXIMITY_DEBOUNCE_MS = 1500; // 防抖动：1500ms内连续覆盖才触发（降低灵敏度）

    // V2.2: 接近传感器开关状态
    private boolean proximitySensorEnabled = true; // 默认启用

    // V14.5: 监听应用是否手动移回主屏
    private static final long CHECK_TASK_INTERVAL_MS = 2000; // 每2秒检查一次
    private String monitoredTaskInfo = null; // 格式: "packageName:taskId"

    // V2.3: 临时暂停监控（充电动画显示期间）
    private boolean monitoringPaused = false;

    // V2.4: 持续唤醒背屏（防止自动熄屏）
    private static final long WAKEUP_INTERVAL_MS = 100; // 持续发送，每0.1秒唤醒一次（对熄屏几乎无感）
    private boolean keepScreenOnEnabled = true; // 默认启用背屏常亮

    public static void pauseMonitoring() {
        if (instance != null) {
            instance.monitoringPaused = true;

            // ✅ 取消所有pending的检查任务
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                Log.d(TAG, "⏸️ Monitoring paused, all checks cancelled");
            } else {
                Log.d(TAG, "⏸️ Monitoring paused");
            }
        }
    }

    public static void resumeMonitoring() {
        if (instance != null) {
            instance.monitoringPaused = false;
            Log.d(TAG, "▶️ Monitoring resumed");

            // ✅ 延迟5秒后才开始检查，给投送app足够时间恢复到前台
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                instance.handler.postDelayed(instance.checkTaskRunnable, 5000);
                Log.d(TAG, "⏰ Next check scheduled in 5 seconds");
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // 创建通知渠道
        createNotificationChannel();

        // 创建Handler用于定时任务
        handler = new Handler(Looper.getMainLooper());

        // V2.2: 从SharedPreferences恢复传感器开关状态
        loadProximitySensorSetting();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // V14.6: 处理点击通知返回主屏的事件
        if (intent != null && "ACTION_RETURN_TO_MAIN".equals(intent.getAction())) {

            // 将监控的任务移回主屏
            if (monitoredTaskInfo != null && monitoredTaskInfo.contains(":") && taskService != null) {
                try {
                    String[] parts = monitoredTaskInfo.split(":");
                    String packageName = parts[0];
                    int taskId = Integer.parseInt(parts[1]);

                    // 获取应用名
                    String appName = getAppName(packageName);

                    taskService.moveTaskToDisplay(taskId, 0);

                    // 先移除前台通知
                    stopForeground(Service.STOP_FOREGROUND_REMOVE);

                    // 延迟显示Toast提示
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        Toast.makeText(this, appName + " 已返回主屏", Toast.LENGTH_SHORT).show();
                    }, 100);

                    // 停止服务
                    stopSelf();
                    return START_NOT_STICKY;

                } catch (Exception e) {
                    Log.w(TAG, "Failed to return task to main", e);
                }
            }
        }

        // V2.2: 处理接近传感器开关设置
        if (intent != null && "ACTION_SET_PROXIMITY_ENABLED".equals(intent.getAction())) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            proximitySensorEnabled = enabled;

            Log.d(TAG, "🔧 传感器开关状态已更新: " + enabled);

            // 如果关闭了传感器，且当前正在监听，则注销监听
            if (!enabled && sensorManager != null && proximitySensor != null) {
                sensorManager.unregisterListener(this);
                Log.d(TAG, "⏸️ 传感器监听器已注销");
            }
            // 如果打开了传感器，且当前没有监听，则注册监听
            else if (enabled && sensorManager != null && proximitySensor != null) {
                boolean registered = sensorManager.registerListener(this, proximitySensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
                if (registered) {
                    Log.d(TAG, "✅ 传感器监听器已注册");
                } else {
                    Log.w(TAG, "⚠ 传感器监听器注册失败");
                }
            }

            return START_STICKY;
        }

        // V2.5: 处理背屏常亮开关设置
        if (intent != null && "ACTION_SET_KEEP_SCREEN_ON_ENABLED".equals(intent.getAction())) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            keepScreenOnEnabled = enabled;

            Log.d(TAG, "🔆 背屏常亮开关已" + (enabled ? "开启" : "关闭"));

            // 如果关闭了常亮，停止发送WAKEUP
            if (!enabled && handler != null) {
                handler.removeCallbacks(wakeupRearScreenRunnable);
                Log.d(TAG, "⏸️ 背屏WAKEUP发送已停止");
            }
            // 如果打开了常亮，启动发送WAKEUP
            else if (enabled && handler != null) {
                handler.removeCallbacks(wakeupRearScreenRunnable);
                startRearScreenWakeup();
            }

            return START_STICKY;
        }

        try {
            // V14.7: 先从Intent获取要监控的任务信息
            if (intent != null) {
                String newMonitoredTask = intent.getStringExtra("lastMovedTask");
                if (newMonitoredTask != null) {
                    monitoredTaskInfo = newMonitoredTask;
                }
            }

            // V2.5: 从Intent获取背屏常亮开关状态
            if (intent != null) {
                keepScreenOnEnabled = intent.getBooleanExtra("keepScreenOnEnabled", true);
                Log.d(TAG, "🔆 背屏常亮开关状态: " + (keepScreenOnEnabled ? "开启" : "关闭"));
            }

            // V15.1: 立即显示通知，不等待其他操作
            Notification notification = buildNotification();
            startForeground(NOTIFICATION_ID, notification);

            // 在后台线程执行耗时操作，不阻塞通知显示
            new Thread(() -> {
                // 绑定Shizuku TaskService
                bindTaskService();

                // 初始化接近传感器
                initProximitySensor();
            }).start();

            // 2. 获取WakeLock保持屏幕常亮
            if (wakeLock == null || !wakeLock.isHeld()) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

                // 使用SCREEN_BRIGHT_WAKE_LOCK保持屏幕亮起
                // 注意：这会让屏幕保持亮起，但可能无法指定是哪个display
                wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK, // 移除ACQUIRE_CAUSES_WAKEUP避免唤醒主屏
                        "MRSS::RearScreenKeeper");

                // 持续持有WakeLock（不设置超时）
                wakeLock.acquire();

            } else {
            }

            // 3. V12.2: 初始杀进程（只杀几次，不持续监控）
            performInitialKills();

            // 4. V14.5: 启动定期检查任务
            if (monitoredTaskInfo != null) {
                startTaskMonitoring();
            }

            // 5. V2.5: 启动持续唤醒背屏（每0.5秒，根据开关状态）
            startRearScreenWakeup();

        } catch (Exception e) {
            Log.e(TAG, "✗ Error starting service", e);
        }

        // START_STICKY: 如果被系统杀死，会自动重启
        return START_STICKY;
    }

    /**
     * V15.2: 启动任务监听 - 检测应用是否在前台
     * 监控被投放到背屏的应用，如果不在前台了（被关闭或切换），自动停止服务并清除通知
     */
    private final Runnable checkTaskRunnable = new Runnable() {
        @Override
        public void run() {
            // V2.3: 如果监控已暂停（充电动画显示中），跳过本次检查
            if (monitoringPaused) {
                handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                return;
            }

            if (monitoredTaskInfo != null && taskService != null) {
                try {
                    // V15.2: 检查背屏(displayId=1)的前台应用是否还是我们监控的应用
                    String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);

                    // V2.3: 排除充电动画/通知动画（临时占用背屏，不应导致Service销毁）
                    if (rearForegroundApp != null && (rearForegroundApp.contains("RearScreenChargingActivity")
                            || rearForegroundApp.contains("RearScreenNotificationActivity"))) {
                        // 充电动画正在显示，跳过本次检查
                        handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                        return;
                    }

                    // 如果背屏前台应用不是我们监控的应用，说明它被关闭或切换了
                    if (rearForegroundApp == null || !rearForegroundApp.equals(monitoredTaskInfo)) {
                        // 应用不在背屏前台了（被关闭或切换），停止服务
                        stopForeground(Service.STOP_FOREGROUND_REMOVE);
                        stopSelf();
                        return;
                    }

                    // 继续监听
                    handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);

                } catch (Exception e) {
                    Log.w(TAG, "Task check failed: " + e.getMessage());
                    handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                }
            } else {
                handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
            }
        }
    };

    private void startTaskMonitoring() {
        if (monitoredTaskInfo != null && handler != null) {
            handler.postDelayed(checkTaskRunnable, CHECK_TASK_INTERVAL_MS);
        }
    }

    /**
     * V2.5: 持续唤醒背屏任务 - 每0.5秒发送WAKEUP，防止背屏自动熄屏
     */
    private final Runnable wakeupRearScreenRunnable = new Runnable() {
        @Override
        public void run() {
            // 检查开关状态
            if (keepScreenOnEnabled && taskService != null) {
                try {
                    // 向背屏(displayId=1)发送WAKEUP唤醒信号
                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    // Log.d(TAG, "✨ 背屏保活唤醒已发送"); // 注释掉以减少日志
                } catch (Exception e) {
                    Log.w(TAG, "背屏唤醒失败: " + e.getMessage());
                }
            }

            // 持续发送，每0.5秒执行一次
            if (keepScreenOnEnabled) {
                handler.postDelayed(this, WAKEUP_INTERVAL_MS);
            }
        }
    };

    private void startRearScreenWakeup() {
        if (handler != null && keepScreenOnEnabled) {
            // 立即执行第一次唤醒，然后开始持续发送
            handler.post(wakeupRearScreenRunnable);
            Log.d(TAG, "⏰ 背屏持续唤醒已启动 (0.5秒间隔)");
        }
    }

    /**
     * V12.3: 初始杀进程 - 只杀1次，不持续监控
     */
    private void performInitialKills() {

        for (int i = 0; i < INITIAL_KILL_COUNT; i++) {
            final int killNumber = i + 1;

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (taskService != null) {
                        try {
                            taskService.killLauncherProcess();
                        } catch (Exception e) {
                            Log.w(TAG, "⚠ Kill #" + killNumber + " failed: " + e.getMessage());
                        }
                    } else {
                        Log.w(TAG, "⚠ TaskService not available for kill #" + killNumber);
                    }

                    // 最后一次杀完后的总结
                    if (killNumber == INITIAL_KILL_COUNT) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                            }
                        }, 100);
                    }
                }
            }, i * KILL_INTERVAL_MS);
        }
    }

    /**
     * Shizuku TaskService连接回调
     */
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);

            // 取消重连任务（如果存在）
            if (handler != null) {
                handler.removeCallbacks(reconnectTaskServiceRunnable);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "⚠ TaskService disconnected - will attempt to reconnect");
            taskService = null;

            // 启动重连任务
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
                handler.postDelayed(this, 1000);
            } else {
            }
        }
    };

    /**
     * 安排TaskService重连
     */
    private void scheduleReconnectTaskService() {
        if (handler != null) {
            handler.postDelayed(reconnectTaskServiceRunnable, 300);
        }
    };

    /**
     * 绑定Shizuku TaskService
     */
    private void bindTaskService() {
        if (taskService != null) {
            return;
        }

        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(getPackageName(), TaskService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("task_service")
                    .debuggable(false)
                    .version(1);

            Shizuku.bindUserService(args, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to bind TaskService", e);
        }
    }

    /**
     * 解绑TaskService
     */
    private void unbindTaskService() {
        if (taskService != null) {
            try {
                Shizuku.unbindUserService(
                        new Shizuku.UserServiceArgs(
                                new ComponentName(getPackageName(), TaskService.class.getName()))
                                .daemon(false)
                                .processNameSuffix("task_service")
                                .debuggable(false)
                                .version(1),
                        taskServiceConnection,
                        true);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unbind TaskService", e);
            }
            taskService = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.w(TAG, "═══════════════════════════════════════");
        Log.w(TAG, "⚠ Service onDestroy called");

        // 立即移除前台通知
        stopForeground(Service.STOP_FOREGROUND_REMOVE);

        // 清理所有待执行的任务
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        // V12.2: 恢复并主动唤醒Launcher
        if (taskService != null) {
            try {

                // 1. 恢复Launcher（unsuspend）
                taskService.enableSubScreenLauncher();

                // 2. 短暂延迟，确保unsuspend生效
                Thread.sleep(300);

                // 3. 主动启动Launcher的Activity来唤醒它

            } catch (Exception e) {
                Log.w(TAG, "Failed to restore launcher", e);
            }
        }

        // 解绑TaskService
        unbindTaskService();

        // 释放WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // 注销接近传感器
        unregisterProximitySensor();

        instance = null;
        Log.w(TAG, "═══════════════════════════════════════");
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 不支持绑定
        return null;
    }

    /**
     * 创建通知渠道（Android 8.0+必需）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_kernel_service),
                    NotificationManager.IMPORTANCE_LOW // 低重要性，减少干扰
            );
            channel.setDescription("com.xiaomi.subscreencenter.SubScreenLauncher真是高高在上呢");
            channel.setShowBadge(false); // 不显示角标
            channel.enableLights(false); // 不闪烁LED
            channel.enableVibration(false); // 不振动

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);

        }
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

    /**
     * V2.4: 创建通用的Service前台通知（供多个Service共用）
     */
    public static Notification createServiceNotification(Context context) {
        // 创建通知渠道
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_kernel_service),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("com.xiaomi.subscreencenter.SubScreenLauncher真是高高在上呢");
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notif_kernel_service))
                .setContentText(context.getString(R.string.notif_mrss_running))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 构建前台通知
     */
    private Notification buildNotification() {
        // 获取应用名称
        String appName = "应用";

        if (monitoredTaskInfo != null && monitoredTaskInfo.contains(":")) {
            String packageName = monitoredTaskInfo.split(":")[0];
            appName = getAppName(packageName);
        } else {
            Log.w(TAG, "⚠ Invalid monitored task info: " + monitoredTaskInfo);
        }

        // 点击通知切换回主屏
        Intent returnIntent = new Intent(this, RearScreenKeeperService.class);
        returnIntent.setAction("ACTION_RETURN_TO_MAIN");
        PendingIntent pendingIntent = PendingIntent.getService(
                this, 0, returnIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(appName + " " + getString(R.string.notif_running_on_rear))
                .setContentText(getString(R.string.notif_click_to_return, appName))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW) // 低优先级
                .setOngoing(true) // 持续通知，不可滑动清除
                .setShowWhen(false) // 不显示时间
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 检查Service是否正在运行
     */
    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * 停止Service
     */
    public static void stop() {
        if (instance != null) {
            instance.stopSelf();
        }
    }

    // ========================================
    // 接近传感器相关方法
    // ========================================

    /**
     * 从SharedPreferences加载传感器开关状态
     */
    private void loadProximitySensorSetting() {
        try {
            SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            proximitySensorEnabled = prefs.getBoolean("flutter.proximity_sensor_enabled", true);
            Log.d(TAG, "🔧 传感器开关状态已恢复: " + proximitySensorEnabled);
        } catch (Exception e) {
            Log.e(TAG, "✗ 加载传感器设置失败", e);
            proximitySensorEnabled = true; // 默认启用
        }
    }

    /**
     * 初始化接近传感器（背屏接近传感器）
     */
    private void initProximitySensor() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

            if (sensorManager != null) {
                // 获取所有传感器列表
                List<Sensor> allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);

                // 查找背屏接近传感器（名称包含 "Back" 和 "Proximity"）
                // 优先选择 Wakeup 版本，如果没有则选择 Non-wakeup 版本
                Sensor wakeupSensor = null;
                Sensor nonWakeupSensor = null;

                for (Sensor sensor : allSensors) {
                    String name = sensor.getName();
                    if (name.contains("Proximity") && name.contains("Back")) {
                        if (name.contains("Wakeup")) {
                            wakeupSensor = sensor;
                        } else {
                            nonWakeupSensor = sensor;
                        }
                    }
                }

                // 优先使用 Wakeup 版本
                if (wakeupSensor != null) {
                    proximitySensor = wakeupSensor;
                } else if (nonWakeupSensor != null) {
                    proximitySensor = nonWakeupSensor;
                    Log.w(TAG, "→ Using NON-WAKEUP sensor (may not provide continuous data)");
                }

                // 如果找不到背屏传感器，回退到默认传感器
                if (proximitySensor == null) {
                    Log.w(TAG, "⚠ Rear proximity sensor not found, using default");
                    proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                }

                if (proximitySensor != null) {
                    // V2.2: 只有在传感器开关启用时才注册监听器
                    if (proximitySensorEnabled) {
                        boolean registered = sensorManager.registerListener(
                                this,
                                proximitySensor,
                                SensorManager.SENSOR_DELAY_NORMAL);

                        if (registered) {
                            Log.d(TAG, "✅ 接近传感器已注册 (开关状态: " + proximitySensorEnabled + ")");
                        } else {
                            Log.w(TAG, "⚠ Failed to register proximity sensor");
                        }
                    } else {
                        Log.d(TAG, "⏸️ 接近传感器已禁用，跳过注册");
                    }
                } else {
                    Log.w(TAG, "⚠ No proximity sensor available");
                }
            } else {
                Log.w(TAG, "⚠ SensorManager not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error initializing proximity sensor", e);
        }
    }

    /**
     * 注销接近传感器
     */
    private void unregisterProximitySensor() {
        try {
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error unregistering proximity sensor", e);
        }
    }

    /**
     * 传感器数据变化回调
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        // V2.2: 如果传感器已关闭，不处理事件
        if (!proximitySensorEnabled) {
            return;
        }

        // 检查是否是我们的背屏接近传感器
        if (event.sensor == proximitySensor) {
            float distance = event.values[0];
            float maxRange = proximitySensor.getMaximumRange();

            // 详细日志 - 每次传感器变化都记录

            // 当距离接近0（被覆盖）时触发
            boolean isCovered = (distance < maxRange * 0.2f); // 小于最大距离的20%视为覆盖

            long currentTime = System.currentTimeMillis();

            if (isCovered && !isProximityCovered) {
                // 从未覆盖到覆盖
                isProximityCovered = true;
                lastProximityTime = currentTime;

                Log.w(TAG, "👋 PROXIMITY COVERED! Distance: " + distance + " cm");
                Log.w(TAG, "👋 Starting debounce timer (" + PROXIMITY_DEBOUNCE_MS + "ms)...");

                // 防抖动：延迟检查
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isProximityCovered &&
                                (System.currentTimeMillis() - lastProximityTime >= PROXIMITY_DEBOUNCE_MS)) {
                            // 确认覆盖超过500ms，触发拉回主屏
                            Log.w(TAG, "👋 Debounce timer expired - triggering return to main display!");
                            handleProximityCovered();
                        } else {
                        }
                    }
                }, PROXIMITY_DEBOUNCE_MS);

            } else if (!isCovered && isProximityCovered) {
                // 从覆盖到未覆盖
                isProximityCovered = false;
            } else if (isCovered && isProximityCovered) {
                // 持续覆盖中
            } else {
                // 持续未覆盖
            }
        } else {
            // 其他传感器的数据，也记录一下
        }
    }

    /**
     * 传感器精度变化回调（不需要处理）
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要处理
    }

    /**
     * 处理接近传感器覆盖事件 - 拉回主屏并停止Service
     */
    private void handleProximityCovered() {
        Log.w(TAG, "═══════════════════════════════════════");
        Log.w(TAG, "🤚 PROXIMITY TRIGGER - Return to main display");
        Log.w(TAG, "═══════════════════════════════════════");

        try {
            if (taskService != null) {
                // 获取最后移动的任务信息
                String lastTask = SwitchToRearTileService.getLastMovedTask();

                if (lastTask != null && lastTask.contains(":")) {
                    String[] parts = lastTask.split(":");
                    String packageName = parts[0];
                    int taskId = Integer.parseInt(parts[1]);

                    // 获取应用名
                    String appName = getAppName(packageName);

                    // 拉回主屏
                    boolean success = taskService.moveTaskToDisplay(taskId, 0);

                    if (success) {
                        // 延迟显示Toast
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            Toast.makeText(RearScreenKeeperService.this, appName + " 已返回主屏", Toast.LENGTH_SHORT).show();
                        }, 100);
                    } else {
                        Log.w(TAG, "⚠ Failed to return task (may already be on main display)");
                    }
                } else {
                    Log.w(TAG, "⚠ No active rear screen task found");
                }

                // 先移除前台通知
                stopForeground(Service.STOP_FOREGROUND_REMOVE);

                // 停止Service（会自动恢复系统Launcher）
                stopSelf();

            } else {
                Log.w(TAG, "⚠ TaskService not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error handling proximity event", e);
        }
    }
}
