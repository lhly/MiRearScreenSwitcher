package dev.remrss.sample

import android.app.Application
import android.content.ComponentName
import android.util.Log
import dev.remrss.platform.sdk.RearClient
import dev.remrss.platform.sdk.RearPlatformEvent
import dev.remrss.platform.sdk.RearScenePresentation
import dev.remrss.platform.sdk.RearScenePresentationGate
import dev.remrss.platform.sdk.RearScenePresentationRequestEpoch
import dev.remrss.platform.sdk.RearSceneRequest
import java.io.Closeable

class SampleApplication : Application() {
    private val scoreboard = ScoreboardState()
    private val status = SampleStatus("Connecting to ReMRSS…")
    val readyGate = TicketReadyGate()
    @Volatile var lease: SampleLease? = null
    @Volatile var keepVisibleForNextScene: Boolean = true
    @Volatile var sceneVisibleNow: Boolean = false
    val lastStatus: String get() = status.current()
    lateinit var client: RearClient
    private val leaseLock = Any()
    private val leaseWaiters = mutableMapOf<String, MutableSet<(SampleLease) -> Unit>>()
    private val readyBeforeAccepted = mutableMapOf<String, Long>()
    private val visibilityLock = Any()
    private val visibilityObservers = linkedSetOf<(Boolean) -> Unit>()
    @Volatile private var sceneVisible = false
    private val presentationRequestEpoch = RearScenePresentationRequestEpoch()

    fun score(): Int = scoreboard.current()
    fun addPoint(): Int = scoreboard.addPoint()
    fun observeScore(observer: (Int) -> Unit): Closeable = scoreboard.observe(observer)

    override fun onCreate() {
        super.onCreate()
        client = RearClient.forHostPackage(this, BuildConfig.REM_RSS_HOST_PACKAGE) { event ->
            if (event.type == "accepted") {
                val accepted = SampleLease(
                    event.data.getString("sessionId"), event.data.getLong("generation"), event.data.getString("launchTicket"),
                    event.data.optBoolean("keepVisible", keepVisibleForNextScene)
                )
                publishSceneVisibility(false)
                val waiters = synchronized(leaseLock) {
                    lease = accepted
                    readyBeforeAccepted.remove(accepted.launchTicket)?.let(accepted::markReady)
                    leaseWaiters.remove(accepted.launchTicket).orEmpty().toList()
                }
                waiters.forEach { it(accepted) }
                publishStatus("Scene approved; waiting for the rear window")
            } else if (event.type == "ready" || event.type == "viewportChanged") {
                val session = event.sessionId
                val revision = runCatching { event.data.getJSONObject("viewport").getLong("revision") }.getOrNull()
                val current = lease
                if (current != null && current.sessionId == session && revision != null) {
                    current.markReady(revision)
                    readyGate.markReady(current.launchTicket)
                    reconcileScenePresentation(current)
                }
            } else if (event.type == "closed") {
                val current = lease
                if (current != null && current.sessionId == event.sessionId && current.generation == event.generation) {
                    readyGate.markClosed(current.launchTicket)
                    lease = null
                    publishSceneVisibility(false)
                }
                publishStatus("Rear scene closed")
            } else if (event.type in setOf("visibilityChanged", "suspended", "restored") &&
                lease?.let { it.sessionId == event.sessionId && it.generation == event.generation } == true) {
                // Observer callbacks are invalidation hints, not authorization. A delayed old
                // Restored event re-queries the current Core snapshot before changing UI state.
                lease?.let(::reconcileScenePresentation)
                if (event.type == "suspended") publishStatus("Rear scene suspended: ${event.data.optString("reason", "COVERED")}")
            } else if (event.type == "backendLost" && lease?.sessionId == event.sessionId) {
                publishStatus("Rear scene backend lost")
            } else if (event.type == "error") {
                publishStatus("Request failed: ${event.data.optString("code", "E_UNKNOWN")}")
            } else if (event.type == "backendDisconnected") {
                publishStatus("ReMRSS connection lost")
            }
        }
        client.connect { result -> publishStatus(if (result.isSuccess) "Connected to ReMRSS" else "Connection failed: ${result.exceptionOrNull()?.message}") }
    }

    fun observeStatus(observer: (String) -> Unit): java.io.Closeable = status.observe(observer)
    fun publishStatus(value: String) = status.publish(value)

    fun observeSceneVisibility(observer: (Boolean) -> Unit, emitCurrent: Boolean = true): Closeable {
        synchronized(visibilityLock) { visibilityObservers += observer }
        if (emitCurrent) observer(sceneVisible)
        return Closeable { synchronized(visibilityLock) { visibilityObservers -= observer } }
    }

    private fun publishSceneVisibility(visible: Boolean) {
        val changed = sceneVisible != visible
        sceneVisible = visible
        sceneVisibleNow = visible
        if (changed) Log.i(TAG_PRESENTATION, "core_presentation visible=$visible revision=${lease?.presentationRevision() ?: 0L}")
        val observers = synchronized(visibilityLock) { visibilityObservers.toList() }
        observers.forEach { runCatching { it(visible) } }
    }

