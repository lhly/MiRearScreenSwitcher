/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 监听小米背屏状态广播
 * 当背屏点亮/熄灭时，自动恢复常亮Activity，防止被系统Launcher覆盖
 */
public class RearScreenBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "RearScreenReceiver";
    
    // 保存最后投射的应用信息
    private static String lastMovedPackage = null;
    private static int lastTaskId = -1;
    private static boolean rearScreenActive = false;
    
    /**
     * 保存最后投射的应用信息
     * 由 TaskService 调用
     */
    public static void saveLastTask(String packageName, int taskId) {
        lastMovedPackage = packageName;
        lastTaskId = taskId;
        rearScreenActive = true;
    }
    
    /**
     * 清除保存的任务信息
     */
    public static void clearLastTask() {
        lastMovedPackage = null;
        lastTaskId = -1;
        rearScreenActive = false;
    }
    
    /**
     * 检查是否有活跃的背屏任务
     */
    public static boolean hasActiveTask() {
        return rearScreenActive && lastMovedPackage != null;
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long timestamp = System.currentTimeMillis();
        if (hasActiveTask()) {
        }
        if ("miui.intent.action.SUB_SCREEN_ON".equals(action)) {
            // 背屏点亮时的处理
            handleScreenOn(context);
        } else if ("miui.intent.action.SUB_SCREEN_OFF".equals(action)) {
            // 背屏熄灭时的处理
            handleScreenOff(context);
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            // 系统屏幕关闭（可能是双击息屏）
            handleSystemScreenOff(context);
        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
            // 系统屏幕打开
            handleSystemScreenOn(context);
        }
    }
    
    /**
     * 处理背屏点亮事件
     * 尝试恢复之前的常亮Activity和投射的应用
     */
    private void handleScreenOn(Context context) {
        if (hasActiveTask()) {
            // V8.3: 移除Activity机制 - 完全依靠Service
            // Activity的透明窗口会干扰锁屏时的触摸事件，导致滑动卡住
            // V8.3: 不需要发送恢复广播，Service会持续禁用Launcher
        } else {
        }
    }
    
    /**
     * 处理背屏熄灭事件
     */
    private void handleScreenOff(Context context) {
        // 背屏熄灭时，保持任务信息，以便下次点亮时恢复
        if (hasActiveTask()) {
        } else {
        }
    }
    
    /**
     * 处理系统屏幕关闭事件（双击息屏等）
     */
    private void handleSystemScreenOff(Context context) {
        if (hasActiveTask()) {
            // 确保Service仍在运行
            if (!RearScreenKeeperService.isRunning()) {
                Intent serviceIntent = new Intent(context, RearScreenKeeperService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
            }
        }
    }
    
    /**
     * 处理系统屏幕打开事件
     */
    private void handleSystemScreenOn(Context context) {
        if (hasActiveTask()) {
            // 确保Service仍在运行
            if (!RearScreenKeeperService.isRunning()) {
                Intent serviceIntent = new Intent(context, RearScreenKeeperService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}

