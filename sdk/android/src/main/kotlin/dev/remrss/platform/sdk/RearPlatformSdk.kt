package dev.remrss.platform.sdk

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformCallback
import top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformService
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

/** Stable error codes returned by the SDK's connection and foreground gates. */
object RearSdkErrorCode {
    const val CLIENT_CLOSED = "E_CLIENT_CLOSED"
    const val NOT_CONNECTED = "E_NOT_CONNECTED"
    const val SERVICE_UNAVAILABLE = "E_SERVICE_UNAVAILABLE"
    const val SERVICE_DISCONNECTED = "E_SERVICE_DISCONNECTED"
    const val CONTRACT_INCOMPATIBLE = "E_CONTRACT_INCOMPATIBLE"
    const val BINDING_DIED = "E_BINDING_DIED"
    const val FOREGROUND_REQUIRED = "E_FOREGROUND_REQUIRED"
}

/** SDK package metadata and the host wire contract accepted by this SDK release. */
object RearPlatformSdk {
    @JvmField val SDK_VERSION: String = BuildConfig.SDK_VERSION
    const val HOST_CONTRACT_VERSION = "remrss.platform/1"
}

internal object RearContractNegotiation {
    fun failureCode(contract: String?): String? = when {
        contract == null -> RearSdkErrorCode.SERVICE_UNAVAILABLE
        contract != RearPlatformSdk.HOST_CONTRACT_VERSION -> RearSdkErrorCode.CONTRACT_INCOMPATIBLE
        else -> null
    }
}

