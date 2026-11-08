package top.zainzh.rearscreen;

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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * V3.5: 未投放应用时常亮服务
 * 以100ms间隔持续发送KEYCODE_WAKEUP唤醒背屏
 * ⚠️ 警告：可能导致烧屏和额外耗电
 */
public class AlwaysWakeUpService extends Service {
    private static final String TAG = "AlwaysWakeUpService";
    private static final int NOTIFICATION_ID = 1001; // 与其他Service共用ID
    private static final int WAKEUP_INTERVAL_MS = 100; // 100ms间隔
    private static volatile AlwaysWakeUpService instance;
    private static volatile String activeModuleLeaseId;
    
    private volatile boolean taskServiceReleased;
    private volatile ITaskService taskService;
    private Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private volatile boolean isRunning = false;
    private SharedPreferences prefs;
    private volatile boolean moduleLeaseSuspended;
    private volatile String moduleLeaseId;
    private volatile boolean sceneLeaseSuspended;
    private volatile String sceneLeaseId;
    
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            taskService = ITaskService.Stub.asInterface(service);
            Log.d(TAG, "✓ TaskService connected");
            
            // TaskService连接后开始发送wakeup
            startWakeupLoop();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        String leaseId = ModuleDisplayRecoveryService.activeLeaseId();
        if (leaseId == null) leaseId = activeModuleLeaseId;
        if (leaseId != null) activeModuleLeaseId = leaseId;
        if (leaseId != null && ModuleDisplayRecoveryService.isLeaseCurrent(leaseId)) {
            moduleLeaseSuspended = true;
            moduleLeaseId = leaseId;
        }
        String sceneLease = SceneLegacyServiceLease.activeLeaseId();
        if (sceneLease != null) {
            sceneLeaseSuspended = true;
            sceneLeaseId = sceneLease;
        }
        Log.d(TAG, "📱 onCreate");
        
        prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
        wakeupHandler = new Handler(Looper.getMainLooper());
        
        // 创建前台通知
        createForegroundNotification();
        
