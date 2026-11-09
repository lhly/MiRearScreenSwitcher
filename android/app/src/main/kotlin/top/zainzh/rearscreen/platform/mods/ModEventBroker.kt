package top.zainzh.rearscreen.platform.mods

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.platform.runtime.IsolatedModRuntimeClient
import top.zainzh.rearscreen.platform.clock.AndroidClockSnapshotSource
import top.zainzh.rearscreen.platform.clock.ClockSnapshotSource
import top.zainzh.rearscreen.platform.clock.ExecutorOneShotScheduler
import top.zainzh.rearscreen.platform.clock.VisibleScheduleLease
import top.zainzh.rearscreen.platform.clock.VisibleScheduleProvider
import top.zainzh.rearscreen.platform.clock.VisibleTick
import top.zainzh.rearscreen.platform.clock.VisibleTickCause
import java.util.ArrayDeque
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Host effects are validated before the model commit and dispatched only after it succeeds. */
sealed interface ModHostEffect {
    val id: String
    val requiresUserActivation: Boolean
}

data class ModRearOpenEffect(
    override val id: String,
    val scene: String,
    val region: String,
    val keepVisible: Boolean,
    override val requiresUserActivation: Boolean = true,
) : ModHostEffect

data class ModVisibleScheduleEffect(
    override val id: String,
    val key: String,
    val intervalMs: Long?,
    val deadlineElapsedRealtimeMs: Long?,
    val cancel: Boolean = false,
    override val requiresUserActivation: Boolean = false,
) : ModHostEffect

data class ModRegisteredActionEffect(
    override val id: String,
    val ref: top.zainzh.rearscreen.platform.actions.RegisteredActionRef,
    override val requiresUserActivation: Boolean = true,
) : ModHostEffect

data class ModNotificationPublishEffect(
    override val id: String,
    val notificationId: String,
    val title: String,
    val text: String,
    val durationMs: Long,
    val priority: String,
    val privacy: String,
    override val requiresUserActivation: Boolean = false,
) : ModHostEffect

data class ModNotificationCancelEffect(
    override val id: String,
    val notificationId: String,
    override val requiresUserActivation: Boolean = false,
) : ModHostEffect

data class ModNotificationDeniedEffect(
    override val id: String,
    val reason: String,
    override val requiresUserActivation: Boolean = false,
) : ModHostEffect

fun interface ModEffectDispatcher {
    fun requestRearScene(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect)
    fun requestRearSceneWithResult(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect, complete: (Boolean, String?) -> Unit) {
        runCatching { requestRearScene(mod, eventId, effect) }
            .onSuccess { complete(true, null) }
            .onFailure { complete(false, "E_EFFECT_DISPATCH") }
    }
}

interface ModBrokerListener {
    fun onReady(modId: String, model: ModPersistedModel)
    fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel)
    fun onError(modId: String, code: String, message: String)
    fun onEffectRequested(modId: String, eventId: String)
}

internal sealed interface ModBrokerCommand {
    data class Event(
        val eventId: String,
        val type: String,
        val payload: JSONObject,
        /** Host-only evidence from a native button callback; never serialized to the worker. */
        val activation: ModUserGesture? = null,
        val effectChain: ModEffectChainBudget = ModEffectChainBudget(),
    ) : ModBrokerCommand
    data class Config(val eventId: String, val config: JSONObject, val callbackExecutor: Executor, val complete: (Boolean, String?) -> Unit,
        val effectChain: ModEffectChainBudget = ModEffectChainBudget()) : ModBrokerCommand
}

internal class ModEffectChainBudget {
    private val lock = Any()
    private var eventCount = 1
    private var cpuNanos = 0L
    private var hasEffects = false
    fun markEffectIssued() = synchronized(lock) { hasEffects = true }
    fun hasEffects(): Boolean = synchronized(lock) { hasEffects }
    fun reserveResultEvent(): ResultEventReservation = synchronized(lock) {
        when {
            cpuNanos > MAX_CPU_NANOS -> ResultEventReservation.CPU_LIMIT
            eventCount >= MAX_EVENTS -> ResultEventReservation.EVENT_LIMIT
            else -> { eventCount++; ResultEventReservation.ACCEPTED }
        }
    }
    fun addCpu(nanos: Long): Boolean = synchronized(lock) {
        if (nanos > 0) cpuNanos = if (Long.MAX_VALUE - cpuNanos < nanos) Long.MAX_VALUE else cpuNanos + nanos
        cpuNanos <= MAX_CPU_NANOS
    }
    fun withinCpuBudget(): Boolean = synchronized(lock) { cpuNanos <= MAX_CPU_NANOS }
    companion object { const val MAX_EVENTS = 16; const val MAX_CPU_NANOS = 100_000_000L }
}

internal enum class ResultEventReservation { ACCEPTED, CPU_LIMIT, EVENT_LIMIT }