/** An app-owned binding. Keep one instance in Application scope while its sessions are active. */
class RearClient private constructor(
    context: Context,
    private val hostPackageName: String,
    private val eventExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
    private val onEvent: (RearPlatformEvent) -> Unit
) : Closeable {
    constructor(
        context: Context,
        eventExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
        onEvent: (RearPlatformEvent) -> Unit
    ) : this(context, HOST_PACKAGE, eventExecutor, onEvent)

    private val appContext = context.applicationContext
    private val callerPackage = context.packageName
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rear-sdk-client").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val resultDispatcher = ClientResultDispatcher(eventExecutor) { closed.get() }
    private val epochIds = AtomicLong(0L)
    private val connectionStateVersion = AtomicLong(0L)
    private val currentEpoch = AtomicReference<ClientEpoch?>(null)
    private val eventDispatcher = ClientEventDispatcher(eventExecutor, { closed.get() }) { epoch -> currentEpoch.get() === epoch }
    private val operationQueue = ClientOperationQueue(
        worker,
        resultDispatcher,
        { closed.get() },
        { epoch -> currentEpoch.get() === epoch },
        { epoch -> releaseEpochAsync(epoch as ClientEpoch) }
    )
    @Volatile var contractVersion: String? = null
        private set
    private val connectionGate = ConnectionHandshakeGate<IBinder>(RearPlatformSdk.HOST_CONTRACT_VERSION)
    @Volatile private var activeBinding: BindingHandle? = null
    private val dataLock = Any()
    private val dataHandlers = ConcurrentHashMap<String, (RearDataSnapshot) -> Unit>()
    private val closedDataIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingData = LinkedHashMap<String, RearDataSnapshot>()
    val isConnected: Boolean get() = currentEpoch.get() != null

    private inner class ClientEpoch(
        val id: Long,
        val bindAttemptId: Long,
        val binder: IBinder,
        val service: IRemoteRearPlatformService
    ) {
        val callback: IRemoteRearPlatformCallback = object : IRemoteRearPlatformCallback.Stub() {
            override fun onEvent(eventJson: String) = handleEvent(this@ClientEpoch, eventJson)
        }
    }

    private class BindingHandle(val attemptId: Long, val connection: ServiceConnection) {
        val unbound = AtomicBoolean(false)
    }

    private fun newServiceConnection(attemptId: Long): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = handleServiceConnected(attemptId, service)
        override fun onServiceDisconnected(name: ComponentName) = handleServiceDisconnected(attemptId, name)
        override fun onBindingDied(name: ComponentName) = invalidateBinding(attemptId, RearSdkErrorCode.BINDING_DIED)
        override fun onNullBinding(name: ComponentName) = invalidateBinding(attemptId, RearSdkErrorCode.SERVICE_UNAVAILABLE)
    }

    private fun handleEvent(epoch: ClientEpoch, eventJson: String) {
        if (closed.get() || currentEpoch.get() !== epoch) return
        val event = runCatching { RearPlatformEvent.fromJson(eventJson) }.getOrElse {
            RearPlatformEvent("error", data = JSONObject().put("code", "E_INVALID_STATE").put("message", it.message))
        }
        eventDispatcher.dispatch(epoch) {
            if (event.type == "data") {
                val id = event.data.optString("subscriptionId")
                val snapshot = runCatching { RearDataSnapshot.fromJson(event.data.toString()) }.getOrNull()
                if (id.isNotEmpty() && snapshot != null && id !in closedDataIds) {
                    val handler = dataHandlers[id]
                    if (handler != null) runCatching { handler(snapshot) }
                    else synchronized(dataLock) {
                        if (!closed.get() && currentEpoch.get() === epoch && id !in closedDataIds) pendingData[id] = snapshot
                    }
                }
            }
            runCatching { onEvent(event) }
        }
    }

    private fun handleServiceConnected(attemptId: Long, binder: IBinder) {
        val candidate: IRemoteRearPlatformService = IRemoteRearPlatformService.Stub.asInterface(binder)
        var replacedEpoch: ClientEpoch? = null
        val acceptedBinding = connectionGate.serviceConnected(attemptId, binder) {
                currentEpoch.get()?.takeIf { it.bindAttemptId == attemptId }?.let { previous ->
                    replacedEpoch = previous
                    currentEpoch.compareAndSet(previous, null)
                    contractVersion = null
                    connectionStateVersion.incrementAndGet()
                }
            }
        if (!acceptedBinding) return
        replacedEpoch?.let(::releaseEpochAsync)
        try {
            worker.execute {
                val result: Result<String> = runCatching { candidate.getContractVersion() }
                val contract: String? = result.getOrNull()
                val transition: ConnectionHandshakeGate.Transition = connectionGate.handshakeCompleted(
                    attemptId, binder, contract,
                    publish = {
                        val epoch = ClientEpoch(epochIds.incrementAndGet(), attemptId, binder, candidate)
                        currentEpoch.set(epoch)
                        contractVersion = contract
                        connectionStateVersion.incrementAndGet()
                    },
                    clear = { currentEpoch.set(null); contractVersion = null }
                ) ?: return@execute
                if (RearContractNegotiation.failureCode(contract) != null && transition.wasBound) {
                    activeBinding?.takeIf { it.attemptId == attemptId }?.let(::unbindService)
                }
                transition.completion?.let { completion ->
                    val expectedEpoch = currentEpoch.get()
                    completeConnectionWaiters(completion) { expectedEpoch != null && currentEpoch.get() === expectedEpoch }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            activeBinding?.takeIf { it.attemptId == attemptId }?.let(::unbindService)
        }
    }

    private fun handleServiceDisconnected(attemptId: Long, name: ComponentName) {
        var retiredEpoch: ClientEpoch? = null
        val transition = connectionGate.disconnected(attemptId) {
            currentEpoch.get()?.takeIf { it.bindAttemptId == attemptId }?.let { current ->
                retiredEpoch = current
                currentEpoch.compareAndSet(current, null)
            }
            contractVersion = null
        }
        retiredEpoch?.let(::releaseEpochAsync)
        transition?.completion?.let(::completeConnectionWaiters)
        if (transition != null) {
            val stateVersion = connectionStateVersion.incrementAndGet()
            eventExecutor.execute {
                if (!closed.get() && connectionStateVersion.get() == stateVersion && currentEpoch.get() == null)
                    runCatching { onEvent(RearPlatformEvent("backendDisconnected")) }
            }
        }
    }

    /** A dead/null binding will not reconnect by itself; release it so a later connect() can retry. */
    private fun invalidateBinding(attemptId: Long, code: String) {
        var retiredEpoch: ClientEpoch? = null
        val transition = connectionGate.bindingFailed(attemptId, code) {
            retiredEpoch = currentEpoch.getAndSet(null)
            contractVersion = null
        } ?: return
        retiredEpoch?.let(::releaseEpochAsync)
        if (transition.wasBound) activeBinding?.takeIf { it.attemptId == attemptId }?.let(::unbindService)
        activeBinding?.takeIf { it.attemptId == attemptId }?.let { activeBinding = null }
        transition.completion?.let(::completeConnectionWaiters)
        val stateVersion = connectionStateVersion.incrementAndGet()
        eventExecutor.execute {
            if (!closed.get() && connectionStateVersion.get() == stateVersion && currentEpoch.get() == null)
                runCatching { onEvent(RearPlatformEvent("backendDisconnected", data = JSONObject().put("code", code))) }
        }
    }

    private fun unbindService(binding: BindingHandle) {
        if (!binding.unbound.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).post {
            try { appContext.unbindService(binding.connection) } catch (_: RuntimeException) { /* Already unbound or service process died. */ }
            if (activeBinding === binding) activeBinding = null
        }
    }

    /** Uses the retired epoch's exact service/callback pair and never blocks the caller. */
    private fun releaseEpochAsync(epoch: ClientEpoch) {
        Thread({ runCatching { epoch.service.releaseClient(callerPackage, epoch.callback) } },
            "rear-sdk-release-${epoch.id}").apply { isDaemon = true }.start()
    }

    private fun completeConnectionWaiters(completion: ConnectionHandshakeGate.Completion, isCurrent: () -> Boolean = { true }) {
        completion.waiters.forEach { waiter -> resultDispatcher.dispatch(waiter, completion.result, isCurrent) }
    }

    fun connect(callback: (Result<Unit>) -> Unit) {
        when (val decision = connectionGate.connect(callback)) {
            ConnectionHandshakeGate.ConnectDecision.AlreadyConnected -> dispatchResult(callback, Result.success(Unit))
            ConnectionHandshakeGate.ConnectDecision.AwaitingBinding -> Unit
            is ConnectionHandshakeGate.ConnectDecision.Rejected -> {
                val code = decision.code
                dispatchResult(callback, Result.failure(IllegalStateException(code)))
            }
            is ConnectionHandshakeGate.ConnectDecision.StartBinding -> {
                val attemptId = decision.attemptId
                val binding = BindingHandle(attemptId, newServiceConnection(attemptId))
                activeBinding = binding
                if (!connectionGate.shouldStartBinding(attemptId)) {
                    if (activeBinding === binding) activeBinding = null
                    return
                }
                val intent = Intent().setComponent(ComponentName(hostPackageName, HOST_SERVICE))
                val didBind = attemptBinding({ appContext.bindService(intent, binding.connection, Context.BIND_AUTO_CREATE) }) {
                    val transition = connectionGate.bindingFailed(attemptId, RearSdkErrorCode.SERVICE_UNAVAILABLE) {
                        currentEpoch.set(null)
                        contractVersion = null
                    }
                    transition?.completion?.let(::completeConnectionWaiters)
                }
                val bindResult = connectionGate.bindingCompleted(attemptId, didBind)
                bindResult.completion?.let(::completeConnectionWaiters)
                if (bindResult.unbind) unbindService(binding)
                if (!didBind && activeBinding === binding) activeBinding = null
            }
        }
    }

    fun queryCapabilities(callback: (Result<RearCapabilities>) -> Unit) = onBinder(callback) { service ->
        RearCapabilities.fromJson(service.service.queryCapabilities(callerPackage))
    }

    /**
     * Read the selected rear panel without requiring the privileged backend. The reported panel
     * size is physical pixels; an opened Scene's measured window is available in its Ready event.
     */
    fun queryRearInfo(callback: (Result<RearInfoSnapshot>) -> Unit) = onBinder(callback) { service ->
        RearInfoSnapshot.fromJson(service.service.queryRearInfo(callerPackage))
    }

    /** Returns a one-shot host picker PendingIntent. Launch it for result to receive the opaque ref. */
    fun requestRegisteredActionSelection(callback: (Result<PendingIntent>) -> Unit) = onBinder(callback) { service ->
        requireActionCapabilitySupported(service)
        service.service.requestRegisteredActionSelection(callerPackage, service.callback)
    }

    /** Returns a host confirmation PendingIntent; each run still requires the user's explicit tap. */
    fun requestRegisteredActionInvocation(ref: RearRegisteredActionRef, callback: (Result<PendingIntent>) -> Unit) = onBinder(callback) { service ->
        requireActionCapabilitySupported(service)
        val capability = registeredActionCapabilityState(service.service.queryCapabilities(callerPackage))
        if (!capability.granted) throw SecurityException("E_CAPABILITY_DENIED: no registered action has been authorized")
        service.service.requestRegisteredActionInvocation(callerPackage, ref.id, ref.version, service.callback)
    }

    private fun requireActionCapabilitySupported(service: ClientEpoch) {
        val capability = registeredActionCapabilityState(service.service.queryCapabilities(callerPackage))
        if (!capability.supported)
            throw UnsupportedOperationException("E_UNSUPPORTED: host does not implement registered actions")
    }

    /** Start this only from a focused Activity; the host asks the user to choose data categories. */
    fun requestDataAuthorization(activity: Activity, fields: Set<RearDataField>, callback: (Result<Unit>) -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
            dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED))); return
        }
        require(fields.isNotEmpty()) { "At least one data field is required" }
        val payload = JSONArray().apply { fields.sortedBy { it.name }.forEach { put(it.name) } }.toString()
        onBinder<PendingIntent>({ result -> result.fold(onSuccess = { pending ->
            if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                callback(Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED)))
            } else try {
                activity.startIntentSenderForResult(pending.intentSender, DATA_CONSENT_REQUEST_CODE, null, 0, 0, 0)
                callback(Result.success(Unit))
            } catch (error: IntentSender.SendIntentException) { callback(Result.failure(error)) }
        }, onFailure = { callback(Result.failure(it)) }) }) { service ->
            service.service.requestDataAuthorization(callerPackage, payload, service.callback)
        }
    }

    /** Opens the host's independent per-publisher grant screen from a focused Activity. */
    fun requestNotificationAuthorization(activity: Activity, callback: (Result<Unit>) -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
            dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED))); return
        }
        onBinder<PendingIntent>({ result -> result.fold(onSuccess = { pending ->
            if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                callback(Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED)))
            } else try {
                activity.startIntentSenderForResult(pending.intentSender, NOTIFICATION_CONSENT_REQUEST_CODE, null, 0, 0, 0)
                callback(Result.success(Unit))
            } catch (error: IntentSender.SendIntentException) { callback(Result.failure(error)) }
        }, onFailure = { callback(Result.failure(it)) }) }) { service ->
            service.service.requestNotificationAuthorization(callerPackage, service.callback)
        }
    }

    /** Publish a new notice or replace this publisher's notice with the same id. */
    fun publishNotification(request: RearNotificationRequest, callback: (Result<RearNotificationSubmitResult>) -> Unit) =
        onBinder(callback) { service ->
            RearNotificationSubmitResult.fromJson(service.service.publishNotification(callerPackage, request.toJson(), service.callback))
        }

    /** Cancel only this SDK connection's notice with the given id. */
    fun cancelNotification(id: String, callback: (Result<RearNotificationSubmitResult>) -> Unit) = onBinder(callback) { service ->
        RearNotificationSubmitResult.fromJson(service.service.cancelNotification(callerPackage, id, service.callback))
    }

    fun queryData(fields: Set<RearDataField>, callback: (Result<RearDataSnapshot>) -> Unit) = onBinder(callback) { service ->
        val payload = JSONArray().apply { fields.sortedBy { it.name }.forEach { put(it.name) } }.toString()
        RearDataSnapshot.fromJson(service.service.queryData(callerPackage, payload, service.callback))
    }

    /** Sampling is host-owned; callbacks arrive through this client's configured event executor. */
    fun observeData(fields: Set<RearDataField>, intervalMs: Long = 5_000L,
                    onSnapshot: (RearDataSnapshot) -> Unit,
                    callback: (Result<Closeable>) -> Unit) {
        val payload = JSONArray().apply { fields.sortedBy { it.name }.forEach { put(it.name) } }.toString()
        onBinder({ result -> result.fold(onSuccess = { id ->
            closedDataIds.remove(id)
            val lease = RearDataSubscription(this, id)
            dataHandlers[id] = onSnapshot
            val queued = synchronized(dataLock) { pendingData.remove(id) }
            queued?.let { runCatching { onSnapshot(it) } }
            callback(Result.success(lease))
        }, onFailure = { callback(Result.failure(it)) }) }) { service ->
            val root = JSONObject(service.service.observeData(callerPackage, payload, intervalMs, service.callback))
            if (!root.optBoolean("success")) throw IllegalStateException(root.optString("error", "E_SERVICE_UNAVAILABLE"))
            root.getString("subscriptionId")
        }
    }

    fun closeDataObservation(subscriptionId: String, callback: (Result<RearOperationResult>) -> Unit = {}) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.service.closeDataObservation(callerPackage, subscriptionId, service.callback))
    }

    internal fun closeDataLease(subscriptionId: String) {
        closedDataIds.add(subscriptionId)
        dataHandlers.remove(subscriptionId)
        synchronized(dataLock) { pendingData.remove(subscriptionId) }
        closeDataObservation(subscriptionId)
    }

    /** Call from a focused foreground Activity after the app's own user action. The SDK never starts the host UI from a service. */
    fun requestSceneActivation(activity: Activity, request: RearSceneRequest, callback: (Result<Unit>) -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
            dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED)))
            return
        }
        onBinder<PendingIntent>({ result ->
            result.fold(
                onSuccess = { pendingIntent ->
                    if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                        callback(Result.failure(IllegalStateException(RearSdkErrorCode.FOREGROUND_REQUIRED)))
                    } else {
                        try {
                            activity.startIntentSenderForResult(pendingIntent.intentSender, ACTIVATION_REQUEST_CODE, null, 0, 0, 0)
                            callback(Result.success(Unit))
                        } catch (error: IntentSender.SendIntentException) {
                            callback(Result.failure(error))
                        }
                    }
                },
                onFailure = { callback(Result.failure(it)) }
            )
        }) { service -> service.service.requestSceneActivation(callerPackage, request.toJson().toString(), service.callback) }
    }

    /** Call from the target Activity after its first real layout, not before setContent/onCreate. */
    fun reportWindowReady(activity: Activity, launchTicket: String, revision: Long = 1L, callback: (Result<RearOperationResult>) -> Unit) {
        val report = runCatching { RearViewportReporter.capture(activity, revision) }.getOrElse {
            dispatchResult(callback, Result.failure(it)); return
        }
        onBinder({ result ->
            val operation = result.getOrNull()
            // A timed-out/failed privileged launch can arrive after its reservation was discarded.
            // Only this ticket-bound Activity may remove its own task; never guess by component/package.
            if (operation?.error == "E_STALE_SESSION" && !activity.isFinishing && !activity.isDestroyed) {
                activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) activity.finishAndRemoveTask() }
            }
            callback(result)
        }) { service -> RearOperationResult.fromJson(
            service.service.reportSceneWindowReady(callerPackage, launchTicket, report.toJson().toString(), service.callback)
        ) }
    }

    fun reportViewport(activity: Activity, sessionId: String, generation: Long, revision: Long, callback: (Result<RearOperationResult>) -> Unit) {
        val report = runCatching { RearViewportReporter.capture(activity, revision) }.getOrElse {
            dispatchResult(callback, Result.failure(it)); return
        }
        reportViewport(sessionId, generation, report, callback)
    }

    /** Submit an already captured window report so its revision and measured geometry stay one observation. */
    fun reportViewport(sessionId: String, generation: Long, report: RearWindowReport, callback: (Result<RearOperationResult>) -> Unit) {
        require(report.revision > 0) { "revision must be positive" }
        onBinder(callback) { service -> RearOperationResult.fromJson(
            service.service.reportSceneViewport(callerPackage, sessionId, generation, report.toJson().toString(), service.callback)
        ) }
    }

    fun attachObserver(sessionId: String, generation: Long, callback: (Result<RearOperationResult>) -> Unit) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.service.attachSceneObserver(callerPackage, sessionId, generation, service.callback))
    }

    fun reportVisibility(sessionId: String, generation: Long, visible: Boolean, callback: (Result<RearOperationResult>) -> Unit) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.service.reportSceneVisibility(callerPackage, sessionId, generation, visible, service.callback))
    }

    /** Reconcile delayed observer callbacks against the Core's current owner/session snapshot. */
    fun queryScenePresentation(
        sessionId: String,
        generation: Long,
        callback: (Result<RearScenePresentation>) -> Unit
    ) = onBinder(callback) { service ->
        RearScenePresentation.fromJson(service.service.queryScenePresentation(callerPackage, sessionId, generation, service.callback))
    }

    fun closeScene(sessionId: String, callback: (Result<RearOperationResult>) -> Unit = {}) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.service.closeScene(callerPackage, sessionId, service.callback))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var epochAtClose: ClientEpoch? = null
        val transition = connectionGate.close {
            epochAtClose = currentEpoch.getAndSet(null)
            contractVersion = null
        }
        val epoch = epochAtClose
        transition.completion?.let(::completeConnectionWaiters)
        dataHandlers.clear()
        closedDataIds.clear()
        synchronized(dataLock) { pendingData.clear() }
        val binding = transition.attemptId?.let { id -> activeBinding?.takeIf { it.attemptId == id } }
        ClientCloseSequence(
            operations = operationQueue,
            unbind = { if (transition.wasBound && binding != null) unbindService(binding) },
            scheduleHostRelease = { epoch?.let(::releaseEpochAsync) },
            shutdownWorker = { worker.shutdown() }
        ).close()
    }

    private fun <T> onBinder(callback: (Result<T>) -> Unit, action: (ClientEpoch) -> T) {
        if (closed.get()) { dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.CLIENT_CLOSED))); return }
        val epoch = currentEpoch.get()
        if (epoch == null) {
            dispatchResult(callback, Result.failure(IllegalStateException(RearSdkErrorCode.NOT_CONNECTED)))
            return
        }
        operationQueue.submit(epoch, { action(epoch) }, callback)
    }

    private fun <T> dispatchResult(callback: (Result<T>) -> Unit, result: Result<T>) {
        resultDispatcher.dispatch(callback, result)
    }

    companion object {
        const val HOST_PACKAGE = "top.zainzh.rearscreen"
        const val HOST_SERVICE = "top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService"
        const val EXTRA_LAUNCH_TICKET = "remrss_scene_ticket"
        const val ACTIVATION_REQUEST_CODE = 0x524D
        const val DATA_CONSENT_REQUEST_CODE = 0x524E
        const val NOTIFICATION_CONSENT_REQUEST_CODE = 0x524F

        /** For local host-debug variants; production callers should use the default constructor. */
        @JvmStatic fun forHostPackage(
            context: Context,
            hostPackageName: String,
            eventExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
            onEvent: (RearPlatformEvent) -> Unit
        ) = RearClient(context, hostPackageName, eventExecutor, onEvent)
    }
}