        // 绑定TaskService
        bindTaskService();
    }
    
    private void bindTaskService() {
        if (!taskServiceReleased) TaskServiceManager.acquire(this, taskServiceConnection);
    }
    
    private void createForegroundNotification() {
        String channelId = "mrss_core_service";
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                "ReMRSS内核服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("ReMRSS目前正在运行");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
        
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }
        
        Notification notification = builder
            .setContentTitle("ReMRSS内核服务")
            .setContentText("ReMRSS目前正在运行")
            .setSmallIcon(R.drawable.ic_stat_mrss)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
        
        startForeground(NOTIFICATION_ID, notification);
        Log.d(TAG, "✓ 前台服务已启动");
    }
    
    private void startWakeupLoop() {
        if (prefs == null || !prefs.getBoolean("always_wakeup_enabled", false)) {
            stopWakeupLoop();
            stopSelf();
            return;
        }
        String sceneId = SceneLegacyServiceLease.activeLeaseId();
        if (sceneId != null) {
            suspendForSceneLease(sceneId);
            return;
        }
        if (!LegacyDisplayKeeperPolicy.mayWake(
                prefs != null && prefs.getBoolean("always_wakeup_enabled", false),
                moduleLeaseSuspended, sceneLeaseSuspended, ModuleDisplayRecoveryService.hasActiveLease(),
                SceneLegacyServiceLease.hasActiveLease())) {
            suspendForCurrentModuleLease();
            return;
        }
        if (isRunning) {
            Log.w(TAG, "⚠️ Wakeup loop already running");
            return;
        }
        
        isRunning = true;
        
        wakeupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                
                // 检查开关状态
                boolean enabled = prefs.getBoolean("always_wakeup_enabled", false);
                if (!enabled) {
                    Log.d(TAG, "开关已关闭，停止wakeup循环");
                    stopSelf();
                    return;
                }

                String sceneId = SceneLegacyServiceLease.activeLeaseId();
                if (sceneId != null) {
                    suspendForSceneLease(sceneId);
                    return;
                }
                if (!LegacyDisplayKeeperPolicy.mayWake(
                        prefs != null && prefs.getBoolean("always_wakeup_enabled", false),
                        moduleLeaseSuspended, sceneLeaseSuspended, ModuleDisplayRecoveryService.hasActiveLease(),
                        SceneLegacyServiceLease.hasActiveLease())) {
                    if (SceneLegacyServiceLease.activeLeaseId() != null) {
                        suspendForSceneLease(SceneLegacyServiceLease.activeLeaseId());
                        return;
                    }
                    suspendForCurrentModuleLease();
                    return;
                }
                
                // 发送wakeup命令
                try {
                    if (taskService != null) {
                        taskService.executeShellCommand("input -d " + RearDisplay.requireId() + " keyevent KEYCODE_WAKEUP");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "发送wakeup失败: " + t.getMessage());
                }
                
                // 100ms后继续
                wakeupHandler.postDelayed(this, WAKEUP_INTERVAL_MS);
            }
        };
        
        // 立即开始
        wakeupHandler.post(wakeupRunnable);
        Log.d(TAG, "✓ Wakeup loop started (100ms interval)");
    }
    
    private void stopWakeupLoop() {
        isRunning = false;
        if (wakeupHandler != null && wakeupRunnable != null) {
            wakeupHandler.removeCallbacks(wakeupRunnable);
        }
        Log.d(TAG, "✓ Wakeup loop stopped");
    }

    private void suspendForActiveModuleLease() {
        moduleLeaseSuspended = true;
        stopWakeupLoop();
    }

    private void suspendForCurrentModuleLease() {
        String leaseId = ModuleDisplayRecoveryService.activeLeaseId();
        if (leaseId != null) {
            activeModuleLeaseId = leaseId;
            moduleLeaseId = leaseId;
        }
        suspendForActiveModuleLease();
    }

    /** Pause only the existing instance; do not start the service for a module lease. */
    public static void suspendForModuleLease(String leaseId) {
        if (leaseId == null || !ModuleDisplayRecoveryService.isLeaseCurrent(leaseId)) return;
        activeModuleLeaseId = leaseId;
        AlwaysWakeUpService service = instance;
        if (service == null || service.wakeupHandler == null) return;
        // Gate the runnable immediately; callback removal is thread-safe and must not wait
        // behind a wakeup already queued on the main looper.
        service.moduleLeaseSuspended = true;
        service.moduleLeaseId = leaseId;
        service.stopWakeupLoop();
        service.wakeupHandler.post(() -> {
            if (instance != service || !ModuleDisplayRecoveryService.isLeaseCurrent(leaseId)
                    || !leaseId.equals(service.moduleLeaseId)) return;
            service.stopWakeupLoop();
        });
    }

    /** Resume only the same still-running instance after the matching lease is gone. */
    public static void resumeAfterModuleLease(String leaseId) {
        AlwaysWakeUpService service = instance;
        if (leaseId == null || !leaseId.equals(activeModuleLeaseId)) return;
        if (service == null || service.wakeupHandler == null) {
            if (!ModuleDisplayRecoveryService.hasActiveLease() && leaseId.equals(activeModuleLeaseId)) {
                activeModuleLeaseId = null;
            }
            return;
        }
        if (!leaseId.equals(service.moduleLeaseId)) {
            if (!ModuleDisplayRecoveryService.hasActiveLease() && leaseId.equals(activeModuleLeaseId)) {
                activeModuleLeaseId = null;
            }
            return;
        }
        service.wakeupHandler.post(() -> {
            if (instance != service || ModuleDisplayRecoveryService.hasActiveLease()
                    || !leaseId.equals(service.moduleLeaseId) || !leaseId.equals(activeModuleLeaseId)) return;
            service.moduleLeaseSuspended = false;
            service.moduleLeaseId = null;
            activeModuleLeaseId = null;
            boolean enabled = service.prefs != null && service.prefs.getBoolean("always_wakeup_enabled", false);
            if (enabled && service.taskService != null) service.startWakeupLoop();
        });
    }

    /** Pause only the already-running global keeper for this exact Scene lease. */
    public static void suspendForSceneLease(String leaseId) {
        if (!SceneLegacyServiceLease.isCurrent(leaseId)) return;
        AlwaysWakeUpService service = instance;
        if (service == null || service.wakeupHandler == null) return;
        service.sceneLeaseSuspended = true;
        service.sceneLeaseId = leaseId;
        service.stopWakeupLoop();
    }

    /** Restore the existing instance only after the matching Scene releases ownership. */
    public static void resumeAfterSceneLease(String leaseId, boolean restorePriorMode) {
        AlwaysWakeUpService service = instance;
        if (service == null || service.wakeupHandler == null || !leaseId.equals(service.sceneLeaseId)) return;
        service.wakeupHandler.post(() -> {
            if (instance != service || !leaseId.equals(service.sceneLeaseId)
                    || SceneLegacyServiceLease.hasActiveLease() || ModuleDisplayRecoveryService.hasActiveLease()) return;
            boolean matchingLease = leaseId.equals(service.sceneLeaseId);
            service.sceneLeaseId = null;
            service.sceneLeaseSuspended = false;
            boolean enabled = LegacyDisplayKeeperPolicy.mayRestoreAfterScene(restorePriorMode,
                    service.prefs != null && service.prefs.getBoolean("always_wakeup_enabled", false),
                    matchingLease, SceneLegacyServiceLease.hasActiveLease(),
                    ModuleDisplayRecoveryService.hasActiveLease());
            if (enabled && service.taskService != null) service.startWakeupLoop();
        });
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        if (ModuleDisplayRecoveryService.hasActiveLease()) {
            suspendForCurrentModuleLease();
        }
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        taskServiceReleased = true;
        Log.d(TAG, "🔴 onDestroy");
        
        stopWakeupLoop();
        
        // 解绑TaskService
        TaskServiceManager.release(taskServiceConnection);
        taskService = null;

        if (instance == this) instance = null;
        
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

