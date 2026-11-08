package top.zainzh.rearscreen.platform.runtime;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import org.json.JSONException;
import org.json.JSONObject;
import top.zainzh.rearscreen.BuildConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Client for one isolated JavaScript Mod runtime. Never call binder methods on a UI thread. */
public final class IsolatedModRuntimeClient implements AutoCloseable {
    public static final int MAX_ACTIVE_WORKERS = 2;
    public static final int MAX_SOURCE_BYTES = 512 * 1024;
    public static final int MAX_MESSAGE_BYTES = 256 * 1024;
    private static final AtomicInteger ACTIVE_WORKERS = new AtomicInteger();
    private static final AtomicReference<CloseBarrierForTesting> NEXT_CLOSE_BARRIER = new AtomicReference<>();
    // Keep framework lifecycle callbacks independent from the owner executor: an
    // owner may shut that executor down after closeAsync completes.
    private static final Executor CONNECTION_EXECUTOR = Runnable::run;

    /** Fixed package-private gate used only to assert slot retention before real Binder close. */
    static final class CloseBarrierForTesting {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException { return entered.await(timeout, unit); }
        void release() { release.countDown(); }
    }

    static CloseBarrierForTesting blockNextCloseForTesting() {
        if (!BuildConfig.DEBUG) throw new SecurityException("debug-only close completion test gate");
        CloseBarrierForTesting barrier = new CloseBarrierForTesting();
        if (!NEXT_CLOSE_BARRIER.compareAndSet(null, barrier)) {
            throw new IllegalStateException("a close completion test gate is already installed");
        }
        return barrier;
    }

