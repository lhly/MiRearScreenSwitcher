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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 监听插拔电源，在背屏显示充电动画。所有shell调用在同一后台队列执行。 */
public class ChargingService extends Service {
    private static final String TAG = "ChargingService";
    private static final int NOTIFICATION_ID = 1001;
    private static final long READY_TIMEOUT_MS = 5000;
    private static volatile ChargingService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private volatile ITaskService taskService;
    private volatile boolean destroyed;
    private boolean powerConnected;
    private volatile ChargingRequest activeRequest;
    private PowerManager.WakeLock wakeLock;
    private boolean wakeupRunning;
    private final Runnable readyCheck = this::executePending;

    private static final class ChargingRequest {
        final long id;
        final RearAnimationManager.Session session;
        final long createdAt = SystemClock.elapsedRealtime();
        final int level;
        volatile boolean cancelled;
        boolean executing; // 主线程状态
        int rearTaskId = -1; // 由启动线程读取并写入
        ChargingRequest(int level, RearAnimationManager.Session session) {
            this.level = level; this.session = session; this.id = session.id;
        }
    }

    public static ITaskService getTaskService() {
        ChargingService service = instance;
        return service != null ? service.taskService : null;
    }

    static boolean isRequestActive(long id) {
        ChargingService service = instance;
        ChargingRequest request = service != null ? service.activeRequest : null;
        return request != null && request.id == id && service.isCurrent(request);
    }