enum class RearNotificationPriority { LOW, NORMAL, HIGH }
enum class RearNotificationPrivacy { STANDARD, HIDE_TITLE, HIDE_TEXT }
enum class RearNotificationSubmitStatus { ACCEPTED, SUPPRESSED, REJECTED }
enum class RearNotificationEventStatus { DISPLAYED, SUPPRESSED, ENDED, FAILED }
enum class RearNotificationEndReason {
    TIMEOUT, REPLACED, CANCELLED, OWNER_CLOSED, OWNER_DIED, AUTH_REVOKED, GLOBAL_DISABLED,
    PRIVACY_POLICY, DND, COVERED, DISPLAY_UNAVAILABLE, SCENE_TAKEOVER, SERVICE_STOPPED, RENDER_FAILED, HOST_ERROR
}

data class RearNotificationRequest(
    val id: String,
    val title: String,
    val text: String = "",
    val durationMs: Long = 8_000L,
    val priority: RearNotificationPriority = RearNotificationPriority.NORMAL,
    val privacy: RearNotificationPrivacy = RearNotificationPrivacy.STANDARD,
) {
    internal fun toJson(): String = JSONObject().put("id", id).put("title", title).put("text", text)
        .put("durationMs", durationMs).put("priority", priority.name).put("privacy", privacy.name).toString()
}

