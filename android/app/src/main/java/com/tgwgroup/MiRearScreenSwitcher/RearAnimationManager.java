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

package com.tgwgroup.MiRearScreenSwitcher;

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
    
    // 当前正在播放的动画类型
    private static volatile AnimationType currentAnimation = AnimationType.NONE;
    
    // 当前动画是否应该恢复官方Launcher（被新动画打断则不恢复）
    private static volatile boolean shouldRestoreOnDestroy = true;
    
    // V3.5: 被打断的充电动画是否是常亮模式
    private static volatile boolean interruptedChargingWasAlwaysOn = false;
    
    /**
     * 开始播放动画
     * @param type 动画类型
     * @return 被打断的旧动画类型（NONE表示没有旧动画）
     */
    public static synchronized AnimationType startAnimation(AnimationType type) {
        if (type == AnimationType.NONE) {
            Log.w(TAG, "⚠️ 尝试启动NONE类型的动画，忽略");
            return AnimationType.NONE;
        }
        
        AnimationType oldAnimation = currentAnimation;
        
        if (oldAnimation != AnimationType.NONE) {
            Log.d(TAG, String.format("🔄 新动画[%s]打断旧动画[%s]", type, oldAnimation));
            // 标记旧动画不需要恢复官方Launcher
            shouldRestoreOnDestroy = false;
        } else {
            Log.d(TAG, String.format("▶️ 开始播放动画[%s]", type));
        }
        
        // 设置新动画为当前动画
        currentAnimation = type;
        shouldRestoreOnDestroy = true;  // 新动画默认需要恢复
        
        return oldAnimation;  // 返回被打断的旧动画
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
        if (currentAnimation != type) {
            Log.w(TAG, String.format("⚠️ 尝试结束动画[%s]，但当前动画是[%s]", type, currentAnimation));
            return false;  // 不是当前动画，不需要恢复
        }
        
        boolean shouldRestore = shouldRestoreOnDestroy;
        
        if (shouldRestore) {
            Log.d(TAG, String.format("⏹️ 动画[%s]正常结束，需要恢复官方Launcher", type));
        } else {
            Log.d(TAG, String.format("⏹️ 动画[%s]被打断结束，不需要恢复官方Launcher", type));
        }
        
        currentAnimation = AnimationType.NONE;
        shouldRestoreOnDestroy = true;
        
        return shouldRestore;
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
                action = "com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_CHARGING_ANIMATION";
                break;
            case NOTIFICATION:
                action = "com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION";
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
                action = "com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_CHARGING_ANIMATION";
                break;
            case NOTIFICATION:
                action = "com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION";
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

