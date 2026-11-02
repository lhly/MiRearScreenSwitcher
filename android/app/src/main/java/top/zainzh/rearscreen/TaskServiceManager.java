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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONObject;

import rikka.shizuku.Shizuku;

/** One selected privileged connection shared by all consumers in this app process. */
public final class TaskServiceManager {
    private static final String TAG = "TaskServiceManager";
    private static volatile String lastConnectionEvent = "not_requested";
    static String diagnostics() { return "mode=" + INSTANCE.mode + " state=" + INSTANCE.state + " " + lastConnectionEvent; }
    private static final TaskServiceManager INSTANCE = new TaskServiceManager();
    private static final long BIND_TIMEOUT_MS = 5000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<ServiceConnection, String> consumers = new LinkedHashMap<>();
    private final Runnable retry = this::connect;
    private final Runnable bindTimeout = () -> disconnect("bind_timeout");
    private volatile Context context;
    private PrivilegeBackend backend;
    private volatile PrivilegeMode mode = PrivilegeMode.SHIZUKU;
    private volatile PrivilegeBackend.State state = PrivilegeBackend.State.DISCONNECTED;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "privilege-switch"); t.setDaemon(true); return t; });
    private int failures;
    private volatile long switchSequence;
    private ComponentName component;
    private ServiceConnection remoteConnection;
    private volatile IBinder binder;
    private IBinder.DeathRecipient deathRecipient;
    private boolean binding;
    private long generation;
    private long bindStartedAt;

    public static void acquire(Context context, ServiceConnection consumer) {
        INSTANCE.onMain(() -> INSTANCE.add(context, consumer));
    }

    public static void release(ServiceConnection consumer) {
        INSTANCE.onMain(() -> INSTANCE.remove(consumer));
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else handler.post(action);
    }

    private synchronized void initialize(Context supplied) {
        if (context != null) return;
        context = supplied.getApplicationContext();
        mode = PrivilegeMode.read(context);
        component = new ComponentName(context.getPackageName(), TaskService.class.getName());
        backend = createBackend();
        Shizuku.addBinderReceivedListenerSticky(() -> onMain(() -> { if (mode == PrivilegeMode.SHIZUKU) connect(); }));
        Shizuku.addBinderDeadListener(() -> onMain(() -> { if (mode == PrivilegeMode.SHIZUKU) disconnect("shizuku_died"); }));
        Shizuku.addRequestPermissionResultListener((request, result) -> onMain(() -> { if (mode == PrivilegeMode.SHIZUKU) connect(); }));
    }

    private PrivilegeBackend createBackend() {
        return mode == PrivilegeMode.ROOT ? new RootBackend(context) : new ShizukuBackend(context.getPackageName());
    }

    public static boolean isRoot(Context context) { return PrivilegeMode.read(context) == PrivilegeMode.ROOT; }
    static PrivilegeBackend.State state(Context context) {
        INSTANCE.initialize(context);
        if (INSTANCE.state == PrivilegeBackend.State.SWITCHING) return INSTANCE.state;
        if (INSTANCE.mode == PrivilegeMode.SHIZUKU && INSTANCE.backend.availability() != PrivilegeBackend.State.READY)
            return INSTANCE.backend.availability();
        if (INSTANCE.state == PrivilegeBackend.State.AUTHORIZING
                || INSTANCE.state == PrivilegeBackend.State.BINDING) return INSTANCE.state;
        if (INSTANCE.binder != null && INSTANCE.binder.isBinderAlive()) return PrivilegeBackend.State.READY;
        return INSTANCE.mode == PrivilegeMode.ROOT ? INSTANCE.state : INSTANCE.backend.availability();
    }
    static boolean canConnect(Context context) {
        PrivilegeBackend.State value = state(context);
        return value == PrivilegeBackend.State.READY || value == PrivilegeBackend.State.BINDING
                || value == PrivilegeBackend.State.AUTHORIZING;
    }
    static void requestConnection(Context context) {
        INSTANCE.onMain(() -> { INSTANCE.initialize(context); INSTANCE.failures = 0; INSTANCE.connect(true); });
    }
    static void startSelected(Context context) {
        if (isRoot(context) && context.getSharedPreferences(PrivilegeMode.PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean("root_approved", false)) requestConnection(context);
    }

    static void switchMode(Context context, PrivilegeMode next, Consumer<String> completion) {
        INSTANCE.onMain(() -> INSTANCE.changeMode(context, next, completion));
    }
    private void changeMode(Context supplied, PrivilegeMode next, Consumer<String> completion) {
        initialize(supplied);
        if (next == mode) { completion.accept(null); return; }
        if (!PrivilegeOperations.beginSwitch()) { completion.accept("busy"); return; }
        PrivilegeBackend.State previous = state;
        state = PrivilegeBackend.State.SWITCHING;
        IBinder old = binder;
        boolean localBusy = ScreenRecordService.isRunning() || RearScreenKeeperService.isRunning();
        for (String owner : consumers.values()) {
            if (owner.contains("VirtualRearHostActivity") || owner.contains("ModuleRearActivity")
                    || owner.contains("RearInfoActivity") || owner.contains("RearInfoSession")
                    || owner.contains("NativeModuleLaunchActivity")) localBusy = true;
        }
        final boolean busy = localBusy;
        long sequence = ++switchSequence;
        handler.postDelayed(() -> {
            if (sequence != switchSequence || state != PrivilegeBackend.State.SWITCHING) return;
            ++switchSequence;
            state = binder != null && binder.isBinderAlive() ? previous : PrivilegeBackend.State.DISCONNECTED;
            PrivilegeOperations.endSwitch();
            completion.accept("failed");
        }, 8000);
        worker.execute(() -> {
            String error = null;
            try {
                if (busy) throw new IllegalStateException("busy");
                if (old != null && old.isBinderAlive()) {
                    ITaskService remote = ITaskService.Stub.asInterface(old);
                    for (String status : new String[]{remote.getRearVirtualCastStatus(), remote.getRearInputProxyStatus()}) {
                        String value = new JSONObject(status).optString("state", "idle");
                        if (!value.equals("idle") && !value.equals("stopped")) throw new IllegalStateException("busy");
                    }
                    int rear = RearDisplay.id();
                    String foreground = rear > 0 ? remote.getForegroundAppOnDisplay(rear) : null;
                    if (foreground != null && !foreground.startsWith(context.getPackageName() + ":")
                            && !foreground.startsWith("com.xiaomi.subscreencenter:")
                            && !foreground.startsWith("com.xiaomi.mirror:")
                            && !foreground.startsWith("com.miui.home:")
                            && !foreground.startsWith("com.android.systemui:")) throw new IllegalStateException("busy");
                }
            } catch (Exception failure) { error = "busy".equals(failure.getMessage()) ? "busy" : "failed"; }
            final String result = error;
            onMain(() -> {
                if (sequence != switchSequence) return;
                ++switchSequence;
                try {
                    if (result != null) { state = binder != null && binder.isBinderAlive() ? previous : PrivilegeBackend.State.DISCONNECTED; completion.accept(result); return; }
                    boolean notify = binder != null;
                    detach();
                    if (notify) for (ServiceConnection owner : new ArrayList<>(consumers.keySet())) {
                        if (consumers.containsKey(owner)) owner.onServiceDisconnected(component);
                    }
                    mode = next; mode.save(context); backend = createBackend(); failures = 0;
                    state = PrivilegeBackend.State.DISCONNECTED;
                    completion.accept(null);
                } finally { PrivilegeOperations.endSwitch(); }
                connect();
            });
        });
    }

    private void add(Context supplied, ServiceConnection consumer) {
        initialize(supplied);
        boolean added = !consumers.containsKey(consumer);
        consumers.put(consumer, consumer.getClass().getName());
        if (added) Log.i(TAG, "acquire owner=" + consumers.get(consumer) + " owners=" + consumers.size());
        if (state == PrivilegeBackend.State.SWITCHING) return;
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

    private void connect() { connect(false); }
    private void connect(boolean allowRequest) {
        handler.removeCallbacks(retry);
        if (consumers.isEmpty() || binding || state == PrivilegeBackend.State.SWITCHING) return;
        if (binder != null) {
            if (binder.isBinderAlive()) return;
            disconnect("binder_not_alive"); return;
        }
        if (mode == PrivilegeMode.ROOT && !allowRequest && (state == PrivilegeBackend.State.DENIED
                || state == PrivilegeBackend.State.ERROR || state == PrivilegeBackend.State.UNAVAILABLE)) return;
        long preparation = ++generation;
        binding = true;
        state = mode == PrivilegeMode.ROOT && allowRequest ? PrivilegeBackend.State.AUTHORIZING : PrivilegeBackend.State.BINDING;
        if (mode == PrivilegeMode.ROOT && allowRequest) handler.postDelayed(() -> {
            if (preparation == generation && state == PrivilegeBackend.State.AUTHORIZING) {
                detach(); state = PrivilegeBackend.State.ERROR; lastConnectionEvent = "authorization_timeout";
            }
        }, 60000);
        backend.prepare(allowRequest, available -> onMain(() -> prepared(preparation, available)));
    }
    private void prepared(long preparation, PrivilegeBackend.State available) {
            if (preparation != generation || consumers.isEmpty()) return;
            if (state == PrivilegeBackend.State.SWITCHING) {
                handler.postDelayed(() -> prepared(preparation, available), 50); return;
            }
            if (available != PrivilegeBackend.State.READY) {
                binding = false; state = available;
                lastConnectionEvent = available.name().toLowerCase(java.util.Locale.ROOT);
                return;
            }
            if (mode == PrivilegeMode.ROOT) context.getSharedPreferences(PrivilegeMode.PREFERENCES, Context.MODE_PRIVATE)
                    .edit().putBoolean("root_approved", true).apply();
            bindRemote();
    }

    private void bindRemote() {
        try {
            state = PrivilegeBackend.State.BINDING;
            long attempt = ++generation;
            binding = true;
            lastConnectionEvent = "binding generation=" + attempt;
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
            backend.bind(remoteConnection);
        } catch (Exception e) {
            Log.e(TAG, "bind_failed", e);
            disconnect("bind_failed");
        }
    }

    private void connected(long attempt, IBinder service) {
        if (attempt != generation || consumers.isEmpty() || state == PrivilegeBackend.State.SWITCHING) return;
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
        RearDisplay.attach(ITaskService.Stub.asInterface(service));
        binder = service;
        deathRecipient = recipient;
        binding = false;
        state = PrivilegeBackend.State.READY;
        failures = 0;
        handler.removeCallbacks(bindTimeout);
        lastConnectionEvent = "ready generation=" + attempt;
        Log.i(TAG, "bind_ready generation=" + attempt + " elapsed_ms="
                + (SystemClock.elapsedRealtime() - bindStartedAt) + " owners=" + consumers.size());
        for (ServiceConnection consumer : new ArrayList<>(consumers.keySet())) {
            if (attempt == generation && binder == service && consumers.containsKey(consumer)) {
                consumer.onServiceConnected(component, service);
            }
        }
    }

    private void disconnect(String reason) {
        boolean switching = state == PrivilegeBackend.State.SWITCHING;
        lastConnectionEvent = reason;
        boolean wasConnected = binder != null;
        Log.w(TAG, "disconnected reason=" + reason + " owners=" + consumers.size());
        detach();
        if (wasConnected) {
            for (ServiceConnection consumer : new ArrayList<>(consumers.keySet())) {
                if (consumers.containsKey(consumer)) consumer.onServiceDisconnected(component);
            }
        }
        state = switching ? PrivilegeBackend.State.SWITCHING : PrivilegeBackend.State.DISCONNECTED;
        if (!switching && !consumers.isEmpty()) {
            handler.removeCallbacks(retry);
            if (mode == PrivilegeMode.SHIZUKU || ++failures <= 2) handler.postDelayed(retry, mode == PrivilegeMode.ROOT ? 1000L * failures : 1000);
            else state = PrivilegeBackend.State.ERROR;
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
        if (state != PrivilegeBackend.State.SWITCHING) state = PrivilegeBackend.State.DISCONNECTED;
        if (remoteConnection != null) {
            try {
                // Safe here: this manager is the ONLY connection for this component.
                // remove=false clears all API connections for the component, but does not kill it.
                backend.unbind(remoteConnection);
            } catch (Exception e) {
                Log.w(TAG, "detach_failed", e);
            }
            remoteConnection = null;
        }
    }
}
