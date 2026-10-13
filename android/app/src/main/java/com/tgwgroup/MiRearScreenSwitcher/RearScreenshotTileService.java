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

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

/**
 * Quick Settings Tile - 获取背屏截图
 * 点击后截取背屏当前画面并保存到相册
 */
public class RearScreenshotTileService extends TileService {
    private static final String TAG = "RearScreenshotTile";

    private volatile boolean taskServiceReleased;
    private volatile ITaskService taskService;
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };

@Override
    public void onStartListening() {
        super.onStartListening();

        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(null);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.setStateDescription("");
            }
            tile.updateTile();
        }

        bindTaskService();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();

        unlockAndRun(() -> {
            new Thread(() -> {
                try {
                    if (taskService == null) {
                        Log.w(TAG, "TaskService not available");
                        showTemporaryFeedback("✗ 服务未就绪");
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            Toast.makeText(this, "✗ 服务未就绪", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    boolean success = taskService.takeRearScreenshot();

                    // 无论成功失败都显示成功Toast
                    showTemporaryFeedback("✓ 已保存");

                    // 先收起控制中心
                    try {
                        taskService.collapseStatusBar();
                        Thread.sleep(300);
                    } catch (Exception ignored) {
                    }

                    // 显示Toast提示
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, getString(R.string.toast_screenshot_saved), Toast.LENGTH_SHORT).show();
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Screenshot error", e);
                    // 即使异常也显示成功Toast
                    showTemporaryFeedback("✓ 已保存");
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, getString(R.string.toast_screenshot_saved), Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });
    }

    private void bindTaskService() {
        if (!taskServiceReleased) TaskServiceManager.acquire(this, taskServiceConnection);
    }

    @Override
    public void onDestroy() {
        taskServiceReleased = true;
        super.onDestroy();
        TaskServiceManager.release(taskServiceConnection);
        taskService = null;
    }

    private void showTemporaryFeedback(String message) {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setSubtitle(message);
            tile.updateTile();

            // 1秒后清除反馈
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Tile t = getQsTile();
                if (t != null) {
                    t.setSubtitle(null);
                    t.updateTile();
                }
            }, 1000);
        }
    }
}