    private boolean isCurrent(ChargingRequest request) {
        return !destroyed && activeRequest == request && !request.cancelled
                && RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.CHARGING, request.id);
    }

    static boolean onAnimationFinished(long id) {
        ChargingService service = instance;
        if (service == null) return true;
        ChargingRequest request = service.activeRequest;
        if (request != null && request.id != id) return false;
        if (request != null && request.id == id) {
            request.cancelled = true;
            service.stopWakeupLoop();
        }
        return true;
    }

    static void cancelForNotification() {
        ChargingService service = instance;
        if (service != null) service.cancelRequest();
    }

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            Log.i(TAG, "TaskService connected");
            executePending();
        }
        @Override public void onServiceDisconnected(ComponentName name) { taskService = null; }
    };

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!prefs.getBoolean("charging_animation_enabled", true)) {
                cancelRequest();
                finishChargingAnimation(activeRequest != null ? activeRequest.id : 0);
            } else if (activeRequest != null && isCurrent(activeRequest)) {
                // 常亮开关只决定页面是否自动结束，播放期间都需要短时维持背屏。
                startWakeupLoop();
            }
        }
    };

    private final BroadcastReceiver resumeChargingReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // 通知结束后的恢复也要重新确认仍在充电，避免拔线后再次弹出。
            if (!isPluggedIn() || !prefs.getBoolean("charging_always_on_enabled", false)) return;
            handler.postDelayed(() -> {
                if (!destroyed && isPluggedIn()
                        && RearAnimationManager.getCurrentAnimation() == RearAnimationManager.AnimationType.NONE) {
                    requestChargingAnimation();
                }
            }, 300);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                // 忽略同一次连接的重复广播，真正拔线后立即允许重新插入。
                if (powerConnected && activeRequest != null && isCurrent(activeRequest)) return;
                powerConnected = true;
                requestChargingAnimation();
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                powerConnected = false;
                cancelRequest();
                RearAnimationManager.clearChargingAlwaysOnFlag();
                finishChargingAnimation(activeRequest != null ? activeRequest.id : 0);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
        powerConnected = isPluggedIn();
        IntentFilter powerFilter = new IntentFilter();
        powerFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        powerFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(batteryReceiver, powerFilter);
        androidx.core.content.ContextCompat.registerReceiver(this, settingsReceiver,
                new IntentFilter(BuildConfig.APPLICATION_ID + ".RELOAD_CHARGING_SETTINGS"),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        androidx.core.content.ContextCompat.registerReceiver(this, resumeChargingReceiver,
                new IntentFilter(BuildConfig.APPLICATION_ID + ".RESUME_CHARGING_ANIMATION"),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        startForeground(NOTIFICATION_ID, RearScreenKeeperService.createServiceNotification(this));
        TaskServiceManager.acquire(this, taskServiceConnection);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (taskService == null) TaskServiceManager.acquire(this, taskServiceConnection);
        return START_STICKY;
    }

    private boolean isPluggedIn() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private void requestChargingAnimation() {
        if (destroyed || !prefs.getBoolean("charging_animation_enabled", true)) return;
        cancelRequest();
        BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
        RearAnimationManager.Session session = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.CHARGING);
        ChargingRequest request = new ChargingRequest(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), session);
        activeRequest = request;
        RearAnimationManager.AnimationType previous = session.previous;
        if (previous == RearAnimationManager.AnimationType.NOTIFICATION) {
            RearAnimationManager.sendInterruptBroadcast(this, previous);
        }
        executePending();
    }

    private void cancelRequest() {
        handler.removeCallbacks(readyCheck);
        ChargingRequest request = activeRequest;
        if (request != null) {
            request.cancelled = true;
            if (!request.executing) RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.CHARGING, request.id);
        }
        stopWakeupLoop();
    }

    private void executePending() {
        ChargingRequest request = activeRequest;
        if (request == null || !isCurrent(request) || request.executing) return;
        ITaskService service = taskService;
        if (service == null) {
            if (SystemClock.elapsedRealtime() - request.createdAt >= READY_TIMEOUT_MS) {
                request.cancelled = true;
                RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.CHARGING, request.id);
                Log.w(TAG, "launch_failed reason=service_timeout request=" + request.id);
            } else {
                handler.postDelayed(readyCheck, 100);
            }
            return;
        }
        request.executing = true;
        worker.execute(() -> RearAnimationManager.runDisplayAction(() -> launchOnRearScreen(service, request)));
    }

    private void launchOnRearScreen(ITaskService service, ChargingRequest request) {
        boolean rearPrepared = false;
        boolean started = false;
        try {
            if (!isCurrent(request)) return;
            DisplayInfoCache.getInstance().initialize(service);
            if (!isCurrent(request)) return;
            RearScreenKeeperService.pauseMonitoring();
            acquireWakeLock();
            rearPrepared = true;
            RearAnimationDisplay.prepare(service, request.session, getPackageName());
            request.rearTaskId = request.session.rearTaskId;
            if (!isCurrent(request)) return;
            String component = getPackageName() + "/" + RearScreenChargingActivity.class.getName();
            String command = "am start --display 1 -n " + component
                    + " --ei batteryLevel " + request.level + " --ei rearTaskId " + request.rearTaskId
                    + " --el " + RearScreenChargingActivity.EXTRA_REQUEST_ID + " " + request.id;
            if (!service.executeShellCommand(command)) throw new IllegalStateException("am start failed");
            started = RearAnimationDisplay.awaitForeground(service, component, getPackageName(), () -> isCurrent(request));
            if (!started) throw new IllegalStateException("charging activity not on rear display");
            Log.i(TAG, "launch_verified display_id=1 request=" + request.id);
            handler.post(() -> {
                if (isCurrent(request)) startWakeupLoop();
            });
        } catch (Exception e) {
            Log.e(TAG, "launch_failed request=" + request.id, e);
        } finally {
            releaseWakeLock();
            if (!started || !isCurrent(request)) {
                finishChargingAnimation(request.id);
                if (activeRequest == request) {
                    request.cancelled = true;
                    RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.CHARGING, request.id);
                }
                if (rearPrepared) restoreRearScreen(service, request.rearTaskId, request.id);
            }
        }
    }

    private void finishChargingAnimation(long requestId) {
        Intent finish = new Intent(BuildConfig.APPLICATION_ID + ".FINISH_CHARGING_ANIMATION");
        finish.setPackage(getPackageName());
        finish.putExtra(RearScreenChargingActivity.EXTRA_REQUEST_ID, requestId);
        sendBroadcast(finish);
    }

    static void restoreRearScreen(ITaskService service, int taskId) {
        restoreRearScreen(service, taskId, RearAnimationManager.getGeneration());
    }

    static void restoreRearScreen(ITaskService service, int taskId, long sessionId) {
        if (!RearAnimationManager.isIdle(sessionId)) return;
        try {
            if (taskId > 0 && service.isTaskOnDisplay(taskId, 1)
                    && service.moveTaskToDisplay(taskId, 1)) {
                Log.i(TAG, "restored projected_task=" + taskId);
            } else {
                service.enableSubScreenLauncher();
                Log.i(TAG, "restored official_launcher");
            }
        } catch (Exception e) {
            Log.e(TAG, "restore_failed", e);
            try { service.enableSubScreenLauncher(); }
            catch (Exception fallback) { Log.w(TAG, "launcher_restore_failed", fallback); }
        } finally {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (RearAnimationManager.isIdle(sessionId)) RearScreenKeeperService.resumeMonitoring();
            });
        }
    }

    static void restoreAfterAnimation(ITaskService service, int taskId, long sessionId) {
        ChargingService owner = instance;
        if (owner != null && !owner.destroyed) {
            // 与下一次启动串行，防止旧页面的恢复操作抢占新动画。
            owner.worker.execute(() -> RearAnimationManager.runDisplayAction(() -> restoreRearScreen(service, taskId, sessionId)));
        } else {
            new Thread(() -> RearAnimationManager.runDisplayAction(() -> restoreRearScreen(service, taskId, sessionId)), "animation-restore").start();
        }
    }

    private final Runnable wakeupTick = new Runnable() {
        @Override public void run() {
            ChargingRequest request = activeRequest;
            if (!wakeupRunning || request == null || !isCurrent(request)) {
                stopWakeupLoop();
                return;
            }
            ITaskService service = taskService;
            worker.execute(() -> {
                if (service != null && isCurrent(request)) {
                    try { service.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP"); }
                    catch (Exception e) { Log.w(TAG, "wakeup_failed", e); }
                }
                handler.post(() -> {
                    if (!wakeupRunning || !isCurrent(request)) return;
                    BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
                    RearScreenChargingActivity.updateBatteryLevelStatic(
                            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                    handler.postDelayed(this, 100);
                });
            });
        }
    };

    private void startWakeupLoop() {
        if (wakeupRunning) return;
        wakeupRunning = true;
        Log.i(TAG, "display_hold_started");
        handler.post(wakeupTick);
    }

    private void stopWakeupLoop() {
        if (wakeupRunning) Log.i(TAG, "display_hold_stopped");
        wakeupRunning = false;
        handler.removeCallbacks(wakeupTick);
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReMRSS:ChargingWake");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(8000);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override public void onDestroy() {
        destroyed = true;
        cancelRequest();
        RearAnimationManager.clearChargingAlwaysOnFlag();
        finishChargingAnimation(activeRequest != null ? activeRequest.id : 0);
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(batteryReceiver);
        unregisterReceiver(settingsReceiver);
        unregisterReceiver(resumeChargingReceiver);
        TaskServiceManager.release(taskServiceConnection);
        worker.shutdown();
        if (instance == this) instance = null;
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