data class RearNotificationSubmitResult(
    val status: RearNotificationSubmitStatus,
    val id: String,
    val requestId: String?,
    val reason: String?,
) {
    companion object {
        fun fromJson(json: String): RearNotificationSubmitResult {
            val value = JSONObject(json)
            return RearNotificationSubmitResult(
                RearNotificationSubmitStatus.valueOf(value.getString("status")), value.getString("id"),
                value.optString("requestId").takeUnless { it.isBlank() || it == "null" },
                value.optString("reason").takeUnless { it.isBlank() || it == "null" }
            )
        }
    }
}

data class RearNotificationEvent(
    val requestId: String,
    val id: String,
    val status: RearNotificationEventStatus,
    val reason: String?,
    val endedBy: RearNotificationEndReason?,
) {
    companion object {
        internal fun fromJson(value: JSONObject): RearNotificationEvent = RearNotificationEvent(
            value.getString("requestId"), value.getString("id"), RearNotificationEventStatus.valueOf(value.getString("status")),
            value.optString("reason").takeUnless { it.isBlank() || it == "null" },
            value.optString("endedBy").takeUnless { it.isBlank() || it == "null" }?.let(RearNotificationEndReason::valueOf)
        )
    }
}

/** Opaque identity returned by the host picker. It contains no Intent or executable target data. */
data class RearRegisteredActionRef(val id: String, val version: Int = 1) {
    companion object {
        /** Read only a successful RegisteredActionsActivity result. */
        @JvmStatic fun fromActivityResult(data: Intent?): RearRegisteredActionRef? {
            val id = data?.getStringExtra("registered_actions_selected_ref_id") ?: return null
            val version = data.getIntExtra("registered_actions_selected_ref_version", 0)
            return RearRegisteredActionRef(id, version).takeIf { id.matches(Regex("[a-f0-9]{48}") ) && version == 1 }
        }
    }
}

