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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * 通知监听服务
 * 监听系统通知，将选中应用的通知显示到背屏
 */
public class NotificationService extends NotificationListenerService {
    private static final String TAG = "NotificationService";
    // Android 自动分组汇总标记（该标记未公开为 SDK 常量）。
    private static final int FLAG_AUTOGROUP_SUMMARY = 0x00000400;
    private static final int NOTIFICATION_ID = 1001; // 与其他Service共用ID
    
    private Set<String> selectedApps = new HashSet<>();
    private boolean privacyHideTitle = false; // V3.2: 隐私模式 - 隐藏标题
    private boolean privacyHideContent = false; // V3.2: 隐私模式 - 隐藏内容
    private boolean followDndMode = true; // 跟随系统勿扰模式（默认开启）
    private boolean onlyWhenLocked = false; // 仅倒扣手机时通知（默认关闭）
    private boolean notificationDarkMode = false; // 通知暗夜模式（默认关闭）
    private boolean serviceEnabled = false; // 服务是否启用
    private volatile long disabledNotificationSessionId = Long.MIN_VALUE;
    private volatile boolean taskServiceReleased;
    private volatile ITaskService taskService; // 自己的TaskService实例
    private SharedPreferences prefs;
    private final java.util.concurrent.ExecutorService animationWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final class OwnedNotificationRender {
        final RearAnimationManager.Session session;
        final String packageName, title, text;
        final long expiresAtElapsedRealtimeMs;
        final top.zainzh.rearscreen.platform.notifications.NotificationRenderListener listener;
        final OwnedNotificationActivityReset activityReset;
        volatile boolean cancelled, covered;
        volatile boolean prepared;
        boolean launchScheduled;
        boolean restored;
        OwnedNotificationRender(RearAnimationManager.Session session, String packageName, String title, String text,
                long expiresAtElapsedRealtimeMs,
                top.zainzh.rearscreen.platform.notifications.NotificationRenderListener listener) {
            this.session = session; this.packageName = packageName; this.title = title; this.text = text;
            this.expiresAtElapsedRealtimeMs = expiresAtElapsedRealtimeMs; this.listener = listener;
            this.activityReset = new OwnedNotificationActivityReset(session.id, expiresAtElapsedRealtimeMs);
        }
    }
    
    // 主屏接近传感器相关
    private SensorManager sensorManager;
    private Sensor mainProximitySensor; // 主屏接近传感器
    private boolean mainProximityRegistered;
    private volatile boolean isMainScreenCovered = false; // 主屏是否被遮盖
    
    // 静态实例，供外部访问
    private static NotificationService instance;
    private final java.util.concurrent.ConcurrentHashMap<Long, OwnedNotificationRender> ownedNotificationRenders =
            new java.util.concurrent.ConcurrentHashMap<>();
    
    public static ITaskService getTaskService() {
        return instance != null ? instance.taskService : null;
    }

    /** Reads the existing notification sensor state without registering or keeping a sensor active. */
    public static boolean isMainScreenCoveredForNotifications() {
        NotificationService service = instance;
        return service != null && service.isMainScreenCovered;
    }
    
