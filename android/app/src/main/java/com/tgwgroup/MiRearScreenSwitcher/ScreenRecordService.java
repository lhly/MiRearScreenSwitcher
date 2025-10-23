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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import rikka.shizuku.Shizuku;

/**
 * 背屏录屏服务
 * 功能：
 * 1. 显示悬浮窗（录制/停止按钮+关闭按钮）
 * 2. 录制背屏画面（screenrecord --display-id 1）
 * 3. 前台Service保活
 */
public class ScreenRecordService extends Service {
    private static final String TAG = "ScreenRecordService";
    private static final String CHANNEL_ID = "rear_screen_keeper"; // 使用MRSS内核服务通道
    private static final int NOTIFICATION_ID = 10004; // 避免与KeeperService冲突
    
    private static ScreenRecordService instance = null;
    private WindowManager windowManager;
    private View floatingView;
    private boolean isRecording = false;
    private String currentVideoPath;
    private int recordPid = -1; // 录屏进程ID
    private Handler wakeupHandler = new Handler(android.os.Looper.getMainLooper());
    private static final long WAKEUP_INTERVAL_MS = 100; // 每100ms唤醒一次背屏
    
    // TaskService
    private ITaskService taskService;
    private final Shizuku.UserServiceArgs serviceArgs = 
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("task_service")
            .debuggable(false)
            .version(1);
    
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            Log.d(TAG, "✓ TaskService connected");
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };
    
    public static boolean isRunning() {
        return instance != null;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "📹 ScreenRecordService onCreate");
        
        // 创建通知渠道
        createNotificationChannel();
        
        // 绑定TaskService
        bindTaskService();
        
        // 启动前台通知
        startForeground(NOTIFICATION_ID, buildNotification());
        Log.d(TAG, "✓ 前台Service已启动");
        
        // 显示悬浮窗
        try {
            showFloatingWindow();
        } catch (Exception e) {
            Log.e(TAG, "❌ 显示悬浮窗失败", e);
            e.printStackTrace();
            Toast.makeText(this, "显示悬浮窗失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        
        Log.d(TAG, "═══════════════════════════════════════");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 被杀后自动重启
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void bindTaskService() {
        if (taskService != null) {
            Log.d(TAG, "TaskService已连接，跳过绑定");
            return;
        }
        
        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "❌ Shizuku不可用");
                return;
            }
            
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ 无Shizuku权限");
                return;
            }
            
            Log.d(TAG, "→ 正在绑定TaskService...");
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "❌ 绑定TaskService失败", e);
            e.printStackTrace();
        }
    }
    
    private void createNotificationChannel() {
        // 不创建新通道，使用MRSS内核服务的通道（已经存在）
    }
    
    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        );
        
        // 统一使用MRSS内核服务的通知样式
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MRSS内核服务")
            .setContentText("MRSS目前正在运行")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    /**
     * 显示悬浮窗
     */
    private void showFloatingWindow() {
        Log.d(TAG, "→ 准备显示悬浮窗");
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "❌ 无法获取WindowManager");
            return;
        }
        Log.d(TAG, "✓ WindowManager已获取");
        
        // 创建悬浮窗布局
        Log.d(TAG, "→ 创建悬浮窗视图");
        floatingView = createFloatingView();
        if (floatingView == null) {
            Log.e(TAG, "❌ 创建视图失败");
            return;
        }
        Log.d(TAG, "✓ 视图已创建");
        
        // 设置悬浮窗参数
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 20;
        params.y = 200;
        
        Log.d(TAG, "→ 参数设置完成，准备添加视图");
        Log.d(TAG, "  TYPE: " + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? "TYPE_APPLICATION_OVERLAY" : "TYPE_PHONE"));
        
        try {
            windowManager.addView(floatingView, params);
            Log.d(TAG, "✅ 悬浮窗已成功添加到WindowManager");
        } catch (Exception e) {
            Log.e(TAG, "❌ 添加悬浮窗失败", e);
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * 创建悬浮窗视图
     */
    private View createFloatingView() {
        Log.d(TAG, "→ 开始创建悬浮窗布局");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(16, 16, 16, 16);
        layout.setGravity(android.view.Gravity.CENTER); // 上下左右居中
        
        Log.d(TAG, "✓ LinearLayout已创建");
        
        // 背景 - 四色渐变（与其他UI一致）
        GradientDrawable background = new GradientDrawable();
        background.setOrientation(GradientDrawable.Orientation.TL_BR);
        background.setColors(new int[]{
            0xE0FF9D88,  // 珊瑚橙（88%不透明）
            0xE0FFB5C5,  // 粉红（88%不透明）
            0xE0E0B5DC,  // 紫色（88%不透明）
            0xE0A8C5E5   // 蓝色（88%不透明）
        });
        background.setCornerRadius(60);
        layout.setBackground(background);
        
        // 关闭按钮（×）- 先声明
        final android.widget.TextView closeButton = new android.widget.TextView(this);
        closeButton.setText("×");
        closeButton.setTextColor(Color.WHITE);
        closeButton.setTextSize(32);
        closeButton.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        closeParams.gravity = android.view.Gravity.CENTER; // 上下居中
        closeParams.leftMargin = 24;
        closeButton.setLayoutParams(closeParams);
        
        closeButton.setOnClickListener(v -> {
            // 录制中不允许关闭
            if (isRecording) {
                Toast.makeText(this, "请先停止录制", Toast.LENGTH_SHORT).show();
                return;
            }
            // 停止服务（关闭悬浮窗）
            stopSelf();
        });
        
        // 录制/停止按钮（圆形，红色）
        final View recordButton = new View(this);
        int buttonSize = 120;
        LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(buttonSize, buttonSize);
        recordParams.gravity = android.view.Gravity.CENTER; // 上下居中
        recordButton.setLayoutParams(recordParams);
        
        // 初始状态：录制按钮（实心圆）
        updateRecordButtonState(recordButton, false);
        
        // 点击事件
        recordButton.setOnClickListener(v -> {
            if (!isRecording) {
                startRecording();
                updateRecordButtonState(recordButton, true);
                // 录制时隐藏关闭按钮
                closeButton.setVisibility(View.GONE);
            } else {
                stopRecordingInternal(recordButton, closeButton);
                updateRecordButtonState(recordButton, false);
                // 注意：关闭按钮会在停止录制完成后才显示（在stopRecordingInternal的Toast回调中）
            }
        });
        
        layout.addView(recordButton);
        layout.addView(closeButton);
        
        Log.d(TAG, "✓ 按钮已添加到布局");
        
        // 拖动功能
        final WindowManager.LayoutParams[] params = new WindowManager.LayoutParams[1];
        layout.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (params[0] == null) {
                    params[0] = (WindowManager.LayoutParams) floatingView.getLayoutParams();
                }
                
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params[0].x;
                        initialY = params[0].y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        params[0].x = initialX + (int) (initialTouchX - event.getRawX());
                        params[0].y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params[0]);
                        return true;
                }
                return false;
            }
        });
        
        Log.d(TAG, "✓ 悬浮窗布局创建完成");
        return layout;
    }
    
    /**
     * 更新录制按钮状态
     */
    private void updateRecordButtonState(View button, boolean recording) {
        GradientDrawable drawable = new GradientDrawable();
        
        if (recording) {
            // 停止状态：方形
            drawable.setShape(GradientDrawable.RECTANGLE);
            drawable.setCornerRadius(20);
            drawable.setColor(Color.RED);
            drawable.setSize(60, 60); // 方形内部稍小
        } else {
            // 录制状态：圆形
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setColor(Color.RED);
        }
        
        drawable.setStroke(6, Color.WHITE); // 白色边框
        button.setBackground(drawable);
    }
    
    /**
     * 确保TaskService连接
     */
    private boolean ensureTaskServiceConnected() {
        if (taskService != null) {
            Log.d(TAG, "✓ TaskService已连接");
            return true;
        }
        
        Log.w(TAG, "⚠ TaskService未连接，尝试重新绑定...");
        
        // 尝试绑定
        bindTaskService();
        
        // 等待连接（最多3秒）
        int attempts = 0;
        while (taskService == null && attempts < 30) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
            attempts++;
        }
        
        if (taskService != null) {
            Log.d(TAG, "✅ TaskService重连成功");
            return true;
        } else {
            Log.e(TAG, "❌ TaskService重连失败（超时3秒）");
            return false;
        }
    }
    
    /**
     * 持续唤醒背屏任务 - 录制期间防止背屏熄屏
     */
    private final Runnable wakeupRearScreenRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording && taskService != null) {
                try {
                    // 向背屏(displayId=1)发送WAKEUP唤醒信号
                    taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    // 不输出日志以减少刷屏
                } catch (Exception e) {
                    Log.w(TAG, "背屏唤醒失败: " + e.getMessage());
                }
            }
            
            // 持续发送，每100ms执行一次
            if (isRecording) {
                wakeupHandler.postDelayed(this, WAKEUP_INTERVAL_MS);
            }
        }
    };
    
    /**
     * 启动背屏持续唤醒
     */
    private void startRearScreenWakeup() {
        if (wakeupHandler != null) {
            // 立即执行第一次唤醒，然后开始持续发送
            wakeupHandler.post(wakeupRearScreenRunnable);
            Log.d(TAG, "⏰ 背屏持续唤醒已启动 (100ms间隔)");
        }
    }
    
    /**
     * 停止背屏持续唤醒
     */
    private void stopRearScreenWakeup() {
        if (wakeupHandler != null) {
            wakeupHandler.removeCallbacks(wakeupRearScreenRunnable);
            Log.d(TAG, "⏸️ 背屏持续唤醒已停止");
        }
    }
    
    /**
     * 开始录制
     */
    private void startRecording() {
        new Thread(() -> {
            // 确保TaskService已连接
            if (!ensureTaskServiceConnected()) {
                Log.e(TAG, "TaskService未连接");
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(this, "服务未就绪，请稍后重试", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            
            // 启动录制前先发送一次keycode wakeup到背屏
            try {
                taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                Thread.sleep(200); // 等待wakeup生效
            } catch (Exception e) {
                Log.w(TAG, "启动前背屏keycode wakeup失败: " + e.getMessage());
            }
            
            try {
                // 生成文件名
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                    .format(new java.util.Date());
                currentVideoPath = "/storage/emulated/0/Movies/MRSS_" + timestamp + ".mp4";
                
                // 创建保存目录
                taskService.executeShellCommand("mkdir -p /storage/emulated/0/Movies");
                Log.d(TAG, "✓ 目录已创建");
                
                // 获取背屏的真实display ID（照抄截图逻辑）
                String getDisplayIdCmd = "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print $2}'";
                String displayId = taskService.executeShellCommandWithResult(getDisplayIdCmd);
                
                if (displayId == null || displayId.trim().isEmpty()) {
                    displayId = "1"; // 默认使用1
                    Log.w(TAG, "⚠ 未能获取display ID，使用默认值: 1");
                } else {
                    displayId = displayId.trim();
                    Log.d(TAG, "✓ 背屏display ID: " + displayId);
                }
                
                // 先测试screenrecord命令是否可用
                String testCmd = "which screenrecord";
                String testResult = taskService.executeShellCommandWithResult(testCmd);
                Log.d(TAG, "screenrecord路径: " + testResult);
                
                if (testResult == null || testResult.trim().isEmpty()) {
                    Log.e(TAG, "❌ screenrecord命令不存在");
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, "系统不支持screenrecord命令", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                
                // 使用完整路径启动录屏
                String screenrecordPath = testResult.trim();
                String pidFile = "/data/local/tmp/mrss_record.pid";
                String logFile = "/data/local/tmp/mrss_record.log";
                
                // 后台启动录屏并保存输出到日志
                String recordCmd = String.format(
                    "%s --display-id %s --bit-rate 20000000 %s > %s 2>&1 & echo $! > %s",
                    screenrecordPath, displayId, currentVideoPath, logFile, pidFile
                );
                
                Log.d(TAG, "→ 执行录屏命令: " + recordCmd);
                
                // 通过TaskService执行（有Shizuku权限）
                boolean cmdSuccess = taskService.executeShellCommand(recordCmd);
                Log.d(TAG, "命令执行结果: " + cmdSuccess);
                
                if (!cmdSuccess) {
                    Log.e(TAG, "❌ 启动录屏命令失败");
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, "启动录屏失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                
                // 等待进程启动和PID文件生成
                Thread.sleep(800);
                
                // 读取PID
                String pidStr = taskService.executeShellCommandWithResult("cat " + pidFile);
                Log.d(TAG, "PID文件内容: " + pidStr);
                
                if (pidStr != null && !pidStr.trim().isEmpty()) {
                    try {
                        recordPid = Integer.parseInt(pidStr.trim());
                        Log.d(TAG, "✓ 录屏进程PID: " + recordPid);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "⚠ 解析PID失败: " + pidStr);
                    }
                } else {
                    Log.e(TAG, "❌ 无法读取PID文件");
                }
                
                // 读取启动日志查看错误
                String logContent = taskService.executeShellCommandWithResult("cat " + logFile);
                if (logContent != null && !logContent.trim().isEmpty()) {
                    Log.d(TAG, "录屏进程日志: " + logContent);
                }
                
                // 验证进程是否真的在运行（多种方式）
                Log.d(TAG, "→ 验证录屏进程...");
                
                // 方法1: ps aux
                String checkCmd1 = "ps -A | grep screenrecord";
                String checkResult1 = taskService.executeShellCommandWithResult(checkCmd1);
                Log.d(TAG, "ps -A结果: " + checkResult1);
                
                // 方法2: ps -p
                String checkCmd2 = "ps -p " + recordPid;
                String checkResult2 = taskService.executeShellCommandWithResult(checkCmd2);
                Log.d(TAG, "ps -p结果: " + checkResult2);
                
                // 方法3: 检查文件是否开始生成
                Thread.sleep(500);
                String checkFile = "ls -l " + currentVideoPath;
                String fileCheck = taskService.executeShellCommandWithResult(checkFile);
                Log.d(TAG, "文件检查: " + fileCheck);
                
                // 如果进程在运行 或 文件已开始生成，认为成功
                boolean processRunning = (checkResult1 != null && checkResult1.contains("screenrecord")) ||
                                       (checkResult2 != null && checkResult2.contains(String.valueOf(recordPid)));
                boolean fileExists = (fileCheck != null && !fileCheck.contains("No such file"));
                
                if (processRunning || fileExists) {
                    Log.d(TAG, "✓ 录屏已启动 (进程运行=" + processRunning + ", 文件存在=" + fileExists + ")");
                    isRecording = true;
                    
                    // 录制成功启动后，开始持续唤醒背屏
                    startRearScreenWakeup();
                } else {
                    Log.e(TAG, "❌ 录屏进程未启动");
                    
                    // 检查错误原因
                    String errorCheck = "screenrecord --display-id 1 --help 2>&1 | head -n 5";
                    String errorMsg = taskService.executeShellCommandWithResult(errorCheck);
                    Log.e(TAG, "错误信息: " + errorMsg);
                    
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, "录屏进程启动失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                
                // 更新通知和Toast
                new Handler(Looper.getMainLooper()).post(() -> {
                    Notification notification = buildNotification();
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.notify(NOTIFICATION_ID, notification);
                    }
                    
                    Toast.makeText(this, "开始录制背屏", Toast.LENGTH_SHORT).show();
                });
                
                Log.d(TAG, "✅ 录屏已开始: " + currentVideoPath);
                
            } catch (Exception e) {
                Log.e(TAG, "录屏失败", e);
                e.printStackTrace();
                isRecording = false;
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(this, "录屏失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    /**
     * 停止录制（带按钮引用，用于更新状态）
     */
    private void stopRecordingInternal(final View recordButton, final android.widget.TextView closeButton) {
        if (!isRecording) {
            return;
        }
        
        new Thread(() -> {
            // 确保TaskService连接（主动重连）
            if (!ensureTaskServiceConnected()) {
                Log.e(TAG, "❌ 停止录制失败：TaskService未连接");
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(this, "服务未就绪，无法停止录制", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            
            try {
                if (recordPid > 0) {
                    Log.d(TAG, "→ 停止录屏进程 (PID=" + recordPid + ")");
                    
                    // 发送SIGINT信号停止录制（优雅停止）
                    String killCmd = "kill -2 " + recordPid;
                    boolean killed = taskService.executeShellCommand(killCmd);
                    
                    if (killed) {
                        Log.d(TAG, "✓ SIGINT信号已发送");
                    } else {
                        Log.w(TAG, "⚠ SIGINT失败，尝试SIGTERM");
                        taskService.executeShellCommand("kill " + recordPid);
                    }
                    
                    Thread.sleep(1000); // 等待进程优雅退出并保存文件
                    
                    isRecording = false;
                    recordPid = -1;
                    
                    // 停止背屏持续唤醒
                    stopRearScreenWakeup();
                    
                    // 验证文件是否存在
                    String checkFile = "ls -lh " + currentVideoPath;
                    String fileInfo = taskService.executeShellCommandWithResult(checkFile);
                    Log.d(TAG, "文件信息: " + fileInfo);
                    
                    // 刷新媒体库
                    String refreshCmd = "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + currentVideoPath;
                    taskService.executeShellCommand(refreshCmd);
                    Log.d(TAG, "✓ 媒体库已刷新");
                    
                    // 更新通知和Toast
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Notification notification = buildNotification();
                        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        if (nm != null) {
                            nm.notify(NOTIFICATION_ID, notification);
                        }
                        
                        if (fileInfo != null && !fileInfo.contains("No such file")) {
                            Toast.makeText(this, "录屏已保存到Movies文件夹", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "录屏可能失败，请检查Movies文件夹", Toast.LENGTH_LONG).show();
                        }
                        
                        // 显示关闭按钮
                        if (closeButton != null) {
                            closeButton.setVisibility(View.VISIBLE);
                        }
                    });
                    
                    Log.d(TAG, "✅ 录屏已停止并保存: " + currentVideoPath);
                }
            } catch (Exception e) {
                Log.e(TAG, "停止录屏失败", e);
                e.printStackTrace();
            }
        }).start();
    }
    
    /**
     * 停止录制（兼容方法）
     */
    private void stopRecording() {
        stopRecordingInternal(null, null);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // 停止背屏持续唤醒
        stopRearScreenWakeup();
        
        // 停止录制
        if (isRecording) {
            stopRecording();
        }
        
        // 移除悬浮窗
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
                Log.d(TAG, "✓ 悬浮窗已移除");
            } catch (Exception e) {
                Log.e(TAG, "移除悬浮窗失败", e);
            }
        }
        
        // 解绑TaskService
        if (taskService != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
            } catch (Exception e) {
                Log.e(TAG, "解绑TaskService失败", e);
            }
            taskService = null;
        }
        
        instance = null;
        Log.d(TAG, "⚠ Service已销毁");
    }
}