class RearDataSubscription internal constructor(private val client: RearClient, private val id: String) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) client.closeDataLease(id) }
}

data class RearSceneRequest(
    val component: ComponentName,
    val region: RearRegion = RearRegion.CAMERA_SAFE,
    val keepVisible: Boolean = true,
    val requiredCapabilities: Set<CapabilityRequirement> = emptySet()
) {
    internal fun toJson() = JSONObject().put("component", component.flattenToString()).put("region", region.name)
        .put("keepVisible", keepVisible).put("requiredCapabilities", JSONArray().apply {
            requiredCapabilities.forEach { put(JSONObject().put("name", it.name).put("minimumVersion", it.minimumVersion)) }
        })
}

enum class RearRegion { CAMERA_SAFE, FULL }

/** Applies the window-level half of a requested Scene keep-visible lease. */
object RearSceneWindowPolicy {
    /** Call from the Scene Activity's visible lifecycle; clear it when the Activity is hidden. */
    @JvmStatic fun applyKeepVisible(activity: Activity, enabled: Boolean) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Focus/resumed state is not ownership; a live request follows Core visibility and the real window. */
    @JvmStatic fun shouldKeepScreenOn(
        requested: Boolean,
        leaseActive: Boolean,
        windowActuallyVisible: Boolean,
        sceneVisible: Boolean = true
    ): Boolean = requested && leaseActive && windowActuallyVisible && sceneVisible

