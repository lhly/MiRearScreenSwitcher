package top.zainzh.rearscreen.platform.runtime;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import top.zainzh.rearscreen.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import top.zainzh.rearscreen.MainActivity;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Exercises the real API-29+ isolated service and its Binder/FD/JNI path. */
@RunWith(AndroidJUnit4.class)
public final class ModRuntimeWorkerE2ETest {
    private static final int LIMIT = 256 * 1024;
    private static final String MODULE = "globalThis.__remrss={" +
            "init(ctx){if(ctx.spin){while(true){}}return {value:0,greeting:'背屏🌟'}}," +
            "reduce({state,event}){if(event.type==='spin'||event.type==='spin2'||event.type==='budget'){while(true){}}" +
            "if(event.type==='throw')throw Error('config rejected');" +
            "if(event.type==='oom')return {state:new ArrayBuffer(16*1024*1024),effects:[]};" +
            "if(event.type==='microtasks'){const again=()=>Promise.resolve().then(again);again();return {state,effects:[]}}" +
            "if(event.type==='recurse'){const f=()=>f();f()}" +
            "if(event.type==='memory'){let a=[];for(let i=0;i<1000000;i++)a.push({x:'allocation'});return {state:a}}" +
            "if(event.type==='nan')return {state:{score:NaN}};" +
            "if(event.type==='fakeBudgetCode')return {state:{code:'E_CPU_BUDGET'},effects:[]};"+
            "if(event.type==='fakeScriptCode')return {state:{ok:false,code:'E_SCRIPT'},effects:[]};"+
            "return {state:{value:state.value+(event.delta||0)},effects:[]}}};";

    private final ExecutorService callbacks = Executors.newSingleThreadExecutor();

    @Test public void typedEngineErrorsSeparateScriptRejectionBudgetAndBusinessJson() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        TypedEvents typedEvents = new TypedEvents();
        IsolatedModRuntimeClient typed = IsolatedModRuntimeClient.bind(context, 730, callbacks, typedEvents);
        try {
            awaitConnected(typedEvents, 730);
            typed.loadModule(MODULE.getBytes(StandardCharsets.UTF_8));
            assertTrue(awaitResult(typedEvents, 730, "loadModule").contains("\"ok\":true"));

            typed.reduce("{\"state\":{\"value\":0},\"event\":{\"type\":\"fakeBudgetCode\"},\"config\":{},\"env\":{}}");
            String business = awaitResult(typedEvents, 730, "reduce");
            assertTrue(business.contains("\"code\":\"E_CPU_BUDGET\""));
            assertNull("business JSON is not reclassified as an engine error", typedEvents.nextError(150, TimeUnit.MILLISECONDS));
            typed.reduce("{\"state\":{\"value\":0},\"event\":{\"type\":\"fakeScriptCode\"},\"config\":{},\"env\":{}}");
            String scriptShapedBusiness = awaitResult(typedEvents, 730, "reduce");
            assertTrue(scriptShapedBusiness.contains("\"ok\":false"));
            assertTrue(scriptShapedBusiness.contains("\"code\":\"E_SCRIPT\""));
            assertNull("business E_SCRIPT field is not an engine error", typedEvents.nextError(150, TimeUnit.MILLISECONDS));

            typed.reduce("{\"state\":{\"value\":0},\"event\":{\"type\":\"throw\"},\"config\":{},\"env\":{}}");
            IsolatedModRuntimeClient.EngineCallError rejectedConfig = awaitEngineError(typedEvents, 730, "reduce");
            assertEquals("E_SCRIPT", rejectedConfig.code());
            assertEquals(IsolatedModRuntimeClient.EngineFaultCategory.SCRIPT, rejectedConfig.category());
            assertFalse("E_SCRIPT can also represent engine resource exhaustion", rejectedConfig.workerReusable());
            assertTrue("E_SCRIPT requires old epoch reset without relying on connection timing", rejectedConfig.terminal());
            assertTrue("terminal is a reset policy, not proof the binder is already closed", typed.isConnected());
            typed.closeAsync().get(8, TimeUnit.SECONDS);
        } finally {
            typed.closeAsync().get(8, TimeUnit.SECONDS);
        }

