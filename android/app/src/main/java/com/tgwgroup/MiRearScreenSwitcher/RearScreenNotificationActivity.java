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

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 背屏通知显示Activity
 * 显示应用图标、名称和通知内容，带非线性动画效果
 */
public class RearScreenNotificationActivity extends Activity {
    private static final String TAG = "RearScreenNotificationActivity";
    
    // 静态实例追踪
    private static volatile RearScreenNotificationActivity currentInstance = null;
    
    private String packageName;
    private boolean contentInitialized = false;  // 标记内容是否已初始化
    
    // 通知动画期间持续唤醒和杀死launcher
    private android.os.Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private boolean isWakeupRunning = false;
    
    // 广播接收器：接收打断命令
    private android.content.BroadcastReceiver interruptReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            if ("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION".equals(intent.getAction())) {
                Log.d(TAG, "🔄 收到打断广播（新动画来了），立即销毁但不恢复Launcher");
                finish();
            }
        }
    };
    
    public RearScreenNotificationActivity() {
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
        
        // 获取Intent数据
        packageName = getIntent().getStringExtra("packageName");
        String title = getIntent().getStringExtra("title");
        String text = getIntent().getStringExtra("text");
        long when = getIntent().getLongExtra("when", System.currentTimeMillis());
        boolean darkMode = getIntent().getBooleanExtra("darkMode", false);
        
        // ⚠️ 关键：在 setContentView 之前强制使用背屏DPI
        forceRearScreenDensityBeforeInflate();
        
        // ✅ 统一设置布局，确保主屏占位后移动到背屏也能正常显示
        setContentView(R.layout.activity_rear_screen_notification);
        
        // 应用暗夜模式或常规布局调整
        if (darkMode) {
            applyDarkMode();
        } else {
            applyRegularLayout();
        }
        
        // V3.2: 保持常亮 + 锁屏显示
        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        
        // 适配新API：锁屏时显示
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        
        // 设置窗口背景，防止上滑回桌面时露出白色底
        getWindow().setBackgroundDrawableResource(R.drawable.bg_gradient_rear_screen);
        
        // 如果在主屏启动，只是占位符，先隐藏内容，等待移动到背屏
        if (displayId == 0) {
            Log.d(TAG, String.format("[%tT.%tL] 💤 在主屏启动(占位)，隐藏内容等待移动", 
                onCreateStartTime, onCreateStartTime));
            View container = findViewById(R.id.notification_container);
            container.setVisibility(View.INVISIBLE);
            // 标记内容未初始化，等待onResume在背屏时初始化
            contentInitialized = false;
            Log.d(TAG, String.format("[%tT.%tL] ⏸️ 占位符模式，contentInitialized=false", 
                onCreateStartTime, onCreateStartTime));
            
            // 注册广播接收器（即使是占位符也要注册）
            android.content.IntentFilter interruptFilter = new android.content.IntentFilter("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(interruptReceiver, interruptFilter, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(interruptReceiver, interruptFilter);
            }
            currentInstance = this;
            return;
        }
        
        // --- 以下代码只在背屏执行 ---
        Log.d(TAG, String.format("[%tT.%tL] 🎯 在背屏执行，开始设置内容", onCreateStartTime, onCreateStartTime));
        
        contentInitialized = true;
        
        // 获取背屏信息并应用安全区域适配
        applySafeAreaPadding();
        
        // 获取视图
        ImageView appIconCenter = findViewById(R.id.app_icon_center);
        ImageView appIconSmall = findViewById(R.id.app_icon_small);
        TextView appNameText = findViewById(R.id.app_name);
        TextView notificationTitle = findViewById(R.id.notification_title);
        TextView notificationContent = findViewById(R.id.notification_content);
        View container = findViewById(R.id.notification_container);
        View appNameContainer = findViewById(R.id.app_name_container);
        View contentContainer = findViewById(R.id.notification_content_container);
        
        // 加载应用信息
        try {
            PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            String appName = pm.getApplicationLabel(appInfo).toString();
            Drawable icon = pm.getApplicationIcon(packageName);
            
            // 设置图标和应用名
            appIconCenter.setImageDrawable(icon);
            appIconSmall.setImageDrawable(icon);
            appNameText.setText(appName);
            
            // 设置通知标题和内容
            if (title != null && !title.isEmpty()) {
                notificationTitle.setText(title);
                notificationTitle.setVisibility(View.VISIBLE);
            } else {
                notificationTitle.setVisibility(View.GONE);
            }
            
            if (text != null && !text.isEmpty()) {
                notificationContent.setText(text);
                notificationContent.setVisibility(View.VISIBLE);
            } else {
                notificationContent.setVisibility(View.GONE);
            }
            
            // 如果标题为空，隐藏间距
            if (title == null || title.isEmpty()) {
                notificationContent.setPadding(
                    notificationContent.getPaddingLeft(),
                    0,
                    notificationContent.getPaddingRight(),
                    notificationContent.getPaddingBottom()
                );
            }
            
            Log.d(TAG, String.format("[%tT.%tL] 📱 通知: %s - %s: %s", 
                onCreateStartTime, onCreateStartTime, appName, title, text));
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load app info", e);
            appNameText.setText(packageName);
            notificationTitle.setText(title);
            notificationContent.setText(text);
        }
        
        // 开始动画
        startNotificationAnimation(appIconCenter, appNameContainer, contentContainer);
        
        // 点击跳转到应用（优先在背屏启动）
        container.setOnClickListener(v -> {
            long clickTime = System.currentTimeMillis();
            Log.d(TAG, String.format("[%tT.%tL] 👆 收到点击，准备跳转 package=%s", clickTime, clickTime, packageName));
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                Log.d(TAG, String.format("[%tT.%tL] 🔍 getLaunchIntentForPackage -> %s", clickTime, clickTime, (launchIntent == null ? "null" : String.valueOf(launchIntent.getComponent()))));
                if (launchIntent == null) {
                    Log.w(TAG, String.format("[%tT.%tL] ⚠️ 无法获取启动Intent: %s", clickTime, clickTime, packageName));
                    finish();
                    return;
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

                boolean started = false;
                // 尝试直接在主屏启动（ActivityOptions -> display=0）
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                        // setLaunchDisplayId 在部分ROM可用；若不可用将抛异常，进入fallback
                        java.lang.reflect.Method m = android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
                        m.invoke(opts, 0);
                        Log.d(TAG, String.format("[%tT.%tL] 🚀 尝试ActivityOptions在display=0(主屏)启动", clickTime, clickTime));
                        // 点亮主屏
                        try {
                            ITaskService tsWake = NotificationService.getTaskService();
                            if (tsWake != null) {
                                tsWake.executeShellCommand("// 主屏唤醒功能已移除");
                                Log.d(TAG, String.format("[%tT.%tL] ✓ 已唤醒主屏", clickTime, clickTime));
                            }
                        } catch (Throwable ignored) {}
                        startActivity(launchIntent, opts.toBundle());
                        started = true;
                        Log.d(TAG, String.format("[%tT.%tL] ✓ ActivityOptions主屏启动成功", clickTime, clickTime));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, String.format("[%tT.%tL] 🔁 ActivityOptions不可用，转fallback: %s", clickTime, clickTime, t.getMessage()));
                }

                if (!started) {
                    // 回退：使用shell在主屏启动
                    try {
                        String component = null;
                        if (launchIntent.getComponent() != null) {
                            component = launchIntent.getComponent().flattenToShortString();
                        }
                        if (component == null) {
                            // 解析默认LAUNCHER Activity
                            android.content.pm.PackageManager pm = getPackageManager();
                            Intent resolve = new Intent(Intent.ACTION_MAIN);
                            resolve.addCategory(Intent.CATEGORY_LAUNCHER);
                            resolve.setPackage(packageName);
                            android.content.pm.ResolveInfo ri = pm.resolveActivity(resolve, 0);
                            if (ri != null && ri.activityInfo != null) {
                                component = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                            }
                        }
                        Log.d(TAG, String.format("[%tT.%tL] 🧭 解析到组件: %s", clickTime, clickTime, String.valueOf(component)));
                        if (component != null) {
                            ITaskService ts = NotificationService.getTaskService();
                            if (ts != null) {
                                // 先唤醒主屏
                                try {
                                    ts.executeShellCommand("// 主屏唤醒功能已移除");
                                    Log.d(TAG, String.format("[%tT.%tL] ✓ 已唤醒主屏", clickTime, clickTime));
                                } catch (Throwable ignored) {}
                                String cmd = "am start --display 0 -n " + component;
                                boolean ok = ts.executeShellCommand(cmd);
                                if (!ok) {
                                    // 有些ROM不支持 --display 0，退化为默认显示
                                    cmd = "am start -n " + component;
                                    ok = ts.executeShellCommand(cmd);
                                    Log.d(TAG, String.format("[%tT.%tL] 🔁 改为默认显示启动，结果=%s", clickTime, clickTime, ok));
                                }
                                Log.d(TAG, String.format("[%tT.%tL] ✓ Fallback shell主屏启动%s 组件=%s", clickTime, clickTime, ok ? "成功" : "失败", component));
                                started = ok;
                            } else {
                                Log.w(TAG, String.format("[%tT.%tL] ⚠️ TaskService为空，无法shell启动", clickTime, clickTime));
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, String.format("[%tT.%tL] Fallback shell start 异常: %s", clickTime, clickTime, t.getMessage()));
                    }
                }

                Log.d(TAG, String.format("[%tT.%tL] 🧹 结束通知Activity，started=%s", clickTime, clickTime, started));
                // 无论如何结束当前通知Activity
                finish();
            } catch (Exception e) {
                Log.e(TAG, String.format("[%tT.%tL] ❌ 启动失败: %s", clickTime, clickTime, e.getMessage()), e);
                finish();
            }
        });
        
        // V3.4: 根据设置的时间自动关闭
        int duration = getSharedPreferences("mrss_settings", MODE_PRIVATE).getInt("notification_duration", 10);
        container.postDelayed(this::finish, duration * 1000L);
        
        long onCreateEndTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] ✓ onCreate完成 (总耗时%dms)", 
            onCreateEndTime, onCreateEndTime, onCreateEndTime - onCreateStartTime));
        
        // 注册广播接收器（监听打断事件）
        android.content.IntentFilter interruptFilter = new android.content.IntentFilter("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(interruptReceiver, interruptFilter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(interruptReceiver, interruptFilter);
        }
        Log.d(TAG, String.format("[%tT.%tL] ✓ 已注册通知动画广播接收器", onCreateEndTime, onCreateEndTime));
        
        // 设置为当前实例
        currentInstance = this;
        
        // 初始化wakeup循环
        wakeupHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        // 如果在背屏，立即启动wakeup循环
        if (displayId == 1) {
            startWakeupAndKillLoop();
        }
    }
    
    /**
     * 执行通知动画
     * 1. 大图标从中心缩放
     * 2. 应用名毛玻璃容器淡入
     * 3. 通知内容毛玻璃容器淡入
     */
    private void startNotificationAnimation(ImageView iconCenter, View appNameContainer, View contentContainer) {
        // 初始状态
        appNameContainer.setAlpha(0f);
        appNameContainer.setScaleX(0.9f);
        appNameContainer.setScaleY(0.9f);
        contentContainer.setAlpha(0f);
        contentContainer.setTranslationY(30f);
        
        // 启用硬件加速
        iconCenter.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        appNameContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        contentContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        
        // 阶段1: 中心图标放大 (0-300ms)
        iconCenter.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .setDuration(300)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .withEndAction(() -> {
                // 阶段1.5: 停留展示 (300-800ms) - 增加500ms停留时间
                iconCenter.postDelayed(() -> {
                    // 阶段2: 中心图标缩小淡出 (800-1000ms)
                    iconCenter.animate()
                        .scaleX(0.5f)
                        .scaleY(0.5f)
                        .alpha(0f)
                        .setDuration(200)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            iconCenter.setVisibility(View.GONE);
                            iconCenter.setLayerType(View.LAYER_TYPE_NONE, null);
                        })
                        .start();
                    
                    // 阶段3: 应用名毛玻璃容器淡入并缩放(800-1050ms)
                    appNameContainer.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(250)
                        .setStartDelay(50)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            appNameContainer.setLayerType(View.LAYER_TYPE_NONE, null);
                        })
                        .start();
                    
                    // 阶段4: 通知内容毛玻璃容器从下方滑入 (950-1250ms)
                    contentContainer.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(300)
                        .setStartDelay(150)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            contentContainer.setLayerType(View.LAYER_TYPE_NONE, null);
                        })
                        .start();
                }, 500); // 停留500ms
            })
            .start();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        long resumeTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🟢 onResume", resumeTime, resumeTime));
        
        // V3.2: 再次确保Window flags（保持常亮 + 锁屏显示）
        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        
        // 确保锁屏显示设置持续生效
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        
        // 确保Handler已初始化
        if (wakeupHandler == null) {
            wakeupHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        
        // 检查是否已从主屏移动到背屏（占位符变为实际显示）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int currentDisplayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            View container = findViewById(R.id.notification_container);
            
            Log.d(TAG, String.format("[%tT.%tL] 🔍 检查移动: displayId=%d, contentInitialized=%s, container=%s", 
                resumeTime, resumeTime, currentDisplayId, contentInitialized, (container != null ? "存在" : "null")));
            
            // 如果当前在背屏且内容还未初始化（说明是从主屏移动过来的）
            if (currentDisplayId == 1 && !contentInitialized && container != null) {
                Log.d(TAG, String.format("[%tT.%tL] 🔄 检测到从主屏移动到背屏，初始化内容", resumeTime, resumeTime));
                
                // 显示内容
                container.setVisibility(View.VISIBLE);
                
                // 获取Intent数据并初始化内容
                String title = getIntent().getStringExtra("title");
                String text = getIntent().getStringExtra("text");
                
                // 应用安全区域适配（只调用一次）
                applySafeAreaPadding();
                contentInitialized = true;
                
                // 获取视图
                ImageView appIconCenter = findViewById(R.id.app_icon_center);
                ImageView appIconSmall = findViewById(R.id.app_icon_small);
                TextView appNameText = findViewById(R.id.app_name);
                TextView notificationTitle = findViewById(R.id.notification_title);
                TextView notificationContent = findViewById(R.id.notification_content);
                View appNameContainer = findViewById(R.id.app_name_container);
                View contentContainer = findViewById(R.id.notification_content_container);
                
                // 加载应用信息
                try {
                    PackageManager pm = getPackageManager();
                    android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                    String appName = pm.getApplicationLabel(appInfo).toString();
                    Drawable icon = pm.getApplicationIcon(packageName);
                    
                    // 设置图标和应用名
                    appIconCenter.setImageDrawable(icon);
                    appIconSmall.setImageDrawable(icon);
                    appNameText.setText(appName);
                    
                    // 设置通知标题和内容
                    if (title != null && !title.isEmpty()) {
                        notificationTitle.setText(title);
                        notificationTitle.setVisibility(View.VISIBLE);
                    } else {
                        notificationTitle.setVisibility(View.GONE);
                    }
                    
                    if (text != null && !text.isEmpty()) {
                        notificationContent.setText(text);
                        notificationContent.setVisibility(View.VISIBLE);
                    } else {
                        notificationContent.setVisibility(View.GONE);
                    }
                    
                    // 如果标题为空，隐藏间距
                    if (title == null || title.isEmpty()) {
                        notificationContent.setPadding(
                            notificationContent.getPaddingLeft(),
                            0,
                            notificationContent.getPaddingRight(),
                            notificationContent.getPaddingBottom()
                        );
                    }
                    
                    Log.d(TAG, String.format("[%tT.%tL] 📱 通知: %s - %s: %s", 
                        resumeTime, resumeTime, appName, title, text));
                    
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load app info in onResume", e);
                    appNameText.setText(packageName);
                    if (title != null) notificationTitle.setText(title);
                    if (text != null) notificationContent.setText(text);
                }
                
                // 启动动画
                startNotificationAnimation(appIconCenter, appNameContainer, contentContainer);
                
                // 设置点击事件和自动关闭
                container.setOnClickListener(v -> {
                    long clickTime = System.currentTimeMillis();
                    Log.d(TAG, String.format("[%tT.%tL] 👆 收到点击，准备跳转 package=%s", clickTime, clickTime, packageName));
                    try {
                        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                        Log.d(TAG, String.format("[%tT.%tL] 🔍 getLaunchIntentForPackage -> %s", clickTime, clickTime, (launchIntent == null ? "null" : String.valueOf(launchIntent.getComponent()))));
                        if (launchIntent == null) {
                            Log.w(TAG, String.format("[%tT.%tL] ⚠️ 无法获取启动Intent: %s", clickTime, clickTime, packageName));
                            finish();
                            return;
                        }
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

                        boolean started = false;
                        // 尝试直接在主屏启动（ActivityOptions -> display=0）
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                                java.lang.reflect.Method m = android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
                                m.invoke(opts, 0);
                                Log.d(TAG, String.format("[%tT.%tL] 🚀 尝试ActivityOptions在display=0(主屏)启动", clickTime, clickTime));
                                // 点亮主屏
                                try {
                                    ITaskService tsWake = NotificationService.getTaskService();
                                    if (tsWake != null) {
                                        tsWake.executeShellCommand("// 主屏唤醒功能已移除");
                                        Log.d(TAG, String.format("[%tT.%tL] ✓ 已唤醒主屏", clickTime, clickTime));
                                    }
                                } catch (Throwable ignored) {}
                                startActivity(launchIntent, opts.toBundle());
                                started = true;
                                Log.d(TAG, String.format("[%tT.%tL] ✓ ActivityOptions主屏启动成功", clickTime, clickTime));
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, String.format("[%tT.%tL] 🔁 ActivityOptions不可用，转fallback: %s", clickTime, clickTime, t.getMessage()));
                        }

                        if (!started) {
                            // 回退：使用shell在主屏启动
                            try {
                                String component = null;
                                if (launchIntent.getComponent() != null) {
                                    component = launchIntent.getComponent().flattenToShortString();
                                }
                                if (component == null) {
                                    // 解析默认LAUNCHER Activity
                                    android.content.pm.PackageManager pm = getPackageManager();
                                    Intent resolve = new Intent(Intent.ACTION_MAIN);
                                    resolve.addCategory(Intent.CATEGORY_LAUNCHER);
                                    resolve.setPackage(packageName);
                                    android.content.pm.ResolveInfo ri = pm.resolveActivity(resolve, 0);
                                    if (ri != null && ri.activityInfo != null) {
                                        component = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                                    }
                                }
                                Log.d(TAG, String.format("[%tT.%tL] 🧭 解析到组件: %s", clickTime, clickTime, String.valueOf(component)));
                                if (component != null) {
                                    ITaskService ts = NotificationService.getTaskService();
                                    if (ts != null) {
                                        // 先唤醒主屏
                                        try {
                                            ts.executeShellCommand("// 主屏唤醒功能已移除");
                                            Log.d(TAG, String.format("[%tT.%tL] ✓ 已唤醒主屏", clickTime, clickTime));
                                        } catch (Throwable ignored) {}
                                        String cmd = "am start --display 0 -n " + component;
                                        boolean ok = ts.executeShellCommand(cmd);
                                        if (!ok) {
                                            cmd = "am start -n " + component;
                                            ok = ts.executeShellCommand(cmd);
                                            Log.d(TAG, String.format("[%tT.%tL] 🔁 改为默认显示启动，结果=%s", clickTime, clickTime, ok));
                                        }
                                        Log.d(TAG, String.format("[%tT.%tL] ✓ Fallback shell主屏启动%s 组件=%s", clickTime, clickTime, ok ? "成功" : "失败", component));
                                        started = ok;
                                    } else {
                                        Log.w(TAG, String.format("[%tT.%tL] ⚠️ TaskService为空，无法shell启动", clickTime, clickTime));
                                    }
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, String.format("[%tT.%tL] Fallback shell start 异常: %s", clickTime, clickTime, t.getMessage()));
                            }
                        }

                        Log.d(TAG, String.format("[%tT.%tL] 🧹 结束通知Activity，started=%s", clickTime, clickTime, started));
                        finish();
                    } catch (Exception e) {
                        Log.e(TAG, String.format("[%tT.%tL] ❌ 启动失败: %s", clickTime, clickTime, e.getMessage()), e);
                        finish();
                    }
                });
                
                // V3.4: 根据设置的时间自动关闭
                int duration = getSharedPreferences("mrss_settings", MODE_PRIVATE).getInt("notification_duration", 10);
                container.postDelayed(this::finish, duration * 1000L);
                
                // 启动wakeup循环
                startWakeupAndKillLoop();
                
                Log.d(TAG, String.format("[%tT.%tL] ✓ 移动后初始化完成", resumeTime, resumeTime));
            } else if (currentDisplayId == 1 && !isWakeupRunning) {
                // 如果已经在背屏但循环未启动，也启动（防止遗漏）
                startWakeupAndKillLoop();
            }
        }
    }
    
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        long configTime = System.currentTimeMillis();
        
        // 记录配置变化（用于调试）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int currentDisplayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            int densityDpi = newConfig.densityDpi;
            
            Log.d(TAG, String.format("[%tT.%tL] ⚙️ 配置变化: displayId=%d, densityDpi=%d", 
                configTime, configTime, currentDisplayId, densityDpi));
        }
    }
    
    @Override
    protected void onDestroy() {
        long destroyTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🔴 onDestroy被调用", destroyTime, destroyTime));
        
        // 停止wakeup循环
        stopWakeupLoop();
        
        // 注销广播接收器
        try {
            unregisterReceiver(interruptReceiver);
            Log.d(TAG, String.format("[%tT.%tL] ✓ 已注销通知动画广播接收器", destroyTime, destroyTime));
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister interrupt receiver: " + e.getMessage());
        }
        
        super.onDestroy();
        
        // 检查是否是当前实例
        if (this != currentInstance) {
            Log.w(TAG, String.format("[%tT.%tL] ⚠️ 这是旧实例，跳过恢复操作", destroyTime, destroyTime));
            return;
        }
        
        // 通知动画管理器：通知动画结束
        boolean shouldRestore = RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        
        // 只有正常结束时才恢复Launcher，被打断时不恢复
        if (!shouldRestore) {
            Log.d(TAG, String.format("[%tT.%tL] 🔄 通知动画被打断，跳过恢复Launcher", destroyTime, destroyTime));
            return;
        }
        
        // V3.5: 检查是否需要恢复充电动画（常亮模式）
        if (RearAnimationManager.shouldResumeChargingAnimation()) {
            Log.d(TAG, String.format("[%tT.%tL] 🔋 检测到充电动画常亮模式，发送恢复广播", destroyTime, destroyTime));
            
            // 发送恢复充电动画的广播
            android.content.Intent resumeIntent = new android.content.Intent("com.tgwgroup.MiRearScreenSwitcher.RESUME_CHARGING_ANIMATION");
            resumeIntent.setPackage(getPackageName());
            sendBroadcast(resumeIntent);
            
            // 清除标记
            RearAnimationManager.clearChargingAlwaysOnFlag();
            return;  // 不恢复官方Launcher
        }
        
        // 在背屏恢复官方Launcher
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int currentDisplayId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            Log.d(TAG, String.format("[%tT.%tL] 📍 当前displayId=%d", destroyTime, destroyTime, currentDisplayId));
            
            if (currentDisplayId == 1) {
                new Thread(() -> {
                    try {
                        // 通过ChargingService获取TaskService（NotificationService也绑定了）
                        ITaskService taskService = ChargingService.getTaskService();
                        if (taskService != null) {
                            taskService.executeShellCommand(
                                "am start --display 1 -n com.xiaomi.mirror/.SubscreenLauncher"
                            );
                            Log.d(TAG, "✓ Official launcher restored");
                        } else {
                            Log.w(TAG, "TaskService not available");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to restore launcher", e);
                    }
                }).start();
            }
        }
    }
    
    @Override
    public void finish() {
        super.finish();
        // 禁用转场动画
        overridePendingTransition(0, 0);
    }
    
    /**
     * 在inflate布局之前强制使用背屏DPI（最关键！）
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
                    taskService = NotificationService.getTaskService();
                    if (taskService == null) {
                        taskService = ChargingService.getTaskService();
                    }
                    
                    if (taskService != null) {
                        break;
                    }
                    
                    Log.w(TAG, String.format("⚠️ TaskService未连接，重试 %d/3", retry + 1));
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
                        Log.d(TAG, "✓ 实时获取背屏DPI: " + rearScreenDpi);
                    } catch (Exception e) {
                        Log.e(TAG, "❌ 实时获取背屏DPI失败", e);
                        return;
                    }
                } else {
                    Log.e(TAG, "❌ TaskService重试3次后仍不可用，跳过DPI强制");
                    return;
                }
            }
            
            // 获取当前的 DisplayMetrics
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int currentDpi = metrics.densityDpi;
            
            Log.d(TAG, String.format("🔧 inflate前 - 当前DPI=%d, 背屏DPI=%d", currentDpi, rearScreenDpi));
            
            // 强制修改为背屏DPI
            metrics.densityDpi = rearScreenDpi;
            metrics.density = rearScreenDpi / 160f;
            metrics.scaledDensity = metrics.density;
            
            // 同时修改 Configuration
            android.content.res.Configuration config = new android.content.res.Configuration(getResources().getConfiguration());
            config.densityDpi = rearScreenDpi;
            
            // 应用新的配置
            getResources().updateConfiguration(config, metrics);
            
            Log.d(TAG, String.format("✓ inflate前已强制应用背屏DPI: %d (density=%.2f)", 
                metrics.densityDpi, metrics.density));
                
        } catch (Exception e) {
            Log.e(TAG, "❌ inflate前应用DPI失败", e);
        }
    }
    
    /**
     * 强制使用背屏的DPI（覆盖系统DPI）
     */
    private void applyRearScreenDensity() {
        try {
            // 获取背屏的DPI
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            int rearScreenDpi = info.densityDpi;
            
            // 获取当前的 DisplayMetrics
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int currentDpi = metrics.densityDpi;
            
            Log.d(TAG, String.format("🔧 当前DPI=%d, 背屏DPI=%d", currentDpi, rearScreenDpi));
            
            // 如果当前DPI与背屏DPI不一致，强制修改
            if (currentDpi != rearScreenDpi) {
                Log.d(TAG, String.format("⚠️ DPI不匹配！强制使用背屏DPI: %d", rearScreenDpi));
                
                // 修改 DisplayMetrics
                metrics.densityDpi = rearScreenDpi;
                metrics.density = rearScreenDpi / 160f;
                metrics.scaledDensity = metrics.density;
                
                // 同时修改 Configuration
                android.content.res.Configuration config = getResources().getConfiguration();
                config.densityDpi = rearScreenDpi;
                
                // 更新 Resources
                getResources().updateConfiguration(config, metrics);
                
                Log.d(TAG, String.format("✓ 已强制应用背屏DPI: %d, density=%.2f", 
                    metrics.densityDpi, metrics.density));
            } else {
                Log.d(TAG, "✓ DPI已匹配，无需调整");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 应用背屏DPI失败", e);
        }
    }
    
    /**
     * 应用安全区域适配（避开Cutout）
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
            
            // 获取内容布局的根容器（RelativeLayout with id=notification_container）
            android.view.View contentLayout = findViewById(R.id.notification_container);
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
                
                Log.d(TAG, String.format("✓ 已应用安全区域margin: left=%d, top=%d, right=%d, bottom=%d",
                    info.cutout.left, info.cutout.top, info.cutout.right, info.cutout.bottom));
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ 应用安全区域失败", e);
        }
    }
    
    /**
     * 应用暗夜模式
     * 显示黑色背景层，毛玻璃去除，文字改为白色，调整布局
     */
    private void applyDarkMode() {
        try {
            Log.d(TAG, "🌙 应用暗夜模式");
            
            // 显示黑色背景层
            View darkBackground = findViewById(R.id.dark_mode_background);
            if (darkBackground != null) {
                darkBackground.setVisibility(View.VISIBLE);
                Log.d(TAG, "✓ 黑色背景层已显示");
            }
            
            // 获取整体通知容器 - 确保垂直居中
            View wrapperContainer = findViewById(R.id.notification_wrapper);
            if (wrapperContainer != null) {
                Log.d(TAG, "✓ 整体通知容器已获取，保持垂直居中");
            }
            
            // 获取毛玻璃容器 - 移除毛玻璃效果，设置为透明
            View contentContainer = findViewById(R.id.notification_content_container);
            if (contentContainer != null) {
                contentContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                
                // 移除容器的padding限制，让文字可以到屏幕边缘
                contentContainer.setPadding(0, 0, 0, 0);
                Log.d(TAG, "✓ 毛玻璃容器已移除，布局已调整");
            }
            
            // 获取应用名和图标容器 - 调整位置与标题内容对齐
            View appNameContainer = findViewById(R.id.app_name_container);
            if (appNameContainer != null) {
                android.widget.LinearLayout.LayoutParams containerParams = (android.widget.LinearLayout.LayoutParams) appNameContainer.getLayoutParams();
                containerParams.leftMargin = 0; // 与标题内容左侧对齐
                appNameContainer.setLayoutParams(containerParams);
                Log.d(TAG, "✓ 应用名容器已调整，与标题内容左侧对齐");
            }
            
            // 获取应用名称 - 改为白色
            TextView appName = findViewById(R.id.app_name);
            if (appName != null) {
                appName.setTextColor(android.graphics.Color.WHITE);
                Log.d(TAG, "✓ 应用名称已设为白色");
            }
            
            // 获取通知标题 - 改为白色，限制1行，调整间距
            TextView notificationTitle = findViewById(R.id.notification_title);
            if (notificationTitle != null) {
                notificationTitle.setTextColor(android.graphics.Color.WHITE);
                notificationTitle.setMaxLines(1); // 暗夜模式改为1行
                
                // 调整标题的margin，与icon到标题的间距保持一致
                android.widget.LinearLayout.LayoutParams titleParams = (android.widget.LinearLayout.LayoutParams) notificationTitle.getLayoutParams();
                titleParams.topMargin = 8; // 与icon到标题的间距一致
                notificationTitle.setLayoutParams(titleParams);
                Log.d(TAG, "✓ 通知标题已设为白色，限制1行，间距已调整");
            }
            
            // 获取通知内容 - 改为白色，限制6行，调整间距
            TextView notificationContent = findViewById(R.id.notification_content);
            if (notificationContent != null) {
                notificationContent.setTextColor(android.graphics.Color.WHITE);
                notificationContent.setMaxLines(6); // 暗夜模式改为6行
                
                // 调整内容的margin，与标题到内容的间距保持一致
                android.widget.LinearLayout.LayoutParams contentParams = (android.widget.LinearLayout.LayoutParams) notificationContent.getLayoutParams();
                contentParams.topMargin = 8; // 与标题到内容的间距一致
                notificationContent.setLayoutParams(contentParams);
                Log.d(TAG, "✓ 通知内容已设为白色，限制6行，间距已调整");
            }
            
            Log.d(TAG, "✓ 暗夜模式已应用 - 全黑背景，毛玻璃已移除，文字已变白，布局已调整");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ 应用暗夜模式失败", e);
        }
    }
    
    /**
     * 应用常规布局调整
     * 使用暗夜模式的布局调整，但保持原有的颜色
     */
    private void applyRegularLayout() {
        try {
            Log.d(TAG, "🎨 应用常规布局调整");
            
            // 获取整体通知容器 - 确保垂直居中
            View wrapperContainer = findViewById(R.id.notification_wrapper);
            if (wrapperContainer != null) {
                Log.d(TAG, "✓ 整体通知容器已获取，保持垂直居中");
            }
            
            // 获取毛玻璃容器 - 移除毛玻璃效果，设置为透明
            View contentContainer = findViewById(R.id.notification_content_container);
            if (contentContainer != null) {
                // 移除毛玻璃效果，设置为透明
                contentContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                
                // 移除容器的padding限制，让文字可以到屏幕边缘
                contentContainer.setPadding(0, 0, 0, 0);
                Log.d(TAG, "✓ 常规布局容器已调整，毛玻璃已移除");
            }
            
            // 获取应用名和图标容器 - 调整位置与标题内容对齐
            View appNameContainer = findViewById(R.id.app_name_container);
            if (appNameContainer != null) {
                android.widget.LinearLayout.LayoutParams containerParams = (android.widget.LinearLayout.LayoutParams) appNameContainer.getLayoutParams();
                containerParams.leftMargin = 0; // 与标题内容左侧对齐
                appNameContainer.setLayoutParams(containerParams);
                Log.d(TAG, "✓ 应用名容器已调整，与标题内容左侧对齐");
            }
            
            // 获取应用名称 - 改为白色并添加阴影
            TextView appName = findViewById(R.id.app_name);
            if (appName != null) {
                appName.setTextColor(android.graphics.Color.WHITE);
                appName.setShadowLayer(3, 0, 1, android.graphics.Color.parseColor("#40000000"));
                Log.d(TAG, "✓ 应用名称已设为白色，添加阴影");
            }
            
            // 获取通知标题 - 改为白色并添加阴影，调整间距和行数
            TextView notificationTitle = findViewById(R.id.notification_title);
            if (notificationTitle != null) {
                // 改为白色文字并添加阴影
                notificationTitle.setTextColor(android.graphics.Color.WHITE);
                notificationTitle.setShadowLayer(3, 0, 1, android.graphics.Color.parseColor("#40000000"));
                notificationTitle.setMaxLines(1); // 限制1行
                
                // 调整标题的margin，与icon到标题的间距保持一致
                android.widget.LinearLayout.LayoutParams titleParams = (android.widget.LinearLayout.LayoutParams) notificationTitle.getLayoutParams();
                titleParams.topMargin = 8; // 与icon到标题的间距一致
                notificationTitle.setLayoutParams(titleParams);
                Log.d(TAG, "✓ 通知标题已设为白色，添加阴影，间距已调整");
            }
            
            // 获取通知内容 - 调整间距和行数，保持原有颜色
            TextView notificationContent = findViewById(R.id.notification_content);
            if (notificationContent != null) {
                // 改为白色文字并添加阴影
                notificationContent.setTextColor(android.graphics.Color.WHITE);
                notificationContent.setShadowLayer(3, 0, 1, android.graphics.Color.parseColor("#40000000"));
                notificationContent.setMaxLines(6); // 限制6行
                
                // 调整内容的margin，与标题到内容的间距保持一致
                android.widget.LinearLayout.LayoutParams contentParams = (android.widget.LinearLayout.LayoutParams) notificationContent.getLayoutParams();
                contentParams.topMargin = 8; // 与标题到内容的间距一致
                notificationContent.setLayoutParams(contentParams);
                Log.d(TAG, "✓ 通知内容已设为白色，添加阴影，间距已调整");
            }
            
            Log.d(TAG, "✓ 常规布局已调整 - 毛玻璃已移除，文字已变白并添加阴影");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ 应用常规布局调整失败", e);
        }
    }
    
    /**
     * 启动通知动画期间的唤醒循环
     */
    private void startWakeupAndKillLoop() {
        if (isWakeupRunning) {
            Log.w(TAG, "⚠️ Wakeup loop already running");
            return;
        }
        
        isWakeupRunning = true;
        
        wakeupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isWakeupRunning) return;
                
                // 检查Activity是否还在运行
                if (isFinishing()) {
                    stopWakeupLoop();
                    return;
                }
                
                // 获取TaskService
                ITaskService taskService = NotificationService.getTaskService();
                
                if (taskService != null) {
                    // 发送wakeup命令
                    try {
                        taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                        Log.d(TAG, "✓ Wakeup sent");
                    } catch (Throwable t) {
                        Log.w(TAG, "发送wakeup失败: " + t.getMessage());
                    }
                } else {
                    Log.w(TAG, "⚠️ TaskService is null, skipping wakeup");
                }
                
                // 100ms后继续
                if (wakeupHandler != null) {
                    wakeupHandler.postDelayed(this, 100);
                }
            }
        };
        
        // 立即开始
        if (wakeupHandler != null) {
            wakeupHandler.post(wakeupRunnable);
            Log.d(TAG, "✓ Wakeup loop started");
        }
    }
    
    /**
     * 停止唤醒循环
     */
    private void stopWakeupLoop() {
        isWakeupRunning = false;
        if (wakeupHandler != null && wakeupRunnable != null) {
            wakeupHandler.removeCallbacks(wakeupRunnable);
        }
        Log.d(TAG, "✓ Wakeup loop stopped");
    }
}