    @JvmStatic fun applySceneLease(
        activity: Activity,
        requested: Boolean,
        leaseActive: Boolean,
        windowActuallyVisible: Boolean,
        sceneVisible: Boolean = true
    ) {
        applyKeepVisible(activity, shouldKeepScreenOn(requested, leaseActive, windowActuallyVisible, sceneVisible))
    }
}
data class CapabilityRequirement(val name: String, val minimumVersion: Int)
data class RearCapability(val name: String, val version: Int, val availability: String, val granted: Boolean, val reasonCode: String?)
data class RearCapabilities(val contract: String, val deviceAvailable: Boolean, val backendConnected: Boolean, val capabilities: List<RearCapability>) {
    companion object {
        fun fromJson(json: String): RearCapabilities {
            val root = JSONObject(json)
            val caps = root.getJSONArray("capabilities")
            return RearCapabilities(root.getString("contract"), root.getBoolean("deviceAvailable"), root.getBoolean("backendConnected"),
                (0 until caps.length()).map { i -> caps.getJSONObject(i).let { RearCapability(it.getString("name"), it.getInt("version"),
                    it.getString("availability"), it.getBoolean("granted"), it.optString("reasonCode").takeUnless(String::isEmpty)) } })
        }
    }
}

data class RearPanelInfo(
    val displayId: Int,
    /** Physical raster of the current Display.Mode, in that mode's native orientation; null if unavailable. */
    val physicalModeWidthPx: Int?,
    val physicalModeHeightPx: Int?,
    /** Current logical display size in px; can differ from physical mode under size overrides. */
    val logicalWidthPx: Int,
    val logicalHeightPx: Int,
    /** Current logical display density in DPI, not the final Scene window dp size. */
    val densityDpi: Int,
    /** Current Display.rotation quarter-turns (0..3), independent of the mode's native orientation. */
    val rotation: Int,
    /** ON, OFF, DOZE, DOZE_SUSPEND, VR, or UNKNOWN. */
    val state: String
)

data class RearInfoSnapshot(
    val contract: String,
    val deviceAvailable: Boolean,
    val backendConnected: Boolean,
    val panelInfoAvailable: Boolean,
    val panel: RearPanelInfo?,
    val capabilities: List<RearCapability>
) {
    companion object {
        fun fromJson(json: String): RearInfoSnapshot {
            val root = JSONObject(json)
            val panel = root.optJSONObject("display")?.let { display ->
                val dimensions = display.optJSONObject("dimensions")
                val modeSize = dimensions?.optJSONObject("physicalModePx")
                val logicalSize = dimensions?.optJSONObject("logicalDisplayPx")
                if (dimensions == null || logicalSize == null) null else RearPanelInfo(
                    display.getInt("displayId"),
                    modeSize?.getInt("width"), modeSize?.getInt("height"),
                    logicalSize.getInt("width"), logicalSize.getInt("height"),
                    dimensions.getInt("densityDpi"), dimensions.getInt("rotation"), display.getString("state")
                )
            }
            val caps = root.getJSONArray("capabilities")
            val capabilities = (0 until caps.length()).map { index -> caps.getJSONObject(index).let {
                RearCapability(it.getString("name"), it.getInt("version"), it.getString("availability"),
                    it.getBoolean("granted"), it.optString("reasonCode").takeUnless(String::isEmpty))
            } }
            val infoAvailable = root.getBoolean("displayInfoAvailable")
            require(infoAvailable == (panel != null)) { "E_INVALID_STATE: panel info" }
            return RearInfoSnapshot(root.getString("contract"), root.getBoolean("deviceAvailable"),
                root.getBoolean("backendConnected"), infoAvailable, panel, capabilities)
        }
    }
}

