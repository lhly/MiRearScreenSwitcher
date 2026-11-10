/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 *
 * Chief Tester: 汐木�? *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package top.zainzh.rearscreen;

import android.util.Log;

/**
 * 背屏动画管理器
 * 统一管理充电动画和通知动画，实现动画打断机制
 */
public class RearAnimationManager {
    private static final String TAG = "RearAnimationManager";
    
    // 动画类型
    public enum AnimationType {
        NONE,           // 无动画
        CHARGING,       // 充电动画
        NOTIFICATION    // 通知动画
    }
    
    static final String EXTRA_SESSION_ID = "rearAnimationSessionId";
    private static final DisplayActionGate DISPLAY_ACTION_GATE = new DisplayActionGate();
    private static long generation = System.nanoTime();
    private static volatile AnimationType currentAnimation = AnimationType.NONE;
    private static Session currentSession = new Session(generation, AnimationType.NONE, AnimationType.NONE, -1);
    private static volatile boolean interruptedChargingWasAlwaysOn;
    private static volatile long sceneOverlaySessionId = Long.MIN_VALUE;

    static final class Session {
        final long id;
        final AnimationType type, previous;
        final long replacedSessionId;
        volatile int rearTaskId;
        volatile int animationActivityTaskId = -1;
        Session(long id, AnimationType type, AnimationType previous, int rearTaskId) {
            this(id, type, previous, rearTaskId, Long.MIN_VALUE);
        }
        Session(long id, AnimationType type, AnimationType previous, int rearTaskId, long replacedSessionId) {
            this.id = id; this.type = type; this.previous = previous; this.rearTaskId = rearTaskId;
            this.replacedSessionId = replacedSessionId;
        }
    }

    static synchronized Session beginAnimation(AnimationType type) {
        if (type == AnimationType.NONE) throw new IllegalArgumentException("Cannot begin NONE");
        Session previousSession = currentSession;
        boolean replacesAnimation = currentAnimation != AnimationType.NONE;
        int inheritedRearTaskId = replacesAnimation ? previousSession.rearTaskId : -1;
        Session session = new Session(++generation, type, currentAnimation, inheritedRearTaskId,
                replacesAnimation ? previousSession.id : Long.MIN_VALUE);
        currentSession = session;
        currentAnimation = type;
        if (type == AnimationType.CHARGING) interruptedChargingWasAlwaysOn = false;
        sceneOverlaySessionId = top.zainzh.rearscreen.platform.RearCapabilityCore.beginTransientOverlay(
                session.id, replacesAnimation ? previousSession.id : Long.MIN_VALUE, inheritedRearTaskId > 0)
                ? session.id : Long.MIN_VALUE;
        RearInfoSession.animationChanged();
        RearModuleSession.INSTANCE.animationChanged();
        return session;
    }

    /** Owned notices may begin only when no legacy source notification or charging animation owns the display. */
    static synchronized Session beginOwnedNotificationIfIdle() {
        if (currentAnimation != AnimationType.NONE) return null;
        return beginAnimation(AnimationType.NOTIFICATION);
    }

    /** Replaces the exact live charge owner without ending its Scene restoration lease. */
    static synchronized Session beginPersistentNotificationDuringCharging(long chargeSessionId, int rearTaskId) {
        if (!PersistentNotificationChargeGate.canTransfer(
                currentAnimation == AnimationType.CHARGING, currentSession.id, chargeSessionId)) return null;
        Session session = beginAnimation(AnimationType.NOTIFICATION);
        if (rearTaskId > 0) session.rearTaskId = rearTaskId;
        return session;
    }

    /** Returns the actual current charging owner; the persistent-notice lease decides whether it covers a notice. */
    static synchronized long currentChargingSessionId() {
        return currentAnimation == AnimationType.CHARGING ? currentSession.id : Long.MIN_VALUE;
    }

    public static synchronized AnimationType startAnimation(AnimationType type) {
        return type == AnimationType.NONE ? AnimationType.NONE : beginAnimation(type).previous;
    }

    static synchronized long getGeneration() { return currentSession.id; }
    static synchronized long captureSessionId(AnimationType type) {
        return currentAnimation == type ? currentSession.id : Long.MIN_VALUE;
    }
    static synchronized boolean isCurrent(AnimationType type, long id) {
        return currentAnimation == type && currentSession.id == id;
    }
    static synchronized boolean isIdle(long id) {
        return currentAnimation == AnimationType.NONE && currentSession.id == id;
    }
    static boolean isSceneOverlaySession(long id) { return sceneOverlaySessionId == id; }
    private static void endSceneOverlay(long id) {
        boolean suppressLegacyRestore = top.zainzh.rearscreen.platform.RearCapabilityCore.endTransientOverlay(id);
        if (sceneOverlaySessionId == id) sceneOverlaySessionId = Long.MIN_VALUE;
        // A Scene or cast can take ownership after this animation began. The Core's epoch
        // check remains authoritative even when the animation did not cover a Scene at start.
        if (suppressLegacyRestore) sceneOverlaySessionId = id;
    }
    static synchronized void registerAnimationActivityTask(long id, int taskId) {
        if (currentSession.id == id && currentAnimation != AnimationType.NONE && taskId > 0)
            currentSession.animationActivityTaskId = taskId;
    }
    static synchronized boolean isAnimationActivityTask(long id, int taskId) {
        return currentSession.id == id && taskId > 0 && currentSession.animationActivityTaskId == taskId;
    }

