package top.zainzh.rearscreen;

import android.content.Context;
import android.os.Binder;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.*;

/** App-side lease owner. Each cast gets a distinct binder and session ID. */
public final class RearInputProxySession implements AutoCloseable {
    public static final String PREFS = "input_proxy_prototype";
    public static boolean enabled(Context context) {
        return BuildConfig.INPUT_PROXY_PROTOTYPE && context.getSharedPreferences(PREFS, 0).getBoolean("enabled", false);
    }
    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", enabled && BuildConfig.INPUT_PROXY_PROTOTYPE).apply();
    }
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeat;
    private final Binder owner = new Binder();
    private final ITaskService service;
    private volatile boolean closed;
    private String id;
    public RearInputProxySession(Context context, ITaskService service, int task) {
        this.service = service;
        String path = new File(context.getApplicationInfo().nativeLibraryDir, "libremrss_input_proxy.so").getPath();
        worker.execute(() -> {
            try {
                if (closed) return;
                long deadline = android.os.SystemClock.uptimeMillis() + 3500;
                while (!closed && !service.isTaskOnDisplay(task, 1) && android.os.SystemClock.uptimeMillis() < deadline)
                    Thread.sleep(100);
                if (closed) return;
                id = new JSONObject(service.startRearInputProxy(path, task, owner)).getString("id");
                if (closed) service.stopRearInputProxy(id);
            } catch (Exception e) { Log.e("RearInputProxy", "start_failed", e); close(); }
        });
        heartbeat = worker.scheduleAtFixedRate(() -> {
            try { if (!closed && id != null && !service.renewRearInputProxyLease(id)) close(); }
            catch (Exception e) { Log.w("RearInputProxy", "lease_lost", e); close(); }
        }, 1000, 1000, TimeUnit.MILLISECONDS);
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        // Queued after start: a late start can only stop its own session.
        worker.execute(() -> { try { if (id != null) service.stopRearInputProxy(id); } catch (Exception ignored) { } });
        if (heartbeat != null) heartbeat.cancel(false);
    }
}