data class RearOperationResult(val success: Boolean, val error: String?, val retryable: Boolean) {
    companion object { fun fromJson(json: String) = JSONObject(json).let { RearOperationResult(it.optBoolean("success"), it.optString("error").takeUnless(String::isEmpty), it.optBoolean("retryable")) } }
}

enum class RearDataField { BATTERY_LEVEL_PERCENT, BATTERY_CHARGING, SYSTEM_CPU_UTILIZATION_PERCENT, SYSTEM_MEMORY_AVAILABLE_BYTES, SYSTEM_MEMORY_TOTAL_BYTES, SYSTEM_GPU_UTILIZATION_PERCENT }
sealed interface RearDataValue {
    val sampledAtElapsedRealtimeMs: Long
    data class Number(val value: Double, val unit: String, override val sampledAtElapsedRealtimeMs: Long) : RearDataValue
    data class Flag(val value: Boolean, override val sampledAtElapsedRealtimeMs: Long) : RearDataValue
    data class Unavailable(val reason: String, override val sampledAtElapsedRealtimeMs: Long) : RearDataValue
}

data class RearDataSnapshot(val fields: Map<RearDataField, RearDataValue>, val sampledAtElapsedRealtimeMs: Long) {
    companion object {
        fun fromJson(json: String): RearDataSnapshot {
            val root = JSONObject(json)
            if (!root.optBoolean("success", true)) throw IllegalStateException(root.optString("error", "E_SERVICE_UNAVAILABLE"))
            val fields = root.optJSONObject("fields") ?: JSONObject()
            val values = buildMap {
                fields.keys().forEach { key ->
                    val field = RearDataField.valueOf(key)
                    val value = fields.getJSONObject(key)
                    val sampleTime = value.getLong("sampledAtElapsedRealtimeMs")
                    put(field, when (value.getString("type")) {
                        "number" -> RearDataValue.Number(value.getDouble("value"), value.getString("unit"), sampleTime)
                        "flag" -> RearDataValue.Flag(value.getBoolean("value"), sampleTime)
                        "unavailable" -> RearDataValue.Unavailable(value.getString("reason"), sampleTime)
                        else -> throw IllegalArgumentException("E_INVALID_STATE: data value")
                    })
                }
            }
            return RearDataSnapshot(values, root.getLong("sampledAtElapsedRealtimeMs"))
        }
    }
}

data class RearPlatformEvent(
    val type: String,
    val sessionId: String? = null,
    val generation: Long? = null,
    val data: JSONObject = JSONObject(),
    val notificationEvent: RearNotificationEvent? = null,
) {
    companion object {
        fun fromJson(json: String): RearPlatformEvent {
            val root = JSONObject(json)
            val type = root.getString("type")
            return RearPlatformEvent(type, root.optString("sessionId").takeUnless(String::isEmpty),
                root.optLong("generation").takeIf { root.has("generation") }, root,
                if (type == "notificationEvent") RearNotificationEvent.fromJson(root) else null)
        }
    }
}

/** Current Core-authoritative content permission for one exact logical Scene generation. */
data class RearScenePresentation(
    val sessionId: String,
    val generation: Long,
    val revision: Long,
    val visible: Boolean,
    val hiddenReason: String?
) {
    companion object {
        fun fromJson(json: String): RearScenePresentation {
            val root = JSONObject(json)
            if (!root.optBoolean("success", false)) throw IllegalStateException(root.optString("error", "E_SERVICE_UNAVAILABLE"))
            return RearScenePresentation(
                root.getString("sessionId"), root.getLong("generation"), root.getLong("revision"),
                root.getBoolean("visible"), if (root.isNull("hiddenReason")) null else root.getString("hiddenReason")
            )
        }
    }
}

/** Drops delayed presentation snapshots from another logical lease or an older Core revision. */
class RearScenePresentationGate(val sessionId: String, val generation: Long) {
    private var appliedRevision = 0L
    private var currentVisible = false

    @Synchronized fun apply(snapshot: RearScenePresentation): Boolean {
        if (snapshot.sessionId != sessionId || snapshot.generation != generation || snapshot.revision < appliedRevision) return false
        // onStart may clear a window-local presentation while Core's current state has not changed.
        // Reapplying that exact same snapshot is idempotent; equal-revision conflicting state is not.
        if (snapshot.revision == appliedRevision) return snapshot.visible == currentVisible
        appliedRevision = snapshot.revision
        currentVisible = snapshot.visible
        return true
    }

    @Synchronized fun visible(): Boolean = currentVisible
    @Synchronized fun revision(): Long = appliedRevision
}

/** Invalidates Binder snapshot replies captured by an Activity window before stop/restart. */
class RearScenePresentationRequestEpoch {
    private val value = java.util.concurrent.atomic.AtomicLong(0L)
    fun begin(): Long = value.incrementAndGet()
    fun invalidate(): Long = value.incrementAndGet()
    fun isCurrent(request: Long): Boolean = value.get() == request
}