    private static void awaitCloseBarrierForTesting() {
        if (!BuildConfig.DEBUG) return;
        CloseBarrierForTesting barrier = NEXT_CLOSE_BARRIER.getAndSet(null);
        if (barrier == null) return;
        barrier.entered.countDown();
        try { barrier.release.await(); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    public interface Listener {
        void onConnected(long epoch);
        void onResult(long epoch, String operation, String json);
        void onDisconnected(long epoch, String reason);

        /**
         * Called only for a failure decoded from the private engine envelope.
         * Legacy listeners keep their prior callback contract through this default.
         * Consumers that override this method receive the typed failure instead of
         * the legacy error JSON via onResult.
         */
        default void onEngineError(long epoch, String operation, EngineCallError error) {
            onResult(epoch, operation, error.toLegacyJson());
        }
    }

    /** Classification of a failure created by the runtime engine, not script data. */
    public enum EngineFaultCategory {
        SCRIPT,
        MIGRATION,
        INVALID_RESULT,
        BUDGET,
        INPUT,
        RUNTIME_STATE,
        OTHER
    }

    /** Immutable engine failure metadata; terminal means the old epoch must be reset. */
    public static final class EngineCallError {
        private final String code;
        private final String message;
        private final EngineFaultCategory category;
        private final boolean workerReusable;
        private final boolean terminal;
        private final String legacyJson;

        private EngineCallError(String code, String message, EngineFaultCategory category,
                                boolean workerReusable, boolean terminal, String legacyJson) {
            this.code = code;
            this.message = message;
            this.category = category;
            this.workerReusable = workerReusable;
            this.terminal = terminal;
            this.legacyJson = legacyJson;
        }

        public String code() { return code; }
        public String message() { return message; }
        public EngineFaultCategory category() { return category; }
        public boolean workerReusable() { return workerReusable; }
        public boolean terminal() { return terminal; }

        private String toLegacyJson() { return legacyJson; }
    }

    private final Context ownerContext;
    private final long epoch;
    private final Executor callbackExecutor;
    private final Listener listener;
    // ActivityManager accepts only ASCII letters/digits/period/underscore here.
    private final String instanceName = "mod" + UUID.randomUUID().toString().replace("-", "");
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean intentionalCloseRequested = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final AtomicBoolean slotReleased = new AtomicBoolean();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private final Object unbindLock = new Object();
    private final ThreadPoolExecutor rpcExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            runnable -> { Thread thread = new Thread(runnable, "remrss-mod-runtime-rpc"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    private volatile IModRuntimeWorker remote;
    private volatile boolean bound;
    private volatile long lastExecutionCpuTimeNanos = -1;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            IModRuntimeWorker worker = IModRuntimeWorker.Stub.asInterface(service);
            if (closed.get()) {
                // A close racing a pending bind can receive the callback only if
                // ActivityManager had already committed the connection. The close
                // path owns teardown; never publish it as a usable client again.
                beginClose(worker, false);
                return;
            }
            remote = worker;
            if (closed.get()) {
                remote = null;
                beginClose(worker, false);
                return;
            }
            dispatchClientCallback("onConnected", () -> listener.onConnected(epoch));
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            if (closed.get()) { finishAfterConfirmedDeath(); return; }
            failAndUnbind("E_WORKER_DIED: isolated worker process exited", true);
        }

        @Override public void onBindingDied(ComponentName name) {
            remote = null;
            if (closed.get()) { finishAfterConfirmedDeath(); return; }
            failAndUnbind("E_WORKER_DIED: isolated worker binding died", true);
        }

        @Override public void onNullBinding(ComponentName name) {
            remote = null;
            // No AIDL client call could have created a native runtime; revoke the
            // pending bind and release only after that unbind completes.
            failAndUnbind("isolated worker did not provide a binder", false);
        }
    };

    public static IsolatedModRuntimeClient bind(Context context, long epoch, Executor callbackExecutor, Listener listener) {
        if (Build.VERSION.SDK_INT < 29) throw new UnsupportedOperationException("script Mods require Android API 29 or later");
        if (context == null || callbackExecutor == null || listener == null) throw new IllegalArgumentException("client arguments must not be null");
        int active;
        do {
            active = ACTIVE_WORKERS.get();
            if (active >= MAX_ACTIVE_WORKERS) throw new IllegalStateException("E_BUSY: at most two isolated Mod workers may be active");
        } while (!ACTIVE_WORKERS.compareAndSet(active, active + 1));
        IsolatedModRuntimeClient client = new IsolatedModRuntimeClient(context, epoch, callbackExecutor, listener);
        try {
            Intent intent = new Intent(client.ownerContext, ModRuntimeWorkerService.class);
            client.bound = client.ownerContext.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, client.instanceName, CONNECTION_EXECUTOR, client.connection);
            if (!client.bound) throw new IllegalStateException("isolated worker binding was rejected");
            return client;
        } catch (RuntimeException e) {
            client.closed.set(true);
            if (client.bound) {
                try { client.ownerContext.unbindService(client.connection); client.bound = false; }
                catch (IllegalArgumentException ignored) { /* keep the slot until the pending binding dies */ }
            }
            if (!client.bound) client.releaseSlot();
            client.rpcExecutor.shutdownNow();
            throw e;
        }
    }

    private IsolatedModRuntimeClient(Context context, long epoch, Executor callbackExecutor, Listener listener) {
        Context applicationContext = context.getApplicationContext();
        this.ownerContext = applicationContext == null ? context : applicationContext;
        this.epoch = epoch;
        this.callbackExecutor = callbackExecutor;
        this.listener = listener;
    }

    /** Reads source from the writer pipe in the worker; source bytes never cross as Binder String. */
    public void loadModule(byte[] sourceUtf8) { submit("loadModule", sourceUtf8); }

    /** Call only when no committed model exists; persist the returned JSON before exposing it. */
    public void init(String contextJson) { submit("init", contextJson); }

    /** Send one ordered event with the last committed state/config/environment snapshot. */
    public void reduce(String contextJson) { submit("reduce", contextJson); }

    /** Migration is pure and bounded; the host validates both new schemas before commit. */
    public void migrate(String migrationContextJson) { submit("migrate", migrationContextJson); }

    public boolean isConnected() { return !closed.get() && remote != null; }

    /** Test/diagnostic proof that this connection is backed by a distinct isolated process. */
    public void readWorkerPid() {
        try {
            rpcExecutor.execute(() -> {
                IModRuntimeWorker worker = remote;
                if (worker == null) { notifyFailure("workerPid", "E_NOT_READY: isolated worker is not connected"); return; }
                try {
                    int pid = worker.workerPid();
                    dispatchClientCallback("workerPid", () -> listener.onResult(epoch, "workerPid", "{\"pid\":" + pid + "}"));
                } catch (RemoteException e) { failAndUnbind("E_WORKER_FAILURE: " + e.getClass().getSimpleName(), e instanceof android.os.DeadObjectException); }
            });
        } catch (RuntimeException e) {
            notifyFailure("workerPid", "E_BUSY: runtime client queue is full");
        }
    }

    public void readWorkerPssKb() {
        try {
            rpcExecutor.execute(() -> {
                IModRuntimeWorker worker = remote;
                if (worker == null) { notifyFailure("workerPssKb", "E_NOT_READY: isolated worker is not connected"); return; }
                try {
                    long pssKb = worker.workerPssKb();
                    dispatchClientCallback("workerPssKb", () -> listener.onResult(epoch, "workerPssKb", "{\"pssKb\":" + pssKb + "}"));
                } catch (RemoteException e) { failAndUnbind("E_WORKER_FAILURE: " + e.getClass().getSimpleName(), e instanceof android.os.DeadObjectException); }
            });
        } catch (RuntimeException e) {
            notifyFailure("workerPssKb", "E_BUSY: runtime client queue is full");
        }
    }

    /** Bit 0: target-thread CPU clock; bit 1: optional schedstat diagnostics; bit 2: independent CPU watchdog. */
    public void readBudgetCapabilityFlags() {
        try {
            rpcExecutor.execute(() -> {
                IModRuntimeWorker worker = remote;
                if (worker == null) { notifyFailure("budgetCapabilityFlags", "E_NOT_READY: isolated worker is not connected"); return; }
                try {
                    int flags = worker.budgetCapabilityFlags();
                    dispatchClientCallback("budgetCapabilityFlags", () -> listener.onResult(epoch, "budgetCapabilityFlags", "{\"flags\":" + flags + "}"));
                } catch (RemoteException e) { failAndUnbind("E_WORKER_FAILURE: " + e.getClass().getSimpleName(), e instanceof android.os.DeadObjectException); }
            });
        } catch (RuntimeException e) {
            notifyFailure("budgetCapabilityFlags", "E_BUSY: runtime client queue is full");
        }
    }

    /** Fixed 1.5s off-CPU pause, available only through the worker's Debug AIDL implementation. */
    void pauseBudgetThreadForTesting() {
        try {
            rpcExecutor.execute(() -> {
                IModRuntimeWorker worker = remote;
                if (worker == null) { notifyFailure("pauseBudgetThreadForTesting", "E_NOT_READY: isolated worker is not connected"); return; }
                try {
                    RuntimeReply reply = decodeReply(worker.pauseBudgetThreadForTesting());
                    if (reply == null) { failAndUnbind("E_PROTOCOL: invalid watchdog test result"); return; }
                    lastExecutionCpuTimeNanos = reply.cpuTimeNanos;
                    dispatchClientCallback("pauseBudgetThreadForTesting", () -> listener.onResult(epoch, "pauseBudgetThreadForTesting", reply.publicJson));
                } catch (RemoteException e) { failAndUnbind("E_WORKER_FAILURE: " + e.getClass().getSimpleName(), e instanceof android.os.DeadObjectException); }
            });
        } catch (RuntimeException e) {
            notifyFailure("pauseBudgetThreadForTesting", "E_BUSY: runtime client queue is full");
        }
    }

    /** Fixed native busy loop for proving the independent CPU watchdog; always kills this worker. */
    void stallForWatchdogTesting() {
        try {
            rpcExecutor.execute(() -> {
                IModRuntimeWorker worker = remote;
                if (worker == null) { notifyFailure("stallForWatchdogTesting", "E_NOT_READY: isolated worker is not connected"); return; }
                try { worker.stallForWatchdogTesting(); }
                catch (RemoteException e) { failAndUnbind("E_WORKER_DIED: isolated worker process exited", e instanceof android.os.DeadObjectException); }
            });
        } catch (RuntimeException e) {
            notifyFailure("stallForWatchdogTesting", "E_BUSY: runtime client queue is full");
        }
    }

    /** Debug-build-only fault injection used by instrumentation tests. */
    public void crashForTesting() {
        try {
            rpcExecutor.execute(() -> {
                IModRuntimeWorker worker = remote;
                if (worker == null) { notifyFailure("crashForTesting", "E_NOT_READY: isolated worker is not connected"); return; }
                try { worker.crashForTesting(); }
                catch (RemoteException e) { failAndUnbind("E_WORKER_DIED: isolated worker process exited", e instanceof android.os.DeadObjectException); }
            });
        } catch (RuntimeException e) {
            notifyFailure("crashForTesting", "E_BUSY: runtime client queue is full");
        }
    }

    private void submit(String operation, Object payload) {
        if (closed.get()) { notifyFailure(operation, "E_CLOSED: runtime client is closed"); return; }
        if (!(payload instanceof byte[]) && (!(payload instanceof String) ||
                ((String) payload).getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES)) {
            notifyFailure(operation, "E_MESSAGE_TOO_LARGE: message exceeds 256 KiB");
            return;
        }
        if (payload instanceof byte[] && ((byte[]) payload).length > MAX_SOURCE_BYTES) {
            notifyFailure(operation, "E_MESSAGE_TOO_LARGE: source exceeds 512 KiB");
            return;
        }
        if (payload instanceof byte[]) payload = ((byte[]) payload).clone();
        final Object requestPayload = payload;
        try {
            rpcExecutor.execute(() -> execute(operation, requestPayload));
        } catch (RuntimeException e) {
            notifyFailure(operation, "E_BUSY: runtime client queue is full");
        }
    }

    private void execute(String operation, Object payload) {
        if (closed.get()) return;
        IModRuntimeWorker worker = remote;
        if (worker == null) { notifyFailure(operation, "E_NOT_READY: isolated worker is not connected"); return; }
        try {
            String result;
            if ("loadModule".equals(operation)) result = loadSource(worker, (byte[]) payload);
            else if ("init".equals(operation)) result = worker.init((String) payload);
            else if ("migrate".equals(operation)) result = worker.migrate((String) payload);
            else result = worker.reduce((String) payload);
            if (closed.get()) return;
            // Keep the epoch on every reply. The owner, not this transport, decides
            // whether a reply belongs to the current model transaction.
            RuntimeReply reply = decodeReply(result);
            if (reply == null) {
                failAndUnbind("E_PROTOCOL: isolated worker returned an invalid result envelope");
                return;
            }
            if (reply.cpuTimeNanos >= 0) lastExecutionCpuTimeNanos = reply.cpuTimeNanos;
            if (reply.engineError != null) {
                // Keep the typed disposition in-band with the matching operation;
                // callers must never infer a budget fault from isConnected timing.
                dispatchClientCallback(operation, () -> listener.onEngineError(epoch, operation, reply.engineError));
            } else {
                dispatchClientCallback(operation, () -> listener.onResult(epoch, operation, reply.publicJson));
            }
            // Preserve the established client auto-close order for execution-budget
            // failures. Other terminal classifications (notably E_SCRIPT, which can
            // also mask allocator failure) require consumer-coordinated closeAsync().
            if (reply.engineError != null && reply.engineError.category() == EngineFaultCategory.BUDGET) close();
        } catch (RemoteException | IOException e) {
            String reason = e instanceof android.os.DeadObjectException
                    ? "E_WORKER_DIED: isolated worker process exited"
                    : "E_WORKER_FAILURE: " + e.getClass().getSimpleName();
            failAndUnbind(reason, e instanceof android.os.DeadObjectException);
        }
    }

    private String loadSource(IModRuntimeWorker worker, byte[] source) throws RemoteException, IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor readEnd = pipe[0];
        ParcelFileDescriptor writeEnd = pipe[1];
        Thread writer = new Thread(() -> {
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                out.write(source);
                out.flush();
            } catch (IOException ignored) { }
        }, "remrss-mod-source-writer");
        writer.setDaemon(true);
        writer.start();
        try {
            return worker.loadModule(readEnd, source.length);
        } finally {
            try { readEnd.close(); } catch (IOException ignored) { }
        }
    }

    private static RuntimeReply decodeReply(String result) {
        try {
            JSONObject envelope = new JSONObject(result);
            if (!envelope.optBoolean("__remrss_runtime_v1", false)) return null;
            if (envelope.optBoolean("ok", false)) {
                Object payload = envelope.get("payload");
                return new RuntimeReply(payloadJson(payload), null, envelope.optLong("cpuTimeNanos", -1));
            }
            String code = envelope.optString("code", null);
            JSONObject error = envelope.optJSONObject("error");
            if (code == null && error != null) code = error.optString("code", null);
            String message = envelope.optString("message", error == null ? "runtime call failed" : error.optString("message", "runtime call failed"));
            if (code == null) return null;
            JSONObject publicError = new JSONObject();
            publicError.put("ok", false);
            publicError.put("code", code);
            publicError.put("message", message);
            String legacyJson = publicError.toString();
            EngineFaultCategory category = faultCategory(code);
            // QuickJS exception conversion uses E_SCRIPT both for ordinary throws
            // and resource exhaustion (for example an 8 MiB heap OOM). Never promise
            // that such a worker is safe to reuse; the owner must close and reload
            // the last committed model in a new epoch before retrying.
            boolean terminal = category == EngineFaultCategory.BUDGET || "E_SCRIPT".equals(code);
            boolean reusable = !terminal;
            EngineCallError engineError = new EngineCallError(code, message, category, reusable, terminal, legacyJson);
            return new RuntimeReply(legacyJson, engineError, envelope.optLong("cpuTimeNanos", -1));
        } catch (JSONException e) {
            return null;
        }
    }

    private static EngineFaultCategory faultCategory(String code) {
        if ("E_SCRIPT".equals(code)) return EngineFaultCategory.SCRIPT;
        if ("E_MIGRATION_REQUIRED".equals(code) || "E_INVALID_MIGRATION".equals(code)) return EngineFaultCategory.MIGRATION;
        if ("E_INVALID_RESULT".equals(code)) return EngineFaultCategory.INVALID_RESULT;
        if ("E_BUDGET_EXCEEDED".equals(code) || "E_CPU_BUDGET".equals(code) ||
                "E_WALL_BUDGET".equals(code) || "E_MICROTASK_LIMIT".equals(code) ||
                "E_BUDGET_UNAVAILABLE".equals(code)) return EngineFaultCategory.BUDGET;
        if ("E_INVALID_INPUT".equals(code) || "E_MESSAGE_TOO_LARGE".equals(code)) return EngineFaultCategory.INPUT;
        if ("E_CLOSED".equals(code) || "E_NOT_INITIALIZED".equals(code) || "E_BUSY".equals(code)) {
            return EngineFaultCategory.RUNTIME_STATE;
        }
        return EngineFaultCategory.OTHER;
    }

    private static final class RuntimeReply {
        final String publicJson;
        final EngineCallError engineError;
        final long cpuTimeNanos;
        RuntimeReply(String publicJson, EngineCallError engineError, long cpuTimeNanos) {
            this.publicJson = publicJson;
            this.engineError = engineError;
            this.cpuTimeNanos = cpuTimeNanos;
        }
    }

    /** Last engine-thread CPU consumption in nanoseconds; -1 until the first native operation completes. */
    public long lastExecutionCpuTimeNanos() { return lastExecutionCpuTimeNanos; }

    private static String payloadJson(Object payload) {
        if (payload == JSONObject.NULL) return "null";
        if (payload instanceof String) return JSONObject.quote((String) payload);
        return payload.toString();
    }

    private void notifyFailure(String operation, String reason) {
        dispatchClientCallback(operation, () -> listener.onDisconnected(epoch, operation + ": " + reason));
    }

    private void dispatchClientCallback(String operation, Runnable callback) {
        if (closed.get()) return;
        try {
            callbackExecutor.execute(callback);
        } catch (RejectedExecutionException rejected) {
            // A close racing this submission makes the late callback intentionally
            // irrelevant. While live, report the lost accepted response explicitly
            // and close/reclaim the worker rather than leaking rejection on rpc thread.
            if (closed.get() || intentionalCloseRequested.get()) return;
            failAndUnbind(operation + ": E_CALLBACK_DISPATCH: owner callback executor rejected a runtime callback", false);
        }
    }

    private void dispatchTerminalFailure(String reason) {
        if (intentionalCloseRequested.get()) return;
        try {
            callbackExecutor.execute(() -> listener.onDisconnected(epoch, reason));
        } catch (RejectedExecutionException rejected) {
            if (intentionalCloseRequested.get()) return;
            // The owner executor cannot accept its terminal error; report that
            // explicitly inline as a last resort, never throw on the Binder/RPC thread.
            try {
                listener.onDisconnected(epoch, "E_CALLBACK_DISPATCH: " + reason +
                        "; owner callback executor rejected terminal notification");
            } catch (RuntimeException ignored) { }
        }
    }

    private void failAndUnbind(String reason) {
        failAndUnbind(reason, false);
    }

    private void failAndUnbind(String reason, boolean workerReleasedOrDead) {
        if (!closed.compareAndSet(false, true)) {
            if (workerReleasedOrDead) finishAfterConfirmedDeath();
            return;
        }
        IModRuntimeWorker worker = remote;
        remote = null;
        rpcExecutor.shutdownNow();
        beginClose(worker, workerReleasedOrDead);
        dispatchTerminalFailure(reason);
    }

    /**
     * Starts asynchronous teardown and completes only after native destruction is
     * acknowledged (or worker death is confirmed), the binding is removed, and the
     * global worker slot is returned. Repeated calls share this lifecycle result.
     * Cancelling the returned view does not cancel teardown or return the slot.
     */
    public CompletableFuture<Void> closeAsync() {
        intentionalCloseRequested.set(true);
        if (closed.compareAndSet(false, true)) {
            IModRuntimeWorker worker = remote;
            remote = null;
            rpcExecutor.shutdownNow();
            beginClose(worker, false);
        }
        // Do not expose the internally-owned future: callers may cancel their view,
        // but cannot forge the shared close result or free the slot early.
        return closeCompletion.thenApply(ignored -> null);
    }

    @Override public void close() { closeAsync(); }

    private void beginClose(IModRuntimeWorker worker, boolean workerKnownDead) {
        if (!closeStarted.compareAndSet(false, true)) {
            if (workerKnownDead) finishAfterConfirmedDeath();
            return;
        }
        remote = null;
        closed.set(true);
        rpcExecutor.shutdownNow();
        if (workerKnownDead) {
            finishAfterConfirmedDeath();
            return;
        }
        if (worker == null) {
            // No Binder reached this client, therefore it could not create a native
            // runtime. A successful unbind cancels the pending isolated binding.
            finishAfterUnbind();
            return;
        }
        Thread closer = new Thread(() -> {
            long retryDelayMs = 50;
            while (!slotReleased.get()) {
                try {
                    awaitCloseBarrierForTesting();
                    // Synchronous Binder close returns only after the worker's
                    // engine thread has destroyed its native runtime and requested
                    // service stop. If the OS freezes the worker, this waits; the
                    // global slot remains reserved until an actual outcome exists.
                    worker.close();
                    if (finishAfterDestroyAcknowledged()) return;
                    try { Thread.sleep(retryDelayMs); }
                    catch (InterruptedException ignored) { /* continue closing */ }
                    retryDelayMs = Math.min(retryDelayMs * 2, 1000);
                } catch (android.os.DeadObjectException dead) {
                    finishAfterConfirmedDeath();
                    return;
                } catch (RemoteException failure) {
                    if (!worker.asBinder().isBinderAlive()) {
                        finishAfterConfirmedDeath();
                        return;
                    }
                    // A live Binder may have failed transiently. Retry the actual
                    // idempotent close call; elapsed time never counts as proof.
                    try { Thread.sleep(retryDelayMs); }
                    catch (InterruptedException ignored) { /* continue closing */ }
                    retryDelayMs = Math.min(retryDelayMs * 2, 1000);
                }
            }
        }, "remrss-mod-runtime-close");
        closer.setDaemon(true);
        try {
            closer.start();
        } catch (RuntimeException startFailure) {
            // Fail closed: retain the slot and leave the completion pending rather
            // than claiming teardown when no close transaction ran.
        }
    }

    private boolean finishAfterDestroyAcknowledged() {
        if (!unbindOwnerConnection()) return false;
        releaseSlot();
        return true;
    }

    private void finishAfterConfirmedDeath() {
        closed.set(true);
        closeStarted.compareAndSet(false, true);
        remote = null;
        rpcExecutor.shutdownNow();
        // Once Binder death is confirmed, the isolated runtime cannot remain alive.
        // Still remove our local binding before returning the worker slot.
        unbindOwnerConnection();
        releaseSlot();
    }

    private void finishAfterUnbind() {
        if (unbindOwnerConnection()) releaseSlot();
    }

    private boolean unbindOwnerConnection() {
        synchronized (unbindLock) {
            if (!bound) return true;
            try {
                ownerContext.unbindService(connection);
                bound = false;
                return true;
            } catch (IllegalArgumentException alreadyUnbound) {
                // All bind/unbind calls use ownerContext. The platform reports this
                // only when this ServiceConnection is no longer registered.
                bound = false;
                return true;
            } catch (RuntimeException unconfirmed) {
                // Keep the slot reserved; unknown unbind failure is not completion.
                return false;
            }
        }
    }

    private void releaseSlot() {
        if (slotReleased.compareAndSet(false, true)) {
            ACTIVE_WORKERS.decrementAndGet();
            closeCompletion.complete(null);
        }
    }
}