    // 广播接收器：监听设置重新加载
    private BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ((BuildConfig.APPLICATION_ID + ".RELOAD_NOTIFICATION_SETTINGS").equals(intent.getAction())) {
                Log.d(TAG, "🔄 收到重新加载设置的广播");
                loadNotificationServiceSettings(); // 重新加载开关状态
                loadSettings(); // 重新加载其他设置
                top.zainzh.rearscreen.platform.RearCapabilityCore.get(NotificationService.this).onNotificationGlobalPolicyChanged();
            } else if (android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(intent.getAction())) {
                top.zainzh.rearscreen.platform.RearCapabilityCore.get(NotificationService.this).onNotificationGlobalPolicyChanged();
            }
        }
    };
    
    // TaskService连接
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.d(TAG, "✓ TaskService connected");
            taskService = ITaskService.Stub.asInterface(binder);
            schedulePendingOwnedRenders();
            
            // 初始化显示屏信息缓存
            try {
                DisplayInfoCache.getInstance().initialize(taskService);
            } catch (Exception e) {
                Log.w(TAG, "初始化显示屏缓存失败: " + e.getMessage());
            }
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🟢 NotificationService created");
        
        // 保存实例
        instance = this;
        
        // 初始化SharedPreferences
        prefs = getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        
        // 注册广播接收器（监听设置变化）
        IntentFilter filter = new IntentFilter(BuildConfig.APPLICATION_ID + ".RELOAD_NOTIFICATION_SETTINGS");
        filter.addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        androidx.core.content.ContextCompat.registerReceiver(this, settingsReceiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        Log.d(TAG, "✓ 广播接收器已注册");

        // 绑定TaskService
        bindTaskService();
        
        // V2.4: 加载通知服务开关状态
        Log.d(TAG, "🔧 开始加载通知服务开关状态...");
        loadNotificationServiceSettings();
        Log.d(TAG, "🔧 通知服务开关状态加载完成: " + serviceEnabled);
        
        // 启动为前台服务，防止被系统杀死
        startForeground(NOTIFICATION_ID, RearScreenKeeperService.createServiceNotification(this));
        Log.d(TAG, "✓ 前台服务已启动");
        
        loadSettings();
        updateMainProximitySensorRegistration();
    }
    
    private void bindTaskService() {
        if (!taskServiceReleased) TaskServiceManager.acquire(this, taskServiceConnection);
    }
    
    /**
     * 加载通知服务开关状态
     */
    private void loadNotificationServiceSettings() {
        try {
            Log.d(TAG, "🔧 开始读取FlutterSharedPreferences...");
            // 从FlutterSharedPreferences读取开关状态
            SharedPreferences flutterPrefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            Log.d(TAG, "🔧 FlutterSharedPreferences读取成功");
            
            serviceEnabled = flutterPrefs.getBoolean("flutter.notification_service_enabled", false);
            Log.d(TAG, "🔧 通知服务开关状态已恢复: " + serviceEnabled);
            
            // NotificationListenerService由系统管理，不能手动停止
            // 如果开关关闭，服务仍会运行但不处理通知
            if (!serviceEnabled) {
                Log.d(TAG, "⏸️ 通知服务已禁用，将忽略所有通知");
                closeNotificationDisplaysForServiceOff();
            } else {
                Log.d(TAG, "✅ 通知服务已启用，将处理通知");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ 加载通知服务设置失败", e);
            serviceEnabled = false; // 默认关闭
        }
    }

    static void postNotificationFinishForSession(Context context, Handler handler, long sessionId) {
        if (sessionId == Long.MIN_VALUE) return;
        handler.post(() -> context.sendBroadcast(new Intent(BuildConfig.APPLICATION_ID + ".FINISH_NOTIFICATION_ANIMATION")
                .setPackage(context.getPackageName()).putExtra(RearAnimationManager.EXTRA_SESSION_ID, sessionId)));
    }

    void closeNotificationDisplaysForServiceOff() {
        try { top.zainzh.rearscreen.platform.RearCapabilityCore.get(this).onNotificationGlobalPolicyChanged(); }
        catch (Exception ignored) { }
        long sessionId = RearAnimationManager.captureSessionId(RearAnimationManager.AnimationType.NOTIFICATION);
        disabledNotificationSessionId = sessionId;
        postNotificationFinishForSession(this, mainHandler, sessionId);
        RearCastNotification.clearNotification(RearCastNotification.captureNotificationToken());
        unregisterMainProximitySensor();
    }

    static void disableForUser(Context context) {
        NotificationService active = instance;
        if (active != null) {
            active.serviceEnabled = false;
            active.closeNotificationDisplaysForServiceOff();
            return;
        }
        long sessionId = RearAnimationManager.captureSessionId(RearAnimationManager.AnimationType.NOTIFICATION);
        postNotificationFinishForSession(context, new Handler(Looper.getMainLooper()), sessionId);
        RearCastNotification.clearNotification(RearCastNotification.captureNotificationToken());
    }

    static void enableForUser(Context context) {
        NotificationService active = instance;
        if (active == null) return;
        active.loadNotificationServiceSettings();
        active.loadSettings();
    }

    void closeOwnedDisplaysOnDestroy() {
        if (instance != this) return;
        closeNotificationDisplaysForServiceOff();
        for (Long token : new java.util.ArrayList<>(ownedNotificationRenders.keySet()))
            finishOwnedNotificationRender(token, "SERVICE_STOPPED", true);
        instance = null;
    }
    
    private void loadSettings() {
        try {
            selectedApps = prefs.getStringSet("notification_selected_apps", new HashSet<>());
            privacyHideTitle = prefs.getBoolean("notification_privacy_hide_title", false);
            privacyHideContent = prefs.getBoolean("notification_privacy_hide_content", false);
            followDndMode = prefs.getBoolean("notification_follow_dnd_mode", true);
            onlyWhenLocked = prefs.getBoolean("notification_only_when_locked", false);
            notificationDarkMode = prefs.getBoolean("notification_dark_mode", false);
            // 注意：不在这里重新设置 serviceEnabled，保持 loadNotificationServiceSettings() 的值
            
            Log.d(TAG, "⚙️ 已加载设置");
            Log.d(TAG, "   - 启用状态: " + serviceEnabled + " (由loadNotificationServiceSettings设置)");
            Log.d(TAG, "   - 选中应用: " + selectedApps.size() + " 个");
            Log.d(TAG, "   - 隐藏标题: " + privacyHideTitle);
            Log.d(TAG, "   - 隐藏内容: " + privacyHideContent);
            
            if (!selectedApps.isEmpty()) {
                Log.d(TAG, "📋 选中应用列表: " + selectedApps.toString());
            } else {
                Log.w(TAG, "⚠️ 没有选中任何应用");
            }
            updateMainProximitySensorRegistration();
        } catch (Exception e) {
            Log.e(TAG, "加载设置失败", e);
            selectedApps = new HashSet<>();
            // 不在这里重置 serviceEnabled
        }
    }
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        
        // V2.4: 每次收到通知时重新加载开关状态
        loadNotificationServiceSettings();
        
        // V2.4: 如果通知服务开关关闭，不处理通知
        if (!serviceEnabled) {
            Log.d(TAG, "⏸️ 通知服务已禁用，忽略通知");
            return;
        }
        
        try {
            String packageName = sbn.getPackageName();
            Notification notification = sbn.getNotification();
            
            Log.d(TAG, "📢 收到通知: " + packageName);
            
            // 忽略常驻通知
            if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                Log.d(TAG, "⏭️ 忽略常驻通知: " + packageName);
                return;
            }
            
            // 忽略自己的通知
            if (packageName.equals(getPackageName())) {
                Log.d(TAG, "⏭️ 忽略自己的通知");
                return;
            }
            
            // 每次都重新加载设置（确保实时生效）
            loadSettings();
            
            // 检查服务是否启用
            if (!serviceEnabled) {
                Log.d(TAG, "⏭️ 通知服务未启用，跳过");
                return;
            }
            
            // 检查系统勿扰模式
            if (followDndMode) {
                try {
                    android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null && nm.getCurrentInterruptionFilter() != android.app.NotificationManager.INTERRUPTION_FILTER_ALL) {
                        Log.d(TAG, "⏭️ 系统勿扰模式已开启，跳过通知动画");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "检查勿扰模式失败: " + e.getMessage());
                }
            }
            
            // 检查是否仅倒扣手机时通知（检测主屏接近传感器）
            if (onlyWhenLocked) {
                if (!isMainScreenCovered) {
                    Log.d(TAG, "⏭️ 主屏未被遮盖，仅倒扣手机通知模式已开启，跳过");
                    return;
                }
            }
            
            Log.d(TAG, "📋 当前选中应用数量: " + selectedApps.size());
            Log.d(TAG, "📋 选中应用列表: " + selectedApps.toString());
            
            // 检查是否在选中列表中
            if (!selectedApps.contains(packageName)) {
                Log.d(TAG, "⏭️ 应用不在选中列表中: " + packageName);
                return;
            }

            if ((notification.flags & FLAG_AUTOGROUP_SUMMARY) != 0) {
                Log.d(TAG, "⏭️ 忽略系统自动分组汇总通知");
                return;
            }
            
            Log.d(TAG, "✓ 应用在选中列表中: " + packageName);
            
            // 提取通知内容
            String title = notification.extras.getString(Notification.EXTRA_TITLE, "");
            String text = notification.extras.getString(Notification.EXTRA_TEXT, "");
            long when = notification.when;
            
            Log.d(TAG, "📝 通知标题: " + title);
            Log.d(TAG, "📝 通知内容: " + text);
            
            // V3.2: 隐私模式处理（区分标题和内容）
            if (privacyHideTitle) {
                Log.d(TAG, "🔒 隐藏通知标题");
                title = getString(R.string.privacy_mode_enabled);
            }
            if (privacyHideContent) {
                Log.d(TAG, "🔒 隐藏通知内容");
                text = getString(R.string.new_message_placeholder);
            }
            
            Log.d(TAG, "🚀 开始显示背屏通知: " + packageName);
            
            // 触发背屏通知显示
            showNotificationOnRearScreen(packageName, title, text, when);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ 处理通知时出错", e);
        }
    }
    
    /** Positive tokens identify physical animation sessions; negative tokens identify virtual capsules. */
    public static final long OWNED_RENDER_DEFERRED = Long.MIN_VALUE;
    public static Long offerOwnedNotification(String packageName, String title, String text, long expiresAtElapsedRealtimeMs,
            top.zainzh.rearscreen.platform.notifications.NotificationRenderListener listener) {
        NotificationService service = instance;
        if (service == null || service.taskServiceReleased || !service.serviceEnabled) return null;
        if (android.os.SystemClock.elapsedRealtime() >= expiresAtElapsedRealtimeMs) return null;

        // A virtual source notification/charging capsule is a higher-level owner too; the cast
        // target atomically reports deferred instead of replacing its message.
        long capsule = RearCastNotification.offerOwned(packageName, title, text, expiresAtElapsedRealtimeMs,
                service.prefs.getBoolean("notification_dark_mode", false), new RearCastNotification.Listener() {
                    @Override public void onDisplayed() { listener.onDisplayed(); }
                    @Override public void onEnded(String reason) { listener.onEnded(reason); }
                });
        if (capsule == RearCastNotification.OWNED_DEFERRED) return OWNED_RENDER_DEFERRED;
        if (capsule != 0) return capsule;

        RearAnimationManager.Session session = RearAnimationManager.beginOwnedNotificationIfIdle();
        if (session == null) return OWNED_RENDER_DEFERRED;
        OwnedNotificationRender render = new OwnedNotificationRender(session, packageName, title, text,
                expiresAtElapsedRealtimeMs, listener);
        service.ownedNotificationRenders.put(session.id, render);
        service.disabledNotificationSessionId = Long.MIN_VALUE;
        service.scheduleOwnedRender(render);
        return session.id;
    }

    public static void cancelOwnedNotification(long token, String reason) {
        if (token == OWNED_RENDER_DEFERRED || token == 0) return;
        if (token < 0) { RearCastNotification.clearNotification(-token, reason); return; }
        NotificationService service = instance;
        if (service != null) service.invalidateOwnedNotificationRender(token, reason, false);
    }

    /** Suspend an owned handle under a higher-priority owned item without terminating its TTL. */
    public static void suspendOwnedNotification(long token) {
        if (token < 0) { RearCastNotification.suspendOwnedNotification(-token); return; }
        if (token <= 0) return;
        NotificationService service = instance;
        if (service == null) return;
        OwnedNotificationRender render = service.ownedNotificationRenders.get(token);
        if (render == null || render.cancelled || render.covered) return;
        render.covered = true;
        boolean current = RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.NOTIFICATION, token);
        postNotificationFinishForSession(service, service.mainHandler, token);
        if (current) service.disabledNotificationSessionId = token;
    }

    /** Retires a stale physical token immediately before the router issues its resumed render. */
    public static void abandonOwnedNotification(long token) {
        if (token <= 0) return;
        NotificationService service = instance;
        if (service == null) return;
        OwnedNotificationRender render = service.ownedNotificationRenders.remove(token);
        if (render != null) render.cancelled = true;
    }

    public static Long resumeOwnedCapsule(long token, long expiresAtElapsedRealtimeMs) {
        if (android.os.SystemClock.elapsedRealtime() >= expiresAtElapsedRealtimeMs) return null;
        long result = RearCastNotification.resumeOwnedNotification(token);
        if (result == RearCastNotification.OWNED_DEFERRED) return OWNED_RENDER_DEFERRED;
        return result == 0 ? null : result;
    }

    public static void ownedNotificationDisplayed(long sessionId, int displayId, boolean displayOnAtDraw,
                                                  long drawEventUptime) {
        NotificationService service = instance;
        if (service == null) return;
        OwnedNotificationRender render = service.ownedNotificationRenders.get(sessionId);
        if (render == null || render.cancelled || render.covered ||
                android.os.SystemClock.elapsedRealtime() >= render.expiresAtElapsedRealtimeMs ||
                !RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.NOTIFICATION, sessionId)) return;
        try {
            service.animationWorker.execute(() -> {
                int selectedRearDisplayId = RearDisplay.id();
                long now = android.os.SystemClock.elapsedRealtime();
                OwnedNotificationActivityReset.Result result = render.activityReset.afterDraw(
                        sessionId, () -> service.isOwnedNotificationRenderCurrent(render), true,
                        displayId, selectedRearDisplayId, displayOnAtDraw, now, drawEventUptime,
                        (targetDisplayId, targetSessionId, deadline, eventUptime) -> {
                            ITaskService taskService = service.taskService;
                            ISceneTaskActionGate currentGate = new ISceneTaskActionGate.Stub() {
                                @Override public boolean isCurrent() {
                                    return service.isOwnedNotificationRenderCurrent(render);
                                }
                            };
                            return taskService != null && taskService.reportOwnedNotificationActivity(
                                    targetDisplayId, targetSessionId, deadline, eventUptime, currentGate);
                        });
                Log.i(TAG, "owned_activity_refresh session=" + sessionId + " display=" + displayId
                        + " draw_uptime=" + drawEventUptime + " worker_uptime=" + android.os.SystemClock.uptimeMillis()
                        + " result=" + result.name().toLowerCase(java.util.Locale.ROOT));
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            Log.i(TAG, "owned_activity_refresh session=" + sessionId + " display=" + displayId + " result=worker_unavailable");
        }
        render.listener.onDisplayed();
    }

    public static boolean isOwnedNotificationRenderCurrent(long sessionId) {
        NotificationService service = instance;
        if (service == null) return false;
        OwnedNotificationRender render = service.ownedNotificationRenders.get(sessionId);
        return render != null && service.isOwnedNotificationRenderCurrent(render);
    }

    public static void ownedNotificationEnded(long sessionId, String reason) {
        NotificationService service = instance;
        if (service == null) return;
        OwnedNotificationRender render = service.ownedNotificationRenders.get(sessionId);
        if (render == null || render.cancelled) return;
        if ("COVERED".equals(reason)) {
            if (!render.covered) {
                render.covered = true;
                render.listener.onEnded(reason);
            }
            return;
        }
        if (render.covered) return;
        if (service.ownedNotificationRenders.remove(sessionId, render)) {
            render.cancelled = true;
            render.listener.onEnded(reason);
        }
    }

    private boolean isOwnedNotificationRenderCurrent(OwnedNotificationRender render) {
        return !render.cancelled && !render.covered && ownedNotificationRenders.get(render.session.id) == render &&
                !taskServiceReleased && serviceEnabled &&
                android.os.SystemClock.elapsedRealtime() < render.expiresAtElapsedRealtimeMs &&
                RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.NOTIFICATION, render.session.id);
    }

    private void schedulePendingOwnedRenders() {
        for (OwnedNotificationRender render : ownedNotificationRenders.values()) scheduleOwnedRender(render);
    }

    private void scheduleOwnedRender(OwnedNotificationRender render) {
        synchronized (render) {
            if (render.launchScheduled || render.cancelled || taskService == null ||
                    ownedNotificationRenders.get(render.session.id) != render) return;
            render.launchScheduled = true;
        }
        try {
            animationWorker.execute(() -> RearAnimationManager.runDisplayAction(() -> launchOwnedNotification(render)));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            finishOwnedNotificationRender(render.session.id, "RENDER_FAILED", true);
        }
    }

    private void finishOwnedNotificationRender(long sessionId, String reason, boolean report) {
        OwnedNotificationRender render = ownedNotificationRenders.remove(sessionId);
        if (render == null) return;
        render.cancelled = true;
        boolean current = RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.NOTIFICATION, sessionId);
        postNotificationFinishForSession(this, mainHandler, sessionId);
        if (current) restoreOwnedPreparation(render, taskService != null ? taskService : ChargingService.getTaskService());
        if (report) render.listener.onEnded(reason);
    }

    private void invalidateOwnedNotificationRender(long sessionId, String reason, boolean report) {
        finishOwnedNotificationRender(sessionId, reason, report);
    }

    private void launchOwnedNotification(OwnedNotificationRender render) {
        ITaskService service = null;
        boolean prepared = false;
        long sessionId = render.session.id;
        try {
            if (!isOwnedNotificationRenderCurrent(render)) { reportOwnedInvalidation(render); return; }
            if (taskService == null) {
                synchronized (render) { render.launchScheduled = false; }
                bindTaskService();
                return;
            }
            if (!isOwnedNotificationRenderCurrent(render)) { reportOwnedInvalidation(render); return; }
            service = taskService;
            if (service == null) throw new IllegalStateException("TaskService unavailable");
            acquireWakeLock(6000);
            RearScreenKeeperService.pauseMonitoring();
            prepared = true;
            RearAnimationDisplay.prepare(service, render.session, getPackageName());
            render.prepared = true;
            if (!isOwnedNotificationRenderCurrent(render)) { reportOwnedInvalidation(render); restoreOwnedPreparation(render, service); return; }
            String component = getPackageName() + "/" + RearScreenNotificationActivity.class.getName();
            String command = "am start --display " + RearDisplay.requireId() + " -n " + component
                    + " --es packageName " + RearAnimationDisplay.quote(render.packageName)
                    + " --es title " + RearAnimationDisplay.quote(render.title)
                    + " --es text " + RearAnimationDisplay.quote(render.text)
                    + " --el when " + android.os.SystemClock.uptimeMillis()
                    + " --ez darkMode " + prefs.getBoolean("notification_dark_mode", false)
                    + " --ez ownedNotification true"
                    + " --el expiresAtElapsedRealtimeMs " + render.expiresAtElapsedRealtimeMs
                    + " --ei rearTaskId " + render.session.rearTaskId
                    + " --el " + RearAnimationManager.EXTRA_SESSION_ID + " " + sessionId;
            if (!isOwnedNotificationRenderCurrent(render)) { reportOwnedInvalidation(render); restoreOwnedPreparation(render, service); return; }
            boolean started = service.executeShellCommand(command);
            if (!isOwnedNotificationRenderCurrent(render)) { reportOwnedInvalidation(render); restoreOwnedPreparation(render, service); return; }
            if (!started || !RearAnimationDisplay.awaitForeground(service, component, getPackageName(),
                    () -> isOwnedNotificationRenderCurrent(render))) {
                if (!isOwnedNotificationRenderCurrent(render)) { reportOwnedInvalidation(render); restoreOwnedPreparation(render, service); return; }
                throw new IllegalStateException("Notification is not foreground on rear display");
            }
            Log.i(TAG, "owned_launch_verified display_id=" + RearDisplay.requireId() + " session=" + sessionId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (isOwnedNotificationRenderCurrent(render))
                finishOwnedNotificationRender(sessionId, "RENDER_FAILED", true);
            else reportOwnedInvalidation(render);
            restoreOwnedPreparation(render, service);
        } catch (Exception error) {
            if (isOwnedNotificationRenderCurrent(render)) {
                Log.e(TAG, "owned_launch_failed session=" + sessionId, error);
                finishOwnedNotificationRender(sessionId, "RENDER_FAILED", true);
                restoreOwnedPreparation(render, service);
            } else {
                reportOwnedInvalidation(render);
                restoreOwnedPreparation(render, service);
            }
        } finally { releaseWakeLock(); }
    }

    private void reportOwnedInvalidation(OwnedNotificationRender render) {
        if (render.cancelled || ownedNotificationRenders.get(render.session.id) != render) return;
        if (android.os.SystemClock.elapsedRealtime() >= render.expiresAtElapsedRealtimeMs) {
            finishOwnedNotificationRender(render.session.id, "TIMEOUT", true);
        } else if (!render.covered && !RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.NOTIFICATION, render.session.id)) {
            ownedNotificationEnded(render.session.id, "COVERED");
            postNotificationFinishForSession(this, mainHandler, render.session.id);
        }
    }

    private void restoreOwnedPreparation(OwnedNotificationRender render, ITaskService service) {
        if (service == null || !render.prepared || !RearAnimationManager.isIdle(render.session.id) || render.session.rearTaskId <= 0) return;
        synchronized (render) {
            if (render.restored) return;
            render.restored = true;
        }
        ChargingService.restoreAfterAnimation(service, render.session.rearTaskId, render.session.id);
    }

    void showNotificationOnRearScreen(String packageName, String title, String text, long when) {
        // The existing host can present a passive capsule without changing display or input ownership.
        if (!RearAnimationManager.isAnimationPlaying() && RearCastNotification.offer(packageName, title, text,
                android.os.SystemClock.elapsedRealtime(), prefs.getInt("notification_duration", 10),
                prefs.getBoolean("notification_dark_mode", false))) return;
        RearAnimationManager.Session session = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        disabledNotificationSessionId = Long.MIN_VALUE;
        if (session.previous == RearAnimationManager.AnimationType.CHARGING) {
            RearAnimationManager.markInterruptedChargingAsAlwaysOn(prefs.getBoolean("charging_always_on_enabled", false));
            ChargingService.cancelForNotification();
            RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.CHARGING);
        }
        // 同类通知由onNewIntent换代，避免旧广播/固定延时关闭新窗口。
        animationWorker.execute(() -> RearAnimationManager.runDisplayAction(() ->
                launchNotification(session, packageName, title, text, when)));
    }

    private boolean isCurrent(RearAnimationManager.Session session) {
        return !taskServiceReleased && !isDisabledNotificationSession(session)
                && RearAnimationManager.isCurrent(session.type, session.id);
    }

    private boolean isDisabledNotificationSession(RearAnimationManager.Session session) {
        return session.type == RearAnimationManager.AnimationType.NOTIFICATION
                && disabledNotificationSessionId == session.id;
    }

    private void endDisabledNotificationBeforeLaunch(RearAnimationManager.Session session, boolean prepared, ITaskService service) {
        if (!isDisabledNotificationSession(session) || !RearAnimationManager.endAnimation(session.type, session.id)) return;
        if (prepared && service != null) ChargingService.restoreAfterAnimation(service, session.rearTaskId, session.id);
    }

    private void launchNotification(RearAnimationManager.Session session, String packageName, String title, String text, long when) {
        ITaskService service = null;
        boolean prepared = false;
        try {
            if (!isCurrent(session)) { endDisabledNotificationBeforeLaunch(session, false, null); return; }
            if (taskService == null) bindTaskService();
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (taskService == null && isCurrent(session) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            service = taskService;
            if (!isCurrent(session)) { endDisabledNotificationBeforeLaunch(session, false, service); return; }
            if (service == null) throw new IllegalStateException("TaskService unavailable");
            acquireWakeLock(6000);
            RearScreenKeeperService.pauseMonitoring();
            prepared = true;
            RearAnimationDisplay.prepare(service, session, getPackageName());
            if (!isCurrent(session)) { endDisabledNotificationBeforeLaunch(session, true, service); return; }
            String component = getPackageName() + "/" + RearScreenNotificationActivity.class.getName();
            String command = "am start --display " + RearDisplay.requireId() + " -n " + component
                    + " --es packageName " + RearAnimationDisplay.quote(packageName)
                    + " --es title " + RearAnimationDisplay.quote(title)
                    + " --es text " + RearAnimationDisplay.quote(text)
                    + " --el when " + when
                    + " --ez darkMode " + prefs.getBoolean("notification_dark_mode", false)
                    + " --ez ownedNotification false"
                    + " --ei rearTaskId " + session.rearTaskId
                    + " --el " + RearAnimationManager.EXTRA_SESSION_ID + " " + session.id;
            if (!service.executeShellCommand(command)
                    || !RearAnimationDisplay.awaitForeground(service, component, getPackageName(), () -> isCurrent(session))) {
                throw new IllegalStateException("Notification is not foreground on rear display");
            }
            Log.i(TAG, "launch_verified display_id=" + RearDisplay.requireId() + " session=" + session.id);
        } catch (Exception error) {
            Log.e(TAG, "launch_failed session=" + session.id, error);
            sendBroadcast(new Intent(BuildConfig.APPLICATION_ID + ".FINISH_NOTIFICATION_ANIMATION")
                    .setPackage(getPackageName()).putExtra(RearAnimationManager.EXTRA_SESSION_ID, session.id));
            if (RearAnimationManager.endAnimation(session.type, session.id) && prepared && service != null) {
                ChargingService.restoreRearScreen(service, session.rearTaskId, session.id);
            }
        } finally { releaseWakeLock(); }
    }

    private void acquireWakeLock(long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReMRSS:NotificationWake");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(timeoutMs);
                    Log.d(TAG, "🔒 PARTIAL_WAKE_LOCK acquired for " + timeoutMs + "ms");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to acquire wakelock: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "🔓 PARTIAL_WAKE_LOCK released");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to release wakelock: " + t.getMessage());
        }
    }
    
    /**
     * 初始化主屏接近传感器（用于检测倒扣手机）
     */
    private void initMainProximitySensor() {
        try {
            if (!serviceEnabled || !onlyWhenLocked || mainProximityRegistered) return;
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            
            if (sensorManager != null) {
                // 获取所有传感器列表
                java.util.List<Sensor> allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
                
                // 查找主屏接近传感器（不包含"Back"的接近传感器）
                // 优先选择 Wakeup 版本，如果没有则选择 Non-wakeup 版本
                Sensor wakeupSensor = null;
                Sensor nonWakeupSensor = null;
                
                for (Sensor sensor : allSensors) {
                    String name = sensor.getName();
                    if (name.contains("Proximity") && !name.contains("Back")) {
                        // 主屏接近传感器（不包含Back）
                        if (name.contains("Wakeup")) {
                            wakeupSensor = sensor;
                        } else {
                            nonWakeupSensor = sensor;
                        }
                    }
                }
                
                // 优先使用 Wakeup 版本
                if (wakeupSensor != null) {
                    mainProximitySensor = wakeupSensor;
                } else if (nonWakeupSensor != null) {
                    mainProximitySensor = nonWakeupSensor;
                    Log.w(TAG, "→ Using NON-WAKEUP main proximity sensor");
                }
                
                // 如果找不到主屏传感器，回退到默认传感器
                if (mainProximitySensor == null) {
                    Log.w(TAG, "⚠ Main proximity sensor not found, using default");
                    mainProximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                }
                
                if (mainProximitySensor != null) {
                    // 注册传感器监听器
                    boolean registered = sensorManager.registerListener(
                            proximitySensorListener,
                            mainProximitySensor,
                            SensorManager.SENSOR_DELAY_NORMAL);
                    
                    if (registered) {
                        mainProximityRegistered = true;
                        Log.d(TAG, "✅ 主屏接近传感器已注册");
                    } else {
                        Log.w(TAG, "⚠ Failed to register main proximity sensor");
                    }
                } else {
                    Log.w(TAG, "⚠ No main proximity sensor available");
                }
            } else {
                Log.w(TAG, "⚠ SensorManager not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error initializing main proximity sensor", e);
        }
    }
    
    /**
     * 注销主屏接近传感器
     */
    private void unregisterMainProximitySensor() {
        try {
            if (!mainProximityRegistered) {
                isMainScreenCovered = false;
                return;
            }
            if (sensorManager != null && proximitySensorListener != null) {
                sensorManager.unregisterListener(proximitySensorListener);
                Log.d(TAG, "✓ 主屏接近传感器已注销");
            }
            mainProximityRegistered = false;
            isMainScreenCovered = false;
        } catch (Exception e) {
            Log.e(TAG, "✗ Error unregistering main proximity sensor", e);
        }
    }

    private void updateMainProximitySensorRegistration() {
        if (serviceEnabled && onlyWhenLocked) initMainProximitySensor();
        else unregisterMainProximitySensor();
    }
    
    /**
     * 主屏接近传感器监听器
     */
    private final SensorEventListener proximitySensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor == mainProximitySensor) {
                float distance = event.values[0];
                float maxRange = mainProximitySensor.getMaximumRange();
                
                // 当距离接近0（被覆盖）时触发
                // 小于最大距离的20%视为覆盖
                boolean isCovered = (distance < maxRange * 0.2f);
                
                isMainScreenCovered = isCovered;
                
                if (isCovered) {
                    Log.d(TAG, "📱 主屏接近传感器：被遮盖 (距离: " + distance + " cm)");
                } else {
                    Log.d(TAG, "📱 主屏接近传感器：未遮盖 (距离: " + distance + " cm)");
                }
            }
        }
        
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // 不需要处理
        }
    };
    
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "🔗 NotificationListener connected");
        loadSettings();
        Log.d(TAG, "✓ 通知监听器已就绪");
    }
    
    @Override
    public void onDestroy() {
        closeOwnedDisplaysOnDestroy();
        taskServiceReleased = true;
        animationWorker.shutdown();
        super.onDestroy();
        Log.d(TAG, "🔴 NotificationService destroyed");
        
        // 注销广播接收器
        try {
            unregisterReceiver(settingsReceiver);
            Log.d(TAG, "✓ 广播接收器已注销");
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister receiver", e);
        }

        // 解绑TaskService
        TaskServiceManager.release(taskServiceConnection);
        taskService = null;
        
        // 注销主屏接近传感器
        unregisterMainProximitySensor();
        
        stopForeground(true);
    }
}

