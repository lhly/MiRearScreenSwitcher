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
    private static final OrdinaryNotificationSessionFinalizer ORDINARY_SESSION_FINALIZER = new OrdinaryNotificationSessionFinalizer();
    private volatile String activeOrdinaryNotificationKey;
    private volatile long activeOrdinaryNotificationPostTime;
    private volatile long activeOrdinaryNotificationSession = Long.MIN_VALUE;
    private volatile boolean activeOrdinaryNotificationPersistent;
    private volatile String activeOrdinaryNotificationPackage;
    private volatile String activeOrdinaryNotificationTitle;
    private volatile String activeOrdinaryNotificationText;
    private volatile long activeOrdinaryNotificationWhen;
    private final OrdinaryNotificationChargeReturn.Lease ordinaryChargeLease =
            new OrdinaryNotificationChargeReturn.Lease();
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
                boolean wasHideTitle = privacyHideTitle, wasHideContent = privacyHideContent;
                boolean wasPersistent = activeOrdinaryNotificationPersistent;
                loadNotificationServiceSettings(); // 重新加载开关状态
                loadSettings(); // 重新加载其他设置
                boolean renderPolicyChanged = wasHideTitle != privacyHideTitle || wasHideContent != privacyHideContent
                        || !selectedApps.contains(activeOrdinaryNotificationPackage)
                        || (onlyWhenLocked && !isMainScreenCovered)
                        || (followDndMode && isDndActive())
                        || (wasPersistent && !prefs.getBoolean("notification_never_auto_close", false));
                if (wasPersistent && renderPolicyChanged) finishTrackedPersistentNotification();
                top.zainzh.rearscreen.platform.RearCapabilityCore.get(NotificationService.this).onNotificationGlobalPolicyChanged();
            } else if (android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(intent.getAction())) {
                if (activeOrdinaryNotificationPersistent && followDndMode && isDndActive()) finishTrackedPersistentNotification();
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
        ordinaryChargeLease.clear();
        try { top.zainzh.rearscreen.platform.RearCapabilityCore.get(this).onNotificationGlobalPolicyChanged(); }
        catch (Exception ignored) { }
        long sessionId = RearAnimationManager.captureSessionId(RearAnimationManager.AnimationType.NOTIFICATION);
        long ordinarySession = activeOrdinaryNotificationSession;
        activeOrdinaryNotificationKey = null;
        activeOrdinaryNotificationPostTime = 0;
        activeOrdinaryNotificationSession = Long.MIN_VALUE;
        activeOrdinaryNotificationPersistent = false;
        activeOrdinaryNotificationPackage = null;
        activeOrdinaryNotificationTitle = null;
        activeOrdinaryNotificationText = null;
        activeOrdinaryNotificationWhen = 0;
        disabledNotificationSessionId = sessionId;
        if (ordinarySession != Long.MIN_VALUE) ORDINARY_SESSION_FINALIZER.requestFinish(ordinarySession);
        if (!ORDINARY_SESSION_FINALIZER.requestFinish(sessionId))
            postNotificationFinishForSession(this, mainHandler, sessionId);
        RearCastNotification.clearNotification(RearCastNotification.captureNotificationToken());
        unregisterMainProximitySensor();
    }

    private boolean isDndActive() {
        try {
            android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.getCurrentInterruptionFilter() != android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Exception ignored) { return false; }
    }

    private void finishTrackedPersistentNotification() {
        long expected = activeOrdinaryNotificationSession;
        ordinaryChargeLease.clear();
        activeOrdinaryNotificationKey = null;
        activeOrdinaryNotificationPostTime = 0;
        activeOrdinaryNotificationSession = Long.MIN_VALUE;
        activeOrdinaryNotificationPersistent = false;
        activeOrdinaryNotificationPackage = null;
        activeOrdinaryNotificationTitle = null;
        activeOrdinaryNotificationText = null;
        activeOrdinaryNotificationWhen = 0;
        if (expected != Long.MIN_VALUE) ORDINARY_SESSION_FINALIZER.requestFinish(expected);
    }

    private void clearPersistentNoticeSnapshot(long expectedSessionId) {
        if (activeOrdinaryNotificationSession != expectedSessionId) return;
        activeOrdinaryNotificationKey = null;
        activeOrdinaryNotificationPostTime = 0;
        activeOrdinaryNotificationSession = Long.MIN_VALUE;
        activeOrdinaryNotificationPersistent = false;
        activeOrdinaryNotificationPackage = null;
        activeOrdinaryNotificationTitle = null;
        activeOrdinaryNotificationText = null;
        activeOrdinaryNotificationWhen = 0;
    }

    static boolean ordinaryNotificationActivityAttached(long sessionId, Object window) {
        return ORDINARY_SESSION_FINALIZER.activityAttached(sessionId, window);
    }
    static void ordinaryNotificationActivityHandoff(long sessionId, Object window) {
        ORDINARY_SESSION_FINALIZER.activityHandoff(sessionId, window);
    }
    static void ordinaryNotificationActivityDetachedForRecreation(long sessionId, Object window) {
        ORDINARY_SESSION_FINALIZER.activityDetachedForRecreation(sessionId, window);
    }
    /** Called synchronously from real user controls, before Activity finish or app-launch work begins. */
    static void ordinaryNotificationUserHandled(long sessionId) {
        NotificationService service = instance;
        if (service != null && service.ordinaryChargeLease.userHandled(sessionId))
            service.clearPersistentNoticeSnapshot(sessionId);
    }

    static boolean ordinaryNotificationActivityDestroyed(long sessionId, Object window) {
        NotificationService service = instance;
        if (service != null) {
            if (service.ordinaryChargeLease.ordinaryActivityDestroyed(sessionId,
                    RearAnimationManager.currentChargingSessionId()))
                service.clearPersistentNoticeSnapshot(sessionId);
        }
        return ORDINARY_SESSION_FINALIZER.activityDestroyed(sessionId, window);
    }

    static void chargingSessionStarted(RearAnimationManager.Session session) {
        NotificationService service = instance;
        if (service == null || session == null) return;
        service.ordinaryChargeLease.chargingStarted(session.id, session.replacedSessionId,
                session.previous == RearAnimationManager.AnimationType.NOTIFICATION,
                session.previous == RearAnimationManager.AnimationType.CHARGING);
    }

    static boolean resumePersistentOrdinaryNotificationAfterCharging(long chargeSessionId, int returnTaskId) {
        NotificationService service = instance;
        if (service == null) return false;
        return OrdinaryNotificationChargeReturn.resumeAfterCharge(new OrdinaryNotificationChargeReturn.Host() {
            @Override public OrdinaryNotificationChargeReturn.Notice currentPersistentNotice(long expectedChargeSession) {
                OrdinaryNotificationChargeReturn.Notice notice = service.ordinaryChargeLease.noticeCoveredBy(expectedChargeSession);
                return service.matchesActiveNotice(notice) ? notice : null;
            }
            @Override public OrdinaryNotificationChargeReturn.Notice refreshForCurrentPolicy(
                    OrdinaryNotificationChargeReturn.Notice notice) {
                return service.resolveCurrentPersistentNotice(notice);
            }
            @Override public void discardIfCurrent(OrdinaryNotificationChargeReturn.Notice notice) {
                if (service.matchesActiveNotice(notice) || service.ordinaryChargeLease.isCurrent(notice))
                    service.finishTrackedPersistentNotification();
            }
            @Override public OrdinaryNotificationChargeReturn.ResumedSession beginAfterCharge(
                    OrdinaryNotificationChargeReturn.Notice notice, long expectedChargeSession, int taskId) {
                if (service.taskServiceReleased || !service.matchesActiveNotice(notice)
                        || !service.ordinaryChargeLease.isCurrent(notice)) return null;
                OrdinaryNotificationChargeReturn.Notice current = service.resolveCurrentPersistentNotice(notice);
                if (current == null) {
                    service.finishTrackedPersistentNotification();
                    return null;
                }
                RearAnimationManager.Session resumed = RearAnimationManager.beginPersistentNotificationDuringCharging(
                        expectedChargeSession, taskId);
                if (resumed == null) return null;
                service.activeOrdinaryNotificationSession = resumed.id;
                service.activeOrdinaryNotificationTitle = current.title;
                service.activeOrdinaryNotificationText = current.text;
                service.activeOrdinaryNotificationWhen = current.when;
                service.ordinaryChargeLease.resumed(notice, current, resumed.id);
                service.registerOrdinaryNotificationSession(resumed);
                return new OrdinaryNotificationChargeReturn.ResumedSession(resumed.id, resumed.rearTaskId,
                        current.withSessionId(resumed.id));
            }
            @Override public void launch(OrdinaryNotificationChargeReturn.Notice notice,
                    OrdinaryNotificationChargeReturn.ResumedSession resumedSession) {
                RearAnimationManager.Session resumed = new RearAnimationManager.Session(resumedSession.id,
                        RearAnimationManager.AnimationType.NOTIFICATION, RearAnimationManager.AnimationType.NONE,
                        resumedSession.returnTaskId);
                service.disabledNotificationSessionId = Long.MIN_VALUE;
                service.animationWorker.execute(() -> RearAnimationManager.runDisplayAction(() -> {
                    OrdinaryNotificationChargeReturn.Notice latest =
                            service.ordinaryChargeLease.noticeForSession(resumed.id);
                    OrdinaryNotificationChargeReturn.Notice current = service.resolveCurrentPersistentNotice(latest);
                    if (current == null) {
                        service.finishTrackedPersistentNotification();
                        ORDINARY_SESSION_FINALIZER.requestFinish(resumed.id);
                        ORDINARY_SESSION_FINALIZER.launchFinished(resumed.id);
                        return;
                    }
                    service.updatePersistentNoticeContent(current);
                    service.launchNotification(resumed, current.packageName, current.title, current.text,
                            current.when, current.key, current.postTime, true);
                }));
            }
        }, chargeSessionId, returnTaskId);
    }

    private boolean matchesActiveNotice(OrdinaryNotificationChargeReturn.Notice notice) {
        return notice != null && activeOrdinaryNotificationPersistent
                && activeOrdinaryNotificationSession == notice.sessionId
                && activeOrdinaryNotificationPostTime == notice.postTime
                && java.util.Objects.equals(activeOrdinaryNotificationKey, notice.key)
                && java.util.Objects.equals(activeOrdinaryNotificationPackage, notice.packageName);
    }

    private OrdinaryNotificationChargeReturn.Notice resolveCurrentPersistentNotice(
            OrdinaryNotificationChargeReturn.Notice notice) {
        if (!matchesActiveNotice(notice) || !ordinaryChargeLease.isCurrent(notice) || taskServiceReleased) return null;
        SharedPreferences currentSettings = getSharedPreferences("mrss_settings", MODE_PRIVATE);
        SharedPreferences flutterSettings = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
        PersistentNotificationResumePolicy.Snapshot currentPolicy = new PersistentNotificationResumePolicy.Snapshot(
                new PersistentNotificationResumePolicy.CurrentSettings() {
                    @Override public boolean serviceEnabled() {
                        return currentSettings.contains("notification_service_enabled")
                                ? currentSettings.getBoolean("notification_service_enabled", false)
                                : flutterSettings.getBoolean("flutter.notification_service_enabled", false);
                    }
                    @Override public boolean keepUntilRemoved() {
                        return currentSettings.contains("notification_never_auto_close")
                                ? currentSettings.getBoolean("notification_never_auto_close", false)
                                : flutterSettings.getBoolean("flutter.notification_never_auto_close", false);
                    }
                    @Override public boolean packageSelected(String packageName) {
                        return currentSettings.getStringSet("notification_selected_apps", java.util.Collections.emptySet())
                                .contains(packageName);
                    }
                    @Override public boolean onlyWhenLocked() {
                        return currentSettings.getBoolean("notification_only_when_locked", false);
                    }
                    @Override public boolean mainScreenCovered() { return isMainScreenCovered; }
                    @Override public boolean followDnd() {
                        return currentSettings.getBoolean("notification_follow_dnd_mode", true);
                    }
                    @Override public boolean dndActive() { return isDndActive(); }
                    @Override public boolean hideTitle() {
                        return currentSettings.getBoolean("notification_privacy_hide_title", false);
                    }
                    @Override public boolean hideContent() {
                        return currentSettings.getBoolean("notification_privacy_hide_content", false);
                    }
                }, notice.packageName);
        StatusBarNotification source = null;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return null;
            for (StatusBarNotification item : active) {
                if (notice.key.equals(item.getKey()) && notice.postTime == item.getPostTime()
                        && notice.packageName.equals(item.getPackageName())) {
                    source = item;
                    break;
                }
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "persistent_notification_resume_source_check_failed", failure);
            return null;
        }
        if (!PersistentNotificationResumePolicy.allows(notice, source != null, currentPolicy)) return null;
        android.app.Notification notification = source.getNotification();
        CharSequence rawTitle = notification != null && notification.extras != null
                ? notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) : null;
        CharSequence rawText = notification != null && notification.extras != null
                ? notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT) : null;
        return PersistentNotificationResumePolicy.renderCurrent(notice,
                rawTitle == null ? "" : rawTitle.toString(), rawText == null ? "" : rawText.toString(),
                notification == null ? 0L : notification.when, currentPolicy.hideTitle, currentPolicy.hideContent,
                getString(R.string.privacy_mode_enabled), getString(R.string.new_message_placeholder));
    }

    private void updatePersistentNoticeContent(OrdinaryNotificationChargeReturn.Notice notice) {
        if (!matchesActiveNotice(notice)) return;
        activeOrdinaryNotificationTitle = notice.title;
        activeOrdinaryNotificationText = notice.text;
        activeOrdinaryNotificationWhen = notice.when;
        ordinaryChargeLease.updateContent(notice);
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
            showNotificationOnRearScreen(packageName, title, text, when, sbn.getKey(), sbn.getPostTime());
            
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
            boolean locked = LegacyNotificationLaunchRoute.forKeyguard(isPrimaryKeyguardLocked())
                    == LegacyNotificationLaunchRoute.Route.HIDDEN_MAIN_THEN_REAR;
            int launchDisplay = locked ? 0 : RearDisplay.requireId();
            String command = "am start --display " + launchDisplay + " -n " + component
                    + " --es packageName " + RearAnimationDisplay.quote(render.packageName)
                    + " --es title " + RearAnimationDisplay.quote(render.title)
                    + " --es text " + RearAnimationDisplay.quote(render.text)
                    + " --el when " + android.os.SystemClock.uptimeMillis()
                    + " --ez darkMode " + prefs.getBoolean("notification_dark_mode", false)
                    + " --ez ownedNotification true"
                    + (locked ? " --ez " + RearAnimationDisplay.EXTRA_LOCKED_REAR_PLACEMENT + " true" : "")
                    + " --el expiresAtElapsedRealtimeMs " + render.expiresAtElapsedRealtimeMs
                    + " --ei rearTaskId " + render.session.rearTaskId
                    + " --el " + RearAnimationManager.EXTRA_SESSION_ID + " " + sessionId;
            if (!isOwnedNotificationRenderCurrent(render)) { reportOwnedInvalidation(render); restoreOwnedPreparation(render, service); return; }
            Intent activityIntent = new Intent(this, RearScreenNotificationActivity.class)
                    .putExtra("packageName", render.packageName).putExtra("title", render.title)
                    .putExtra("text", render.text).putExtra("when", android.os.SystemClock.uptimeMillis())
                    .putExtra("darkMode", prefs.getBoolean("notification_dark_mode", false))
                    .putExtra("ownedNotification", true)
                    .putExtra(RearAnimationDisplay.EXTRA_LOCKED_REAR_PLACEMENT, locked)
                    .putExtra("expiresAtElapsedRealtimeMs", render.expiresAtElapsedRealtimeMs)
                    .putExtra("rearTaskId", render.session.rearTaskId)
                    .putExtra(RearAnimationManager.EXTRA_SESSION_ID, sessionId);
            java.util.function.BooleanSupplier launchCurrent = () -> isOwnedNotificationRenderCurrent(render)
                    && (!locked || isPrimaryKeyguardLocked());
            int taskId = locked
                    ? startLockedNotificationTask(service, component, command, activityIntent, launchCurrent)
                    : (launchCurrent.getAsBoolean() && service.executeShellCommand(command) ? -2 : -1);
            if (!isOwnedNotificationRenderCurrent(render)) { reportOwnedInvalidation(render); restoreOwnedPreparation(render, service); return; }
            boolean foreground = taskId == -2 || (taskId > 0 && awaitLockedNotificationPresentation(service, taskId,
                    sessionId, launchCurrent));
            if (!foreground || !RearAnimationDisplay.awaitForeground(service, component, getPackageName(),
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
        showNotificationOnRearScreen(packageName, title, text, when, null, 0);
    }

    void showNotificationOnRearScreen(String packageName, String title, String text, long when, String notificationKey, long postTime) {
        // The existing host can present a passive capsule without changing display or input ownership.
        if (!RearAnimationManager.isAnimationPlaying() && RearCastNotification.offer(packageName, title, text,
                android.os.SystemClock.elapsedRealtime(), prefs.getInt("notification_duration", 10),
                prefs.getBoolean("notification_dark_mode", false))) return;
        RearAnimationManager.Session session = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        registerOrdinaryNotificationSession(session);
        activeOrdinaryNotificationKey = notificationKey;
        activeOrdinaryNotificationPostTime = postTime;
        activeOrdinaryNotificationSession = session.id;
        boolean keepUntilRemoved = NotificationDisplayOptions.shouldKeepUntilRemoved(
                prefs.getBoolean("notification_never_auto_close", false), notificationKey, postTime);
        activeOrdinaryNotificationPersistent = keepUntilRemoved;
        activeOrdinaryNotificationPackage = packageName;
        activeOrdinaryNotificationTitle = title;
        activeOrdinaryNotificationText = text;
        activeOrdinaryNotificationWhen = when;
        if (keepUntilRemoved) {
            ordinaryChargeLease.noticePresented(new OrdinaryNotificationChargeReturn.Notice(
                    notificationKey, postTime, packageName, title, text, when, session.id));
        } else {
            ordinaryChargeLease.clear();
        }
        disabledNotificationSessionId = Long.MIN_VALUE;
        if (session.previous == RearAnimationManager.AnimationType.CHARGING) {
            RearAnimationManager.markInterruptedChargingAsAlwaysOn(prefs.getBoolean("charging_always_on_enabled", false));
            ChargingService.cancelForNotification();
            RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.CHARGING);
        }
        // 同类通知由onNewIntent换代，避免旧广播/固定延时关闭新窗口。
        animationWorker.execute(() -> RearAnimationManager.runDisplayAction(() ->
                launchNotification(session, packageName, title, text, when, notificationKey, postTime, keepUntilRemoved)));
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn == null || !NotificationDisplayOptions.matchesRemoval(activeOrdinaryNotificationKey,
                activeOrdinaryNotificationPostTime, sbn.getKey(), sbn.getPostTime(),
                activeOrdinaryNotificationPersistent, activeOrdinaryNotificationSession)) return;
        long expected = activeOrdinaryNotificationSession;
        ordinaryChargeLease.sourceRemoved(sbn.getKey(), sbn.getPostTime());
        activeOrdinaryNotificationKey = null;
        activeOrdinaryNotificationPostTime = 0;
        activeOrdinaryNotificationSession = Long.MIN_VALUE;
        activeOrdinaryNotificationPersistent = false;
        activeOrdinaryNotificationPackage = null;
        activeOrdinaryNotificationTitle = null;
        activeOrdinaryNotificationText = null;
        activeOrdinaryNotificationWhen = 0;
        if (expected != Long.MIN_VALUE) ORDINARY_SESSION_FINALIZER.requestFinish(expected);
    }

    private boolean isCurrent(RearAnimationManager.Session session) {
        return !taskServiceReleased && !isDisabledNotificationSession(session)
                && RearAnimationManager.isCurrent(session.type, session.id);
    }

    private boolean isDisabledNotificationSession(RearAnimationManager.Session session) {
        return session.type == RearAnimationManager.AnimationType.NOTIFICATION
                && disabledNotificationSessionId == session.id;
    }

    private void endDisabledNotificationBeforeLaunch(RearAnimationManager.Session session) {
        if (!isDisabledNotificationSession(session)) return;
        ORDINARY_SESSION_FINALIZER.requestFinish(session.id);
    }

    private void registerOrdinaryNotificationSession(RearAnimationManager.Session session) {
        ORDINARY_SESSION_FINALIZER.register(session.id, new OrdinaryNotificationSessionFinalizer.Host() {
            @Override public boolean isCurrent(long sessionId) { return NotificationService.this.isCurrent(session); }
            @Override public boolean hasDifferentOwner(long sessionId) {
                RearAnimationManager.AnimationType owner = RearAnimationManager.getCurrentAnimation();
                return owner != RearAnimationManager.AnimationType.NONE && !RearAnimationManager.isCurrent(owner, sessionId);
            }
            @Override public boolean end(long sessionId) {
                return RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.NOTIFICATION, sessionId);
            }
            @Override public void finishActivity(long sessionId) {
                postNotificationFinishForSession(NotificationService.this, mainHandler, sessionId);
            }
            @Override public void restore(long sessionId) { restoreOrdinaryNotificationSession(session); }
        });
    }

    private void restoreOrdinaryNotificationSession(RearAnimationManager.Session session) {
        if (RearAnimationManager.shouldResumeChargingAnimation()) {
            RearAnimationManager.clearChargingAlwaysOnFlag();
            sendBroadcast(new Intent(BuildConfig.APPLICATION_ID + ".RESUME_CHARGING_ANIMATION").setPackage(getPackageName()));
            return;
        }
        ITaskService service = taskService != null ? taskService : ChargingService.getTaskService();
        if (service != null) ChargingService.restoreAfterAnimation(service, session.rearTaskId, session.id);
        top.zainzh.rearscreen.platform.RearCapabilityCore.get(this).onNotificationPresentationAvailable();
    }

    private void launchNotification(RearAnimationManager.Session session, String packageName, String title, String text, long when,
                                    String notificationKey, long postTime, boolean keepUntilRemoved) {
        ITaskService service = null;
        try {
            if (!isCurrent(session)) { endDisabledNotificationBeforeLaunch(session); return; }
            if (taskService == null) bindTaskService();
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (taskService == null && isCurrent(session) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            service = taskService;
            if (!isCurrent(session)) { endDisabledNotificationBeforeLaunch(session); return; }
            if (service == null) throw new IllegalStateException("TaskService unavailable");
            acquireWakeLock(6000);
            RearScreenKeeperService.pauseMonitoring();
            ORDINARY_SESSION_FINALIZER.markPrepared(session.id);
            RearAnimationDisplay.prepare(service, session, getPackageName());
            if (!isCurrent(session)) { endDisabledNotificationBeforeLaunch(session); return; }
            String displayTitle = title;
            String displayText = text;
            long displayWhen = when;
            if (keepUntilRemoved) {
                OrdinaryNotificationChargeReturn.Notice current = resolveCurrentPersistentNotice(
                        ordinaryChargeLease.noticeForSession(session.id));
                if (current == null) {
                    finishTrackedPersistentNotification();
                    return;
                }
                updatePersistentNoticeContent(current);
                displayTitle = current.title;
                displayText = current.text;
                displayWhen = current.when;
            }
            String component = getPackageName() + "/" + RearScreenNotificationActivity.class.getName();
            boolean locked = LegacyNotificationLaunchRoute.forKeyguard(isPrimaryKeyguardLocked())
                    == LegacyNotificationLaunchRoute.Route.HIDDEN_MAIN_THEN_REAR;
            Intent activityIntent = notificationActivityIntent(packageName, displayTitle, displayText, displayWhen, session, locked, notificationKey, postTime, keepUntilRemoved);
            String command = notificationActivityCommand(component, locked ? 0 : RearDisplay.requireId(),
                    packageName, displayTitle, displayText, displayWhen, session, locked, notificationKey, postTime, keepUntilRemoved);
            java.util.function.BooleanSupplier launchCurrent = () -> isCurrent(session)
                    && (!locked || isPrimaryKeyguardLocked());
            int taskId = locked
                    ? startLockedNotificationTask(service, component, command, activityIntent, launchCurrent)
                    : (launchCurrent.getAsBoolean() && service.executeShellCommand(command) ? -2 : -1);
            boolean ready = !locked || (taskId > 0 && awaitLockedNotificationPresentation(service, taskId,
                    session.id, launchCurrent));
            if (taskId == -1 || !ready || !RearAnimationDisplay.awaitForeground(service, component, getPackageName(), () -> isCurrent(session))) {
                throw new IllegalStateException("Notification is not foreground on rear display");
            }
            Log.i(TAG, "launch_verified display_id=" + RearDisplay.requireId() + " session=" + session.id);
        } catch (Exception error) {
            Log.e(TAG, "launch_failed session=" + session.id, error);
            ORDINARY_SESSION_FINALIZER.requestFinish(session.id);
        } finally {
            releaseWakeLock();
            ORDINARY_SESSION_FINALIZER.launchFinished(session.id);
        }
    }

    private boolean isPrimaryKeyguardLocked() {
        android.app.KeyguardManager keyguard = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    private int startLockedNotificationTask(ITaskService service, String component, String command,
                                            Intent activityIntent, java.util.function.BooleanSupplier current) throws Exception {
        NotificationIntentDelivery.Result delivery = RearScreenNotificationActivity.updateCurrentNotification(activityIntent);
        if (delivery.outcome == NotificationIntentDelivery.Outcome.TERMINATED)
            throw new IllegalStateException("notification session delivery was precisely terminated");
        if (delivery.outcome == NotificationIntentDelivery.Outcome.ACCEPTED) {
            int activeTask = delivery.taskId;
            if (activeTask <= 0) throw new IllegalStateException("accepted notification delivery has no task id");
            if (!current.getAsBoolean()) return -1;
            if (service.isTaskOnDisplay(activeTask, RearDisplay.requireId())) return current.getAsBoolean() ? activeTask : -1;
            return current.getAsBoolean() && service.moveTaskToDisplay(activeTask, RearDisplay.requireId())
                    && current.getAsBoolean() ? activeTask : -1;
        }
        if (!delivery.mayStartFreshPlaceholder())
            throw new IllegalStateException("notification session delivery is not safe to retry: " + delivery.outcome);
        return RearAnimationDisplay.startLockedPlaceholder(service, component, command,
                RearDisplay.requireId(), current);
    }

    private boolean awaitLockedNotificationPresentation(ITaskService service, int taskId, long sessionId,
                                                         java.util.function.BooleanSupplier current) throws Exception {
        for (int attempt = 0; attempt < 70 && current.getAsBoolean(); attempt++) {
            if (RearScreenNotificationActivity.isSessionPresentationReady(sessionId, taskId)
                    && service.isTaskOnDisplay(taskId, RearDisplay.requireId())) return true;
            Thread.sleep(50);
        }
        return false;
    }

    static long remainingOwnedNotificationTtlMs(long deadlineElapsedRealtimeMs, long nowElapsedRealtimeMs) {
        return Math.max(0L, deadlineElapsedRealtimeMs - nowElapsedRealtimeMs);
    }

    private String notificationActivityCommand(String component, int displayId, String packageName,
                                               String title, String text, long when,
                                               RearAnimationManager.Session session, boolean lockedPlaceholder,
                                               String notificationKey, long postTime, boolean keepUntilRemoved) {
        return "am start --display " + displayId + " -n " + component
                + " --es packageName " + RearAnimationDisplay.quote(packageName)
                + " --es title " + RearAnimationDisplay.quote(title)
                + " --es text " + RearAnimationDisplay.quote(text)
                + " --el when " + when
                + " --ez darkMode " + prefs.getBoolean("notification_dark_mode", false)
                + " --ez ownedNotification false"
                + " --ez keepUntilNotificationRemoved " + keepUntilRemoved
                + " --el notificationPostTime " + postTime
                + (notificationKey == null ? "" : " --es notificationKey " + RearAnimationDisplay.quote(notificationKey))
                + (lockedPlaceholder ? " --ez " + RearAnimationDisplay.EXTRA_LOCKED_REAR_PLACEMENT + " true" : "")
                + " --ei rearTaskId " + session.rearTaskId
                + " --el " + RearAnimationManager.EXTRA_SESSION_ID + " " + session.id;
    }

    private Intent notificationActivityIntent(String packageName, String title, String text, long when,
                                               RearAnimationManager.Session session, boolean lockedPlaceholder,
                                               String notificationKey, long postTime, boolean keepUntilRemoved) {
        return new Intent(this, RearScreenNotificationActivity.class)
                .putExtra("packageName", packageName)
                .putExtra("title", title)
                .putExtra("text", text)
                .putExtra("when", when)
                .putExtra("darkMode", prefs.getBoolean("notification_dark_mode", false))
                .putExtra("ownedNotification", false)
                .putExtra("keepUntilNotificationRemoved", keepUntilRemoved)
                .putExtra("notificationKey", notificationKey)
                .putExtra("notificationPostTime", postTime)
                .putExtra(RearAnimationDisplay.EXTRA_LOCKED_REAR_PLACEMENT, lockedPlaceholder)
                .putExtra("rearTaskId", session.rearTaskId)
                .putExtra(RearAnimationManager.EXTRA_SESSION_ID, session.id);
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
                if (!isCovered && onlyWhenLocked && activeOrdinaryNotificationPersistent)
                    finishTrackedPersistentNotification();
                
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
    
    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        closeNotificationDisplaysForServiceOff();
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