internal class ModEffectRateLimiter(
    private val burst: Double = 16.0,
    private val callsPerSecond: Double = 10.0,
) {
    private var tokens = burst
    private var lastElapsedRealtimeMs: Long? = null
    @Synchronized fun tryAcquire(nowMs: Long): Boolean {
        val last = lastElapsedRealtimeMs
        if (last != null && nowMs < last) return false
        if (last != null) tokens = minOf(burst, tokens + (nowMs - last) * callsPerSecond / 1_000.0)
        lastElapsedRealtimeMs = nowMs
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

internal class ModUserGesture(val modId: String, val epoch: Long, val issuedAtElapsedRealtimeMs: Long) {
    private val consumed = AtomicInteger(0)
    fun consume(modId: String, epoch: Long, nowMs: Long): Boolean =
        this.modId == modId && this.epoch == epoch && nowMs >= issuedAtElapsedRealtimeMs &&
            nowMs - issuedAtElapsedRealtimeMs < top.zainzh.rearscreen.platform.actions.RegisteredActionActivationAuthority.TTL_MS &&
            consumed.compareAndSet(0, 1)
}

private data class ModObserverOpenRequest(val token: Long, val executor: Executor, val visible: Boolean)

/**
 * One process-level broker owns one serial queue per Mod. Runtime epochs are disposable;
 * author+Mod+logicalSessionId and the SQLite model are the durable identity.
 */
class ModEventBroker(
    context: Context,
    private val effectDispatcher: ModEffectDispatcher,
    private val eventCapacity: Int = 32,
    private val clockSource: ClockSnapshotSource = AndroidClockSnapshotSource(),
) : AutoCloseable {
    private val appContext = context.applicationContext
    /** Non-null only in instrumentation. Production callers always use the credential-protected store. */
    internal var testStoreStorage: ModPackageStoreTestSession.Storage? = null
    private val epochs = AtomicLong(SystemClock.elapsedRealtimeNanos())
    private val threadNumber = AtomicInteger()
    private val eventExecutor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256), namedFactory("remrss-mod-host"), ThreadPoolExecutor.AbortPolicy())
    private val runtimeCallbacks = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64), namedFactory("remrss-mod-runtime-callback"), ThreadPoolExecutor.AbortPolicy())
    private val scheduleProvider = VisibleScheduleProvider(
        elapsedRealtime = SystemClock::elapsedRealtime,
        scheduler = ExecutorOneShotScheduler(),
        callbackExecutor = eventExecutor,
        onCallbackFailure = { ownerId, epoch, error ->
            execute { slots[ownerId]?.takeIf { it.acceptsEpoch(epoch) }?.let { failSlot(it, "E_SCHEDULE_DISPATCH", error.message ?: "schedule callback failed") } }
        }
    )
    private val viewSnapshotScheduler = ExecutorOneShotScheduler()
    private val slots = ConcurrentHashMap<String, Slot>()
    private val pendingRuntimeCloses = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()
    private val observerRequestIds = AtomicLong()
    private val observerRequests = ConcurrentHashMap<String, ConcurrentHashMap<ModBrokerListener, ModObserverOpenRequest>>()
    private val replacementGate = Any()
    private val replacementReservations = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicInteger(0)

    internal fun scheduleOwnerCountForTesting(): Int = scheduleProvider.ownerCountForTesting()
    internal fun scheduleHasDeadlineForTesting(modId: String, key: String): Boolean =
        slots[modId]?.scheduleSpecs?.get(key)?.deadlineElapsedRealtimeMs != null
    internal fun scheduleHasIntervalForTesting(modId: String, key: String): Boolean =
        slots[modId]?.scheduleSpecs?.get(key)?.intervalMs != null

    private class Slot(
        val mod: InstalledMod,
        val pkg: VerifiedModPackage,
        initialEpoch: Long,
        val queued: AtomicInteger = AtomicInteger(0),
        val commands: ArrayDeque<ModBrokerCommand> = ArrayDeque(),
        val observers: LinkedHashMap<ModBrokerListener, Executor> = LinkedHashMap(),
        val pendingOpenObservers: ConcurrentHashMap<ModBrokerListener, ModObserverOpenRequest> = ConcurrentHashMap(),
        val closeAfterDrainCompletion: CompletableFuture<Void> = CompletableFuture(),
        var runtime: IsolatedModRuntimeClient? = null,
        var ready: Boolean = false,
        var failed: Boolean = false,
        @Volatile var paused: Boolean = false,
        @Volatile var replacementRequested: Boolean = false,
        var suspended: Boolean = false,
        var closeWhenIdle: Boolean = false,
        var teardownStarted: Boolean = false,
        var runtimeRecoveryInProgress: Boolean = false,
        var recoveryConfigFailure: Pair<ModBrokerCommand.Config, String>? = null,
        var replacementCallback: Pair<Executor, (Boolean, String?) -> Unit>? = null,
        var inFlight: String? = null,
        var model: ModPersistedModel? = null,
        var scheduleLease: VisibleScheduleLease? = null,
        val scheduleSpecs: LinkedHashMap<String, ModVisibleScheduleEffect> = LinkedHashMap(),
        // submitUserEvent reads this from a UI callback while broker visibility updates
        // run on eventExecutor; preserve identity semantics and synchronize both paths.
        val visibleObservers: MutableSet<ModBrokerListener> = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ModBrokerListener, Boolean>())
        ),
        val effectRateLimiter: ModEffectRateLimiter = ModEffectRateLimiter(),
        var lastModelSnapshotElapsedMs: Long = 0L,
        var pendingModelSnapshot: Pair<String?, ModPersistedModel>? = null,
        var modelSnapshotTimer: Closeable? = null,
        val notificationResultLock: Any = Any(),
        var notificationSubmissionInProgress: Boolean = false,
        val pendingNotificationResults: ArrayList<ModQueuedNotificationResult> = ArrayList(),
    ) {
        private val runtimeEpoch = ModRuntimeEpochGate(initialEpoch)
        val epoch: Long get() = runtimeEpoch.current
        fun advanceEpoch(value: Long) = runtimeEpoch.advance(value)
        fun acceptsEpoch(value: Long) = runtimeEpoch.accepts(value)
    }

    private data class ModQueuedNotificationResult(
        val slot: Slot,
        val owner: top.zainzh.rearscreen.platform.notifications.NotificationOwner,
        val ownerEpoch: Long,
        val effectChain: ModEffectChainBudget,
        val effectId: String,
        val requestEventId: String,
        val event: top.zainzh.rearscreen.platform.notifications.NotificationEvent,
    )

    /** Activate a package for a visible host scene. `init` runs only if the durable state is absent. */
    fun open(modId: String, listenerExecutor: Executor, listener: ModBrokerListener, visible: Boolean = false) {
        synchronized(replacementGate) {
          if (modId in replacementReservations) {
              notify(listenerExecutor, listener, modId, "E_PACKAGE_UPDATING", "Mod 正在更新")
              return
          }
          if (closed.get() != 0) return
          val request = ModObserverOpenRequest(observerRequestIds.incrementAndGet(), listenerExecutor, visible)
          observerRequests.computeIfAbsent(modId) { ConcurrentHashMap() }[listener] = request
          execute { activateObserver(modId, listener, request) }
        }
    }

    private fun activateObserver(modId: String, listener: ModBrokerListener, request: ModObserverOpenRequest) {
        if (observerRequests[modId]?.get(listener)?.token != request.token) return
        slots[modId]?.let { slot ->
            if (slot.closeWhenIdle && slot.teardownStarted) {
                // Detach can cancel this request synchronously while closeAsync waits.
                slot.pendingOpenObservers[listener] = request
                return
            }
            slot.observers[listener] = request.executor
            if (request.visible) slot.visibleObservers.add(listener) else slot.visibleObservers.remove(listener)
            updateScheduleVisibility(slot)
            if (slot.closeWhenIdle) {
                slot.closeWhenIdle = false
                slot.paused = false
                if (slot.ready) drain(slot)
            }
            if (slot.ready) deliver(slot, request.executor) { listener.onReady(modId, slot.model ?: error("model missing")) }
            else if (slot.paused) notify(request.executor, listener, modId, "E_PACKAGE_UPDATING", "Mod 正在更新，暂时不能接收新操作")
            return
        }
        try {
            val installed = ModPackageStore.installed(appContext, modId, testStoreStorage) ?: error("Mod 未安装")
            require(installed.enabled) { "Mod 已停用" }
            require(ModPackageStore.isAuthorTrusted(appContext, installed.authorFingerprint, testStoreStorage)) { "作者信任已撤销" }
            val pkg = ModPackageStore.find(appContext, modId, testStoreStorage) ?: error("Mod 包签名或内容无效")
            val slot = Slot(installed, pkg, epochs.incrementAndGet())
            slot.observers[listener] = request.executor
            if (request.visible) slot.visibleObservers.add(listener)
            slots[modId] = slot
            if (request.visible) openScheduleLease(slot)
            if (pkg.runtime == ModRuntimeKind.NONE) {
                slot.model = ModPackageStore.model(appContext, modId, testStoreStorage) ?: error("Mod 本地模型缺失")
                slot.ready = true
                deliver(slot, request.executor) { listener.onReady(modId, slot.model!!) }
            } else bindRuntime(slot)
        } catch (e: Exception) {
            notify(request.executor, listener, modId, "E_MOD_OPEN", e.message ?: "无法打开 Mod")
        }
    }

    /** Detach one screen; another settings/rear screen for the same Mod keeps its listener and worker. */
    fun closeObserver(modId: String, listener: ModBrokerListener) {
        // Cancel a queued open immediately, even if its executor task has not yet
        // registered the observer in the slot.
        observerRequests[modId]?.remove(listener)
        slots[modId]?.pendingOpenObservers?.remove(listener)
        execute {
            val slot = slots[modId] ?: return@execute
            slot.observers.remove(listener)
            slot.visibleObservers.remove(listener)
            updateScheduleVisibility(slot)
            if (slot.observers.isEmpty() && (!slot.paused || slot.runtimeRecoveryInProgress)) {
                slot.paused = true
                slot.closeWhenIdle = true
                drain(slot)
            }
        }
    }

    /** Test synchronization for controlled runtime-close barriers; not a host API. */
    internal fun closeAfterDrainForTesting(modId: String): CompletableFuture<Void>? =
        slots[modId]?.closeAfterDrainCompletion

    internal fun hasRuntimeForTesting(modId: String): Boolean {
        val answer = CompletableFuture<Boolean>()
        try {
            eventExecutor.execute { answer.complete(slots[modId]?.runtime != null) }
        } catch (_: RejectedExecutionException) {
            return false
        }
        return answer.get(3, TimeUnit.SECONDS)
    }

    internal fun hasPendingOpenObserverForTesting(modId: String, listener: ModBrokerListener): Boolean {
        val answer = CompletableFuture<Boolean>()
        try {
            eventExecutor.execute { answer.complete(slots[modId]?.pendingOpenObservers?.containsKey(listener) == true) }
        } catch (_: RejectedExecutionException) {
            return false
        }
        return answer.get(3, TimeUnit.SECONDS)
    }

    /** Freeze new work, drain every accepted event/config transaction, then release the old worker. */
    fun beginPackageReplacement(modId: String, callbackExecutor: Executor, complete: (Boolean, String?) -> Unit) {
        val queued = synchronized(replacementGate) {
            if (!replacementReservations.add(modId)) false
            else {
                execute {
                    val slot = slots[modId]
                    if (slot == null || slot.failed) {
                        callbackExecutor.execute { complete(true, null) }
                        return@execute
                    }
                    if (slot.runtimeRecoveryInProgress || slot.teardownStarted) {
                        replacementReservations.remove(modId)
                        callbackExecutor.execute { complete(false, "Mod runtime 正在恢复，请稍后重试更新") }
                        return@execute
                    }
                    if (slot.paused) {
                        replacementReservations.remove(modId)
                        callbackExecutor.execute { complete(false, "Mod 生命周期正在收尾，请重试更新") }
                        return@execute
                    }
                    slot.replacementRequested = true
                    slot.paused = true
                    closeScheduleLease(slot)
                    slot.replacementCallback = callbackExecutor to complete
                    notifyObservers(slot) { it.onError(modId, "E_PACKAGE_UPDATING", "Mod 正在更新，已接受的操作正在保存") }
                    drain(slot)
                }
                true
            }
        }
        if (!queued) {
            callbackExecutor.execute { complete(false, "Mod 更新已经在进行") }
        }
    }

    /** Commit success discards the old worker; failure resumes the exact old package/model. */
    fun finishPackageReplacement(modId: String, success: Boolean) {
        execute {
            synchronized(replacementGate) {
                replacementReservations.remove(modId)
                val slot = slots[modId] ?: return@synchronized
                if (!slot.paused || !slot.suspended) return@synchronized
                slot.replacementCallback = null
                if (success) {
                    notifyObservers(slot) { it.onError(modId, "E_PACKAGE_REPLACED", "Mod 已更新，请重新打开场景") }
                    slots.remove(modId, slot)
                    slot.failed = true
                    return@synchronized
                }
                slot.replacementRequested = false
                if (slot.observers.isEmpty()) {
                    slot.paused = true
                    slot.suspended = false
                    slot.closeWhenIdle = true
                    closeAfterDrain(slot)
                    return@synchronized
                }
                slot.paused = false
                slot.suspended = false
                slot.model = ModPackageStore.model(appContext, modId, testStoreStorage)
                if (slot.pkg.runtime == ModRuntimeKind.NONE) {
                    slot.ready = true
                    slot.model?.let { model -> notifyObservers(slot) { it.onReady(modId, model) } }
                    drain(slot)
                } else {
                    slot.advanceEpoch(epochs.incrementAndGet())
                    bindRuntime(slot)
                }
            }
        }
    }

    /** Ordered UI business event; rejected at 32 pending inputs rather than silently coalesced. */
    fun submitEvent(modId: String, type: String, payload: JSONObject = JSONObject()): Boolean {
        val slot = slots[modId] ?: return false
        if (type.isBlank() || type in HOST_ONLY_EVENTS || payload.toString().toByteArray(Charsets.UTF_8).size > 16 * 1024) return false
        return enqueue(slot, ModBrokerCommand.Event(UUID.randomUUID().toString(), type, JSONObject(payload.toString())))
    }

    /** Only native Activity click handlers call this; activation stays on the host-side command. */
    fun submitUserEvent(modId: String, type: String, payload: JSONObject = JSONObject()): Boolean {
        val slot = slots[modId] ?: return false
        if (slot.visibleObservers.isEmpty() || type.isBlank() || type in HOST_ONLY_EVENTS || payload.toString().toByteArray(Charsets.UTF_8).size > 16 * 1024) return false
        val eventId = UUID.randomUUID().toString()
        val gesture = ModUserGesture(modId, slot.epoch, SystemClock.elapsedRealtime())
        return enqueue(slot, ModBrokerCommand.Event(eventId, type, JSONObject(payload.toString()), gesture))
    }

    /** The Activity supplies visibility only after checking its actual rear-window state. */
    fun setObserverVisible(modId: String, listener: ModBrokerListener, visible: Boolean) {
        execute {
            val slot = slots[modId] ?: return@execute
            if (listener !in slot.observers) return@execute
            if (visible) slot.visibleObservers.add(listener) else slot.visibleObservers.remove(listener)
            updateScheduleVisibility(slot)
        }
    }

    private fun openScheduleLease(slot: Slot) {
        if (slot.scheduleLease?.epoch == slot.epoch) return
        slot.scheduleLease?.close()
        val epoch = slot.epoch
        slot.scheduleLease = scheduleProvider.open(slot.mod.id, epoch) { tick -> onScheduleTick(slot.mod.id, epoch, tick) }
        slot.scheduleLease?.setVisible(slot.visibleObservers.isNotEmpty())
        slot.scheduleSpecs.values.forEach { spec -> slot.scheduleLease?.set(spec.key, spec.intervalMs, spec.deadlineElapsedRealtimeMs) }
    }

    private fun closeScheduleLease(slot: Slot) {
        slot.scheduleLease?.close()
        slot.scheduleLease = null
    }

    private fun updateScheduleVisibility(slot: Slot) {
        if (slot.visibleObservers.isNotEmpty()) {
            openScheduleLease(slot)
            slot.scheduleLease?.setVisible(true)
        } else slot.scheduleLease?.setVisible(false)
    }

    private fun onScheduleTick(modId: String, epoch: Long, tick: VisibleTick) {
        val slot = slots[modId] ?: return
        val lease = slot.scheduleLease ?: return
        if (!slot.acceptsEpoch(epoch) || slot.visibleObservers.isEmpty() || !lease.tryAccept(tick)) return
        // An elapsed-realtime deadline is a one-shot leg. Consume it on acceptance so
        // worker recovery/reopen cannot replay an already delivered deadline.
        if (VisibleTickCause.DEADLINE in tick.causes) {
            val spec = slot.scheduleSpecs[tick.key]
            if (spec != null && spec.deadlineElapsedRealtimeMs != null) {
                if (spec.intervalMs == null) slot.scheduleSpecs.remove(tick.key)
                else slot.scheduleSpecs[tick.key] = spec.copy(deadlineElapsedRealtimeMs = null)
                slot.scheduleSpecs[tick.key]?.let { lease.set(it.key, it.intervalMs, null) } ?: lease.remove(tick.key)
            }
        }
        val causes = JSONArray().apply { tick.causes.sortedBy { it.name }.forEach { put(it.name.lowercase()) } }
        val actualElapsedRealtimeMs = clockSource.snapshot().elapsedRealtimeMs
        if (!enqueue(slot, ModBrokerCommand.Event(UUID.randomUUID().toString(), HOST_SCHEDULE_TICK_EVENT,
                JSONObject().put("id", tick.key).put("scheduledElapsedRealtimeMs", tick.scheduledElapsedRealtimeMs)
                    .put("actualElapsedRealtimeMs", actualElapsedRealtimeMs).put("missedCount", tick.missedCount).put("causes", causes)))) {
            notifyObservers(slot) { it.onError(modId, "E_SCHEDULE_QUEUE", "可见调度事件队列已满，此次tick未提交；请降低频率或简化reducer") }
        }
    }

    private fun clockEnvironment(): JSONObject {
        val snapshot = clockSource.snapshot()
        return JSONObject().put("wallTimeMs", snapshot.wallTimeMs)
            .put("elapsedRealtimeMs", snapshot.elapsedRealtimeMs)
            .put("timezoneId", snapshot.timezoneId)
            .put("bootId", snapshot.bootId ?: JSONObject.NULL)
    }

    /** Settings are drafts in the renderer; a single model/config transaction occurs on Save. */
    fun saveConfig(modId: String, config: JSONObject, callbackExecutor: Executor, complete: (Boolean, String?) -> Unit) {
        val completed = AtomicInteger(0)
        fun finish(success: Boolean, message: String?) {
            if (completed.compareAndSet(0, 1)) callbackExecutor.execute { complete(success, message) }
        }
        synchronized(replacementGate) {
            if (modId in replacementReservations) {
                finish(false, "Mod 正在更新，请在更新完成后重试")
                return
            }
            val slot = slots[modId]
            if (slot == null) {
                finish(false, "Mod 场景尚未就绪，请稍后重试")
                return
            }
            if (!enqueue(slot, ModBrokerCommand.Config(UUID.randomUUID().toString(), JSONObject(config.toString()), callbackExecutor, ::finish))) {
                finish(false, if (slot.paused) "Mod 正在更新，请在更新完成后重试" else "Mod 输入队列已满，请稍后重试")
            }
        }
    }

    fun invalidateAndWait(modId: String, code: String = "E_MOD_REVOKED", reason: String = "Mod 权限已撤销") {
        finishSlotAndWait(modId, code, reason)
    }

    private fun finishSlotAndWait(modId: String, code: String, reason: String) {
        val completion = CompletableFuture<Unit>()
        execute {
            slots.remove(modId)?.let { slot ->
                closeScheduleLease(slot)
                slot.failed = true
                notifyObservers(slot) { it.onError(modId, code, reason) }
                slot.runtime?.let(::closeRuntimeAsync); slot.runtime = null
                completeRecoveredConfigFailure(slot, "$code: $reason")
                failQueued(slot, code, reason)
            }
            completion.complete(Unit)
        }
        completion.get(3, TimeUnit.SECONDS)
    }

    /** Called by lifecycle code only for disable/uninstall/trust revocation; the display coordinator closes exact owner sessions. */
    fun invalidate(modId: String, code: String = "E_MOD_REVOKED", reason: String = "Mod 权限已撤销") {
        execute {
            slots.remove(modId)?.let { slot ->
                closeScheduleLease(slot)
                slot.failed = true
                notifyObservers(slot) { it.onError(modId, code, reason) }
                slot.runtime?.let(::closeRuntimeAsync); slot.runtime = null
                completeRecoveredConfigFailure(slot, "$code: $reason")
                failQueued(slot, code, reason)
            }
        }
    }

    private fun bindRuntime(slot: Slot) {
        if (Build.VERSION.SDK_INT < 29) {
            failSlot(slot, "E_UNSUPPORTED", "含逻辑 Mod 需要 Android API 29 或更高版本")
            return
        }
        try {
            val client = IsolatedModRuntimeClient.bind(appContext, slot.epoch, runtimeCallbacks, object : IsolatedModRuntimeClient.Listener {
                override fun onConnected(epoch: Long) {
                    execute { if (isCurrent(slot, epoch)) slot.runtime?.loadModule(slot.pkg.reducerSource ?: ByteArray(0)) }
                }
                override fun onResult(epoch: Long, operation: String, json: String) {
                    execute { if (isCurrent(slot, epoch)) handleRuntimeResult(slot, operation, json) }
                }
                override fun onEngineError(
                    epoch: Long,
                    operation: String,
                    error: IsolatedModRuntimeClient.EngineCallError,
                ) {
                    execute { if (isCurrent(slot, epoch)) handleRuntimeEngineError(slot, operation, error) }
                }
                override fun onDisconnected(epoch: Long, reason: String) {
                    execute {
                        if (isCurrent(slot, epoch)) {
                            val code = Regex("(?:^|\\b)(E_[A-Z0-9_]+)(?::|\\b)").find(reason)?.groupValues?.get(1) ?: "E_WORKER_DIED"
                            failSlot(slot, code, reason)
                        }
                    }
                }
            })
            slot.runtime = client
        } catch (e: UnsupportedOperationException) {
            failSlot(slot, "E_UNSUPPORTED", e.message ?: "脚本 Mod 在此 Android 版本不可用")
        } catch (e: IllegalStateException) {
            failSlot(slot, if (e.message?.contains("E_BUSY") == true) "E_BUSY" else "E_RUNTIME_BIND", e.message ?: "无法启动隔离 runtime")
        } catch (e: Exception) {
            failSlot(slot, "E_RUNTIME_BIND", e.message ?: "无法绑定隔离 runtime")
        }
    }

    private fun handleRuntimeResult(slot: Slot, operation: String, raw: String) {
        if (slot.failed || slots[slot.mod.id] !== slot) return
        if (operation == "loadModule") {
            val result = runtimeObject(raw, operation) ?: return failSlot(slot, "E_RUNTIME_RESULT", "runtime loadModule 返回值无效")
            if (!result.optBoolean("ok", false)) return failSlot(slot, result.optString("code", "E_SCRIPT"), result.optString("message", "Mod 脚本加载失败"))
            val model = ModPackageStore.model(appContext, slot.mod.id, testStoreStorage) ?: return failSlot(slot, "E_STORAGE_FAILED", "Mod 模型不存在")
            slot.model = model
            if (model.state == null) {
                if (slot.runtimeRecoveryInProgress) return failSlot(slot, "E_INVALID_STATE", "恢复时已提交模型缺少 state，拒绝重新初始化")
                slot.inFlight = "init"
                slot.runtime?.init(JSONObject().put("config", model.config).put("env", JSONObject().put("clock", clockEnvironment())).put("dataVersion", model.dataVersion).toString())
            } else {
                if (slot.visibleObservers.isNotEmpty() && slot.scheduleSpecs.isNotEmpty()) openScheduleLease(slot)
                slot.ready = true
                if (slot.runtimeRecoveryInProgress) {
                    slot.runtimeRecoveryInProgress = false
                    slot.paused = slot.closeWhenIdle && slot.observers.isEmpty()
                    notifyObservers(slot) { it.onReady(slot.mod.id, model) }
                    completeRecoveredConfigFailure(slot)
                    drain(slot)
                    return
                }
                notifyObservers(slot) { it.onReady(slot.mod.id, model) }
                drain(slot)
            }
            return
        }
        if (operation == "init") {
            val state = runtimeObject(raw, operation) ?: return failSlot(slot, "E_RUNTIME_RESULT", "init 必须返回 JSON object state")
            try {
                val model = ModPackageStore.commitInitialState(appContext, slot.mod.id, state, testStoreStorage)
                slot.model = model; slot.inFlight = null; slot.ready = true
                notifyObservers(slot) { it.onReady(slot.mod.id, model) }
                drain(slot)
            } catch (e: Exception) { failSlot(slot, "E_INVALID_STATE", e.message ?: "init state 与 schema 不匹配") }
            return
        }
        if (operation != "reduce") return failSlot(slot, "E_RUNTIME_RESULT", "unexpected runtime operation: $operation")
        val result = runtimeObject(raw, operation) ?: return failSlot(slot, "E_RUNTIME_RESULT", "reduce 必须返回 JSON object")
        val command = slot.commands.firstOrNull() ?: return failSlot(slot, "E_INVALID_STATE", "reducer 回包没有匹配输入")
        val runtimeCpuNanos = slot.runtime?.lastExecutionCpuTimeNanos() ?: -1L
        val commandChain = when (command) {
            is ModBrokerCommand.Event -> command.effectChain
            is ModBrokerCommand.Config -> command.effectChain
        }
        if (runtimeCpuNanos > 0) commandChain.addCpu(runtimeCpuNanos)
        // Engine failures arrive via typed onEngineError. A script payload, even
        // one with {ok:false, code:"E_SCRIPT"}, is data and cannot impersonate it.
        if (!result.has("state") || result.opt("state") !is JSONObject || result.keys().asSequence().any { it !in setOf("state", "effects") }) {
            return failSlot(slot, "E_INVALID_RESULT", "reduce 只允许返回 {state,effects?}")
        }
        if (result.has("effects") && result.opt("effects") !is JSONArray) return failSlot(slot, "E_INVALID_RESULT", "effects 必须是数组")
        val effects = result.optJSONArray("effects") ?: JSONArray()
        if (effects.length() > 8) return failSlot(slot, "E_LIMIT_EXCEEDED", "单事件 Effect 超过 8 项")
        val parsedEffects = try {
            (0 until effects.length()).map { parseEffect(slot, effects.getJSONObject(it)) }
        } catch (e: Exception) {
            if (command is ModBrokerCommand.Config) {
                rejectConfigCommand(slot, command, "E_CAPABILITY_DENIED", e.message ?: "Effect 无效或未授权")
                return
            }
            return failSlot(slot, "E_CAPABILITY_DENIED", e.message ?: "Effect 无效或未授权")
        }
        if (command is ModBrokerCommand.Config) {
            val activationEffect = parsedEffects.firstOrNull { it.requiresUserActivation }
            if (activationEffect != null) {
                rejectConfigCommand(slot, command, "E_ACTIVATION_REQUIRED", "rear.open 需要由用户手势触发，保存配置不会签发背屏激活")
                return
            }
        }
        val before = slot.model ?: return failSlot(slot, "E_STORAGE_FAILED", "模型快照丢失")
        val nextConfig = (command as? ModBrokerCommand.Config)?.config ?: before.config
        val eventId = when (command) {
            is ModBrokerCommand.Event -> command.eventId
            is ModBrokerCommand.Config -> command.eventId
        }
        try {
            // Persist state and, for a host.configChanged event, its new config in the
            // same optimistic revision before notifying either visible observer.
            val model = ModPackageStore.commitModel(appContext, slot.mod.id, before.revision,
                result.getJSONObject("state"), nextConfig, testStoreStorage)
            slot.model = model; slot.inFlight = null
            slot.commands.removeFirst(); slot.queued.decrementAndGet()
            if (command is ModBrokerCommand.Config) {
                deliver(slot, command.callbackExecutor) { command.complete(true, null) }
            }
            notifyModelCommitted(slot, eventId, model)
            val eventChain = commandChain
            val effectChainCpuExceeded = !eventChain.withinCpuBudget()
            if (effectChainCpuExceeded && (eventChain.hasEffects() || parsedEffects.isNotEmpty())) {
                notifyObservers(slot) { it.onError(slot.mod.id, "E_EFFECT_CHAIN_CPU_LIMIT", "Effect 因果链累计 CPU 超过 100 ms；已提交模型保留，本次及后续 Effect 不再派发") }
            }
            val gesture = (command as? ModBrokerCommand.Event)?.activation
            if (!effectChainCpuExceeded) parsedEffects.forEach { effect ->
                notifyObservers(slot) { it.onEffectRequested(slot.mod.id, eventId) }
                dispatchEffect(slot, eventId, effect, gesture, eventChain)
            }
            drain(slot)
        } catch (e: Exception) { failSlot(slot, "E_STORAGE_FAILED", e.message ?: "模型事务失败") }
    }

    /** Engine disposition comes only from the private typed envelope, never script JSON. */
    private fun handleRuntimeEngineError(
        slot: Slot,
        operation: String,
        error: IsolatedModRuntimeClient.EngineCallError,
    ) {
        if (slot.failed || slots[slot.mod.id] !== slot) return
        val command = slot.commands.firstOrNull()
        if (operation == "reduce" && command is ModBrokerCommand.Config &&
            slot.inFlight == "reduce:${command.eventId}" &&
            error.category() == IsolatedModRuntimeClient.EngineFaultCategory.SCRIPT
        ) {
            recoverAfterConfigScriptError(slot, command, error)
            return
        }
        failSlot(slot, error.code(), error.message())
    }

    /** A script-call error may include native allocation/stack failure: reset the worker before retry. */
    private fun recoverAfterConfigScriptError(
        slot: Slot,
        command: ModBrokerCommand.Config,
        error: IsolatedModRuntimeClient.EngineCallError,
    ) {
        if (slots[slot.mod.id] !== slot || slot.failed) return
        if (slot.commands.firstOrNull() !== command || slot.inFlight != "reduce:${command.eventId}") {
            return failSlot(slot, "E_INVALID_STATE", "配置失败结果没有匹配输入")
        }
        val runtime = slot.runtime ?: return failSlot(slot, "E_WORKER_DIED", "配置失败后找不到待重置的隔离 runtime")
        slot.commands.removeFirst()
        slot.queued.decrementAndGet()
        slot.inFlight = null
        slot.ready = false
        slot.runtimeRecoveryInProgress = true
        slot.recoveryConfigFailure = command to "${error.code()}: ${error.message()}"
        slot.teardownStarted = true

        // Invalidate every old-epoch result now, then wait for confirmed destruction,
        // unbind and global-slot release before binding a fresh worker.
        closeScheduleLease(slot)
        slot.advanceEpoch(epochs.incrementAndGet())
        closeRuntimeAsync(runtime).whenComplete { _, failure ->
            execute {
                if (slots[slot.mod.id] !== slot || slot.failed) return@execute
                slot.teardownStarted = false
                if (failure != null) {
                    failSlot(slot, "E_CLOSE_UNCONFIRMED", "旧隔离 runtime 尚未确认关闭；为避免重复占用，当前场景已停止")
                    return@execute
                }
                slot.runtime = null
                val reopen = slot.pendingOpenObservers.toList()
                slot.pendingOpenObservers.clear()
                reopen.forEach { (listener, request) ->
                    if (observerRequests[slot.mod.id]?.get(listener)?.token == request.token) {
                        activateObserver(slot.mod.id, listener, request)
                    }
                }
                if (slot.closeWhenIdle && slot.observers.isEmpty() && slot.commands.isEmpty() && slot.queued.get() == 0) {
                    slot.runtimeRecoveryInProgress = false
                    slot.failed = true
                    slots.remove(slot.mod.id, slot)
                    completeRecoveredConfigFailure(slot)
                    slot.closeAfterDrainCompletion.complete(null)
                    return@execute
                }
                slot.advanceEpoch(epochs.incrementAndGet())
                bindRuntime(slot)
            }
        }
    }

    private fun completeRecoveredConfigFailure(slot: Slot, overrideMessage: String? = null) {
        val pending = slot.recoveryConfigFailure ?: return
        slot.recoveryConfigFailure = null
        val (command, originalMessage) = pending
        deliver(slot, command.callbackExecutor) {
            command.complete(false, overrideMessage ?: originalMessage)
        }
    }

    /** A reducer refusal belongs to this Save transaction, not to the shared worker/session. */
    private fun rejectConfigCommand(slot: Slot, command: ModBrokerCommand.Config, code: String, message: String) {
        if (slots[slot.mod.id] !== slot || slot.failed) return
        if (slot.commands.firstOrNull() !== command) return failSlot(slot, "E_INVALID_STATE", "配置拒绝结果没有匹配输入")
        slot.inFlight = null
        slot.commands.removeFirst()
        slot.queued.decrementAndGet()
        deliver(slot, command.callbackExecutor) { command.complete(false, "$code: $message") }
        drain(slot)
    }

    private fun parseEffect(slot: Slot, value: JSONObject): ModHostEffect {
        val type = value.optString("type")
        return when (type) {
            "rear.open" -> {
                require(value.keys().asSequence().toSet() in setOf(setOf("type", "scene", "region", "keepVisible"), setOf("id", "type", "scene", "region", "keepVisible"))) { "rear.open 字段无效" }
                require(value.getString("scene") == "rear") { "Mod 只能打开自身声明的 rear scene" }
                require(value.getString("region") in setOf("CAMERA_SAFE", "FULL")) { "rear.open region 无效" }
                require(value.get("keepVisible") is Boolean) { "rear.open keepVisible 必须为 boolean" }
                val needed = mutableSetOf("rear.scene")
                if (value.getString("region") == "FULL") needed += "rear.region"
                if (value.getBoolean("keepVisible")) needed += "rear.scene.keep_visible"
                val declared = (slot.pkg.requiredCapabilities + slot.pkg.optionalCapabilities).map { it.name }.toSet()
                require(needed.all { it in declared }) { "Mod manifest 未声明所需能力：${(needed-declared).joinToString()}" }
                require(needed.all { it in slot.mod.grantedCapabilities }) { "rear.open 缺少能力授权：${(needed-slot.mod.grantedCapabilities).joinToString()}" }
                ModRearOpenEffect(value.optString("id").ifBlank { UUID.randomUUID().toString() }, "rear", value.getString("region"), value.getBoolean("keepVisible"))
            }
            "schedule.visible.register", "schedule.visible.cancel" -> {
                val declared = (slot.pkg.requiredCapabilities + slot.pkg.optionalCapabilities).any { it.name == "schedule.visible" }
                require(declared) { "Mod manifest 未声明 schedule.visible" }
                require("schedule.visible" in slot.mod.grantedCapabilities) { "Mod 未获 schedule.visible 授权" }
                val cancel = type.endsWith("cancel")
                val fields = value.keys().asSequence().toSet()
                val allowed = if (cancel) fields == setOf("id", "type") else
                    fields.containsAll(setOf("id", "type")) && fields.all { it in setOf("id", "type", "intervalMs", "deadlineElapsedRealtimeMs") }
                require(allowed) { "visible schedule 字段无效" }
                val id = value.getString("id")
                require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "visible schedule id 无效" }
                fun safeInteger(field: String): Long {
                    val raw = value.get(field)
                    require(raw is Number) { "$field 必须为安全整数" }
                    val number = raw.toDouble()
                    require(number.isFinite() && number % 1.0 == 0.0 && number in 0.0..9_007_199_254_740_991.0) {
                        "$field 必须为非负安全整数"
                    }
                    return raw.toLong()
                }
                val interval = if (cancel || !value.has("intervalMs")) null else safeInteger("intervalMs")
                val deadline = if (cancel || !value.has("deadlineElapsedRealtimeMs")) null else safeInteger("deadlineElapsedRealtimeMs")
                require(cancel || interval != null || deadline != null) { "至少提供 intervalMs 或 deadlineElapsedRealtimeMs" }
                // `id` in the author API is a stable schedule key. Effect-result IDs
                // are unique per invocation and must not alias that key.
                ModVisibleScheduleEffect(UUID.randomUUID().toString(), id, interval, deadline, cancel)
            }
            "action.invoke" -> {
                require(value.keys().asSequence().toSet() == setOf("type", "ref")) { "action.invoke 字段无效" }
                val declared = slot.pkg.requiredCapabilities.any { it.name == "action.invoke" } ||
                    slot.pkg.optionalCapabilities.any { it.name == "action.invoke" }
                require(declared) { "Mod manifest 未声明 action.invoke" }
                require("action.invoke" in slot.mod.grantedCapabilities) { "Mod 未获 action.invoke 授权" }
                val refJson = value.getJSONObject("ref")
                require(refJson.keys().asSequence().toSet() == setOf("id", "version")) { "registered action ref 字段无效" }
                val ref = top.zainzh.rearscreen.platform.actions.RegisteredActionRef(refJson.getString("id"), refJson.getInt("version"))
                val owner = top.zainzh.rearscreen.platform.RearCapabilityCore.get(appContext).modDataOwner(slot.mod.id)
                    ?: throw IllegalArgumentException("Mod owner 不可用")
                val registry = top.zainzh.rearscreen.platform.actions.PlatformRegisteredActionRegistry.get(appContext)
                val resolved = registry.resolve(owner, ref)
                require(resolved is top.zainzh.rearscreen.platform.actions.ActionResolveResult.Resolved) {
                    "registered action ref 不存在、已撤销、目标无效或不属于此 Mod"
                }
                ModRegisteredActionEffect("${slot.epoch}:${UUID.randomUUID()}", ref)
            }
            "rear.notification.publish", "rear.notification.cancel" -> parseNotificationEffect(slot, value, type)
            else -> throw IllegalArgumentException("不支持的 Mod Effect：$type")
        }
    }

    private fun parseNotificationEffect(slot: Slot, value: JSONObject, type: String): ModHostEffect {
        val declared = (slot.pkg.requiredCapabilities + slot.pkg.optionalCapabilities).any { it.name == "rear.notification" }
        val granted = "rear.notification" in slot.mod.grantedCapabilities
        val fields = value.keys().asSequence().toSet()
        val publish = type == "rear.notification.publish"
        val allowed = if (publish) setOf("type", "id", "notificationId", "title", "text", "durationMs", "priority", "privacy")
            else setOf("type", "id", "notificationId")
        require(fields.all { it in allowed } && fields.containsAll(if (publish) setOf("type", "notificationId", "title") else setOf("type", "notificationId"))) {
            "rear.notification 字段无效"
        }
        val id = if (value.has("id")) {
            val supplied = value.get("id")
            require(supplied is String && supplied.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Effect id 无效" }
            supplied
        } else UUID.randomUUID().toString()
        val rawNotificationId = value.get("notificationId")
        require(rawNotificationId is String) { "notificationId 必须为字符串" }
        val notificationId = rawNotificationId
        require(notificationId.matches(Regex("[A-Za-z0-9._:-]{1,64}"))) { "notificationId 无效" }
        if (!publish) return if (declared && granted) ModNotificationCancelEffect(id, notificationId)
            else ModNotificationDeniedEffect(id, "E_CAPABILITY_DENIED")

        fun boundedPlainText(field: String, maxCodePoints: Int, allowNewline: Boolean): String {
            val raw = if (value.has(field)) value.get(field) else ""
            require(raw is String) { "$field 必须为字符串" }
            val text = raw
            val count = text.codePointCount(0, text.length)
            require(count <= maxCodePoints && (field != "title" || count >= 1)) { "$field 长度无效" }
            var index = 0
            while (index < text.length) {
                val character = text[index]
                if (Character.isHighSurrogate(character)) {
                    require(index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) { "$field Unicode 无效" }
                    index += 2
                } else {
                    require(!Character.isLowSurrogate(character)) { "$field Unicode 无效" }
                    index++
                }
            }
            require(text.none { Character.isISOControl(it) && !(allowNewline && it == '\n') }) { "$field 含控制字符" }
            return text
        }
        val title = boundedPlainText("title", 80, false)
        val text = boundedPlainText("text", 240, true)
        val durationMs = if (value.has("durationMs")) {
            val raw = value.get("durationMs")
            require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() % 1.0 == 0.0 && raw.toLong() in 1_000L..15_000L) {
                "durationMs 应为 1000..15000 的整数"
            }
            raw.toLong()
        } else 8_000L
        val priority = if (value.has("priority")) value.get("priority") else "NORMAL"
        require(priority is String) { "priority 必须为字符串" }
        require(priority in setOf("LOW", "NORMAL", "HIGH")) { "priority 无效" }
        val privacy = if (value.has("privacy")) value.get("privacy") else "STANDARD"
        require(privacy is String) { "privacy 必须为字符串" }
        require(privacy in setOf("STANDARD", "HIDE_TITLE", "HIDE_TEXT")) { "privacy 无效" }
        require(value.toString().toByteArray(Charsets.UTF_8).size <= 2 * 1024) { "通知 Effect 超过 2 KiB" }
        return if (declared && granted) ModNotificationPublishEffect(id, notificationId, title, text, durationMs, priority, privacy)
            else ModNotificationDeniedEffect(id, "E_CAPABILITY_DENIED")
    }

    private fun dispatchEffect(
        slot: Slot,
        eventId: String,
        effect: ModHostEffect,
        gesture: ModUserGesture?,
        effectChain: ModEffectChainBudget,
    ) {
        val completed = AtomicInteger(0)
        fun complete(status: String, code: String? = null) {
            if (!completed.compareAndSet(0, 1)) return
            when (effectChain.reserveResultEvent()) {
                ResultEventReservation.CPU_LIMIT -> {
                    notifyObservers(slot) { it.onError(slot.mod.id, "E_EFFECT_CHAIN_CPU_LIMIT", "Effect 因果链累计 CPU 超过 100 ms") }
                    return
                }
                ResultEventReservation.EVENT_LIMIT -> {
                    notifyObservers(slot) { it.onError(slot.mod.id, "E_EFFECT_CHAIN_LIMIT", "Effect 因果链超过 16 个事件") }
                    return
                }
                ResultEventReservation.ACCEPTED -> Unit
            }
            val payload = JSONObject().put("effectId", effect.id).put("requestEventId", eventId).put("status", status)
            if (code != null) payload.put("code", code)
            if (slots[slot.mod.id] === slot && slot.acceptsEpoch(slot.epoch)) {
                if (!enqueue(slot, ModBrokerCommand.Event(UUID.randomUUID().toString(), HOST_EFFECT_RESULT_EVENT, payload, effectChain = effectChain)))
                    notifyObservers(slot) { it.onError(slot.mod.id, "E_EFFECT_RESULT_QUEUE", "Effect 结果事件队列已满，操作状态未能回送") }
            }
        }
        effectChain.markEffectIssued()
        if (!slot.effectRateLimiter.tryAcquire(SystemClock.elapsedRealtime())) {
            complete("rejected", "E_LIMIT_EXCEEDED")
            return
        }
        when (effect) {
            is ModVisibleScheduleEffect -> runCatching {
                if (slot.scheduleLease == null) openScheduleLease(slot)
                val lease = slot.scheduleLease ?: error("visible schedule lease 不可用")
                if (effect.cancel) {
                    lease.remove(effect.key)
                    slot.scheduleSpecs.remove(effect.key)
                } else {
                    lease.set(effect.key, effect.intervalMs, effect.deadlineElapsedRealtimeMs)
                    slot.scheduleSpecs[effect.key] = effect
                }
            }.onSuccess { complete("accepted") }
                .onFailure { complete("rejected", "E_SCHEDULE_INVALID") }
            is ModRearOpenEffect -> effectDispatcher.requestRearSceneWithResult(slot.mod, eventId, effect) { accepted, code ->
                complete(if (accepted) "accepted" else "rejected", code)
            }
            is ModRegisteredActionEffect -> {
                val now = SystemClock.elapsedRealtime()
                if (gesture == null || !gesture.consume(slot.mod.id, slot.epoch, now)) {
                    complete("rejected", "E_ACTIVATION_REQUIRED")
                } else runCatching {
                    val installed = ModPackageStore.installed(appContext, slot.mod.id, testStoreStorage)
                        ?: throw SecurityException("E_MOD_REVOKED")
                    require(installed.enabled && installed.authorFingerprint == slot.pkg.authorFingerprint) { "E_MOD_REVOKED" }
                    val declared = slot.pkg.requiredCapabilities.any { it.name == "action.invoke" } ||
                        slot.pkg.optionalCapabilities.any { it.name == "action.invoke" }
                    require(declared && "action.invoke" in installed.grantedCapabilities) { "E_CAPABILITY_DENIED" }
                    val owner = top.zainzh.rearscreen.platform.RearCapabilityCore.get(appContext).modDataOwner(slot.mod.id)
                        ?: throw SecurityException("E_MOD_REVOKED")
                    val activation = top.zainzh.rearscreen.platform.RearCapabilityCore.get(appContext)
                        .issueRegisteredActionActivationAfterHostClick(owner, effect.ref, gesture.issuedAtElapsedRealtimeMs)
                    top.zainzh.rearscreen.platform.RearCapabilityCore.get(appContext).invokeRegisteredAction(owner, effect.ref, activation)
                }.onSuccess { result ->
                    when (result) {
                        top.zainzh.rearscreen.platform.actions.ActionDispatchResult.Accepted -> complete("accepted")
                        is top.zainzh.rearscreen.platform.actions.ActionDispatchResult.Rejected -> complete("rejected", "E_ACTION_${result.error.name}")
                    }
                }.onFailure { failure ->
                    val detail = failure.message?.takeIf { it in setOf("E_MOD_REVOKED", "E_CAPABILITY_DENIED") }
                    complete("rejected", detail ?: "E_ACTION_INVOKE")
                }
            }
            is ModNotificationPublishEffect -> {
                synchronized(slot.notificationResultLock) { slot.notificationSubmissionInProgress = true }
                try {
                    val result = runCatching {
                        val core = top.zainzh.rearscreen.platform.RearCapabilityCore.get(appContext)
                        val installed = ModPackageStore.installed(appContext, slot.mod.id, testStoreStorage)
                            ?: throw SecurityException("E_MOD_REVOKED")
                        require(installed.enabled && installed.authorFingerprint == slot.pkg.authorFingerprint) { "E_MOD_REVOKED" }
                        require("rear.notification" in installed.grantedCapabilities &&
                            (installed.requiredCapabilities + installed.optionalCapabilities).any { it.name == "rear.notification" }) { "E_CAPABILITY_DENIED" }
                        val owner = core.modNotificationOwner(slot.mod.id) ?: throw SecurityException("E_MOD_REVOKED")
                        val ownerEpoch = slot.epoch
                        val request = top.zainzh.rearscreen.platform.notifications.NotificationRequest(
                            effect.notificationId, effect.title, effect.text, effect.durationMs,
                            top.zainzh.rearscreen.platform.notifications.NotificationPriority.valueOf(effect.priority),
                            top.zainzh.rearscreen.platform.notifications.NotificationPrivacy.valueOf(effect.privacy),
                        )
                        core.ownedNotifications.publish(owner, request,
                            top.zainzh.rearscreen.platform.notifications.NotificationObserver { event ->
                                deliverHostNotificationResult(slot, owner, ownerEpoch, effectChain, effect.id, eventId, event)
                            })
                    }
                    result.onSuccess { complete(it.status.name.lowercase(java.util.Locale.ROOT), it.reason) }
                        .onFailure { failure ->
                            val code = failure.message?.takeIf { it == "E_MOD_REVOKED" || it == "E_CAPABILITY_DENIED" }
                            complete("rejected", code ?: "E_NOTIFICATION_PUBLISH")
                        }
                } finally {
                    finishNotificationSubmission(slot)
                }
            }
            is ModNotificationDeniedEffect -> complete("rejected", effect.reason)
            is ModNotificationCancelEffect -> {
                synchronized(slot.notificationResultLock) { slot.notificationSubmissionInProgress = true }
                try {
                    val result = runCatching {
                        val core = top.zainzh.rearscreen.platform.RearCapabilityCore.get(appContext)
                        val installed = ModPackageStore.installed(appContext, slot.mod.id, testStoreStorage)
                            ?: throw SecurityException("E_MOD_REVOKED")
                        require(installed.enabled && installed.authorFingerprint == slot.pkg.authorFingerprint) { "E_MOD_REVOKED" }
                        require("rear.notification" in installed.grantedCapabilities &&
                            (installed.requiredCapabilities + installed.optionalCapabilities).any { it.name == "rear.notification" }) { "E_CAPABILITY_DENIED" }
                        val owner = core.modNotificationOwner(slot.mod.id) ?: throw SecurityException("E_MOD_REVOKED")
                        core.ownedNotifications.cancel(owner, effect.notificationId,
                            top.zainzh.rearscreen.platform.notifications.NotificationEndReason.CANCELLED)
                    }
                    result.onSuccess { complete(it.status.name.lowercase(java.util.Locale.ROOT), it.reason) }
                        .onFailure { failure ->
                            val code = failure.message?.takeIf { it == "E_MOD_REVOKED" || it == "E_CAPABILITY_DENIED" }
                            complete("rejected", code ?: "E_NOTIFICATION_CANCEL")
                        }
                } finally {
                    finishNotificationSubmission(slot)
                }
            }
        }
    }

    private fun deliverHostNotificationResult(
        slot: Slot,
        owner: top.zainzh.rearscreen.platform.notifications.NotificationOwner,
        ownerEpoch: Long,
        effectChain: ModEffectChainBudget,
        effectId: String,
        requestEventId: String,
        event: top.zainzh.rearscreen.platform.notifications.NotificationEvent,
    ) {
        if (slots[slot.mod.id] !== slot || !slot.acceptsEpoch(ownerEpoch) || slot.failed) return
        val currentOwner = top.zainzh.rearscreen.platform.RearCapabilityCore.get(appContext).modNotificationOwner(slot.mod.id)
        if (currentOwner == null || currentOwner.generation != owner.generation || currentOwner.namespace != owner.namespace) return
        // Only the host callback can enter this reserved event. Never invoke QuickJS from the router callback thread.
        val queued = ModQueuedNotificationResult(slot, owner, ownerEpoch, effectChain, effectId, requestEventId, event)
        synchronized(slot.notificationResultLock) {
            if (slot.notificationSubmissionInProgress) {
                slot.pendingNotificationResults.add(queued)
                return
            }
        }
        enqueueHostNotificationResult(queued)
    }

    private fun finishNotificationSubmission(slot: Slot) {
        val queued = synchronized(slot.notificationResultLock) {
            slot.notificationSubmissionInProgress = false
            slot.pendingNotificationResults.toList().also { slot.pendingNotificationResults.clear() }
        }
        queued.forEach(::enqueueHostNotificationResult)
    }

    private fun enqueueHostNotificationResult(queued: ModQueuedNotificationResult) {
        val slot = queued.slot
        if (slots[slot.mod.id] !== slot || !slot.acceptsEpoch(queued.ownerEpoch) || slot.failed) return
        if (slot.paused || slot.closeWhenIdle || slot.teardownStarted) return
        val currentOwner = top.zainzh.rearscreen.platform.RearCapabilityCore.get(appContext).modNotificationOwner(slot.mod.id)
        if (currentOwner == null || currentOwner.generation != queued.owner.generation || currentOwner.namespace != queued.owner.namespace) return
        when (queued.effectChain.reserveResultEvent()) {
            ResultEventReservation.CPU_LIMIT -> {
                notifyObservers(slot) { it.onError(slot.mod.id, "E_EFFECT_CHAIN_CPU_LIMIT", "Effect 因果链累计 CPU 超过 100 ms") }
                return
            }
            ResultEventReservation.EVENT_LIMIT -> {
                notifyObservers(slot) { it.onError(slot.mod.id, "E_EFFECT_CHAIN_LIMIT", "Effect 因果链超过 16 个事件") }
                return
            }
            ResultEventReservation.ACCEPTED -> Unit
        }
        val payload = JSONObject()
            .put("effectId", queued.effectId)
            .put("requestEventId", queued.requestEventId)
            .put("notificationId", queued.event.id)
            .put("status", queued.event.status.name)
        queued.event.reason?.let { payload.put("reason", it) }
        queued.event.endedBy?.let { payload.put("endedBy", it.name) }
        if (!enqueue(slot, ModBrokerCommand.Event(UUID.randomUUID().toString(), HOST_NOTIFICATION_RESULT_EVENT, payload, effectChain = queued.effectChain))) {
            notifyObservers(slot) { it.onError(slot.mod.id, "E_NOTIFICATION_RESULT_QUEUE", "通知异步结果事件队列已满") }
        }
    }

    private fun drain(slot: Slot) {
        if (slots[slot.mod.id] !== slot || slot.failed || slot.inFlight != null || slot.runtimeRecoveryInProgress) return
        if (slot.paused) {
            if (slot.commands.isEmpty()) {
                finishPausedDrain(slot)
                return
            }
        }
        if (!slot.ready) return
        while (slot.commands.isNotEmpty()) {
            when (val command = slot.commands.first()) {
                is ModBrokerCommand.Config -> {
                    val before = slot.model ?: ModPackageStore.model(appContext, slot.mod.id, testStoreStorage) ?: return failSlot(slot, "E_STORAGE_FAILED", "模型缺失")
                    try {
                        ModJsonSchema.validateValue(slot.pkg.configSchema, command.config, "config")
                        if (slot.pkg.runtime == ModRuntimeKind.QUICKJS) {
                            val state = before.state ?: return failSlot(slot, "E_INVALID_STATE", "配置更新前模型尚未初始化")
                            val event = JSONObject().put("type", HOST_CONFIG_CHANGED_EVENT).put("payload", JSONObject())
                                .put("eventId", command.eventId).put("revision", before.revision)
                                .put("logicalSessionId", slot.mod.logicalSessionId)
                            val context = JSONObject().put("state", state).put("config", JSONObject(command.config.toString()))
                                .put("previousConfig", JSONObject(before.config.toString())).put("event", event)
                                .put("env", JSONObject().put("clock", clockEnvironment())).put("revision", before.revision)
                            slot.inFlight = "reduce:${command.eventId}"
                            slot.runtime?.reduce(context.toString()) ?: return failSlot(slot, "E_WORKER_DIED", "isolated worker is not connected")
                            return
                        }
                        // Declarative/no-script Mods never bind or start a VM.
                        val model = ModPackageStore.commitModel(appContext, slot.mod.id, before.revision, before.state, command.config, testStoreStorage)
                        slot.model = model; slot.commands.removeFirst(); slot.queued.decrementAndGet()
                        deliver(slot, command.callbackExecutor) { command.complete(true, null) }
                        notifyModelCommitted(slot, command.eventId, model)
                    } catch (e: Exception) {
                        slot.commands.removeFirst(); slot.queued.decrementAndGet()
                        deliver(slot, command.callbackExecutor) { command.complete(false, e.message ?: "配置写入失败") }
                        notifyObservers(slot) { it.onError(slot.mod.id, "E_STORAGE_FAILED", e.message ?: "配置写入失败") }
                    }
                }
                is ModBrokerCommand.Event -> {
                    val current = slot.model ?: return failSlot(slot, "E_STORAGE_FAILED", "模型缺失")
                    if (current.state == null) return failSlot(slot, "E_INVALID_STATE", "首次激活模型未初始化")
                    val event = JSONObject().put("type", command.type).put("payload", command.payload)
                        .put("eventId", command.eventId).put("revision", current.revision).put("logicalSessionId", slot.mod.logicalSessionId)
                    val context = JSONObject().put("state", current.state).put("config", current.config).put("event", event)
                        .put("env", JSONObject().put("clock", clockEnvironment())).put("revision", current.revision)
                    slot.inFlight = "reduce:${command.eventId}"
                    slot.runtime?.reduce(context.toString()) ?: run { failSlot(slot, "E_WORKER_DIED", "isolated worker is not connected") }
                    return
                }
            }
        }
        finishPausedDrain(slot)
    }

    /** Every terminal drain path (including an in-flight reduce followed by Config) must settle. */
    private fun finishPausedDrain(slot: Slot) {
        if (!slot.paused || slot.inFlight != null || slot.commands.isNotEmpty()) return
        if (slot.closeWhenIdle) closeAfterDrain(slot) else suspendForReplacement(slot)
    }

    private fun enqueue(slot: Slot, command: ModBrokerCommand): Boolean {
        return synchronized(replacementGate) {
            if (slot.mod.id in replacementReservations || slot.paused || slot.replacementRequested) return@synchronized false
            val count = slot.queued.incrementAndGet()
            if (count > eventCapacity) { slot.queued.decrementAndGet(); return@synchronized false }
            try {
                eventExecutor.execute {
                if (slots[slot.mod.id] !== slot || slot.failed) {
                    slot.queued.decrementAndGet()
                    if (command is ModBrokerCommand.Config) deliver(slot, command.callbackExecutor) { command.complete(false, "Mod session 已关闭") }
                    return@execute
                }
                slot.commands.addLast(command)
                drain(slot)
            }
                true
            } catch (_: RejectedExecutionException) { slot.queued.decrementAndGet(); false }
        }
    }

    private fun failSlot(slot: Slot, code: String, message: String) {
        if (slot.failed) return
        closeScheduleLease(slot)
        // The centralized Mod teardown also closes this Mod's notification owner.
        runCatching { top.zainzh.rearscreen.platform.RearCapabilityCore.get(appContext).closeModDataSessionsForMod(slot.mod.id) }
        slot.failed = true; slot.ready = false; slot.runtime?.let(::closeRuntimeAsync); slot.runtime = null
        slots.remove(slot.mod.id, slot)
        completeRecoveredConfigFailure(slot, "$code: $message")
        failQueued(slot, code, message)
        slot.replacementCallback?.let { (executor, callback) ->
            slot.replacementCallback = null
            slot.replacementRequested = false
            executor.execute { callback(false, "$code：$message") }
        }
        notifyObservers(slot) { it.onError(slot.mod.id, code, message) }
    }

    private fun failQueued(slot: Slot, code: String, message: String) {
        while (slot.commands.isNotEmpty()) {
            val command=slot.commands.removeFirst(); slot.queued.decrementAndGet()
            if (command is ModBrokerCommand.Config) deliver(slot, command.callbackExecutor) { command.complete(false, message) }
        }
    }

    private fun suspendForReplacement(slot: Slot) {
        if (!slot.paused || slot.suspended || slot.teardownStarted || slot.inFlight != null || slot.commands.isNotEmpty()) return
        val runtime = slot.runtime
        if (runtime == null) {
            slot.ready = false
            slot.suspended = true
            deliverReplacementReady(slot)
            return
        }
        slot.teardownStarted = true
        closeRuntimeAsync(runtime)
            .thenComposeAsync({ CompletableFuture.completedFuture(Unit) }, eventExecutor)
            .whenComplete { _, failure ->
                if (slots[slot.mod.id] !== slot || slot.failed) return@whenComplete
                slot.teardownStarted = false
                if (failure != null) {
                    slot.replacementCallback?.let { (executor, callback) ->
                        slot.replacementCallback = null
                        executor.execute { callback(false, "隔离 runtime 未能确认关闭：${failure.message ?: "未知错误"}") }
                    }
                    notifyObservers(slot) { it.onError(slot.mod.id, "E_RUNTIME_CLOSE", "隔离 runtime 尚未确认关闭；Mod 保持暂停") }
                    return@whenComplete
                }
                slot.runtime = null
                slot.ready = false
                slot.suspended = true
                deliverReplacementReady(slot)
            }
    }

    private fun closeAfterDrain(slot: Slot) {
        if (!slot.closeWhenIdle || slot.teardownStarted || slot.commands.isNotEmpty() || slot.inFlight != null) return
        val runtime = slot.runtime
        if (runtime == null) {
            closeScheduleLease(slot)
            slots.remove(slot.mod.id, slot)
            slot.failed = true
            slot.closeAfterDrainCompletion.complete(null)
            return
        }
        slot.teardownStarted = true
        closeRuntimeAsync(runtime)
            .thenComposeAsync({ CompletableFuture.completedFuture(Unit) }, eventExecutor)
            .whenComplete { _, failure ->
                if (slots[slot.mod.id] !== slot || slot.failed) return@whenComplete
                if (failure != null) {
                    slot.teardownStarted = false
                    notifyObservers(slot) { it.onError(slot.mod.id, "E_RUNTIME_CLOSE", "隔离 runtime 尚未确认关闭；会话仍在收尾") }
                    return@whenComplete
                }
                slot.runtime = null
                closeScheduleLease(slot)
                slots.remove(slot.mod.id, slot)
                slot.failed = true
                val reopen = slot.pendingOpenObservers.toList()
                slot.pendingOpenObservers.clear()
                reopen.forEach { (listener, request) ->
                    if (observerRequests[slot.mod.id]?.get(listener)?.token == request.token) {
                        activateObserver(slot.mod.id, listener, request)
                    }
                }
                slot.closeAfterDrainCompletion.complete(null)
            }
    }

    private fun deliverReplacementReady(slot: Slot) {
        val callback = slot.replacementCallback ?: return
        slot.replacementCallback = null
        callback.first.execute { callback.second(true, null) }
    }

    private fun closeRuntimeAsync(runtime: IsolatedModRuntimeClient): CompletableFuture<Void> {
        val completion = runtime.closeAsync()
        pendingRuntimeCloses += completion
        completion.whenComplete { _, _ -> pendingRuntimeCloses.remove(completion) }
        return completion
    }

    private fun notifyObservers(slot: Slot, callback: (ModBrokerListener) -> Unit) {
        slot.observers.toList().forEach { (listener, executor) -> deliver(slot, executor) { callback(listener) } }
    }

    /** Durable reducers keep every input; only redundant visible render snapshots coalesce. */
    private fun notifyModelCommitted(slot: Slot, eventId: String?, model: ModPersistedModel) {
        val now = SystemClock.elapsedRealtime()
        val waitMs = (MODEL_SNAPSHOT_INTERVAL_MS - (now - slot.lastModelSnapshotElapsedMs)).coerceAtLeast(0L)
        if (waitMs == 0L) {
            slot.lastModelSnapshotElapsedMs = now
            slot.pendingModelSnapshot = null
            slot.modelSnapshotTimer?.close(); slot.modelSnapshotTimer = null
            notifyObservers(slot) { it.onModelCommitted(slot.mod.id, eventId, model) }
            return
        }
        slot.pendingModelSnapshot = eventId to model
        if (slot.modelSnapshotTimer != null) return
        slot.modelSnapshotTimer = viewSnapshotScheduler.schedule(waitMs) {
            execute {
                if (slots[slot.mod.id] !== slot || slot.failed) return@execute
                val pending = slot.pendingModelSnapshot
                slot.pendingModelSnapshot = null
                slot.modelSnapshotTimer = null
                if (pending != null) {
                    slot.lastModelSnapshotElapsedMs = SystemClock.elapsedRealtime()
                    notifyObservers(slot) { it.onModelCommitted(slot.mod.id, pending.first, pending.second) }
                }
            }
        }
    }

    private fun isCurrent(slot: Slot, epoch: Long) = slots[slot.mod.id] === slot && slot.acceptsEpoch(epoch) && !slot.failed
    private fun runtimeObject(raw: String, operation: String): JSONObject? = runCatching { ModPackageReader.parseRuntimeObject(raw, "runtime.$operation") }.getOrNull()
    private fun deliver(slot: Slot, executor: Executor, block: () -> Unit) { runCatching { executor.execute(block) } }
    private fun notify(executor: Executor, listener: ModBrokerListener, id: String, code: String, message: String) { runCatching { executor.execute { listener.onError(id, code, message) } } }
    private fun execute(block: () -> Unit) {
        if (closed.get() != 0) return
        try { eventExecutor.execute(block) } catch (_: RejectedExecutionException) { }
    }

    companion object {
        /** Reserved host-origin event; authors receive it only after an explicit Save. */
        const val HOST_CONFIG_CHANGED_EVENT = "host.configChanged"
        const val HOST_NOTIFICATION_RESULT_EVENT = "host.notificationResult"
        private val HOST_ONLY_EVENTS = setOf(HOST_CONFIG_CHANGED_EVENT, HOST_NOTIFICATION_RESULT_EVENT)
        const val HOST_SCHEDULE_TICK_EVENT = "host.schedule.tick"
        const val HOST_EFFECT_RESULT_EVENT = "host.effectResult"
        const val MODEL_SNAPSHOT_INTERVAL_MS = 100L
    }

    private fun namedFactory(prefix: String) = ThreadFactory { task -> Thread(task, "$prefix-${threadNumber.incrementAndGet()}").apply { isDaemon = true } }

    override fun close() {
        if (closed.getAndSet(1) != 0) return
        eventExecutor.execute {
            val runtimes = slots.values.toList().mapNotNull { slot ->
                slot.failed = true
                closeScheduleLease(slot)
                // A Config whose reducer was rejected is deliberately removed from
                // commands while its old worker is closing. Broker shutdown must
                // still settle that Save exactly once; the close-completion path
                // is suppressed after `closed` flips and can no longer do so.
                completeRecoveredConfigFailure(slot, "E_HOST_CLOSED: Mod broker 已关闭")
                failQueued(slot, "E_HOST_CLOSED", "Mod broker 已关闭")
                slot.runtime?.also { slot.runtime = null }
            }
            slots.clear()
            observerRequests.clear()
            scheduleProvider.close()
            viewSnapshotScheduler.close()
            // Runtime listeners use this executor and can still deliver callbacks
            // while closeAsync is waiting for native destruction/unbind. Drain and
            // stop it only after every client confirms that lifecycle boundary.
            val allCloses = (pendingRuntimeCloses.toList() + runtimes.map(::closeRuntimeAsync)).distinct()
            CompletableFuture.allOf(*allCloses.toTypedArray())
                .whenComplete { _, _ -> runtimeCallbacks.shutdown() }
        }
        eventExecutor.shutdown()
    }

}
