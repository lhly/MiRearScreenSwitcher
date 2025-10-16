package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import rikka.shizuku.Shizuku;

public class FloatingBubbleService extends Service {
    private static final String TAG = "FloatingBubbleService";
    
    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    
    private float initialX;
    private float initialY;
    private float initialTouchX;
    private float initialTouchY;
    
    private ITaskService taskService;
    private final Shizuku.UserServiceArgs serviceArgs = 
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("task_service")
            .debuggable(true)
            .version(1);
    
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.d(TAG, "✅ TaskService connected in FloatingBubbleService!");
            taskService = ITaskService.Stub.asInterface(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "❌ TaskService disconnected");
            taskService = null;
        }
    };
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // 绑定TaskService
        bindTaskService();
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // 创建悬浮球视图
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null);
        
        // 设置悬浮窗参数
        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 100;
        
        // 添加到窗口
        windowManager.addView(floatingView, params);
        
        // 设置点击和拖动事件
        ImageView bubbleIcon = floatingView.findViewById(R.id.bubble_icon);
        
        bubbleIcon.setOnTouchListener(new View.OnTouchListener() {
            private long touchStartTime;
            private static final int CLICK_THRESHOLD = 200; // 200ms内算点击
            
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartTime = System.currentTimeMillis();
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        params.x = (int) (initialX + (event.getRawX() - initialTouchX));
                        params.y = (int) (initialY + (event.getRawY() - initialTouchY));
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                        long touchDuration = System.currentTimeMillis() - touchStartTime;
                        float deltaX = Math.abs(event.getRawX() - initialTouchX);
                        float deltaY = Math.abs(event.getRawY() - initialTouchY);
                        
                        // 判断是点击还是拖动
                        if (touchDuration < CLICK_THRESHOLD && deltaX < 10 && deltaY < 10) {
                            // 这是点击事件
                            onBubbleClicked();
                        }
                        return true;
                }
                return false;
            }
        });
        
        Log.d(TAG, "FloatingBubbleService created");
    }
    
    private void bindTaskService() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku not available");
                return;
            }
            
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "No Shizuku permission");
                return;
            }
            
            Log.d(TAG, "Binding TaskService from FloatingBubbleService...");
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }
    
    private void onBubbleClicked() {
        Log.d(TAG, "Bubble clicked! Switching current app...");
        
        if (taskService == null) {
            Log.w(TAG, "TaskService not available!");
            return;
        }
        
        try {
            // 获取当前前台应用
            String currentApp = taskService.getCurrentForegroundApp();
            Log.d(TAG, "Current app: " + currentApp);
            
            if (currentApp != null && currentApp.contains(":")) {
                String[] parts = currentApp.split(":");
                String packageName = parts[0];
                int taskId = Integer.parseInt(parts[1]);
                
                Log.d(TAG, "Switching " + packageName + " (task " + taskId + ") to display 1");
                
                // 切换到display 1
                boolean success = taskService.moveTaskToDisplay(taskId, 1);
                Log.d(TAG, "Switch result: " + success);
            } else {
                Log.w(TAG, "No foreground app found");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error switching app", e);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null && windowManager != null) {
            windowManager.removeView(floatingView);
        }
        if (taskService != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding TaskService", e);
            }
            taskService = null;
        }
        Log.d(TAG, "FloatingBubbleService destroyed");
    }
}

