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
import java.util.concurrent.ConcurrentHashMap

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
    @Volatile private var remote: IRemoteRearPlatformService? = null
    private val connectionLock = Any()
    private val connectionWaiters = ArrayList<(Result<Unit>) -> Unit>()
    private var bound = false
    private val dataLock = Any()
    private val dataHandlers = ConcurrentHashMap<String, (RearDataSnapshot) -> Unit>()
    private val closedDataIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingData = LinkedHashMap<String, RearDataSnapshot>()
    val isConnected: Boolean get() = remote != null
    private val callback = object : IRemoteRearPlatformCallback.Stub() {
        override fun onEvent(eventJson: String) {
            val event = runCatching { RearPlatformEvent.fromJson(eventJson) }.getOrElse {
                RearPlatformEvent("error", data = JSONObject().put("code", "E_INVALID_STATE").put("message", it.message))
            }
            eventExecutor.execute {
                if (closed.get()) return@execute
                if (event.type == "data") {
                    val id = event.data.optString("subscriptionId")
                    val snapshot = runCatching { RearDataSnapshot.fromJson(event.data.toString()) }.getOrNull()
                    if (id.isNotEmpty() && snapshot != null && id !in closedDataIds) {
                        val handler = dataHandlers[id]
                        if (handler != null) handler(snapshot) else synchronized(dataLock) { pendingData[id] = snapshot }
                    }
                }
                onEvent(event)
            }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = IRemoteRearPlatformService.Stub.asInterface(service)
            val waiters = synchronized(connectionLock) { connectionWaiters.toList().also { connectionWaiters.clear() } }
            waiters.forEach { it(Result.success(Unit)) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            eventExecutor.execute { if (!closed.get()) onEvent(RearPlatformEvent("backendDisconnected")) }
        }
        override fun onBindingDied(name: ComponentName) = invalidateBinding("E_BINDING_DIED")
        override fun onNullBinding(name: ComponentName) {
            invalidateBinding("E_SERVICE_UNAVAILABLE")
        }
    }

    /** A dead/null binding will not reconnect by itself; release it so a later connect() can retry. */
    private fun invalidateBinding(code: String) {
        val (wasBound, waiters) = synchronized(connectionLock) {
            remote = null
            val wasBound = bound
            bound = false
            wasBound to connectionWaiters.toList().also { connectionWaiters.clear() }
        }
        if (wasBound) runCatching { appContext.unbindService(connection) }
        waiters.forEach { it(Result.failure(IllegalStateException(code))) }
        eventExecutor.execute { if (!closed.get()) onEvent(RearPlatformEvent("backendDisconnected", data = JSONObject().put("code", code))) }
    }

    fun connect(callback: (Result<Unit>) -> Unit) {
        check(!closed.get()) { "RearClient is closed" }
        remote?.let { callback(Result.success(Unit)); return }
        synchronized(connectionLock) {
            remote?.let { callback(Result.success(Unit)); return }
            connectionWaiters += callback
            if (bound) return
            val intent = Intent().setComponent(ComponentName(hostPackageName, HOST_SERVICE))
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                val waiters = connectionWaiters.toList()
                connectionWaiters.clear()
                waiters.forEach { it(Result.failure(IllegalStateException("E_SERVICE_UNAVAILABLE"))) }
            }
        }
    }

    fun queryCapabilities(callback: (Result<RearCapabilities>) -> Unit) = onBinder(callback) { service ->
        RearCapabilities.fromJson(service.queryCapabilities(callerPackage))
    }

    /**
     * Read the selected rear panel without requiring the privileged backend. The reported panel
     * size is physical pixels; an opened Scene's measured window is available in its Ready event.
     */
    fun queryRearInfo(callback: (Result<RearInfoSnapshot>) -> Unit) = onBinder(callback) { service ->
        RearInfoSnapshot.fromJson(service.queryRearInfo(callerPackage))
    }

    /** Start this only from a focused Activity; the host asks the user to choose data categories. */
    fun requestDataAuthorization(activity: Activity, fields: Set<RearDataField>, callback: (Result<Unit>) -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
            callback(Result.failure(IllegalStateException("E_FOREGROUND_REQUIRED"))); return
        }
        require(fields.isNotEmpty()) { "At least one data field is required" }
        val payload = JSONArray().apply { fields.sortedBy { it.name }.forEach { put(it.name) } }.toString()
        onBinder<PendingIntent>({ result -> result.fold(onSuccess = { pending ->
            if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                callback(Result.failure(IllegalStateException("E_FOREGROUND_REQUIRED")))
            } else try {
                activity.startIntentSenderForResult(pending.intentSender, DATA_CONSENT_REQUEST_CODE, null, 0, 0, 0)
                callback(Result.success(Unit))
            } catch (error: IntentSender.SendIntentException) { callback(Result.failure(error)) }
        }, onFailure = { callback(Result.failure(it)) }) }) { service ->
            service.requestDataAuthorization(callerPackage, payload, this.callback)
        }
    }

    fun queryData(fields: Set<RearDataField>, callback: (Result<RearDataSnapshot>) -> Unit) = onBinder(callback) { service ->
        val payload = JSONArray().apply { fields.sortedBy { it.name }.forEach { put(it.name) } }.toString()
        RearDataSnapshot.fromJson(service.queryData(callerPackage, payload, this.callback))
    }

    /** Observations are worker-thread samples delivered through the client's event callback. */
    fun observeData(fields: Set<RearDataField>, intervalMs: Long = 5_000L,
                    onSnapshot: (RearDataSnapshot) -> Unit,
                    callback: (Result<Closeable>) -> Unit) {
        val payload = JSONArray().apply { fields.sortedBy { it.name }.forEach { put(it.name) } }.toString()
        onBinder({ result -> result.fold(onSuccess = { id ->
            closedDataIds.remove(id)
            val lease = RearDataSubscription(this, id)
            dataHandlers[id] = onSnapshot
            val queued = synchronized(dataLock) { pendingData.remove(id) }
            queued?.let(onSnapshot)
            callback(Result.success(lease))
        }, onFailure = { callback(Result.failure(it)) }) }) { service ->
            val root = JSONObject(service.observeData(callerPackage, payload, intervalMs, this.callback))
            if (!root.optBoolean("success")) throw IllegalStateException(root.optString("error", "E_SERVICE_UNAVAILABLE"))
            root.getString("subscriptionId")
        }
    }

    fun closeDataObservation(subscriptionId: String, callback: (Result<RearOperationResult>) -> Unit = {}) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.closeDataObservation(callerPackage, subscriptionId, this.callback))
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
            callback(Result.failure(IllegalStateException("E_FOREGROUND_REQUIRED")))
            return
        }
        onBinder<PendingIntent>({ result ->
            result.fold(
                onSuccess = { pendingIntent ->
                    if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                        callback(Result.failure(IllegalStateException("E_FOREGROUND_REQUIRED")))
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
        }) { service -> service.requestSceneActivation(callerPackage, request.toJson().toString(), this.callback) }
    }

    /** Call from the target Activity after its first real layout, not before setContent/onCreate. */
    fun reportWindowReady(activity: Activity, launchTicket: String, revision: Long = 1L, callback: (Result<RearOperationResult>) -> Unit) {
        val report = runCatching { RearViewportReporter.capture(activity, revision) }.getOrElse {
            callback(Result.failure(it)); return
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
            service.reportSceneWindowReady(callerPackage, launchTicket, report.toJson().toString(), this.callback)
        ) }
    }

    fun reportViewport(activity: Activity, sessionId: String, generation: Long, revision: Long, callback: (Result<RearOperationResult>) -> Unit) {
        val report = runCatching { RearViewportReporter.capture(activity, revision) }.getOrElse {
            callback(Result.failure(it)); return
        }
        reportViewport(sessionId, generation, report, callback)
    }

    /** Submit an already captured window report so its revision and measured geometry stay one observation. */
    fun reportViewport(sessionId: String, generation: Long, report: RearWindowReport, callback: (Result<RearOperationResult>) -> Unit) {
        require(report.revision > 0) { "revision must be positive" }
        onBinder(callback) { service -> RearOperationResult.fromJson(
            service.reportSceneViewport(callerPackage, sessionId, generation, report.toJson().toString(), this.callback)
        ) }
    }

    fun attachObserver(sessionId: String, generation: Long, callback: (Result<RearOperationResult>) -> Unit) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.attachSceneObserver(callerPackage, sessionId, generation, this.callback))
    }

    fun reportVisibility(sessionId: String, generation: Long, visible: Boolean, callback: (Result<RearOperationResult>) -> Unit) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.reportSceneVisibility(callerPackage, sessionId, generation, visible, this.callback))
    }

    /** Reconcile delayed observer callbacks against the Core's current owner/session snapshot. */
    fun queryScenePresentation(
        sessionId: String,
        generation: Long,
        callback: (Result<RearScenePresentation>) -> Unit
    ) = onBinder(callback) { service ->
        RearScenePresentation.fromJson(service.queryScenePresentation(callerPackage, sessionId, generation, this.callback))
    }

    fun closeScene(sessionId: String, callback: (Result<RearOperationResult>) -> Unit = {}) = onBinder(callback) { service ->
        RearOperationResult.fromJson(service.closeScene(callerPackage, sessionId, this.callback))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val service = remote
        val wasBound = synchronized(connectionLock) { remote = null; bound.also { bound = false } }
        worker.execute {
            if (service != null) runCatching { service.releaseClient(callerPackage, callback) }
            dataHandlers.clear()
            closedDataIds.clear()
            synchronized(dataLock) { pendingData.clear() }
            if (wasBound) Handler(Looper.getMainLooper()).post { runCatching { appContext.unbindService(connection) } }
            worker.shutdown()
        }
    }

    private fun <T> onBinder(callback: (Result<T>) -> Unit, action: (IRemoteRearPlatformService) -> T) {
        val service = remote
        if (service == null) {
            callback(Result.failure(IllegalStateException("E_NOT_CONNECTED")))
            return
        }
        worker.execute {
            val result = runCatching { action(service) }
            eventExecutor.execute { if (!closed.get()) callback(result) }
        }
    }

    companion object {
        const val HOST_PACKAGE = "top.zainzh.rearscreen"
        const val HOST_SERVICE = "top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService"
        const val EXTRA_LAUNCH_TICKET = "remrss_scene_ticket"
        const val ACTIVATION_REQUEST_CODE = 0x524D
        const val DATA_CONSENT_REQUEST_CODE = 0x524E

        /** For local host-debug variants; production callers should use the default constructor. */
        @JvmStatic fun forHostPackage(
            context: Context,
            hostPackageName: String,
            eventExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
            onEvent: (RearPlatformEvent) -> Unit
        ) = RearClient(context, hostPackageName, eventExecutor, onEvent)
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

data class RearPlatformEvent(val type: String, val sessionId: String? = null, val generation: Long? = null, val data: JSONObject = JSONObject()) {
    companion object {
        fun fromJson(json: String): RearPlatformEvent {
            val root = JSONObject(json)
            return RearPlatformEvent(root.getString("type"), root.optString("sessionId").takeUnless(String::isEmpty),
                root.optLong("generation").takeIf { root.has("generation") }, root)
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
