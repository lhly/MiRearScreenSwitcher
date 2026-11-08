package top.zainzh.rearscreen.platform.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import org.json.JSONException;
import org.json.JSONObject;
import top.zainzh.rearscreen.BuildConfig;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One binder-created instance owns one QuickJS runtime. Register only as a private
 * isolatedProcess service and bind it with Context.bindIsolatedService on API 29+.
 */
public final class ModRuntimeWorkerService extends Service {
    private static final int SOURCE_LIMIT_BYTES = 512 * 1024;
    private static final int MESSAGE_LIMIT_BYTES = 256 * 1024;

    private final ThreadPoolExecutor engineExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            runnable -> {
                Thread thread = new Thread(runnable, "remrss-qjs-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private volatile long runtimeHandle;
    private volatile boolean closed;
    private final AtomicBoolean teardownStarted = new AtomicBoolean();

    private final IModRuntimeWorker.Stub binder = new IModRuntimeWorker.Stub() {
        @Override public String loadModule(ParcelFileDescriptor sourceFd, int sourceLength) {
            if (!isHostCall()) return error("E_CALLER_DENIED", "only the owning app process may call this worker");
            if (sourceFd == null || sourceLength < 0 || sourceLength > SOURCE_LIMIT_BYTES) {
                closeQuietly(sourceFd);
                return error("E_MESSAGE_TOO_LARGE", "module source exceeds 512 KiB");
            }
            String source;
            try {
                source = readSource(sourceFd, sourceLength);
            } catch (IOException e) {
                return error("E_INVALID_INPUT", "module source channel is incomplete or is not UTF-8");
            }
            return runBounded(() -> {
                ensureRuntime();
                return retireIfBudgetExceeded(QuickJsNative.nativeLoadModule(runtimeHandle, source));
            });
        }

        @Override public String init(String contextJson) {
            if (!isHostCall()) return error("E_CALLER_DENIED", "only the owning app process may call this worker");
            if (!bounded(contextJson, MESSAGE_LIMIT_BYTES)) return error("E_MESSAGE_TOO_LARGE", "context exceeds 256 KiB");
            return runBounded(() -> invoke("init", contextJson));
        }

        @Override public String reduce(String contextJson) {
            if (!isHostCall()) return error("E_CALLER_DENIED", "only the owning app process may call this worker");
            if (!bounded(contextJson, MESSAGE_LIMIT_BYTES)) return error("E_MESSAGE_TOO_LARGE", "context exceeds 256 KiB");
            return runBounded(() -> invoke("reduce", contextJson));
        }

        @Override public String migrate(String migrationContextJson) {
            if (!isHostCall()) return error("E_CALLER_DENIED", "only the owning app process may call this worker");
            if (!bounded(migrationContextJson, MESSAGE_LIMIT_BYTES)) return error("E_MESSAGE_TOO_LARGE", "migration context exceeds 256 KiB");
            return runBounded(() -> invoke("migrate", migrationContextJson));
        }

        @Override public int workerPid() {
            if (!isHostCall()) throw new SecurityException("only the owning app process may inspect this worker");
            if (!Process.isIsolated()) throw new SecurityException("worker is not isolated");
            return Process.myPid();
        }

        @Override public long workerPssKb() {
            if (!isHostCall()) throw new SecurityException("only the owning app process may inspect this worker");
            if (!Process.isIsolated()) throw new SecurityException("worker is not isolated");
            return android.os.Debug.getPss();
        }

        @Override public int budgetCapabilityFlags() {
            if (!isHostCall()) throw new SecurityException("only the owning app process may inspect this worker");
            long handle = runtimeHandle;
            return handle == 0 ? 0 : QuickJsNative.nativeBudgetCapabilityFlags(handle);
        }

        @Override public String pauseBudgetThreadForTesting() {
            requireDebugTestCaller();
            long handle = runtimeHandle;
            if (handle == 0) return error("E_NOT_INITIALIZED", "loadModule must complete before runtime diagnostics");
            return runBounded(() -> {
                String result = QuickJsNative.nativePauseBudgetThreadForTesting(handle);
                return attachCpuDiagnostic(result, QuickJsNative.nativeLastExecutionCpuTimeNanos(handle));
            });
        }

        @Override public void stallForWatchdogTesting() {
            requireDebugTestCaller();
            long handle = runtimeHandle;
            if (handle == 0) throw new IllegalStateException("loadModule must complete before runtime diagnostics");
            runBounded(() -> {
                QuickJsNative.nativeStallForWatchdogTesting(handle);
                return "{\"ok\":true}";
            });
        }

        @Override public void crashForTesting() {
            if (!BuildConfig.DEBUG || !isHostCall()) throw new SecurityException("debug-only worker crash hook");
            Process.killProcess(Process.myPid());
        }

        @Override public void close() {
            if (!isHostCall()) throw new SecurityException("only the owning app process may close this worker");
            teardownWorker();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (android.os.Build.VERSION.SDK_INT < 29 || !Process.isIsolated()) {
            closed = true;
            stopSelf();
        }
    }

    @Override public IBinder onBind(Intent intent) {
        // onBind is a framework lifecycle callback, not a remote Binder transaction;
        // Binder.getCallingUid() here may be the worker's own UID. Authenticate each
        // actual Stub call instead.
        if (closed || !Process.isIsolated()) return null;
        return binder;
    }

    @Override public boolean onUnbind(Intent intent) {
        teardownWorker();
        return false;
    }

    @Override public void onDestroy() {
        teardownWorker();
        super.onDestroy();
    }

    private void teardownWorker() {
        if (!teardownStarted.compareAndSet(false, true)) return;
        closed = true;
        if (!engineExecutor.isShutdown()) {
            try {
                FutureTask<Void> task = new FutureTask<>(() -> { destroyRuntime(); return null; });
                engineExecutor.execute(task);
                task.get();
            } catch (Exception e) {
                // Runtime cleanup is mandatory. If a native call cannot return, reclaim
                // the process rather than keeping a hidden worker alive.
                Process.killProcess(Process.myPid());
            } finally {
                engineExecutor.shutdownNow();
            }
        }
        stopSelf();
    }

    private String invoke(String method, String contextJson) {
        if (closed) return error("E_CLOSED", "runtime is closed");
        long handle = runtimeHandle;
        if (handle == 0) return error("E_NOT_INITIALIZED", "loadModule must complete before init or reduce");
        return retireIfBudgetExceeded(QuickJsNative.nativeInvoke(handle, method, contextJson));
    }

    private String retireIfBudgetExceeded(String result) {
        long handle = runtimeHandle;
        if (handle != 0) result = attachCpuDiagnostic(result, QuickJsNative.nativeLastExecutionCpuTimeNanos(handle));
        String code = runtimeErrorCode(result);
        if ("E_BUDGET_EXCEEDED".equals(code) || "E_CPU_BUDGET".equals(code) ||
                "E_WALL_BUDGET".equals(code) || "E_MICROTASK_LIMIT".equals(code) || "E_BUDGET_UNAVAILABLE".equals(code)) {
            closed = true;
            destroyRuntime();
            engineExecutor.shutdown();
            stopSelf();
        }
        return result;
    }

    private static String attachCpuDiagnostic(String result, long cpuTimeNanos) {
        if (result == null) return null;
        try {
            JSONObject envelope = new JSONObject(result);
            if (!envelope.optBoolean("__remrss_runtime_v1", false)) return result;
            envelope.put("cpuTimeNanos", Math.max(0L, cpuTimeNanos));
            return envelope.toString();
        } catch (JSONException ignored) {
            return result;
        }
    }

    private boolean isHostCall() {
        return Process.isIsolated() && Binder.getCallingUid() == getApplicationInfo().uid;
    }

    private void requireDebugTestCaller() {
        if (!BuildConfig.DEBUG || !isHostCall() || !Process.isIsolated()) {
            throw new SecurityException("debug-only isolated runtime watchdog test");
        }
    }

    private void ensureRuntime() throws RemoteException {
        if (closed) throw new RemoteException("runtime is closed");
        if (runtimeHandle != 0) throw new RemoteException("module is already loaded");
        long handle = QuickJsNative.nativeCreate();
        if (handle == 0) throw new RemoteException("QuickJS runtime allocation failed");
        runtimeHandle = handle;
    }

    private void destroyRuntime() {
        long handle = runtimeHandle;
        runtimeHandle = 0;
        if (handle != 0) QuickJsNative.nativeDestroy(handle);
    }

    private String runBounded(Callable<String> call) {
        if (closed) return error("E_CLOSED", "runtime is closed");
        FutureTask<String> task = new FutureTask<>(call);
        try {
            engineExecutor.execute(task);
            return task.get();
        } catch (RejectedExecutionException e) {
            return error("E_BUSY", "worker already has queued work");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("E_CANCELLED", "worker call was interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return error("E_WORKER_FAILURE", cause == null ? "worker call failed" : cause.getClass().getSimpleName());
        }
    }

    private static boolean bounded(String value, int limit) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length <= limit;
    }

    private static String readSource(ParcelFileDescriptor sourceFd, int sourceLength) throws IOException {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(sourceFd);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream(sourceLength)) {
            byte[] chunk = new byte[8192];
            int total = 0;
            while (total < sourceLength) {
                int read = input.read(chunk, 0, Math.min(chunk.length, sourceLength - total));
                if (read < 0) throw new IOException("truncated source");
                bytes.write(chunk, 0, read);
                total += read;
            }
            if (input.read() != -1) throw new IOException("source length mismatch");
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (IOException ignored) { }
    }

    private static String error(String code, String message) {
        JSONObject envelope = new JSONObject();
        JSONObject failure = new JSONObject();
        try {
            envelope.put("__remrss_runtime_v1", true);
            envelope.put("ok", false);
            failure.put("code", code);
            failure.put("message", message);
            envelope.put("error", failure);
            return envelope.toString();
        } catch (JSONException impossible) {
            return "{\"__remrss_runtime_v1\":true,\"ok\":false,\"code\":\"E_INTERNAL\",\"message\":\"could not encode error\"}";
        }
    }

    private static String runtimeErrorCode(String result) {
        if (result == null) return null;
        try {
            JSONObject envelope = new JSONObject(result);
            if (!envelope.optBoolean("__remrss_runtime_v1", false) || envelope.optBoolean("ok", true)) return null;
            JSONObject failure = envelope.optJSONObject("error");
            return envelope.optString("code", failure == null ? null : failure.optString("code", null));
        } catch (JSONException ignored) {
            return null;
        }
    }
}