        TypedEvents restoredEvents = new TypedEvents();
        IsolatedModRuntimeClient restored = IsolatedModRuntimeClient.bind(context, 732, callbacks, restoredEvents);
        try {
            awaitConnected(restoredEvents, 732);
            restored.loadModule(MODULE.getBytes(StandardCharsets.UTF_8));
            assertTrue(awaitResult(restoredEvents, 732, "loadModule").contains("\"ok\":true"));
            restored.reduce("{\"state\":{\"value\":7},\"event\":{\"type\":\"noop\"},\"config\":{},\"env\":{}}");
            assertTrue("new epoch resumes from last committed host state", awaitResult(restoredEvents, 732, "reduce").contains("\"value\":7"));

            restored.reduce("{\"state\":{\"value\":7},\"event\":{\"type\":\"oom\"},\"config\":{},\"env\":{}}");
            IsolatedModRuntimeClient.EngineCallError oom = awaitEngineError(restoredEvents, 732, "reduce");
            assertEquals("E_SCRIPT", oom.code());
            assertEquals(IsolatedModRuntimeClient.EngineFaultCategory.SCRIPT, oom.category());
            assertFalse("8 MiB heap OOM is not safe to reuse", oom.workerReusable());
            assertTrue(oom.terminal());
        } finally {
            restored.closeAsync().get(8, TimeUnit.SECONDS);
        }

        TypedEvents budgetEvents = new TypedEvents();
        IsolatedModRuntimeClient budgetClient = IsolatedModRuntimeClient.bind(context, 733, callbacks, budgetEvents);
        try {
            awaitConnected(budgetEvents, 733);
            budgetClient.loadModule(MODULE.getBytes(StandardCharsets.UTF_8));
            awaitResult(budgetEvents, 733, "loadModule");
            budgetClient.reduce("{\"state\":{\"value\":7},\"event\":{\"type\":\"budget\"},\"config\":{},\"env\":{}}");
            IsolatedModRuntimeClient.EngineCallError budget = awaitEngineError(budgetEvents, 733, "reduce");
            assertEquals(IsolatedModRuntimeClient.EngineFaultCategory.BUDGET, budget.category());
            assertFalse(budget.workerReusable());
            assertTrue(budget.terminal());
            budgetClient.closeAsync().get(8, TimeUnit.SECONDS);
        } finally {
            budgetClient.closeAsync().get(8, TimeUnit.SECONDS);
        }

