package top.zainzh.rearscreen.platform.mods

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.platform.runtime.IsolatedModRuntimeClient
import java.util.ArrayDeque
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

/** Only effects with a real host implementation are admitted. Dispatch happens after model commit. */
data class ModRearOpenEffect(
    val scene: String,
    val region: String,
    val keepVisible: Boolean,
    /** Opening a rear scene needs an activation from a current user gesture. */
    val requiresUserActivation: Boolean = true,
)

fun interface ModEffectDispatcher {
    fun requestRearScene(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect)
}

interface ModBrokerListener {
    fun onReady(modId: String, model: ModPersistedModel)
    fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel)
    fun onError(modId: String, code: String, message: String)
    fun onEffectRequested(modId: String, eventId: String)
}

sealed interface ModBrokerCommand {
    data class Event(val eventId: String, val type: String, val payload: JSONObject) : ModBrokerCommand
    data class Config(val eventId: String, val config: JSONObject, val callbackExecutor: Executor, val complete: (Boolean, String?) -> Unit) : ModBrokerCommand
}

private data class ModObserverOpenRequest(val token: Long, val executor: Executor)

/**
 * One process-level broker owns one serial queue per Mod. Runtime epochs are disposable;
 * author+Mod+logicalSessionId and the SQLite model are the durable identity.
 */
class ModEventBroker(
    context: Context,
    private val effectDispatcher: ModEffectDispatcher,
    private val eventCapacity: Int = 32,
) : AutoCloseable {
    private val appContext = context.applicationContext
    /** Non-null only in instrumentation. Production callers always use the credential-protected store. */
    internal var testStoreStorage: ModPackageStoreTestSession.Storage? = null
    private val epochs = AtomicLong(SystemClock.elapsedRealtimeNanos())
    private val threadNumber = AtomicInteger()
    private val eventExecutor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256), namedFactory("remrss-mod-host"), ThreadPoolExecutor.AbortPolicy())
    private val runtimeCallbacks = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64), namedFactory("remrss-mod-runtime-callback"), ThreadPoolExecutor.AbortPolicy())
    private val slots = ConcurrentHashMap<String, Slot>()
    private val pendingRuntimeCloses = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()
    private val observerRequestIds = AtomicLong()
    private val observerRequests = ConcurrentHashMap<String, ConcurrentHashMap<ModBrokerListener, ModObserverOpenRequest>>()
    private val replacementGate = Any()
    private val replacementReservations = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicInteger(0)

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
    ) {
        private val runtimeEpoch = ModRuntimeEpochGate(initialEpoch)
        val epoch: Long get() = runtimeEpoch.current
        fun advanceEpoch(value: Long) = runtimeEpoch.advance(value)
        fun acceptsEpoch(value: Long) = runtimeEpoch.accepts(value)
    }

    /** Activate a package for a visible host scene. `init` runs only if the durable state is absent. */
    fun open(modId: String, listenerExecutor: Executor, listener: ModBrokerListener) {
        synchronized(replacementGate) {
          if (modId in replacementReservations) {
              notify(listenerExecutor, listener, modId, "E_PACKAGE_UPDATING", "Mod 正在更新")
              return
          }
          if (closed.get() != 0) return
          val request = ModObserverOpenRequest(observerRequestIds.incrementAndGet(), listenerExecutor)
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
            slots[modId] = slot
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
        if (type.isBlank() || type == HOST_CONFIG_CHANGED_EVENT || payload.toString().toByteArray(Charsets.UTF_8).size > 16 * 1024) return false
        return enqueue(slot, ModBrokerCommand.Event(UUID.randomUUID().toString(), type, JSONObject(payload.toString())))
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
                slot.runtime?.init(JSONObject().put("config", model.config).put("env", JSONObject()).put("dataVersion", model.dataVersion).toString())
            } else {
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
            notifyObservers(slot) { it.onModelCommitted(slot.mod.id, eventId, model) }
            parsedEffects.forEach { effect ->
                notifyObservers(slot) { it.onEffectRequested(slot.mod.id, eventId) }
                runCatching { effectDispatcher.requestRearScene(slot.mod, eventId, effect) }
                    .onFailure { error -> notifyObservers(slot) { it.onError(slot.mod.id, "E_EFFECT_DISPATCH", error.message ?: "Effect 执行失败") } }
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

    private fun parseEffect(slot: Slot, value: JSONObject): ModRearOpenEffect {
        require(value.optString("type") == "rear.open") { "当前只支持 rear.open Effect" }
        require(value.keys().asSequence().toSet() == setOf("type", "scene", "region", "keepVisible")) { "rear.open 字段无效" }
        require(value.getString("scene") == "rear") { "Mod 只能打开自身声明的 rear scene" }
        require(value.getString("region") in setOf("CAMERA_SAFE", "FULL")) { "rear.open region 无效" }
        require(value.get("keepVisible") is Boolean) { "rear.open keepVisible 必须为 boolean" }
        val needed = mutableSetOf("rear.scene")
        if (value.getString("region") == "FULL") needed += "rear.region"
        if (value.getBoolean("keepVisible")) needed += "rear.scene.keep_visible"
        require(needed.all { it in slot.mod.grantedCapabilities }) { "rear.open 缺少能力授权：${(needed-slot.mod.grantedCapabilities).joinToString()}" }
        return ModRearOpenEffect("rear", value.getString("region"), value.getBoolean("keepVisible"))
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
                                .put("env", JSONObject()).put("revision", before.revision)
                            slot.inFlight = "reduce:${command.eventId}"
                            slot.runtime?.reduce(context.toString()) ?: return failSlot(slot, "E_WORKER_DIED", "isolated worker is not connected")
                            return
                        }
                        // Declarative/no-script Mods never bind or start a VM.
                        val model = ModPackageStore.commitModel(appContext, slot.mod.id, before.revision, before.state, command.config, testStoreStorage)
                        slot.model = model; slot.commands.removeFirst(); slot.queued.decrementAndGet()
                        deliver(slot, command.callbackExecutor) { command.complete(true, null) }
                        notifyObservers(slot) { it.onModelCommitted(slot.mod.id, command.eventId, model) }
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
                        .put("env", JSONObject()).put("revision", current.revision)
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
    }

    private fun namedFactory(prefix: String) = ThreadFactory { task -> Thread(task, "$prefix-${threadNumber.incrementAndGet()}").apply { isDaemon = true } }

    override fun close() {
        if (closed.getAndSet(1) != 0) return
        eventExecutor.execute {
            val runtimes = slots.values.toList().mapNotNull { slot ->
                slot.failed = true
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
