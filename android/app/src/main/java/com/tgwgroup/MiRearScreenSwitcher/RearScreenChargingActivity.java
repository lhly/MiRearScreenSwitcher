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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 背屏充电动画Activity
 * 显示充电图标、电量百分比和进度条，5秒后自动关闭并恢复投送app或官方Launcher
 */
public class RearScreenChargingActivity extends Activity {
    private static final String TAG = "RearScreenChargingActivity";
    private int rearTaskId = -1;  // 背屏投送的app的taskId，-1表示没有投送app
    private boolean autoFinishScheduled = false; // 是否已安排自动销毁
    
    // 静态实例追踪，防止旧实例干扰新实例
    private static volatile RearScreenChargingActivity currentInstance = null;
    private static volatile long currentInstanceCreateTime = 0;
    
    // 广播接收器：接收立即结束的命令
    private android.content.BroadcastReceiver finishReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            String action = intent.getAction();
            if ("com.tgwgroup.MiRearScreenSwitcher.FINISH_CHARGING_ANIMATION".equals(action)) {
                Log.d(TAG, "🔌 收到拔电广播，立即销毁");
                finish();
            } else if ("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_CHARGING_ANIMATION".equals(action)) {
                Log.d(TAG, "🔄 收到打断广播（新动画来了），立即销毁但不恢复Launcher");
                // 标记为被打断，onDestroy不恢复Launcher
                finish();
            }
        }
    };
    
    public RearScreenChargingActivity() {
        super();
        long time = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🟢 构造函数被调用", time, time));
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long onCreateStartTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🟡 onCreate开始", onCreateStartTime, onCreateStartTime));
        
        super.onCreate(savedInstanceState);
        
        // 判断当前所在的屏幕
        int displayId = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            displayId = getDisplay().getDisplayId();
        }
        Log.d(TAG, String.format("[%tT.%tL] 📍 当前displayId=%d", onCreateStartTime, onCreateStartTime, displayId));
        
        int level = getIntent().getIntExtra("batteryLevel", 0);
        rearTaskId = getIntent().getIntExtra("rearTaskId", -1);
        
        // ✅ 如果在主屏(displayId == 0)，什么都不做，等待被移动到背屏
        if (displayId == 0) {
            Log.d(TAG, String.format("[%tT.%tL] 💤 在主屏启动，保持透明占位符，等待移动", 
                onCreateStartTime, onCreateStartTime));
            return; // 不设置内容，不添加flags，只是透明占位符
        }
        
        // --- 以下代码只在背屏(displayId == 1)执行 ---
        Log.d(TAG, String.format("[%tT.%tL] 🎯 在背屏执行，开始设置内容", onCreateStartTime, onCreateStartTime));
        
        // 在背屏时点亮屏幕并保持常亮
        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );
        
        // 适配新API（Android 8.1+）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        
        // ⚠️ 关键：在 setContentView 之前强制使用背屏DPI！
        forceRearScreenDensityBeforeInflate();
        
        setContentView(R.layout.activity_rear_screen_charging);
        
        long afterSetContentViewTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🟠 setContentView完成", 
            afterSetContentViewTime, afterSetContentViewTime));
        
        // 获取背屏信息并应用安全区域适配
        applySafeAreaPadding();
        
        long afterGetIntentTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] ⚡ Intent数据: Battery=%d%%, rearTaskId=%d", 
            afterGetIntentTime, afterGetIntentTime, level, rearTaskId));
        
        // 获取视图
        LightningShapeView lightningLiquid = findViewById(R.id.lightning_liquid);
        TextView batteryText = findViewById(R.id.battery_text);
        View batteryPercentageContainer = findViewById(R.id.battery_percentage_container);
        View chargingContainer = findViewById(R.id.charging_container);
        
        // 设置电量文字
        batteryText.setText(String.valueOf(level));
        
        // 启动闪电液体填充动画（非线性，从0到电量百分比）
        startLightningLiquidAnimation(lightningLiquid, level);
        
        // 启动电量数值淡入动画
        startPercentageAnimation(batteryPercentageContainer);
        
        long animationStartTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🎬 动画已启动，5秒后自动关闭", 
            animationStartTime, animationStartTime));
        
        // 5秒后自动关闭
        chargingContainer.postDelayed(this::finish, 8000);
        autoFinishScheduled = true;
        
        long onCreateEndTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] ✅ onCreate完成 (总耗时%dms)", 
            onCreateEndTime, onCreateEndTime, onCreateEndTime - onCreateStartTime));
        
        // 注册广播接收器（监听拔电和打断事件）
        android.content.IntentFilter finishFilter = new android.content.IntentFilter();
        finishFilter.addAction("com.tgwgroup.MiRearScreenSwitcher.FINISH_CHARGING_ANIMATION");
        finishFilter.addAction("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_CHARGING_ANIMATION");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishReceiver, finishFilter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(finishReceiver, finishFilter);
        }
        Log.d(TAG, String.format("[%tT.%tL] ✅ 已注册充电动画广播接收器", onCreateEndTime, onCreateEndTime));
        
        // 设置为当前实例
        currentInstance = this;
        currentInstanceCreateTime = onCreateEndTime;
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        long resumeTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🟢 onResume", resumeTime, resumeTime));
        
        // 再次确保Window flags（防止被清除）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        // 补偿：若因主屏占位未安排自动销毁，则在背屏resume时安排
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int displayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            if (displayId == 1 && !autoFinishScheduled) {
                Log.d(TAG, "⏱️ 未安排自动销毁，补偿安排5秒后finish");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finish, 5000);
                autoFinishScheduled = true;
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        long destroyTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🔴 onDestroy被调用", destroyTime, destroyTime));
        
        // 注销广播接收器
        try {
            unregisterReceiver(finishReceiver);
            Log.d(TAG, String.format("[%tT.%tL] ✅ 已注销充电动画广播接收器", destroyTime, destroyTime));
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister finish receiver: " + e.getMessage());
        }
        
        super.onDestroy();
        
        // 检查是否是当前实例，防止旧实例干扰新实例
        if (this != currentInstance) {
            Log.w(TAG, String.format("[%tT.%tL] ⚠️ 这是旧实例，跳过恢复操作", destroyTime, destroyTime));
            return;
        }
        
        // 通知动画管理器：充电动画结束
        boolean shouldRestore = RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.CHARGING);
        
        // 只有正常结束时才恢复Launcher，被打断时不恢复
        if (!shouldRestore) {
            Log.d(TAG, String.format("[%tT.%tL] 🔄 充电动画被打断，跳过恢复Launcher", destroyTime, destroyTime));
            return;
        }
        
        // 在背屏恢复投送app或官方Launcher（仅当在背屏时）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int currentDisplayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            Log.d(TAG, String.format("[%tT.%tL] 📍 当前displayId=%d", destroyTime, destroyTime, currentDisplayId));
            
            if (currentDisplayId == 1) {
                final int finalTaskId = rearTaskId;
                
                // 在后台线程执行恢复操作，不阻塞onDestroy
                new Thread(() -> {
                    try {
                        // 等待50ms让Activity完全销毁
                        Thread.sleep(50);
                        
                        if (finalTaskId > 0) {
                            Log.d(TAG, "⚡ 恢复投送app (taskId=" + finalTaskId + ")");
                            restoreProjectedApp(finalTaskId);
                        } else {
                            Log.d(TAG, "⚡ 恢复官方Launcher");
                            restoreOfficialLauncher();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in restore thread", e);
                    }
                }).start();
            }
        }
    }
    
    private void restoreProjectedApp(int taskId) {
        try {
            // 通过ChargingService获取TaskService并恢复投送的app
            ITaskService taskService = ChargingService.getTaskService();
            if (taskService != null) {
                // 步骤1: 先禁用官方Launcher（防止它抢占背屏）
                taskService.disableSubScreenLauncher();
                
                // 步骤2: 等待200ms让系统稳定（增加延迟）
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
                
                // 步骤3: 移动投送app回到背屏
                taskService.executeShellCommand(
                    "service call activity_task 50 i32 " + taskId + " i32 1"
                );
                
                // 步骤4: 再等待200ms确保app已移动
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
                
                // 步骤5: 再次确认移动（双重保险）
                taskService.executeShellCommand(
                    "service call activity_task 50 i32 " + taskId + " i32 1"
                );
                
                // 步骤6: 等待300ms让app完全显示
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {}
                
                // 步骤7: 不启用官方Launcher（保持禁用状态，让投送app继续占据背屏）
                // taskService.enableSubScreenLauncher(); // ❌ 不要启用，否则会抢占背屏
                
                // 步骤8: 重新启动RearScreenKeeperService来监控恢复的app
                restartKeeperService(taskId);
                
                Log.d(TAG, "✅ Projected app restored (taskId=" + taskId + ")");
            } else {
                Log.w(TAG, "TaskService not available from ChargingService");
                // 回退到MainActivity
                MainActivity mainActivity = MainActivity.getCurrentInstance();
                if (mainActivity != null) {
                    mainActivity.executeShellCommand(
                        "service call activity_task 50 i32 " + taskId + " i32 1"
                    );
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore projected app", e);
            // 如果恢复投送app失败，恢复监控并回退到官方Launcher
            RearScreenKeeperService.resumeMonitoring();
            restoreOfficialLauncher();
        }
    }
    
    private void restartKeeperService(int taskId) {
        try {
            // 获取包名和taskId信息
            String lastTask = SwitchToRearTileService.getLastMovedTask();
            if (lastTask != null) {
                // 启动RearScreenKeeperService
                Intent serviceIntent = new Intent(this, RearScreenKeeperService.class);
                serviceIntent.putExtra("lastMovedTask", lastTask);
                
                // V2.5: 传递背屏常亮开关状态
                try {
                    android.content.SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                    boolean keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                    serviceIntent.putExtra("keepScreenOnEnabled", keepScreenOnEnabled);
                } catch (Exception e) {
                    // 默认为开启
                    serviceIntent.putExtra("keepScreenOnEnabled", true);
                }
                
                startService(serviceIntent);
                
                Log.d(TAG, "🔄 RearScreenKeeperService restarted for: " + lastTask);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart RearScreenKeeperService", e);
        }
    }
    
    private void restoreOfficialLauncher() {
        try {
            // 通过ChargingService获取TaskService并恢复官方Launcher
            ITaskService taskService = ChargingService.getTaskService();
            if (taskService != null) {
                taskService.executeShellCommand(
                    "am start --display 1 -n com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity"
                );
                Log.d(TAG, "✅ Official launcher restored");
            } else {
                Log.w(TAG, "TaskService not available from ChargingService");
                // 回退到MainActivity
                MainActivity mainActivity = MainActivity.getCurrentInstance();
                if (mainActivity != null) {
                    mainActivity.executeShellCommand(
                        "am start --display 1 -n com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity"
                    );
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore official launcher", e);
        }
    }
    
    /**
     * 在inflate布局之前强制使用背屏DPI
     */
    private void forceRearScreenDensityBeforeInflate() {
        try {
            // 从缓存获取背屏DPI（适配所有小米双屏设备）
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            int rearScreenDpi = info.densityDpi;
            
            // 如果缓存未初始化，立即执行dumpsys获取真实DPI
            if (rearScreenDpi <= 0) {
                Log.w(TAG, "⚠️ 背屏DPI未缓存，尝试实时获取");
                
                // 尝试获取TaskService（带重试机制）
                ITaskService taskService = null;
                for (int retry = 0; retry < 3; retry++) {
                    taskService = ChargingService.getTaskService();
                    if (taskService == null) {
                        taskService = NotificationService.getTaskService();
                    }
                    
                    if (taskService != null) {
                        break;
                    }
                    
                    Log.w(TAG, String.format("⏳ TaskService未连接，重试 %d/3", retry + 1));
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                if (taskService != null) {
                    try {
                        DisplayInfoCache.getInstance().initialize(taskService);
                        info = DisplayInfoCache.getInstance().getCachedInfo();
                        rearScreenDpi = info.densityDpi;
                        Log.d(TAG, "✅ 实时获取背屏DPI: " + rearScreenDpi);
                    } catch (Exception e) {
                        Log.e(TAG, "❌ 实时获取背屏DPI失败", e);
                        return;
                    }
                } else {
                    Log.e(TAG, "❌ TaskService重试3次后仍不可用，跳过DPI强制");
                    return;
                }
            }
            
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int currentDpi = metrics.densityDpi;
            
            Log.d(TAG, String.format("🔧 inflate前 - 当前DPI=%d, 背屏DPI=%d", currentDpi, rearScreenDpi));
            
            metrics.densityDpi = rearScreenDpi;
            metrics.density = rearScreenDpi / 160f;
            metrics.scaledDensity = metrics.density;
            
            android.content.res.Configuration config = new android.content.res.Configuration(getResources().getConfiguration());
            config.densityDpi = rearScreenDpi;
            
            getResources().updateConfiguration(config, metrics);
            
            Log.d(TAG, String.format("✅ inflate前已强制应用背屏DPI: %d", metrics.densityDpi));
                
        } catch (Exception e) {
            Log.e(TAG, "❌ inflate前应用DPI失败", e);
        }
    }
    
    /**
     * 闪电液体填充动画（非线性，从0到目标电量）
     */
    private void startLightningLiquidAnimation(LightningShapeView lightningView, int targetLevel) {
        // 目标填充比例
        float targetFillLevel = targetLevel / 100f;
        
        // 创建非线性填充动画（DecelerateInterpolator - 减速效果）
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, targetFillLevel);
        animator.setDuration(1800); // 1.8秒填充动画
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(2.5f));
        
        animator.addUpdateListener(animation -> {
            float animatedValue = (float) animation.getAnimatedValue();
            lightningView.setFillLevel(animatedValue);
        });
        
        // 添加轻微的缩放动画（呼吸效果）
        lightningView.setScaleX(0.95f);
        lightningView.setScaleY(0.95f);
        lightningView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
            .start();
        
        animator.start();
        Log.d(TAG, String.format("⚡ 闪电液体填充动画已启动: 0%% → %d%%", targetLevel));
    }
    
    /**
     * 电量数值淡入动画（从右上角淡入+放大）
     */
    private void startPercentageAnimation(View container) {
        container.setAlpha(0f);
        container.setScaleX(0.6f);
        container.setScaleY(0.6f);
        container.setTranslationY(-30f); // 从上方稍微滑入
        
        container.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(700)
            .setStartDelay(500) // 液体开始填充时显示
            .setInterpolator(new android.view.animation.DecelerateInterpolator(2.0f))
            .start();
    }
    
    /**
     * 应用安全区域适配（避开Cutout）
     * 照抄通知动画的实现
     */
    private void applySafeAreaPadding() {
        try {
            // 从缓存获取背屏信息
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            
            // 如果没有cutout，不需要额外处理
            if (!info.hasCutout()) {
                Log.d(TAG, "ℹ️ 背屏无Cutout，无需调整布局");
                return;
            }
            
            // 获取内容布局的根容器（RelativeLayout with id=charging_container）
            android.view.View contentLayout = findViewById(R.id.charging_container);
            if (contentLayout != null && contentLayout.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams params = 
                    (android.view.ViewGroup.MarginLayoutParams) contentLayout.getLayoutParams();
                
                // 检查是否已经设置过margin（避免重复设置）
                if (params.leftMargin == info.cutout.left && 
                    params.topMargin == info.cutout.top && 
                    params.rightMargin == info.cutout.right && 
                    params.bottomMargin == info.cutout.bottom) {
                    Log.d(TAG, "ℹ️ 安全区域margin已设置，跳过");
                    return;
                }
                
                // 设置margin（避开cutout区域），背景渐变色会填充cutout区域
                params.leftMargin = info.cutout.left;
                params.topMargin = info.cutout.top;
                params.rightMargin = info.cutout.right;
                params.bottomMargin = info.cutout.bottom;
                contentLayout.setLayoutParams(params);
                
                Log.d(TAG, String.format("✅ 已应用安全区域margin: left=%d, top=%d, right=%d, bottom=%d",
                    info.cutout.left, info.cutout.top, info.cutout.right, info.cutout.bottom));
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ 应用安全区域失败", e);
        }
    }
}