        Events legacyEvents = new Events();
        IsolatedModRuntimeClient legacy = IsolatedModRuntimeClient.bind(context, 731, callbacks, legacyEvents);
        try {
            awaitConnected(legacyEvents, 731);
            legacy.loadModule(MODULE.getBytes(StandardCharsets.UTF_8));
            awaitResult(legacyEvents, 731, "loadModule");
            legacy.reduce("{\"state\":{\"value\":0},\"event\":{\"type\":\"throw\"},\"config\":{},\"env\":{}}");
            String legacyError = awaitResult(legacyEvents, 731, "reduce");
            assertTrue(legacyError, legacyError.contains("\"code\":\"E_SCRIPT\""));
            assertTrue("legacy default keeps its prior result callback behavior", legacy.isConnected());
        } finally {
            legacy.closeAsync().get(8, TimeUnit.SECONDS);
        }
    }

    @Test public void isolatedRuntimeBoundariesRecoveryAndSlotLimits() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Events firstEvents = new Events();
        long coldStarted = System.nanoTime();
        IsolatedModRuntimeClient first = IsolatedModRuntimeClient.bind(context, 41, callbacks, firstEvents);
        try {
            awaitConnected(firstEvents, 41);
            long coldBindMs = (System.nanoTime() - coldStarted) / 1_000_000;
            int firstPid = readPid(first, firstEvents, 41);
            long baselinePssKb = readPss(first, firstEvents, 41);

            byte[] boundarySource = sourceExactly(IsolatedModRuntimeClient.MAX_SOURCE_BYTES);
            first.loadModule(boundarySource);
            String loaded = awaitResult(firstEvents, 41, "loadModule");
            assertTrue(loaded, loaded.contains("\"ok\":true"));
            first.readBudgetCapabilityFlags();
            String budgetCapabilities = awaitResult(firstEvents, 41, "budgetCapabilityFlags");
            int budgetFlags = Integer.parseInt(budgetCapabilities.substring(budgetCapabilities.indexOf(':') + 1, budgetCapabilities.indexOf('}')));
            Log.i("ModRuntimeE2E", "budgetCapabilityFlags=" + budgetFlags + " (cpu=1,schedstatDiagnostic=2,watchdog=4)");
            assertEquals("Android must expose target-thread CPU clock and independent native watchdog", 5, budgetFlags & 5);
            long loadedPssKb = readPss(first, firstEvents, 41);

            long offCpuStart = System.nanoTime();
            Log.i("ModRuntimeE2E", "freezeInjectionWindow epoch=41 workerPid=" + firstPid);
            first.pauseBudgetThreadForTesting();
            String resumed = awaitResult(firstEvents, 41, "pauseBudgetThreadForTesting");
            long offCpuElapsedMs = (System.nanoTime() - offCpuStart) / 1_000_000;
            assertTrue(resumed, resumed.contains("\"ok\":true"));
            assertTrue("a 1.5s off-CPU pause is not treated as a Mod budget failure", offCpuElapsedMs >= 1_400 && offCpuElapsedMs < 8_000);
            assertTrue("worker remains usable after modeled off-CPU pause", first.isConnected());
            Log.i("ModRuntimeE2E", "offCpuPauseRecovered elapsedMs=" + offCpuElapsedMs + " connected=" + first.isConnected());

            first.init("{\"config\":{},\"env\":{}}");
            String initial = awaitResult(firstEvents, 41, "init");
            assertTrue(initial, initial.contains("背屏🌟"));
            String blockingGlobals = runDisabledBlockingGlobals(context, 59);
            assertTrue(blockingGlobals, blockingGlobals.contains("\"sab\":\"undefined\""));
            assertTrue(blockingGlobals, blockingGlobals.contains("\"atomics\":\"undefined\""));
            String committedState = "{\"value\":0}";

            String atLimit = paddedContext(LIMIT);
            first.reduce(atLimit);
            String maxMessage = awaitResult(firstEvents, 41, "reduce");
            assertTrue(maxMessage, maxMessage.contains("\"value\":0"));

            first.reduce("{\"state\":{\"value\":0},\"event\":{\"type\":\"fakeBudgetCode\"},\"config\":{},\"env\":{}}");
            String businessCode = awaitResult(firstEvents, 41, "reduce");
            assertTrue("a business state code is preserved", businessCode.contains("\"code\":\"E_CPU_BUDGET\""));
            assertTrue("business fields cannot retire the engine", first.isConnected());

            long[] warmLatenciesMs = new long[20];
            for (int i = 0; i < warmLatenciesMs.length; i++) {
                long started = System.nanoTime();
                first.reduce("{\"state\":{\"value\":0},\"event\":{\"type\":\"noop\"},\"config\":{},\"env\":{}}");
                assertTrue(awaitResult(firstEvents, 41, "reduce").contains("\"value\":0"));
                warmLatenciesMs[i] = (System.nanoTime() - started) / 1_000_000;
            }
            java.util.Arrays.sort(warmLatenciesMs);
            long warmP95Ms = warmLatenciesMs[(int) Math.ceil(warmLatenciesMs.length * 0.95) - 1];

            first.reduce(paddedContext(LIMIT + 1));
            assertTrue(awaitFailure(firstEvents, 41).contains("E_MESSAGE_TOO_LARGE"));
            first.loadModule(new byte[IsolatedModRuntimeClient.MAX_SOURCE_BYTES + 1]);
            assertTrue(awaitFailure(firstEvents, 41).contains("E_MESSAGE_TOO_LARGE"));

            first.reduce("{\"state\":{\"value\":0},\"event\":{\"type\":\"add\",\"delta\":5},\"config\":{},\"env\":{}}");
            String scored = awaitResult(firstEvents, 41, "reduce");
            assertTrue(scored, scored.contains("\"value\":5"));
            committedState = "{\"value\":5}";

            Events secondEvents = new Events();
            IsolatedModRuntimeClient second = IsolatedModRuntimeClient.bind(context, 42, callbacks, secondEvents);
            try {
                awaitConnected(secondEvents, 42);
                int secondPid = readPid(second, secondEvents, 42);
                assertNotEquals("each active Mod must have a separate isolated process/runtime", firstPid, secondPid);
                assertThrows(IllegalStateException.class, () -> IsolatedModRuntimeClient.bind(context, 43, callbacks, new Events()));
            } finally {
                second.close();
            }

            // Debug-only service hook simulates binder death; new epoch restores the
            // host-committed model rather than rerunning init or replaying an effect.
            first.crashForTesting();
            String deathReason = awaitFailure(firstEvents, 41);
            Log.i("ModRuntimeE2E", "isolatedWorkerDeathReason=" + deathReason + " oldPid=" + firstPid);
            assertTrue("expected normalized death-of-worker signal, got: " + deathReason,
                    deathReason.startsWith("E_WORKER_DIED: isolated worker process exited"));
            assertFalse("old epoch is disconnected after worker death", first.isConnected());
            Events recoveredEvents = new Events();
            IsolatedModRuntimeClient recovered = IsolatedModRuntimeClient.bind(context, 43, callbacks, recoveredEvents);
            try {
                awaitConnected(recoveredEvents, 43);
                assertNotEquals("recovery must use a fresh isolated process", firstPid, readPid(recovered, recoveredEvents, 43));
                recovered.loadModule(MODULE.getBytes(StandardCharsets.UTF_8));
                assertTrue(awaitResult(recoveredEvents, 43, "loadModule").contains("\"ok\":true"));
                recovered.reduce("{\"state\":" + committedState + ",\"event\":{\"type\":\"add\",\"delta\":2},\"config\":{},\"env\":{}}");
                String restored = awaitResult(recoveredEvents, 43, "reduce");
                assertTrue(restored, restored.contains("\"value\":7"));
            } finally {
                recovered.close();
            }

            long spinMs = runFault(context, 44, "reduce", "{\"state\":{\"value\":5},\"event\":{\"type\":\"spin\"}}", "E_CPU_BUDGET");
            long initSpinMs = runFault(context, 45, "init", "{\"spin\":true}", "E_CPU_BUDGET");
            runFault(context, 46, "reduce", "{\"state\":{},\"event\":{\"type\":\"microtasks\"}}", "E_MICROTASK_LIMIT");
            runFault(context, 47, "reduce", "{\"state\":{},\"event\":{\"type\":\"recurse\"}}", "\"ok\":false");
            runFault(context, 48, "reduce", "{\"state\":{},\"event\":{\"type\":\"memory\"}}", "\"ok\":false");
            runFault(context, 49, "reduce", "{\"state\":{},\"event\":{\"type\":\"nan\"}}", "E_INVALID_RESULT");
            long workerSourceLoadDeltaKb = loadedPssKb - baselinePssKb;
            Log.i("ModRuntimeE2E", "coldBindMs=" + coldBindMs + " workerPssBaselineKb=" + baselinePssKb +
                    " workerPssLoadedKb=" + loadedPssKb + " workerSourceLoadDeltaKb=" + workerSourceLoadDeltaKb +
                    " warmReducerP95Ms=" + warmP95Ms + " reducerSpinWallMs=" + spinMs + " initSpinWallMs=" + initSpinMs);
            assertTrue("warm reducer response P95 target", warmP95Ms <= 200);

            String migrationModule = "globalThis.__remrss={init(){return{}},reduce(){return{}}," +
                    "migrate({state,config}){return {state:{value:state.value+1},config:{...config,migrated:true}}}};";
            String migrated = runMigration(context, 50, migrationModule,
                    "{\"state\":{\"value\":9},\"config\":{\"theme\":\"dark\"},\"fromVersion\":1,\"toVersion\":2,\"env\":{}}", "\"value\":10");
            assertTrue(migrated, migrated.contains("\"migrated\":true"));
            String noMigration = runMigration(context, 51, MODULE,
                    "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2,\"env\":{}}", "E_MIGRATION_REQUIRED");
            assertTrue(noMigration, noMigration.contains("E_MIGRATION_REQUIRED"));
            runMigration(context, 52, "globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){throw Error('migration failed')}};",
                    "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2,\"env\":{}}", "E_SCRIPT");
            runMigration(context, 53, "globalThis.__remrss={init(){return{}},reduce(){return{}},migrate({state,config}){return {state,config,effects:[]}}};",
                    "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2,\"env\":{}}", "E_INVALID_MIGRATION");
            runMigration(context, 54, "globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){while(true){}}};",
                    "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2,\"env\":{}}", "E_CPU_BUDGET");
            String nonEnumerableToJson = runMigration(context, 55,
                    "globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){const r={state:{score:7},config:{}};Object.defineProperty(r,'toJSON',{value(){return {state:{score:NaN},config:{},effects:[{type:'rear.open'}]}}});return r}};",
                    "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2,\"env\":{}}", "\"score\":7");
            assertFalse("non-enumerable toJSON cannot inject migration effects", nonEnumerableToJson.contains("effects"));
            String inheritedToJson = runMigration(context, 56,
                    "Object.prototype.toJSON=function(){return {state:{score:NaN},config:{},effects:[{type:'rear.open'}]}};globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){return {state:{score:9},config:{}}}};",
                    "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2,\"env\":{}}", "\"score\":9");
            assertFalse("inherited toJSON cannot inject migration effects", inheritedToJson.contains("effects"));
            runMigration(context, 57,
                    "globalThis.__remrss={init(){return{}},reduce(){return{}},migrate(){const r={state:{score:NaN},config:{}};Object.defineProperty(r,'toJSON',{value(){return {state:{score:null},config:{},effects:[]}}});return r}};",
                    "{\"state\":{},\"config\":{},\"fromVersion\":1,\"toVersion\":2,\"env\":{}}", "E_INVALID_RESULT");
            runNativeWatchdogFault(context, 58);
        } finally {
            first.close();
            callbacks.shutdownNow();
        }
    }

    @Test public void activityContextCloseAndRebindDoesNotLeakWorkerSlots() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Events firstEvents = new Events();
            java.util.concurrent.atomic.AtomicReference<IsolatedModRuntimeClient> firstRef = new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(activity -> firstRef.set(IsolatedModRuntimeClient.bind(activity, 700, callbacks, firstEvents)));
            IsolatedModRuntimeClient first = firstRef.get();
            awaitConnected(firstEvents, 700);
            readPid(first, firstEvents, 700);
            first.closeAsync().get(5, TimeUnit.SECONDS);
            first.closeAsync().get(5, TimeUnit.SECONDS);

            Events nextEvents = new Events();
            IsolatedModRuntimeClient next = bindUntilSlotReleased(
                    ApplicationProvider.getApplicationContext(), 701, nextEvents);
            try {
                awaitConnected(nextEvents, 701);
                readPid(next, nextEvents, 701);
            } finally {
                next.closeAsync().get(5, TimeUnit.SECONDS);
                next.closeAsync().get(5, TimeUnit.SECONDS);
            }
        } finally {
            callbacks.shutdownNow();
        }
    }

    @Test public void twoOccupiedSlotsWaitForCloseCompletionBeforeMigrationBind() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Events firstEvents = new Events();
        Events secondEvents = new Events();
        IsolatedModRuntimeClient first = IsolatedModRuntimeClient.bind(context, 710, callbacks, firstEvents);
        IsolatedModRuntimeClient second = IsolatedModRuntimeClient.bind(context, 711, callbacks, secondEvents);
        IsolatedModRuntimeClient.CloseBarrierForTesting closeBarrier = null;
        try {
            awaitConnected(firstEvents, 710);
            awaitConnected(secondEvents, 711);
            first.loadModule(MODULE.getBytes(StandardCharsets.UTF_8));
            assertTrue(awaitResult(firstEvents, 710, "loadModule").contains("\"ok\":true"));

            closeBarrier = IsolatedModRuntimeClient.blockNextCloseForTesting();
            CompletableFuture<Void> firstCloseView = first.closeAsync();
            assertTrue("close thread reached the deterministic pre-Binder barrier",
                    closeBarrier.awaitEntered(5, TimeUnit.SECONDS));
            assertFalse("close cannot complete before worker destroy acknowledgement", firstCloseView.isDone());

            CompletableFuture<Void> cancelledWait = first.closeAsync();
            assertTrue("caller may cancel only its wait view", cancelledWait.cancel(false));
            assertThrows("the old slot remains reserved while close is unconfirmed", IllegalStateException.class,
                    () -> IsolatedModRuntimeClient.bind(context, 712, callbacks, new Events()));

            closeBarrier.release();
            first.closeAsync().get(8, TimeUnit.SECONDS);
            assertFalse("closed client is no longer connected", first.isConnected());

            Events migrationEvents = new Events();
            IsolatedModRuntimeClient migration = IsolatedModRuntimeClient.bind(context, 713, callbacks, migrationEvents);
            try {
                awaitConnected(migrationEvents, 713);
                String migrationModule = "globalThis.__remrss={init(){return{}},reduce(){return{}}," +
                        "migrate({state,config}){return {state:{value:state.value+1},config}}};";
                migration.loadModule(migrationModule.getBytes(StandardCharsets.UTF_8));
                assertTrue(awaitResult(migrationEvents, 713, "loadModule").contains("\"ok\":true"));
                migration.migrate("{\"state\":{\"value\":4},\"config\":{},\"fromVersion\":1,\"toVersion\":2,\"env\":{}}");
                assertTrue(awaitResult(migrationEvents, 713, "migrate").contains("\"value\":5"));
                assertThrows("migration occupies the freed first slot alongside the other Mod", IllegalStateException.class,
                        () -> IsolatedModRuntimeClient.bind(context, 714, callbacks, new Events()));
            } finally {
                migration.closeAsync().get(5, TimeUnit.SECONDS);
            }
        } finally {
            if (closeBarrier != null) closeBarrier.release();
            first.closeAsync().get(5, TimeUnit.SECONDS);
            second.closeAsync().get(5, TimeUnit.SECONDS);
            callbacks.shutdownNow();
        }
    }

    @Test public void callbackExecutorShutdownRaceIsSafeAndLiveRejectionIsTyped() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ControlledExecutor lateCallbacks = new ControlledExecutor();
        Events lateEvents = new Events();
        IsolatedModRuntimeClient late = IsolatedModRuntimeClient.bind(context, 720, lateCallbacks, lateEvents);
        try {
            awaitConnected(lateEvents, 720);
            late.loadModule(MODULE.getBytes(StandardCharsets.UTF_8));
            assertTrue(awaitResult(lateEvents, 720, "loadModule").contains("\"ok\":true"));

            lateCallbacks.blockNextSubmission();
            late.reduce("{\"state\":{\"value\":0},\"event\":{\"type\":\"noop\"},\"config\":{},\"env\":{}}");
            assertTrue("result callback reached the owner executor handoff", lateCallbacks.awaitBlocked(5, TimeUnit.SECONDS));
            CompletableFuture<Void> lateClose = late.closeAsync();
            lateCallbacks.shutdownNow();
            lateCallbacks.releaseBlockedSubmission();
            lateClose.get(8, TimeUnit.SECONDS);
            assertTrue("intentional close suppresses only the callback racing executor shutdown", lateEvents.next(100, TimeUnit.MILLISECONDS) == null);
        } finally {
            lateCallbacks.releaseBlockedSubmission();
            late.closeAsync().get(8, TimeUnit.SECONDS);
        }

        ControlledExecutor rejectedCallbacks = new ControlledExecutor();
        Events rejectedEvents = new Events();
        IsolatedModRuntimeClient rejected = IsolatedModRuntimeClient.bind(context, 721, rejectedCallbacks, rejectedEvents);
        try {
            awaitConnected(rejectedEvents, 721);
            rejected.loadModule(MODULE.getBytes(StandardCharsets.UTF_8));
            assertTrue(awaitResult(rejectedEvents, 721, "loadModule").contains("\"ok\":true"));
            rejectedCallbacks.shutdownNow();
            rejected.reduce("{\"state\":{\"value\":0},\"event\":{\"type\":\"noop\"},\"config\":{},\"env\":{}}");
            String failure = awaitFailure(rejectedEvents, 721);
            assertTrue("live executor rejection is explicit and does not escape the RPC thread: " + failure,
                    failure.contains("E_CALLBACK_DISPATCH") && failure.contains("owner callback executor rejected"));
            rejected.closeAsync().get(8, TimeUnit.SECONDS);
        } finally {
            rejectedCallbacks.shutdownNow();
            rejected.closeAsync().get(8, TimeUnit.SECONDS);
        }

        Events recoveredEvents = new Events();
        IsolatedModRuntimeClient recovered = IsolatedModRuntimeClient.bind(context, 722, callbacks, recoveredEvents);
        try {
            awaitConnected(recoveredEvents, 722);
            readPid(recovered, recoveredEvents, 722);
        } finally {
            recovered.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    private static final class ControlledExecutor implements Executor {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        private final java.util.concurrent.atomic.AtomicBoolean blockNext = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(0);
        private volatile java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(0);
        private volatile boolean rejecting;

        void blockNextSubmission() {
            blocked = new java.util.concurrent.CountDownLatch(1);
            release = new java.util.concurrent.CountDownLatch(1);
            blockNext.set(true);
        }
        boolean awaitBlocked(long timeout, TimeUnit unit) throws InterruptedException { return blocked.await(timeout, unit); }
        void releaseBlockedSubmission() { release.countDown(); }
        void shutdownNow() { rejecting = true; delegate.shutdownNow(); }

        @Override public void execute(Runnable command) {
            if (blockNext.compareAndSet(true, false)) {
                blocked.countDown();
                boolean interrupted = false;
                for (;;) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (rejecting) throw new RejectedExecutionException("test executor is terminated");
            delegate.execute(command);
        }
    }

    private IsolatedModRuntimeClient bindUntilSlotReleased(Context context, long epoch, Events events) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            try { return IsolatedModRuntimeClient.bind(context, epoch, callbacks, events); }
            catch (IllegalStateException busy) {
                if (busy.getMessage() == null || !busy.getMessage().contains("E_BUSY")) throw busy;
                Thread.sleep(50);
            }
        }
        throw new AssertionError("runtime slot was not released after worker close");
    }

    private void runNativeWatchdogFault(Context context, long epoch) throws Exception {
        Events events = new Events();
        IsolatedModRuntimeClient worker = IsolatedModRuntimeClient.bind(context, epoch, callbacks, events);
        try {
            awaitConnected(events, epoch);
            worker.loadModule(MODULE.getBytes(StandardCharsets.UTF_8));
            assertTrue(awaitResult(events, epoch, "loadModule").contains("\"ok\":true"));
            long start = System.nanoTime();
            worker.stallForWatchdogTesting();
            String failure = awaitFailure(events, epoch);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            Log.i("ModRuntimeE2E", "independentNativeWatchdogElapsedMs=" + elapsedMs + " outcome=" + failure);
            assertTrue(failure, failure.startsWith("E_WORKER_DIED: isolated worker process exited"));
            assertTrue("independent watchdog terminates native busy loop promptly", elapsedMs < 1_200);
        } finally { worker.close(); }
    }

    private String runDisabledBlockingGlobals(Context context, long epoch) throws Exception {
        Events events = new Events();
        IsolatedModRuntimeClient worker = IsolatedModRuntimeClient.bind(context, epoch, callbacks, events);
        try {
            awaitConnected(events, epoch);
            String module = "globalThis.__remrss={init(){return {sab:typeof SharedArrayBuffer,atomics:typeof Atomics,"+
                    "functionSab:({}).constructor.constructor('return typeof SharedArrayBuffer')(),"+
                    "functionAtomics:({}).constructor.constructor('return typeof Atomics')()}},"+
                    "reduce(){return Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0)}};";
            worker.loadModule(module.getBytes(StandardCharsets.UTF_8));
            assertTrue(awaitResult(events, epoch, "loadModule").contains("\"ok\":true"));
            worker.init("{}");
            String globals = awaitResult(events, epoch, "init");
            assertTrue(globals, globals.contains("\"functionSab\":\"undefined\""));
            assertTrue(globals, globals.contains("\"functionAtomics\":\"undefined\""));
            worker.reduce("{\"state\":{},\"event\":{},\"config\":{},\"env\":{}}");
            String blocked = awaitResult(events, epoch, "reduce");
            assertTrue("Atomics.wait cannot block the worker without CPU use", blocked.contains("E_SCRIPT"));
            assertTrue("worker remains usable after unavailable blocking API", worker.isConnected());
            return globals;
        } finally { worker.close(); }
    }

    private static byte[] sourceExactly(int bytes) {
        byte[] body = MODULE.getBytes(StandardCharsets.UTF_8);
        assertTrue(body.length < bytes);
        byte[] result = new byte[bytes];
        java.util.Arrays.fill(result, 0, bytes - body.length, (byte) ' ');
        System.arraycopy(body, 0, result, bytes - body.length, body.length);
        return result;
    }

    private static String paddedContext(int bytes) {
        String prefix = "{\"state\":{\"value\":0},\"event\":{\"type\":\"noop\"},\"config\":{\"pad\":\"";
        String suffix = "\"},\"env\":{}}";
        int pad = bytes - prefix.getBytes(StandardCharsets.UTF_8).length - suffix.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(pad >= 0);
        String result = prefix + "x".repeat(pad) + suffix;
        assertEquals(bytes, result.getBytes(StandardCharsets.UTF_8).length);
        return result;
    }

    private static int readPid(IsolatedModRuntimeClient client, Events events, long epoch) throws Exception {
        client.readWorkerPid();
        String json = awaitResult(events, epoch, "workerPid");
        int start = json.indexOf(":") + 1;
        int end = json.indexOf("}");
        return Integer.parseInt(json.substring(start, end));
    }

    private static long readPss(IsolatedModRuntimeClient client, Events events, long epoch) throws Exception {
        client.readWorkerPssKb();
        String json = awaitResult(events, epoch, "workerPssKb");
        return Long.parseLong(json.substring(json.indexOf(":") + 1, json.indexOf("}")));
    }

    private long runFault(Context context, long epoch, String operation, String input, String expected) throws Exception {
        Events events = new Events();
        IsolatedModRuntimeClient worker = IsolatedModRuntimeClient.bind(context, epoch, callbacks, events);
        try {
            awaitConnected(events, epoch);
            worker.loadModule(MODULE.getBytes(StandardCharsets.UTF_8));
            assertTrue(awaitResult(events, epoch, "loadModule").contains("\"ok\":true"));
            long started = System.nanoTime();
            if ("init".equals(operation)) worker.init(input); else worker.reduce(input);
            String result = awaitResult(events, epoch, operation);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            assertTrue(result, result.contains(expected));
            if ("E_CPU_BUDGET".equals(expected)) {
                long cpuMs = worker.lastExecutionCpuTimeNanos() / 1_000_000;
                long hardBudgetMs = "init".equals(operation) ? 500 : 100;
                Log.i("ModRuntimeE2E", operation + "CpuBudgetMs=" + hardBudgetMs +
                        " actualThreadCpuMs=" + cpuMs + " responseWallMs=" + elapsed + " watchdogGraceMs=250");
                assertTrue(operation + " actual CPU reached hard budget", cpuMs >= hardBudgetMs - 5);
                assertTrue(operation + " CPU interrupt is close to hard limit", cpuMs <= hardBudgetMs + 50);
            }
            return elapsed;
        } finally {
            worker.close();
        }
    }

    private String runMigration(Context context, long epoch, String module, String input, String expected) throws Exception {
        Events events = new Events();
        IsolatedModRuntimeClient worker = IsolatedModRuntimeClient.bind(context, epoch, callbacks, events);
        try {
            awaitConnected(events, epoch);
            worker.loadModule(module.getBytes(StandardCharsets.UTF_8));
            assertTrue(awaitResult(events, epoch, "loadModule").contains("\"ok\":true"));
            long started = System.nanoTime();
            worker.migrate(input);
            String result = awaitResult(events, epoch, "migrate");
            assertTrue(result, result.contains(expected));
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            if (expected.contains("E_CPU_BUDGET")) {
                long cpuMs = worker.lastExecutionCpuTimeNanos() / 1_000_000;
                Log.i("ModRuntimeE2E", "migrateCpuBudgetMs=500 actualThreadCpuMs=" + cpuMs +
                        " responseWallMs=" + elapsed + " watchdogGraceMs=250");
                assertTrue("migration actual CPU reached hard budget", cpuMs >= 495);
                assertTrue("migration CPU interrupt is close to hard limit", cpuMs <= 550);
            }
            return result;
        } finally { worker.close(); }
    }

    private static void awaitConnected(Events events, long epoch) throws Exception {
        Event event = events.next(5, TimeUnit.SECONDS);
        assertNotNull("worker did not bind", event);
        assertEquals(epoch, event.epoch);
        assertEquals("connected", event.operation);
    }

    private static String awaitResult(Events events, long epoch, String operation) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            Event event = events.next(250, TimeUnit.MILLISECONDS);
            if (event == null) continue;
            assertEquals("stale worker epoch", epoch, event.epoch);
            if (operation.equals(event.operation)) {
                assertNull("worker call failed: " + event.payload, event.failure);
                return event.payload;
            }
            if (event.failure != null) fail(event.failure);
        }
        fail("timed out waiting for " + operation);
        return "";
    }

    private static IsolatedModRuntimeClient.EngineCallError awaitEngineError(TypedEvents events, long epoch, String operation) throws Exception {
        TypedError event = events.nextError(8, TimeUnit.SECONDS);
        assertNotNull("typed engine error did not arrive", event);
        assertEquals(epoch, event.epoch);
        assertEquals(operation, event.operation);
        return event.error;
    }

    private static String awaitFailure(Events events, long epoch) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            Event event = events.next(250, TimeUnit.MILLISECONDS);
            if (event == null) continue;
            assertEquals(epoch, event.epoch);
            if (event.failure != null) return event.failure;
        }
        fail("timed out waiting for worker failure");
        return "";
    }

    private static final class Event {
        final long epoch;
        final String operation;
        final String payload;
        final String failure;
        Event(long epoch, String operation, String payload, String failure) {
            this.epoch = epoch; this.operation = operation; this.payload = payload; this.failure = failure;
        }
    }

    private static class Events implements IsolatedModRuntimeClient.Listener {
        final BlockingQueue<Event> queue = new LinkedBlockingQueue<>();
        @Override public void onConnected(long epoch) { queue.offer(new Event(epoch, "connected", "", null)); }
        @Override public void onResult(long epoch, String operation, String json) {
            queue.offer(new Event(epoch, operation, json, null));
        }
        @Override public void onDisconnected(long epoch, String reason) { queue.offer(new Event(epoch, "", "", reason)); }
        Event next(long timeout, TimeUnit unit) throws InterruptedException { return queue.poll(timeout, unit); }
    }

    private static final class TypedError {
        final long epoch;
        final String operation;
        final IsolatedModRuntimeClient.EngineCallError error;
        TypedError(long epoch, String operation, IsolatedModRuntimeClient.EngineCallError error) {
            this.epoch = epoch; this.operation = operation; this.error = error;
        }
    }

    private static final class TypedEvents extends Events {
        private final BlockingQueue<TypedError> errors = new LinkedBlockingQueue<>();
        @Override public void onEngineError(long epoch, String operation, IsolatedModRuntimeClient.EngineCallError error) {
            errors.offer(new TypedError(epoch, operation, error));
        }
        TypedError nextError(long timeout, TimeUnit unit) throws InterruptedException { return errors.poll(timeout, unit); }
    }
}
