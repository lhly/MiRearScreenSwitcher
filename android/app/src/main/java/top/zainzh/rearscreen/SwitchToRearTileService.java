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

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Quick Settings tile that moves the foreground task to the rear display. */
public class SwitchToRearTileService extends TileService {
    private static final String TAG = "SwitchToRearTile";
    private static final long READY_TIMEOUT_MS = 5000;
    private static volatile String lastMovedTask;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean listening;
    private boolean destroyed;
    private ITaskService taskService;
    private SwitchRequest pending;
    private long nextRequestId;
    private final Runnable resetFeedback = () -> updateFeedback(null);
    private final Runnable readyTimeout = () -> {
        if (pending != null && !pending.executing) {
            Log.w(TAG, "request=" + pending.id + " stage=ready_timeout elapsed_ms="
                    + (SystemClock.elapsedRealtime() - pending.startedAt));
            pending = null;
            showTemporaryFeedback("连接超时，请检查 Shizuku");
        }
    };

    private static final class SwitchRequest {
        final long id;
        final long startedAt = SystemClock.elapsedRealtime();
        volatile boolean cancelled;
        boolean executing; // Main-thread state.

        SwitchRequest(long id) { this.id = id; }
    }

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            executePending();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
            // The manager reconnects; a pending click continues only within its deadline.
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        listening = true;
        updateFeedback(null);
        TaskServiceManager.acquire(this, taskServiceConnection);
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        listening = false;
        cancelPending("panel_closed");
        if (pending == null) releaseConnection();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        listening = false;
        cancelPending("tile_destroyed");
        handler.removeCallbacksAndMessages(null);
        if (pending == null) releaseConnection();
        worker.shutdown();
        super.onDestroy();
    }

    private void cancelPending(String reason) {
        handler.removeCallbacks(readyTimeout);
        handler.removeCallbacks(resetFeedback);
        if (pending == null) return;
        pending.cancelled = true;
        Log.i(TAG, "request=" + pending.id + " stage=cancel reason=" + reason);
        // An executing worker retains its connection until the operation/cleanup finishes.
        if (!pending.executing) pending = null;
    }

    private void releaseConnection() {
        TaskServiceManager.release(taskServiceConnection);
        taskService = null;
    }

    public static void restoreTaskToRearDisplay(int taskId) {
        // Restoration is handled by RearScreenBroadcastReceiver.
    }

    public static String getLastMovedTask() { return lastMovedTask; }

    @Override
    public void onClick() {
        super.onClick();
        if (destroyed || pending != null) {
            Log.i(TAG, "stage=click_ignored reason=busy_or_destroyed");
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "stage=click_rejected reason=shizuku_unavailable");
                showTemporaryFeedback("请先启动 Shizuku");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "stage=click_rejected reason=permission_denied");
                showTemporaryFeedback("请先打开应用授权");
                return;
            }
            pending = new SwitchRequest(++nextRequestId);
            Log.i(TAG, "request=" + pending.id + " stage=click binder_alive="
                    + (taskService != null && taskService.asBinder().isBinderAlive()));
            handler.removeCallbacks(resetFeedback);
            updateFeedback("正在连接服务...");
            handler.postDelayed(readyTimeout, READY_TIMEOUT_MS);
            TaskServiceManager.acquire(this, taskServiceConnection);
            executePending();
        } catch (Exception e) {
            Log.e(TAG, "stage=click_failed", e);
            pending = null;
            handler.removeCallbacks(readyTimeout);
            showTemporaryFeedback("服务连接失败");
        }
    }

    private void executePending() {
        if (pending == null || pending.executing || pending.cancelled || destroyed || !listening) return;
        if (taskService == null || !taskService.asBinder().isBinderAlive()) return;
        if (SystemClock.elapsedRealtime() - pending.startedAt >= READY_TIMEOUT_MS) {
            readyTimeout.run();
            return;
        }
        SwitchRequest request = pending;
        ITaskService service = taskService; // Stable reference while UI lifecycle callbacks run.
        request.executing = true;
        handler.removeCallbacks(readyTimeout);
        updateFeedback("切换中...");
        Log.i(TAG, "request=" + request.id + " stage=ready elapsed_ms="
                + (SystemClock.elapsedRealtime() - request.startedAt));
        worker.execute(() -> performSwitch(request, service));
    }

    private void performSwitch(SwitchRequest request, ITaskService service) {
        String feedback = "✗ 操作失败";
        boolean launcherStopped = false;
        boolean moved = false;
        try {
            if (request.cancelled) return;
            if (lastMovedTask != null && lastMovedTask.equals(service.getForegroundAppOnDisplay(1))) {
                feedback = "✗ 背屏已占用";
                service.collapseStatusBar();
                String appName = getAppName(lastMovedTask.split(":")[0]);
                handler.postDelayed(() -> Toast.makeText(this,
                        getString(R.string.toast_please_switch_back, appName), Toast.LENGTH_LONG).show(), 300);
                return;
            }
            String currentApp = service.getCurrentForegroundApp();
            if (currentApp == null || !currentApp.contains(":")) {
                feedback = "✗ 未找到应用";
                Log.w(TAG, "request=" + request.id + " stage=no_foreground_task");
                return;
            }
            String[] parts = currentApp.split(":");
            int taskId = Integer.parseInt(parts[1]);
            Log.i(TAG, "request=" + request.id + " stage=target task_id=" + taskId + " display_id=1");
            if (request.cancelled) return;
            launcherStopped = service.disableSubScreenLauncher();
            if (request.cancelled) return;
            moved = service.moveTaskToDisplay(taskId, 1);
            Log.i(TAG, "request=" + request.id + " stage=move_result task_id=" + taskId + " verified=" + moved);
            if (!moved) {
                feedback = "✗ 切屏失败，请查看日志";
                return;
            }
            lastMovedTask = currentApp;
            Intent keeper = new Intent(this, RearScreenKeeperService.class);
            keeper.putExtra("lastMovedTask", currentApp);
            keeper.putExtra("keepScreenOnEnabled", getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
                    .getBoolean("flutter.keep_screen_on_enabled", true));
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(keeper);
                else startService(keeper);
                feedback = "✓ 已切换";
            } catch (Exception e) {
                feedback = "已切换，保活服务未启动";
                Log.e(TAG, "request=" + request.id + " stage=keeper_failed", e);
            }
            service.launchWakeActivity(1);
            service.collapseStatusBar();
            String appName = getAppName(parts[0]);
            handler.postDelayed(() -> Toast.makeText(this,
                    appName + " " + getString(R.string.toast_cast_to_rear), Toast.LENGTH_SHORT).show(), 300);
        } catch (Exception e) {
            Log.e(TAG, "request=" + request.id + " stage=operation_failed moved=" + moved, e);
            if (moved) feedback = "已切换，唤醒失败";
        } finally {
            if (launcherStopped && !moved) {
                try { service.enableSubScreenLauncher(); }
                catch (Exception e) { Log.w(TAG, "request=" + request.id + " stage=restore_launcher_failed", e); }
            }
            String result = feedback;
            handler.post(() -> {
                Log.i(TAG, "request=" + request.id + " stage=finished elapsed_ms="
                        + (SystemClock.elapsedRealtime() - request.startedAt));
                if (pending == request) pending = null;
                if (!destroyed && listening) showTemporaryFeedback(result);
                if (destroyed || !listening) releaseConnection();
            });
        }
    }

    private void updateFeedback(String message) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(message);
            if (Build.VERSION.SDK_INT >= 30) tile.setStateDescription("");
        }
        tile.updateTile();
    }

    private void showTemporaryFeedback(String message) {
        updateFeedback(message);
        handler.removeCallbacks(resetFeedback);
        handler.postDelayed(resetFeedback, 1500);
    }

    private String getAppName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName;
        }
    }
}
