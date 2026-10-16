package top.zainzh.rearscreen;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import rikka.shizuku.Shizuku;

/** One Shizuku connection shared by all consumers in this app process. */
public final class TaskServiceManager {
    private static final String TAG = "TaskServiceManager";
    private static final TaskServiceManager INSTANCE = new TaskServiceManager();
    private static final long BIND_TIMEOUT_MS = 5000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<ServiceConnection, String> consumers = new LinkedHashMap<>();
    private final Runnable retry = this::connect;
    private final Runnable bindTimeout = () -> disconnect("bind_timeout");
    private Shizuku.UserServiceArgs args;
    private ComponentName component;
    private ServiceConnection remoteConnection;
    private IBinder binder;
    private IBinder.DeathRecipient deathRecipient;
    private boolean binding;
    private long generation;
    private long bindStartedAt;

    public static void acquire(Context context, ServiceConnection consumer) {
        INSTANCE.onMain(() -> INSTANCE.add(context.getPackageName(), consumer));
    }

    public static void release(ServiceConnection consumer) {
        INSTANCE.onMain(() -> INSTANCE.remove(consumer));
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else handler.post(action);
    }

    private void add(String packageName, ServiceConnection consumer) {
        if (args == null) {
            component = new ComponentName(packageName, TaskService.class.getName());
            // 更新远端实现时递增，防止升级后复用旧的 shell 服务进程。
            args = new Shizuku.UserServiceArgs(component).daemon(false)
                    .processNameSuffix("task_service").debuggable(false).version(2);
            Shizuku.addBinderReceivedListenerSticky(() -> onMain(this::connect));
            Shizuku.addBinderDeadListener(() -> onMain(() -> disconnect("shizuku_died")));
            Shizuku.addRequestPermissionResultListener((request, result) -> onMain(this::connect));
        }
        boolean added = !consumers.containsKey(consumer);
        consumers.put(consumer, consumer.getClass().getName());
        if (added) Log.i(TAG, "acquire owner=" + consumers.get(consumer) + " owners=" + consumers.size());
        if (binder != null && binder.isBinderAlive()) {
            if (added) consumer.onServiceConnected(component, binder);
        } else {
            connect();
        }
    }

    private void remove(ServiceConnection consumer) {
        String owner = consumers.remove(consumer);
        if (owner == null) return;
        Log.i(TAG, "release owner=" + owner + " owners=" + consumers.size());
        if (consumers.isEmpty()) {
            handler.removeCallbacks(retry);
            detach();
        }
    }

    private void connect() {
        handler.removeCallbacks(retry);
        if (consumers.isEmpty() || binding) return;
        if (binder != null) {
            if (binder.isBinderAlive()) return;
            disconnect("binder_not_alive");
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "waiting reason=shizuku_unavailable owners=" + consumers.size());
                return; // Binder-received listener will resume the connection.
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "waiting reason=permission_denied owners=" + consumers.size());
                return; // Permission-result listener will resume the connection.
            }
            long attempt = ++generation;
            binding = true;
            bindStartedAt = SystemClock.elapsedRealtime();
            remoteConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    onMain(() -> connected(attempt, service));
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    onMain(() -> {
                        if (attempt == generation) disconnect("service_disconnected");
                    });
                }
            };
            Log.i(TAG, "bind_start generation=" + attempt + " owners=" + consumers.size());
            handler.postDelayed(bindTimeout, BIND_TIMEOUT_MS);
            Shizuku.bindUserService(args, remoteConnection);
        } catch (Exception e) {
            Log.e(TAG, "bind_failed", e);
            disconnect("bind_failed");
        }
    }

    private void connected(long attempt, IBinder service) {
        if (attempt != generation || consumers.isEmpty()) return;
        // The API may deliver the same connection more than once.
        if (binder != null && !binding && binder.isBinderAlive()) return;
        if (!service.isBinderAlive()) {
            disconnect("connected_binder_dead");
            return;
        }
        IBinder.DeathRecipient recipient = () -> onMain(() -> {
            if (attempt == generation) disconnect("task_service_died");
        });
        try {
            service.linkToDeath(recipient, 0);
        } catch (RemoteException e) {
            disconnect("link_to_death_failed");
            return;
        }
        binder = service;
        deathRecipient = recipient;
        binding = false;
        handler.removeCallbacks(bindTimeout);
        Log.i(TAG, "bind_ready generation=" + attempt + " elapsed_ms="
                + (SystemClock.elapsedRealtime() - bindStartedAt) + " owners=" + consumers.size());
        for (ServiceConnection consumer : new ArrayList<>(consumers.keySet())) {
            if (attempt == generation && binder == service && consumers.containsKey(consumer)) {
                consumer.onServiceConnected(component, service);
            }
        }
    }

    private void disconnect(String reason) {
        boolean wasConnected = binder != null;
        Log.w(TAG, "disconnected reason=" + reason + " owners=" + consumers.size());
        detach();
        if (wasConnected) {
            for (ServiceConnection consumer : new ArrayList<>(consumers.keySet())) {
                if (consumers.containsKey(consumer)) consumer.onServiceDisconnected(component);
            }
        }
        if (!consumers.isEmpty()) {
            handler.removeCallbacks(retry);
            handler.postDelayed(retry, 1000);
        }
    }

    private void detach() {
        ++generation; // Ignore callbacks from an earlier binding or an already released consumer.
        handler.removeCallbacks(bindTimeout);
        if (binder != null && deathRecipient != null) {
            try {
                binder.unlinkToDeath(deathRecipient, 0);
            } catch (RuntimeException e) {
                Log.w(TAG, "unlink_failed", e);
            }
        }
        binder = null;
        deathRecipient = null;
        binding = false;
        if (remoteConnection != null) {
            try {
                // Safe here: this manager is the ONLY connection for this component.
                // remove=false clears all API connections for the component, but does not kill it.
                Shizuku.unbindUserService(args, remoteConnection, false);
            } catch (Exception e) {
                Log.w(TAG, "detach_failed", e);
            }
            remoteConnection = null;
        }
    }
}