    fun reconcileScenePresentation(current: SampleLease, stillCurrent: () -> Boolean = { true }) {
        val requestEpoch = presentationRequestEpoch.begin()
        client.queryScenePresentation(current.sessionId, current.generation) { result ->
            if (!presentationRequestEpoch.isCurrent(requestEpoch) || !stillCurrent()) return@queryScenePresentation
            val snapshot = result.getOrNull() ?: run {
                Log.w(TAG_PRESENTATION, "query_failed error=${result.exceptionOrNull()?.javaClass?.simpleName ?: "unavailable"}")
                return@queryScenePresentation
            }
            val accepted = synchronized(leaseLock) {
                lease === current && current.applyPresentation(snapshot)
            }
            if (accepted) publishSceneVisibility(current.presentationVisible())
        }
    }

    fun invalidatePresentationQueries() { presentationRequestEpoch.invalidate() }

    fun open(activity: android.app.Activity, onStatus: (String) -> Unit, keepVisible: Boolean = keepVisibleForNextScene) {
        keepVisibleForNextScene = keepVisible
        val request = RearSceneRequest(ComponentName(this, RearSceneActivity::class.java), keepVisible = keepVisible)
        val launch = {
            client.requestSceneActivation(activity, request) { result ->
                publishStatus(if (result.isSuccess) "Host confirmation opened" else "Request failed: ${result.exceptionOrNull()?.message}")
                onStatus(lastStatus)
            }
        }
        if (client.isConnected) launch() else client.connect { result ->
            if (result.isSuccess) launch() else {
                publishStatus("Connection failed: ${result.exceptionOrNull()?.message}")
                onStatus(lastStatus)
            }
        }
    }

    fun markReady(ticket: String, revision: Long) {
        readyGate.markReady(ticket)
        val current = synchronized(leaseLock) {
            lease?.takeIf { it.launchTicket == ticket }?.also { it.markReady(revision) }
                ?: run {
                    readyBeforeAccepted[ticket] = maxOf(readyBeforeAccepted[ticket] ?: 0L, revision)
                    null
                }
        }
        if (current != null) publishStatus("Rear window ready")
    }

    fun observeLease(ticket: String, observer: (SampleLease) -> Unit): Closeable {
        val current = synchronized(leaseLock) {
            lease?.takeIf { it.launchTicket == ticket } ?: run {
                leaseWaiters.getOrPut(ticket) { linkedSetOf() }.add(observer)
                null
            }
        }
        current?.let(observer)
        return Closeable {
            synchronized(leaseLock) {
                leaseWaiters[ticket]?.let { waiters ->
                    waiters.remove(observer)
                    if (waiters.isEmpty()) leaseWaiters.remove(ticket)
                }
            }
        }
    }

    private companion object { const val TAG_PRESENTATION = "RearSceneSampleState" }
}

class SampleLease(val sessionId: String, val generation: Long, val launchTicket: String, val keepVisible: Boolean = true) {
    private val lock = Any()
    private val presentationGate = RearScenePresentationGate(sessionId, generation)
    private var readyRevision = 0L
    private var lastIssuedRevision = 0L
    private var acceptedViewportKey: String? = null
    private val readyObservers = linkedSetOf<(Long) -> Unit>()

    val isReady: Boolean get() = synchronized(lock) { readyRevision > 0L }
    fun applyPresentation(snapshot: RearScenePresentation): Boolean = synchronized(lock) { presentationGate.apply(snapshot) }
    fun presentationVisible(): Boolean = synchronized(lock) { presentationGate.visible() }
    fun presentationRevision(): Long = synchronized(lock) { presentationGate.revision() }

    fun markReady(revision: Long) {
        if (revision <= 0) return
        val (confirmed, observers) = synchronized(lock) {
            readyRevision = maxOf(readyRevision, revision)
            lastIssuedRevision = maxOf(lastIssuedRevision, revision)
            readyRevision to readyObservers.toList().also { readyObservers.clear() }
        }
        observers.forEach { it(confirmed) }
    }

    fun nextViewportRevision(): Long = synchronized(lock) {
        check(readyRevision > 0) { "E_SCENE_NOT_READY" }
        maxOf(readyRevision, lastIssuedRevision).plus(1).also { lastIssuedRevision = it }
    }

    /** Null means this exact measured viewport was already accepted by the host. */
    fun nextViewportRevision(viewportKey: String): Long? = synchronized(lock) {
        check(readyRevision > 0) { "E_SCENE_NOT_READY" }
        if (viewportKey == acceptedViewportKey) null
        else maxOf(readyRevision, lastIssuedRevision).plus(1).also { lastIssuedRevision = it }
    }

    fun markViewportAccepted(viewportKey: String, revision: Long) {
        if (revision <= 0) return
        synchronized(lock) {
            acceptedViewportKey = viewportKey
            readyRevision = maxOf(readyRevision, revision)
            lastIssuedRevision = maxOf(lastIssuedRevision, revision)
        }
    }

    fun whenReady(observer: (Long) -> Unit): Closeable {
        val current = synchronized(lock) {
            readyRevision.takeIf { it > 0 } ?: run { readyObservers += observer; 0L }
        }
        if (current > 0) observer(current)
        return Closeable { synchronized(lock) { readyObservers -= observer } }
    }
}
