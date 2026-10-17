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
    private long animationId;
    private int rearTaskId = -1;
    private final android.os.Handler lifecycleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoFinish = this::finish;
    private final java.util.concurrent.ExecutorService wakeupWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean contentInitialized = false;  // 标记内容是否已初始化
    
    // 通知动画期间短时保亮，保留官方进程。
    private android.os.Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private boolean isWakeupRunning = false;
    
    // 广播接收器：接收打断命令
    private android.content.BroadcastReceiver interruptReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            long target = intent.getLongExtra(RearAnimationManager.EXTRA_SESSION_ID, 0);
            if (target != 0 && target != animationId) return;
            if ((BuildConfig.APPLICATION_ID + ".FINISH_NOTIFICATION_ANIMATION").equals(intent.getAction())
                    || !RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.NOTIFICATION, animationId)) finish();
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
        animationId = getIntent().getLongExtra(RearAnimationManager.EXTRA_SESSION_ID, RearAnimationManager.getGeneration());
        rearTaskId = getIntent().getIntExtra("rearTaskId", -1);
        if (!RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.NOTIFICATION, animationId)) {
            finish(); return;
        }
        
        // 判断当前所在的屏幕
        int displayId = currentDisplayId();
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
        
        applyNotificationStyle(darkMode);
        findViewById(R.id.notification_container).addOnLayoutChangeListener(
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> fitNotificationContent());

        // V3.2: 保持常亮 + 锁屏显示
        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        
        // 适配新API：锁屏时显示
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        
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
            android.content.IntentFilter interruptFilter = new android.content.IntentFilter(BuildConfig.APPLICATION_ID + ".INTERRUPT_NOTIFICATION_ANIMATION");
            interruptFilter.addAction(BuildConfig.APPLICATION_ID + ".FINISH_NOTIFICATION_ANIMATION");
            androidx.core.content.ContextCompat.registerReceiver(this, interruptReceiver, interruptFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
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
        lifecycleHandler.removeCallbacks(autoFinish);
        lifecycleHandler.postDelayed(autoFinish, duration * 1000L);
        
        long onCreateEndTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] ✓ onCreate完成 (总耗时%dms)", 
            onCreateEndTime, onCreateEndTime, onCreateEndTime - onCreateStartTime));
        
        // 注册广播接收器（监听打断事件）
        android.content.IntentFilter interruptFilter = new android.content.IntentFilter(BuildConfig.APPLICATION_ID + ".INTERRUPT_NOTIFICATION_ANIMATION");
        interruptFilter.addAction(BuildConfig.APPLICATION_ID + ".FINISH_NOTIFICATION_ANIMATION");
            androidx.core.content.ContextCompat.registerReceiver(this, interruptReceiver, interruptFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        Log.d(TAG, String.format("[%tT.%tL] ✓ 已注册通知动画广播接收器", onCreateEndTime, onCreateEndTime));
        
        // 设置为当前实例
        currentInstance = this;
        
        // 初始化wakeup循环
        wakeupHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        // 如果在背屏，立即启动wakeup循环
        if (displayId == 1) {
            startWakeupLoop();
        }
    }
    
    /**
     * 执行通知动画
     * 1. 大图标从中心缩放
     * 2. 应用名和图标淡入
     * 3. 通知内容淡入
     */
    private void startNotificationAnimation(ImageView iconCenter, View appNameContainer, View contentContainer) {
        // 卡片随内容入场，避免遮住第一阶段的中心应用图标。
        View wrapper = findViewById(R.id.notification_wrapper);
        wrapper.setAlpha(0f);
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
                    
                    wrapper.animate().alpha(1f).setDuration(250)
                        .setInterpolator(new AccelerateDecelerateInterpolator()).start();

                    // 阶段3: 应用名和图标淡入并缩放(800-1050ms)
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
                    
                    // 阶段4: 通知内容从下方滑入 (950-1250ms)
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
            int currentDisplayId = currentDisplayId();
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
                lifecycleHandler.removeCallbacks(autoFinish);
        lifecycleHandler.postDelayed(autoFinish, duration * 1000L);
                
                // 启动wakeup循环
                startWakeupLoop();
                
                Log.d(TAG, String.format("[%tT.%tL] ✓ 移动后初始化完成", resumeTime, resumeTime));
            } else if (currentDisplayId == 1 && !isWakeupRunning) {
                // 如果已经在背屏但循环未启动，也启动（防止遗漏）
                startWakeupLoop();
            }
        }
    }
    
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyNotificationStyle(getIntent().getBooleanExtra("darkMode", false));
        findViewById(R.id.notification_container).post(this::fitNotificationContent);
        long configTime = System.currentTimeMillis();
        
        // 记录配置变化（用于调试）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int currentDisplayId = currentDisplayId();
            int densityDpi = newConfig.densityDpi;
            
            Log.d(TAG, String.format("[%tT.%tL] ⚙️ 配置变化: displayId=%d, densityDpi=%d", 
                configTime, configTime, currentDisplayId, densityDpi));
        }
    }
    
    protected int currentDisplayId() { return getWindowManager().getDefaultDisplay().getDisplayId(); }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        long nextId = intent.getLongExtra(RearAnimationManager.EXTRA_SESSION_ID, 0);
        if (!RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.NOTIFICATION, nextId)) return;
        setIntent(intent);
        lifecycleHandler.removeCallbacks(autoFinish);
        stopWakeupLoop();
        recreate();
    }

    @Override protected void onDestroy() {
        lifecycleHandler.removeCallbacksAndMessages(null);
        stopWakeupLoop();
        wakeupWorker.shutdown();
        try { unregisterReceiver(interruptReceiver); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
        if (this != currentInstance || isChangingConfigurations()) return;
        currentInstance = null;
        if (!RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.NOTIFICATION, animationId)) return;
        if (RearAnimationManager.shouldResumeChargingAnimation()) {
            RearAnimationManager.clearChargingAlwaysOnFlag();
            sendBroadcast(new Intent(BuildConfig.APPLICATION_ID + ".RESUME_CHARGING_ANIMATION").setPackage(getPackageName()));
            return;
        }
        ITaskService service = NotificationService.getTaskService();
        if (service == null) service = ChargingService.getTaskService();
        if (service != null) ChargingService.restoreAfterAnimation(service, rearTaskId, animationId);
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
                
                // 设置margin（避开cutout区域），中性背景会填充cutout区域
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
    
    /** 常规通知随系统主题；用户启用的暗夜模式始终保留纯黑背屏。 */
    private void applyNotificationStyle(boolean oledMode) {
        int background = getColor(oledMode ? R.color.remrss_oled_background : R.color.remrss_background);
        int foreground = getColor(oledMode ? R.color.remrss_on_oled : R.color.remrss_foreground);
        int secondary = getColor(oledMode ? R.color.remrss_on_oled_secondary : R.color.remrss_secondary);
        findViewById(R.id.notification_root).setBackgroundColor(background);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
        View wrapper = findViewById(R.id.notification_wrapper);
        // setBackgroundResource 会忽略相同资源 ID，主题切换必须重新解析 Drawable。
        wrapper.setBackground(oledMode ? new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            : getDrawable(R.drawable.bg_rear_notification_card));
        wrapper.setElevation(oledMode ? 0 : getResources().getDisplayMetrics().density);
        ((TextView) findViewById(R.id.app_name)).setTextColor(secondary);
        ((TextView) findViewById(R.id.notification_title)).setTextColor(foreground);
        ((TextView) findViewById(R.id.notification_content)).setTextColor(secondary);
    }

    /** 背屏高度有限：保留系统字号，按安全区内实际剩余高度截断正文。 */
    private void fitNotificationContent() {
        View container = findViewById(R.id.notification_container);
        View wrapper = findViewById(R.id.notification_wrapper);
        TextView content = findViewById(R.id.notification_content);
        if (container.getHeight() == 0 || content.getVisibility() == View.GONE) return;
        android.view.ViewGroup.MarginLayoutParams margins = (android.view.ViewGroup.MarginLayoutParams) wrapper.getLayoutParams();
        int fixedHeight = wrapper.getPaddingTop() + wrapper.getPaddingBottom();
        for (int id : new int[]{R.id.app_name_container, R.id.notification_title}) {
            View view = findViewById(id);
            if (view.getVisibility() == View.GONE) continue;
            android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) view.getLayoutParams();
            fixedHeight += view.getMeasuredHeight() + params.topMargin + params.bottomMargin;
        }
        android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) content.getLayoutParams();
        int remaining = container.getHeight() - margins.topMargin - margins.bottomMargin - fixedHeight - params.topMargin;
        int fontPadding = content.getPaddingTop() + content.getPaddingBottom()
            + content.getPaint().getFontMetricsInt().bottom - content.getPaint().getFontMetricsInt().descent
            + content.getPaint().getFontMetricsInt().ascent - content.getPaint().getFontMetricsInt().top;
        int lines = Math.max(1, Math.min(6, (remaining - fontPadding) / Math.max(1, content.getLineHeight())));
        if (content.getMaxLines() != lines) content.setMaxLines(lines);
    }

    /**
     * 启动通知动画期间的唤醒循环
     */
    private void startWakeupLoop() {
        if (isWakeupRunning) {
            Log.w(TAG, "⚠️ Wakeup loop already running");
            return;
        }
        
        isWakeupRunning = true;
        
        wakeupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isWakeupRunning || !RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.NOTIFICATION, animationId)) return;
                
                // 检查Activity是否还在运行
                if (isFinishing()) {
                    stopWakeupLoop();
                    return;
                }
                
                // 获取TaskService
                ITaskService taskService = NotificationService.getTaskService();
                
                if (taskService != null && !wakeupWorker.isShutdown()) {
                    wakeupWorker.execute(() -> {
                        if (!RearAnimationManager.isCurrent(RearAnimationManager.AnimationType.NOTIFICATION, animationId)) return;
                        try { taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP"); }
                        catch (Exception error) { Log.w(TAG, "wakeup_failed", error); }
                    });
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