    /** Explicit casting replaces the transient animation owner, including queued restoration. */
    static synchronized void projectionStarted(android.content.Context context) {
        top.zainzh.rearscreen.platform.RearCapabilityCore.onLegacyProjectionStarted(context, -1, null);
        finishTransientAnimation(context);
    }

    /** Existing physical URI/Tile owner, called only after the task move was verified successful. */
    static synchronized void projectionStarted(android.content.Context context, int taskId, String packageName) {
        top.zainzh.rearscreen.platform.RearCapabilityCore.onLegacyProjectionStarted(context, taskId, packageName);
        finishTransientAnimation(context);
    }

    private static void finishTransientAnimation(android.content.Context context) {
        Session previous = currentSession;
        currentAnimation = AnimationType.NONE;
        currentSession = new Session(++generation, AnimationType.NONE, AnimationType.NONE, -1);
        interruptedChargingWasAlwaysOn = false;
        endSceneOverlay(previous.id);
        RearInfoSession.animationChanged();
        RearModuleSession.INSTANCE.animationChanged();
        String action;
        String extra;
        if (previous.type == AnimationType.NOTIFICATION) {
            action = BuildConfig.APPLICATION_ID + ".FINISH_NOTIFICATION_ANIMATION";
            extra = EXTRA_SESSION_ID;
        } else if (previous.type == AnimationType.CHARGING) {
            action = BuildConfig.APPLICATION_ID + ".FINISH_CHARGING_ANIMATION";
            extra = RearScreenChargingActivity.EXTRA_REQUEST_ID;
        } else return;
        // Never send a wildcard close: a replacement Activity may already accept a newer request.
        context.sendBroadcast(new android.content.Intent(action).setPackage(context.getPackageName())
                .putExtra(extra, previous.id));
    }

    // 串行化动画恢复与显式投放的前台变更；主线程领取新会话不等待Binder调用。
    static void runDisplayAction(Runnable action) {
        DISPLAY_ACTION_GATE.run(action);
    }

    static <T> T callDisplayAction(java.util.concurrent.Callable<T> action) throws Exception {
        return DISPLAY_ACTION_GATE.call(action);
    }

    /**
     * V3.5: 标记被打断的充电动画是常亮模式
     */
    public static synchronized void markInterruptedChargingAsAlwaysOn(boolean alwaysOn) {
        interruptedChargingWasAlwaysOn = alwaysOn;
        Log.d(TAG, "🔖 被打断的充电动画常亮标记: " + alwaysOn);
    }
    
    /**
     * V3.5: 检查被打断的充电动画是否需要恢复
     */
    public static synchronized boolean shouldResumeChargingAnimation() {
        return interruptedChargingWasAlwaysOn;
    }
    
    /**
     * V3.5: 清除充电动画常亮标记
     */
    public static synchronized void clearChargingAlwaysOnFlag() {
        interruptedChargingWasAlwaysOn = false;
    }
    
    /**
     * 结束动画
     * @param type 动画类型
     * @return 是否需要恢复官方Launcher
     */
    public static synchronized boolean endAnimation(AnimationType type) {
        return endAnimation(type, currentSession.id);
    }

    static synchronized boolean endAnimation(AnimationType type, long id) {
        if (!isCurrent(type, id)) return false;
        currentAnimation = AnimationType.NONE;
        endSceneOverlay(id);
        RearInfoSession.animationChanged();
        RearModuleSession.INSTANCE.animationChanged();
        return true;
    }

    /**
     * 检查是否有动画正在播放
     */
    public static synchronized boolean isAnimationPlaying() {
        return currentAnimation != AnimationType.NONE;
    }
    
    /**
     * 获取当前动画类型
     */
    public static synchronized AnimationType getCurrentAnimation() {
        return currentAnimation;
    }
    
    /**
     * 打断指定类型的动画
     */
    private static void interruptAnimation(AnimationType type) {
        android.content.Intent intent;
        String action;
        
        switch (type) {
            case CHARGING:
                action = BuildConfig.APPLICATION_ID + ".INTERRUPT_CHARGING_ANIMATION";
                break;
            case NOTIFICATION:
                action = BuildConfig.APPLICATION_ID + ".INTERRUPT_NOTIFICATION_ANIMATION";
                break;
            default:
                return;
        }
        
        try {
            // 通过静态上下文发送广播（需要从Service获取）
            // 这里暂时用日志标记，实际发送由调用方处理
            Log.d(TAG, String.format("🔔 准备发送打断广播: %s", action));
        } catch (Exception e) {
            Log.e(TAG, "Failed to interrupt animation", e);
        }
    }
    
    /**
     * 发送打断广播（由Service调用）
     */
    public static void sendInterruptBroadcast(android.content.Context context, AnimationType type) {
        String action;
        
        switch (type) {
            case CHARGING:
                action = BuildConfig.APPLICATION_ID + ".INTERRUPT_CHARGING_ANIMATION";
                break;
            case NOTIFICATION:
                action = BuildConfig.APPLICATION_ID + ".INTERRUPT_NOTIFICATION_ANIMATION";
                break;
            default:
                return;
        }
        
        try {
            android.content.Intent intent = new android.content.Intent(action);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            Log.d(TAG, String.format("✓ 已发送打断广播: %s", action));
        } catch (Exception e) {
            Log.e(TAG, "Failed to send interrupt broadcast", e);
        }
    }
}