data class RearWindowReport(
    val component: ComponentName,
    val taskId: Int,
    val revision: Long,
    val displayId: Int,
    val physicalDisplayPx: Pair<Int, Int>?,
    val contentBoundsPx: IntArray,
    val windowPx: Pair<Int, Int>,
    val windowDp: Pair<Float, Float>,
    val densityDpi: Int,
    val renderBufferPx: Pair<Int, Int>?,
    val displayRotation: Int,
    val contentRotation: Int,
    val inputTransform: FloatArray
) {
    internal fun toJson() = JSONObject().put("component", component.flattenToString()).put("taskId", taskId).put("viewport", JSONObject().apply {
        put("revision", revision).put("displayId", displayId)
        fun size(value: Pair<Int, Int>?) = value?.let { JSONObject().put("width", it.first).put("height", it.second) }
        put("physicalDisplayPx", size(physicalDisplayPx))
        put("contentBoundsPx", JSONObject().put("left", contentBoundsPx[0]).put("top", contentBoundsPx[1])
            .put("right", contentBoundsPx[2]).put("bottom", contentBoundsPx[3]))
        put("windowPx", size(windowPx)).put("windowDp", JSONObject().put("width", windowDp.first).put("height", windowDp.second))
        put("densityDpi", densityDpi).put("renderBufferPx", size(renderBufferPx))
        put("displayRotation", displayRotation).put("contentRotation", contentRotation)
        put("inputTransform", JSONObject().put("scaleX", inputTransform[0]).put("skewX", inputTransform[1]).put("translateX", inputTransform[2])
            .put("skewY", inputTransform[3]).put("scaleY", inputTransform[4]).put("translateY", inputTransform[5]))
    })
}

object RearViewportReporter {
    /** Uses the actual target window and display at first layout; no guessed display ID or copied physical DPI. */
    fun capture(activity: Activity, revision: Long): RearWindowReport {
        require(revision > 0) { "revision must be positive" }
        val decor = activity.window.decorView
        val width = decor.width
        val height = decor.height
        require(width > 0 && height > 0) { "E_WINDOW_NOT_READY" }
        @Suppress("DEPRECATION") val display = if (android.os.Build.VERSION.SDK_INT >= 30) activity.display ?: activity.windowManager.defaultDisplay
        else activity.windowManager.defaultDisplay
        val metrics = activity.resources.displayMetrics
        val densityDpi = metrics.densityDpi
        val windowXy = IntArray(2).also(decor::getLocationOnScreen)
        val safeInsets = cameraSafeInsets(activity)
        val safeLeft = safeInsets[0]
        val safeTop = safeInsets[1]
        val safeRight = safeInsets[2]
        val safeBottom = safeInsets[3]
        val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) decor.rootWindowInsets?.displayCutout else null
        if (cutout == null) android.util.Log.w("RearViewportReporter", "ROM did not report a verifiable display-cutout safe area")
        val modeSize = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 23) display.mode.let { it.physicalWidth to it.physicalHeight }
            else null
        }.getOrNull()
        val contentBounds = SceneContentGeometry.safeBounds(
            windowXy[0], windowXy[1], width, height, safeLeft, safeTop, safeRight, safeBottom
        )
        return RearWindowReport(
            component = ComponentName(activity, activity.javaClass), taskId = activity.taskId,
            revision = revision, displayId = display.displayId, physicalDisplayPx = modeSize,
            contentBoundsPx = contentBounds, windowPx = width to height,
            windowDp = (width * 160f / densityDpi) to (height * 160f / densityDpi),
            densityDpi = densityDpi, renderBufferPx = null,
            displayRotation = display.rotation, contentRotation = display.rotation,
            inputTransform = SceneContentGeometry.inputTransform(safeLeft, safeTop)
        )
    }

    /** Safe insets in the actual Activity window's current display orientation, in physical pixels. */
    fun cameraSafeInsets(activity: Activity): IntArray {
        val decor = activity.window.decorView
        val width = decor.width.coerceAtLeast(1)
        val height = decor.height.coerceAtLeast(1)
        val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) decor.rootWindowInsets?.displayCutout else null
        val left = cutout?.safeInsetLeft?.coerceIn(0, width) ?: 0
        val top = cutout?.safeInsetTop?.coerceIn(0, height) ?: 0
        val right = cutout?.safeInsetRight?.coerceIn(0, width - left) ?: 0
        val bottom = cutout?.safeInsetBottom?.coerceIn(0, height - top) ?: 0
        return intArrayOf(left, top, right, bottom)
    }
}

/** Window-local touch coordinates are already in the current display rotation; only crop safe insets. */
internal object SceneContentGeometry {
    fun safeBounds(x: Int, y: Int, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): IntArray {
        require(width > 0 && height > 0)
        val safeLeft = left.coerceIn(0, width)
        val safeTop = top.coerceIn(0, height)
        val safeRight = right.coerceIn(0, width - safeLeft)
        val safeBottom = bottom.coerceIn(0, height - safeTop)
        return intArrayOf(x + safeLeft, y + safeTop, x + width - safeRight, y + height - safeBottom)
    }

    fun inputTransform(safeLeft: Int, safeTop: Int) = floatArrayOf(
        1f, 0f, -safeLeft.toFloat(),
        0f, 1f, -safeTop.toFloat()
    )
}
