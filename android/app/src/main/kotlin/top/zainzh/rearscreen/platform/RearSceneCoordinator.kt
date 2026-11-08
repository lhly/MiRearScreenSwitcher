package top.zainzh.rearscreen.platform

import android.os.SystemClock
import java.security.SecureRandom
import java.util.Base64
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** Privilege/backend boundary. Implementations must create a fresh task, never move or stop a caller task. */
internal interface RearSceneGateway {
    fun queryCapabilities(owner: SessionOwner): CapabilitySnapshot
    fun authorize(owner: SessionOwner, request: SceneRequest): SceneError?
    /** Prepare only the target display for a confirmed Scene lease; defaults to no platform wake. */
    fun prepareDisplay(owner: SessionOwner, request: SceneRequest): SceneError? = null
    /** Target display for lease-scoped lock/cover event observation; null disables the monitor. */
    fun targetDisplayId(): Int? = null
    /** One-shot recovery for an already-owned task, after an authenticated lock/cover cause. */
    fun recoverOwnedDisplay(owner: SessionOwner, request: SceneRequest, task: LaunchedScene,
                            isCurrent: () -> Boolean, onWakeAttempt: () -> SceneRecoveryWakeDecision): SceneRecoveryResult =
        if (!isCurrent()) SceneRecoveryResult(SceneError.STALE_SESSION) else when (onWakeAttempt()) {
            SceneRecoveryWakeDecision.STALE -> SceneRecoveryResult(SceneError.STALE_SESSION)
            SceneRecoveryWakeDecision.ALREADY_ON -> SceneRecoveryResult()
            SceneRecoveryWakeDecision.CLAIMED -> SceneRecoveryResult(prepareDisplay(owner, request))
        }
    fun launchNewTask(owner: SessionOwner, request: SceneRequest, launchTicket: String, expiresAtElapsedRealtimeMs: Long): LaunchedScene?
    fun verifyLaunchedTask(owner: SessionOwner, expected: LaunchedScene, report: SceneWindowReport): Boolean
    fun finishOwnedTask(owner: SessionOwner, task: LaunchedScene)
}

data class LaunchedScene(val component: android.content.ComponentName, val taskId: Int, val displayId: Int)

internal enum class SceneRecoveryWakeDecision { STALE, ALREADY_ON, CLAIMED }
internal data class SceneRecoveryResult(val error: SceneError? = null, val deferredByOverlay: Boolean = false)

/** Bridges Scene ownership into the already-running legacy display keepers. */
internal interface SceneLegacyLeasePort {
    fun acquire(sessionId: String): Boolean
    fun release(sessionId: String, restorePriorMode: Boolean)
}

/** Confirms that an activation was minted after a real host UI action and consumes it once. */
fun interface UserActivationVerifier {
    fun consume(owner: SessionOwner, request: SceneRequest, token: UserActivationToken): Boolean
}

/** Shared scene lease coordinator; worker lifetime is intentionally not a session owner. */
internal class RearSceneCoordinator(
    private val gateway: RearSceneGateway,
    private val activationVerifier: UserActivationVerifier,
    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rear-scene-events").apply { isDaemon = true }
    },
    private val expiryScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "rear-scene-expiry").apply { isDaemon = true }
    },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val randomToken: () -> String = ::newToken,
    private val displayOwnershipEpoch: () -> Long = LegacyDisplayOccupancy::epoch,
    private val lifecycleMonitor: SceneDisplayLifecycleMonitor? = null,
    private val legacyServiceLease: SceneLegacyLeasePort? = null
) : DisplaySessionCoordinator {
    private data class Ticket(
        val owner: SessionOwner,
        val sessionId: String,
        val generation: Long,
        val component: android.content.ComponentName,
        val expiresAt: Long,
        var consumed: Boolean = false
    )

    private data class Record(
        val owner: SessionOwner,
        val request: SceneRequest,
        val generation: Long,
        val displayOwnershipEpoch: Long,
        var launch: LaunchedScene?,
        var observer: SceneObserver?,
        var viewport: RearViewportSnapshot? = null,
        var visible: Boolean = false,
        var presentationRevision: Long = 0L,
        var hiddenReason: SceneSuspendReason? = SceneSuspendReason.DISPLAY_OFF,
        var activityVisible: Boolean = false,
        var targetDisplayOn: Boolean? = null,
        var primaryScreenOff: Boolean = false,
        var covered: Boolean = false,
        var keepVisibleRestorationEligible: Boolean = false,
        var visibleBeforeCover: Boolean = false,
        var visibleAtScreenOff: Boolean = false,
        // A cause is not "attempted" just because a delayed capture was scheduled. It becomes
        // attempted only at the serialized gateway invocation boundary. Temporary occlusion can
        // therefore invalidate a pending capture and allow the same still-valid cause to resume.
        var screenOffRecoveryScheduled: Boolean = false,
        var screenOffRecoveryAttempted: Boolean = false,
        var coverRecoveryScheduled: Boolean = false,
        var coverRecoveryAttempted: Boolean = false,
        /** One same-lease recovery intent survives asynchronous cover/cancellation ordering. */
        var pendingRecoveryFromScreenOff: Boolean? = null,
        var recoveryToken: Long = 0L,
        var lifecycle: Closeable? = null,
        var closed: Boolean = false
    )

    private data class RecoveryCapture(
        val sessionId: String,
        val generation: Long,
        val token: Long,
        val ownershipEpoch: Long,
        val owner: SessionOwner,
        val request: SceneRequest,
        val task: LaunchedScene,
        val fromScreenOff: Boolean,
        val needsWake: Boolean
    )
    private data class OverlayLease(
        val overlayId: Long,
        val sceneSessionIdAtStart: String?,
        val sceneGenerationAtStart: Long?,
        val sceneOwnershipEpochAtStart: Long,
        val displayOwnershipEpochAtStart: Long,
        val suppressLegacyRestore: Boolean
    )

    private val lock = Any()
    private val sessions = LinkedHashMap<String, Record>()
    private val tickets = HashMap<String, Ticket>()
    private var nextGeneration = 1L
    private var temporaryOverlayActive = false
    private var activeOverlayLease: OverlayLease? = null
    private var sceneOwnershipEpoch = 0L
    // One active Binder operation and one replaceable pending lease cause. The dispatcher below
    // keeps newer valid leases from being rejected while an obsolete gateway call is still in flight.
    private val recoveryExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        ThreadFactory { runnable -> Thread(runnable, "scene-display-recovery").apply { isDaemon = true } })
    private val recoveryDispatchLock = Any()
    private var pendingRecovery: RecoveryCapture? = null
    private var recoveryDrainScheduled = false

    internal fun hasActiveOrClosingSceneSessions(): Boolean = synchronized(lock) { sessions.isNotEmpty() }

    internal fun ownsActiveSession(owner: SessionOwner, sessionId: String): Boolean = synchronized(lock) {
        sessions[sessionId]?.let { !it.closed && it.owner == owner } == true
    }

    /**
     * True only for a live, ready lease whose exact verified task is the foreground task returned
     * by the privileged rear-display query. Package name alone never grants cast preemption.
     */
    internal fun ownsActiveSceneTask(packageName: String, taskId: Int, displayId: Int): Boolean = synchronized(lock) {
        taskId > 0 && displayId > 0 && sessions.values.any { record ->
            val launch = record.launch
            !record.closed && record.viewport != null && launch != null &&
                launch.taskId == taskId && launch.displayId == displayId &&
                launch.component.packageName == packageName && record.request.component == launch.component
        }
    }

    internal fun ownsActiveSceneTask(taskId: Int, displayId: Int): Boolean = synchronized(lock) {
        taskId > 0 && displayId > 0 && sessions.values.any { record ->
            !record.closed && record.launch?.let {
                it.taskId == taskId && it.displayId == displayId && record.request.component == it.component
            } == true
        }
    }

    /** Package-wide destructive operations must not kill any live Scene in that package. */
    internal fun ownsActiveScenePackage(packageName: String): Boolean = synchronized(lock) {
        packageName.isNotBlank() && sessions.values.any { record ->
            !record.closed && record.request.component.packageName == packageName
        }
    }

    /** An explicit legacy URI/Tile projection permanently revokes Scene restoration eligibility. */
    internal fun closeAllScenes(reason: SceneCloseReason = SceneCloseReason.PREEMPTED) {
        val owned = synchronized(lock) {
            val result = sessions.toList()
            result.forEach { (id, record) -> closeLocked(id, record) }
            tickets.clear()
            result
        }
        owned.forEach { (id, record) ->
            record.launch?.let { launch ->
                android.util.Log.i("RearSceneCast", "scene_closed reason=${reason.name} task=${launch.taskId} display=${launch.displayId}")
            }
            record.launch?.let { runCatching { gateway.finishOwnedTask(record.owner, it) } }
            finishLeaseResources(id, record, reason)
        }
    }

    /** Host Activity bootstrap only; never expose this capability through the external SDK Binder. */
    internal fun resolveLaunchTicketForHostActivity(launchTicket: String, component: android.content.ComponentName): SceneLaunchContext? = synchronized(lock) {
        val ticket = tickets[launchTicket] ?: return@synchronized null
        val record = sessions[ticket.sessionId] ?: return@synchronized null
        val isLiveReadyScene = ticket.consumed && record.viewport != null
        if ((!isLiveReadyScene && clock() >= ticket.expiresAt) || ticket.component != component ||
            record.closed || record.generation != ticket.generation || record.request.component != component) return@synchronized null
        SceneLaunchContext(ticket.owner, ticket.sessionId, ticket.generation, record.request)
    }

    override fun queryCapabilities(owner: SessionOwner): CapabilitySnapshot = gateway.queryCapabilities(owner)

    override fun openScene(
        owner: SessionOwner,
        request: SceneRequest,
        activation: UserActivationToken,
        observer: SceneObserver
    ): SceneOpenResult {
        if (!activationVerifier.consume(owner, request, activation)) return SceneOpenResult.Rejected(SceneError.CAPABILITY_DENIED, false)
        val effectiveRequest = request.copy(requiredCapabilities = request.requiredCapabilities + request.derivedCapabilities())
        val ownershipEpoch = displayOwnershipEpoch()
        gateway.authorize(owner, effectiveRequest)?.let { return SceneOpenResult.Rejected(it, it == SceneError.BACKEND_UNAVAILABLE) }

        val ticket = randomToken()
        val now = clock()
        val expiresAt = now + LAUNCH_TICKET_TTL_MS
        val generation: Long
        val sessionId = randomToken()
        synchronized(lock) {
            // A user projection may have claimed the display while backend authorization queried it.
            if (displayOwnershipEpoch() != ownershipEpoch) return SceneOpenResult.Rejected(SceneError.DISPLAY_BUSY, true)
            if (temporaryOverlayActive || sessions.isNotEmpty() || tickets.isNotEmpty()) return SceneOpenResult.Rejected(SceneError.DISPLAY_BUSY, true)
            generation = nextGeneration++
            sceneOwnershipEpoch++
            SceneOwnershipEpoch.noteSceneReservation()
            tickets[ticket] = Ticket(owner, sessionId, generation, request.component, expiresAt)
            sessions[sessionId] = Record(owner, request, generation, ownershipEpoch, null, observer, visible = false, activityVisible = false)
        }

        // Old user-selected global keepers are paused only while this exact Scene lease owns the
        // display. The bridge never starts those services and restores only the matching lease.
        if (legacyServiceLease != null && !legacyServiceLease.acquire(sessionId)) {
            discardPendingLaunch(sessionId, ticket, SceneCloseReason.PREEMPTED)
            return SceneOpenResult.Rejected(SceneError.DISPLAY_BUSY, true)
        }
        try {
            val targetDisplayId = gateway.targetDisplayId()
            if (targetDisplayId != null) {
                val lifecycle = lifecycleMonitor?.start(targetDisplayId) { signal ->
                    handleDisplaySignal(sessionId, generation, signal)
                }
                if (lifecycle != null) {
                    val orphaned = synchronized(lock) {
                        val current = sessions[sessionId]
                        if (current != null && !current.closed) {
                            current.lifecycle = lifecycle
                            false
                        } else true
                    }
                    // Lifecycle close can wait for an in-flight sensor registration/update. Keep
                    // that external resource wait outside the Core state lock: a source update may
                    // synchronously deliver its final far edge back into this Coordinator.
                    if (orphaned) lifecycle.close()
                }
            }
        } catch (_: Exception) {
            discardPendingLaunch(sessionId, ticket, SceneCloseReason.BACKEND_LOST)
            return SceneOpenResult.Rejected(SceneError.DISPLAY_UNAVAILABLE, true)
        }

        // A single explicit wake is scoped to this freshly-reserved, confirmed lease. It is never
        // used to defeat an existing cast or overlay, and an ownership takeover cancels the launch.
        if (displayOwnershipEpoch() != ownershipEpoch) {
            discardPendingLaunch(sessionId, ticket, SceneCloseReason.PREEMPTED)
            return SceneOpenResult.Rejected(SceneError.DISPLAY_BUSY, true)
        }
        val prepareError = try { gateway.prepareDisplay(owner, request) }
            catch (_: Exception) { SceneError.DISPLAY_UNAVAILABLE }
        if (prepareError != null) {
            discardPendingLaunch(sessionId, ticket, SceneCloseReason.BACKEND_LOST)
            return SceneOpenResult.Rejected(prepareError, prepareError == SceneError.BACKEND_UNAVAILABLE)
        }
        if (displayOwnershipEpoch() != ownershipEpoch || !ownsActiveSession(owner, sessionId) || isTransientOverlayActive()) {
            discardPendingLaunch(sessionId, ticket, SceneCloseReason.PREEMPTED)
            return SceneOpenResult.Rejected(SceneError.DISPLAY_BUSY, true)
        }

        val launched = try {
            gateway.launchNewTask(owner, request, ticket, expiresAt)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
        if (launched == null || launched.component != request.component || launched.taskId < 0 || launched.displayId <= 0) {
            discardPendingLaunch(sessionId, ticket, SceneCloseReason.BACKEND_LOST)
            val capabilities = gateway.queryCapabilities(owner)
            val reason = if (!capabilities.deviceAvailable) SceneError.UNSUPPORTED
                else if (!capabilities.backendConnected) SceneError.BACKEND_UNAVAILABLE
                else SceneError.INVALID_STATE
            return SceneOpenResult.Rejected(reason, reason == SceneError.BACKEND_UNAVAILABLE)
        }

        val registered = synchronized(lock) {
            val record = sessions[sessionId]
            if (record == null || record.closed || clock() >= expiresAt || temporaryOverlayActive) false
            else { record.launch = launched; true }
        }
        if (!registered) {
            runCatching { gateway.finishOwnedTask(owner, launched) }
            discardPendingLaunch(sessionId, ticket, SceneCloseReason.LAUNCH_EXPIRED)
            return SceneOpenResult.Rejected(SceneError.STALE_SESSION, true)
        }
        val expiryDelay = (expiresAt - clock()).coerceAtLeast(0L)
        expiryScheduler.schedule({ expirePendingLaunch(sessionId, ticket) }, expiryDelay, TimeUnit.MILLISECONDS)
        return SceneOpenResult.Accepted(SceneSessionHandle(sessionId, generation, ticket, expiresAt))
    }

    override fun closeScene(owner: SessionOwner, sessionId: String): SceneOperationResult {
        val record = synchronized(lock) {
            val current = sessions[sessionId] ?: return SceneOperationResult(false, SceneError.STALE_SESSION)
            if (current.owner != owner) return SceneOperationResult(false, SceneError.CAPABILITY_DENIED)
            closeLocked(sessionId, current)
        }
        record.launch?.let { runCatching { gateway.finishOwnedTask(owner, it) } }
        finishLeaseResources(sessionId, record, SceneCloseReason.CLIENT_CLOSED)
        return SceneOperationResult(true)
    }

    override fun reportSceneWindowReady(owner: SessionOwner, launchTicket: String, window: SceneWindowReport): SceneOperationResult {
        val pair = synchronized(lock) {
            val ticket = tickets[launchTicket] ?: return SceneOperationResult(false, SceneError.STALE_SESSION)
            if (ticket.owner != owner || ticket.consumed || clock() >= ticket.expiresAt)
                return SceneOperationResult(false, SceneError.STALE_SESSION)
            if (ticket.component != window.component) return SceneOperationResult(false, SceneError.CAPABILITY_DENIED)
            val record = sessions[ticket.sessionId] ?: return SceneOperationResult(false, SceneError.STALE_SESSION)
            val launch = record.launch ?: return SceneOperationResult(false, SceneError.INVALID_STATE, true)
            if (record.closed || record.generation != ticket.generation || window.taskId != launch.taskId ||
                window.viewport.displayId != launch.displayId || window.viewport.revision < 1 ||
                window.viewport.windowPx.width <= 0 || window.viewport.windowPx.height <= 0 ||
                window.viewport.densityDpi <= 0) return SceneOperationResult(false, SceneError.INVALID_STATE)
            ticket to record
        }
        val launch = pair.second.launch ?: return SceneOperationResult(false, SceneError.STALE_SESSION)
        if (!gateway.verifyLaunchedTask(owner, launch, window)) {
            closeScene(owner, pair.first.sessionId)
            return SceneOperationResult(false, SceneError.INVALID_STATE)
        }
        synchronized(lock) {
            val ticket = tickets[launchTicket] ?: return SceneOperationResult(false, SceneError.STALE_SESSION)
            val record = sessions[pair.first.sessionId] ?: return SceneOperationResult(false, SceneError.STALE_SESSION)
            if (record !== pair.second || ticket.consumed || ticket.generation != record.generation || clock() >= ticket.expiresAt)
                return SceneOperationResult(false, SceneError.STALE_SESSION)
            ticket.consumed = true
            record.viewport = window.viewport
            emit(pair.first.sessionId, record, SceneEvent.Ready(window.viewport))
            emitInitialVisibilityLocked(pair.first.sessionId, record)
        }
        return SceneOperationResult(true)
    }

    override fun reportSceneViewport(owner: SessionOwner, sessionId: String, generation: Long, window: SceneWindowReport): SceneOperationResult {
        val record = synchronized(lock) {
            val current = sessions[sessionId] ?: return SceneOperationResult(false, SceneError.STALE_SESSION)
            if (current.owner != owner) return SceneOperationResult(false, SceneError.CAPABILITY_DENIED)
            val launch = current.launch ?: return SceneOperationResult(false, SceneError.INVALID_STATE, true)
            if (current.generation != generation || launch.component != window.component ||
                launch.taskId != window.taskId || launch.displayId != window.viewport.displayId)
                return SceneOperationResult(false, SceneError.STALE_SESSION)
            if (current.viewport == null) return SceneOperationResult(false, SceneError.INVALID_STATE, true)
            if (window.viewport.revision <= (current.viewport?.revision ?: 0L) ||
                window.viewport.windowPx.width <= 0 || window.viewport.windowPx.height <= 0 || window.viewport.densityDpi <= 0)
                return SceneOperationResult(false, SceneError.INVALID_STATE)
            current
        }
        val launch = record.launch ?: return SceneOperationResult(false, SceneError.STALE_SESSION)
        if (!gateway.verifyLaunchedTask(owner, launch, window)) return SceneOperationResult(false, SceneError.INVALID_STATE)
        synchronized(lock) {
            val current = sessions[sessionId] ?: return SceneOperationResult(false, SceneError.STALE_SESSION)
            if (current.generation != generation || window.viewport.revision <= (current.viewport?.revision ?: 0L))
                return SceneOperationResult(false, SceneError.STALE_SESSION)
            current.viewport = window.viewport
        }
        emit(sessionId, record, SceneEvent.ViewportChanged(window.viewport.revision, window.viewport))
        return SceneOperationResult(true)
    }

    override fun attachSceneObserver(owner: SessionOwner, sessionId: String, generation: Long, observer: SceneObserver): SceneOperationResult {
        synchronized(lock) {
            val current = sessions[sessionId] ?: return SceneOperationResult(false, SceneError.STALE_SESSION)
            if (current.owner != owner) return SceneOperationResult(false, SceneError.CAPABILITY_DENIED)
            if (current.generation != generation || current.closed) return SceneOperationResult(false, SceneError.STALE_SESSION)
            current.observer = observer
            current.viewport?.let { viewport ->
                emit(sessionId, current, SceneEvent.Ready(viewport))
                emitInitialVisibilityLocked(sessionId, current)
            }
        }
        return SceneOperationResult(true)
    }

    override fun reportSceneVisibility(owner: SessionOwner, sessionId: String, generation: Long, visible: Boolean): SceneOperationResult {
        var edge: String? = null
        var recovery: RecoveryCapture? = null
        synchronized(lock) {
            val current = sessions[sessionId] ?: return SceneOperationResult(false, SceneError.STALE_SESSION)
            if (current.owner != owner) return SceneOperationResult(false, SceneError.CAPABILITY_DENIED)
            if (current.generation != generation || current.closed) return SceneOperationResult(false, SceneError.STALE_SESSION)
            val previousActivityVisible = current.activityVisible
            val previousEffectiveVisible = current.visible
            current.activityVisible = visible
            if (visible) current.visibleAtScreenOff = current.visibleAtScreenOff || current.primaryScreenOff
            updateEffectiveVisibilityLocked(sessionId, current)
            if (!visible && current.primaryScreenOff && current.visibleAtScreenOff && current.targetDisplayOn == true &&
                current.request.keepVisible && current.keepVisibleRestorationEligible) {
                if (current.pendingRecoveryFromScreenOff == null) current.pendingRecoveryFromScreenOff = true
                recovery = resumePendingRecoveryLocked(sessionId, current)
            }
            if (previousActivityVisible != current.activityVisible || previousEffectiveVisible != current.visible) {
                edge = "scene_window_visibility input=$visible effective=${current.visible} " +
                    "reason=${current.hiddenReason?.name ?: "visible"} revision=${current.presentationRevision} " +
                    "task=${current.launch?.taskId ?: -1} display=${current.launch?.displayId ?: -1}"
            }
        }
        edge?.let { runCatching { android.util.Log.i("RearSceneRecovery", it) } }
        recovery?.let(::scheduleRecovery)
        return SceneOperationResult(true)
    }

    override fun queryScenePresentation(owner: SessionOwner, sessionId: String, generation: Long): ScenePresentationSnapshot? = synchronized(lock) {
        val record = sessions[sessionId] ?: return@synchronized null
        if (record.owner != owner || record.generation != generation || record.closed || record.viewport == null) return@synchronized null
        ScenePresentationSnapshot(sessionId, generation, record.presentationRevision, record.visible,
            record.hiddenReason.takeUnless { record.visible })
    }

    /** Notification/charging animations temporarily cover a live Scene without ending its lease. */
    /** Production animation-session entry: binds one overlay id to the Scene it actually covered. */
    internal fun beginTransientOverlay(overlayId: Long, inheritedReturnWasSuppressed: Boolean = false): Boolean {
        val suppressLegacyRestore = synchronized(lock) {
            val entry = sessions.entries.firstOrNull { (_, record) -> !record.closed }
            val sceneOwnsAtStart = entry != null
            val priorOverlayStillSuppresses = activeOverlayLease?.let(::overlaySuppressesLegacyRestoreLocked) == true
            activeOverlayLease = OverlayLease(
                overlayId, entry?.key, entry?.value?.generation, sceneOwnershipEpoch, displayOwnershipEpoch(),
                sceneOwnsAtStart || priorOverlayStillSuppresses || inheritedReturnWasSuppressed
            )
            activeTransientOverlayId = overlayId
            sceneOwnsAtStart || priorOverlayStillSuppresses || inheritedReturnWasSuppressed
        }
        if (synchronized(lock) { activeOverlayLease?.overlayId == overlayId && activeOverlayLease?.sceneSessionIdAtStart != null }) {
            updateTransientOverlay(true, overlayId)
        }
        return suppressLegacyRestore
    }

    /** A stale animation end cannot uncover a newer overlay or restore a Scene that was revoked. */
    internal fun endTransientOverlay(overlayId: Long): Boolean {
        val suppressLegacyRestore = synchronized(lock) {
            if (activeTransientOverlayId != overlayId) return@synchronized null
            activeOverlayLease?.let(::overlaySuppressesLegacyRestoreLocked) == true
        } ?: return false
        updateTransientOverlay(false, overlayId)
        return suppressLegacyRestore
    }

    private var activeTransientOverlayId: Long? = null

    private fun isTransientOverlayActive(): Boolean = synchronized(lock) { temporaryOverlayActive }
    private fun updateTransientOverlay(active: Boolean, overlayId: Long) {
        val recoveryCandidates = mutableListOf<RecoveryCapture>()
        synchronized(lock) {
            if (active) {
                if (activeTransientOverlayId != overlayId || temporaryOverlayActive) return
                activeTransientOverlayId = overlayId
            } else if (activeTransientOverlayId != overlayId) return
            val stateChanged = temporaryOverlayActive != active
            temporaryOverlayActive = active
            if (active) sessions.values.forEach(::invalidateScheduledRecoveryLocked)
            if (!active) {
                // An animation can begin before any Scene exists, then a user can open a Scene
                // while it is running. Its end still has to consume the animation token even
                // though it never covered a Scene (temporaryOverlayActive was already false).
                activeTransientOverlayId = null
                activeOverlayLease = null
            }
            if (!stateChanged) return
            sessions.forEach { (sessionId, record) ->
                if (record.closed) return@forEach
                updateEffectiveVisibilityLocked(sessionId, record, SceneSuspendReason.COVERED)
                if (!active && !record.covered && record.pendingRecoveryFromScreenOff == null && record.request.keepVisible) {
                    val needsRecovery = record.targetDisplayOn == false || (record.targetDisplayOn == true && !record.activityVisible)
                    if (needsRecovery) record.pendingRecoveryFromScreenOff = when {
                        record.primaryScreenOff && record.visibleAtScreenOff -> true
                        record.visibleBeforeCover -> false
                        else -> null
                    }
                }
                if (!active && !record.covered) resumePendingRecoveryLocked(sessionId, record)?.let(recoveryCandidates::add)
            }
        }
        recoveryCandidates.forEach(::scheduleRecovery)
    }

    private fun overlaySuppressesLegacyRestoreLocked(overlay: OverlayLease): Boolean =
        overlay.suppressLegacyRestore || sceneOwnershipEpoch != overlay.sceneOwnershipEpochAtStart ||
            displayOwnershipEpoch() != overlay.displayOwnershipEpochAtStart

    /** Applies one authoritative visibility calculation for Activity, rear power, occlusion and overlay state. */
    private fun updateEffectiveVisibilityLocked(
        sessionId: String,
        record: Record,
        hiddenReason: SceneSuspendReason = if (record.covered || temporaryOverlayActive) SceneSuspendReason.COVERED else SceneSuspendReason.DISPLAY_OFF
    ) {
        val visible = record.activityVisible && record.targetDisplayOn != false && !record.covered && !temporaryOverlayActive
        val nextReason = if (visible) null else hiddenReason
        if (record.visible == visible && record.hiddenReason == nextReason) return
        val visibilityChanged = record.visible != visible
        record.visible = visible
        record.hiddenReason = nextReason
        record.presentationRevision++
        if (visible && record.request.keepVisible) {
            record.keepVisibleRestorationEligible = true
            if (record.pendingRecoveryFromScreenOff != null) {
                record.pendingRecoveryFromScreenOff = null
                invalidateScheduledRecoveryLocked(record)
            }
        }
        if (record.viewport != null) {
            if (visibilityChanged) emitVisibilityChangeLocked(sessionId, record, visible, hiddenReason)
            else if (!visible) emit(sessionId, record, SceneEvent.Suspended(hiddenReason))
        }
    }

    /** Enqueue both halves of a visibility transition under the state lock to preserve ordering. */
    private fun emitVisibilityChangeLocked(sessionId: String, record: Record, visible: Boolean, hiddenReason: SceneSuspendReason) {
        emit(sessionId, record, SceneEvent.VisibilityChanged(visible))
        if (visible) emit(sessionId, record, SceneEvent.Restored)
        else emit(sessionId, record, SceneEvent.Suspended(hiddenReason))
    }

    /** Ready means the exact window supplied a measured viewport; visibility is a separate fact. */
    private fun emitInitialVisibilityLocked(sessionId: String, record: Record) {
        if (record.presentationRevision == 0L) {
            record.presentationRevision = 1L
            record.hiddenReason = if (record.visible) null else if (record.covered || temporaryOverlayActive) SceneSuspendReason.COVERED else SceneSuspendReason.DISPLAY_OFF
        }
        emit(sessionId, record, SceneEvent.VisibilityChanged(record.visible))
        if (!record.visible) emit(sessionId, record, SceneEvent.Suspended(
            if (record.covered || temporaryOverlayActive) SceneSuspendReason.COVERED else SceneSuspendReason.DISPLAY_OFF))
    }

    /** Screen and rear-sensor callbacks are scoped to this lease; a late edge cannot revive it. */
    internal fun handleDisplaySignal(sessionId: String, generation: Long, signal: SceneDisplaySignal) {
        var recovery: RecoveryCapture? = null
        synchronized(lock) {
            val record = sessions[sessionId] ?: return
            if (record.closed || record.generation != generation) return
            when (signal) {
                SceneDisplaySignal.PrimaryScreenOff -> {
                    if (!record.primaryScreenOff) {
                        record.primaryScreenOff = true
                        // Preserve visibility established before asynchronous Activity/display
                        // callbacks report the lock transition. A false report followed by rear
                        // OFF must not erase the valid keep-visible restoration cause.
                        record.visibleAtScreenOff = record.visibleAtScreenOff || record.activityVisible ||
                            record.visible || record.keepVisibleRestorationEligible
                        record.screenOffRecoveryAttempted = false
                    }
                    if (record.request.keepVisible && record.keepVisibleRestorationEligible && record.visibleAtScreenOff &&
                        record.targetDisplayOn == true && !record.activityVisible && record.pendingRecoveryFromScreenOff == null) {
                        record.pendingRecoveryFromScreenOff = true
                    }
                    if (record.pendingRecoveryFromScreenOff != null) {
                        recovery = resumePendingRecoveryLocked(sessionId, record)
                    } else if (record.visibleAtScreenOff && record.targetDisplayOn == false && !record.covered && !temporaryOverlayActive) {
                        recovery = beginRecoveryLocked(sessionId, record, fromScreenOff = true)
                    }
                }
                SceneDisplaySignal.PrimaryScreenOn -> {
                    record.primaryScreenOff = false
                    record.visibleAtScreenOff = false
                    if (record.pendingRecoveryFromScreenOff == null) {
                        record.screenOffRecoveryScheduled = false
                        record.screenOffRecoveryAttempted = false
                        record.recoveryToken++
                    } else if (record.pendingRecoveryFromScreenOff == false) {
                        // Main-screen state is unrelated to an already-qualified near/far
                        // restoration. Keep its token and scheduled bit paired so the old capture
                        // remains current instead of leaving a stale coverScheduled latch behind.
                        record.screenOffRecoveryScheduled = false
                        record.screenOffRecoveryAttempted = false
                    }
                    updateEffectiveVisibilityLocked(sessionId, record)
                    if (record.pendingRecoveryFromScreenOff != null) recovery = resumePendingRecoveryLocked(sessionId, record)
                }
                is SceneDisplaySignal.RearDisplayPower -> {
                    val wasOn = record.targetDisplayOn
                    record.targetDisplayOn = signal.on
                    if (!signal.on && wasOn == true) {
                        record.screenOffRecoveryAttempted = false
                        record.coverRecoveryAttempted = false
                        invalidateScheduledRecoveryLocked(record)
                    }
                    updateEffectiveVisibilityLocked(sessionId, record,
                        if (record.covered || temporaryOverlayActive) SceneSuspendReason.COVERED else SceneSuspendReason.DISPLAY_OFF)
                    if (record.pendingRecoveryFromScreenOff != null) {
                        recovery = resumePendingRecoveryLocked(sessionId, record)
                    } else if (!signal.on && record.primaryScreenOff && record.visibleAtScreenOff && !record.covered && !temporaryOverlayActive) {
                        recovery = beginRecoveryLocked(sessionId, record, fromScreenOff = true)
                    }
                }
                is SceneDisplaySignal.RearProximity -> {
                    if (signal.near && !record.covered) {
                        record.visibleBeforeCover = record.activityVisible || record.visible
                        record.covered = true
                        android.util.Log.i("RearSceneProximity", "core_edge state=covered source=rear_proximity visibleBefore=${record.visible} revision=${record.presentationRevision + 1}")
                        invalidateScheduledRecoveryLocked(record)
                        record.coverRecoveryScheduled = false
                        record.coverRecoveryAttempted = false
                        updateEffectiveVisibilityLocked(sessionId, record, SceneSuspendReason.COVERED)
                    } else if (!signal.near && record.covered) {
                        record.covered = false
                        android.util.Log.i("RearSceneProximity", "core_edge state=uncovered source=rear_proximity visibleBefore=${record.visible} revision=${record.presentationRevision + 1}")
                        updateEffectiveVisibilityLocked(sessionId, record)
                        if (record.pendingRecoveryFromScreenOff == null && record.request.keepVisible && !record.visible) {
                            record.pendingRecoveryFromScreenOff = if (record.primaryScreenOff && record.visibleAtScreenOff) true else false
                        }
                        recovery = resumePendingRecoveryLocked(sessionId, record)
                    }
                }
            }
        }
        recovery?.let(::scheduleRecovery)
    }

    /** Begins one recovery cause, then routes it through the same pending-intent state as every resume. */
    private fun beginRecoveryLocked(sessionId: String, record: Record, fromScreenOff: Boolean): RecoveryCapture? {
        if (record.pendingRecoveryFromScreenOff == null) {
            val causeValid = if (fromScreenOff) record.primaryScreenOff && record.visibleAtScreenOff
                else record.visibleBeforeCover
            if (!causeValid || record.targetDisplayOn == null) return null
            if (record.targetDisplayOn == true && record.activityVisible) return null
            record.pendingRecoveryFromScreenOff = fromScreenOff
        }
        if (record.pendingRecoveryFromScreenOff != fromScreenOff) return null
        return resumePendingRecoveryLocked(sessionId, record)
    }

    /** Reuses the one authorized intent; ON resumes presentation only, OFF wakes only if not attempted. */
    private fun resumePendingRecoveryLocked(sessionId: String, record: Record): RecoveryCapture? {
        val fromScreenOff = record.pendingRecoveryFromScreenOff ?: return null
        if (!record.request.keepVisible || !record.keepVisibleRestorationEligible || record.closed ||
            record.viewport == null || record.launch == null || record.covered || temporaryOverlayActive) return null
        val displayOn = record.targetDisplayOn ?: return null
        if (displayOn && record.activityVisible) {
            record.pendingRecoveryFromScreenOff = null
            return null
        }
        val scheduled = if (fromScreenOff) record.screenOffRecoveryScheduled else record.coverRecoveryScheduled
        if (scheduled) return null
        val needsWake = !displayOn
        val attempted = if (fromScreenOff) record.screenOffRecoveryAttempted else record.coverRecoveryAttempted
        if (needsWake && attempted) return null
        if (fromScreenOff) record.screenOffRecoveryScheduled = true else record.coverRecoveryScheduled = true
        val task = record.launch ?: return null
        return RecoveryCapture(sessionId, record.generation, ++record.recoveryToken,
            record.displayOwnershipEpoch, record.owner, record.request, task, fromScreenOff, needsWake)
    }

    private fun scheduleRecovery(capture: RecoveryCapture) {
        try {
            expiryScheduler.schedule({
                if (isRecoveryCurrent(capture)) enqueueRecovery(capture)
                else logRecoveryEdge(capture, "stale_before_gateway", false, null)
            }, RECOVERY_SETTLE_MS, TimeUnit.MILLISECONDS)
            logRecoveryEdge(capture, "scheduled", false, null)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            reportRecoveryDispatchFailure(capture)
        }
    }

    private fun isRecoveryCurrent(capture: RecoveryCapture): Boolean = synchronized(lock) {
        val record = sessions[capture.sessionId]
        record != null && !record.closed && record.generation == capture.generation &&
            record.recoveryToken == capture.token && record.request.keepVisible &&
            record.pendingRecoveryFromScreenOff == capture.fromScreenOff &&
            !record.covered && !temporaryOverlayActive &&
            displayOwnershipEpoch() == capture.ownershipEpoch
    }

    /** Consume a cause only at the gateway's exact pre-wake boundary, after all blocking inspection. */
    private fun claimWakeAttempt(capture: RecoveryCapture): SceneRecoveryWakeDecision = synchronized(lock) {
        if (!isRecoveryCurrent(capture)) return@synchronized SceneRecoveryWakeDecision.STALE
        val record = sessions[capture.sessionId] ?: return@synchronized SceneRecoveryWakeDecision.STALE
        if (record.targetDisplayOn == true) {
            if (capture.fromScreenOff) {
                if (!record.screenOffRecoveryScheduled) return@synchronized SceneRecoveryWakeDecision.STALE
                record.screenOffRecoveryAttempted = true
            } else {
                if (!record.coverRecoveryScheduled) return@synchronized SceneRecoveryWakeDecision.STALE
                record.coverRecoveryAttempted = true
            }
            return@synchronized SceneRecoveryWakeDecision.ALREADY_ON
        }
        if (record.targetDisplayOn != false || !capture.needsWake) return@synchronized SceneRecoveryWakeDecision.STALE
        if (capture.fromScreenOff) {
            if (!record.screenOffRecoveryScheduled || record.screenOffRecoveryAttempted) return@synchronized SceneRecoveryWakeDecision.STALE
            record.screenOffRecoveryAttempted = true
        } else {
            if (!record.coverRecoveryScheduled || record.coverRecoveryAttempted) return@synchronized SceneRecoveryWakeDecision.STALE
            record.coverRecoveryAttempted = true
        }
        SceneRecoveryWakeDecision.CLAIMED
    }

    /** Invalidate only delayed work. A gateway call already claimed as attempted is never retried. */
    private fun invalidateScheduledRecoveryLocked(record: Record) {
        if (record.screenOffRecoveryScheduled || record.coverRecoveryScheduled) {
            record.screenOffRecoveryScheduled = false
            record.coverRecoveryScheduled = false
            record.recoveryToken++
        }
    }

    /** Keep recovery work serial; one pending current cause can wait behind an obsolete Binder call. */
    private fun enqueueRecovery(capture: RecoveryCapture) {
        var startDrain = false
        synchronized(recoveryDispatchLock) {
            if (!isRecoveryCurrent(capture)) return
            val queued = pendingRecovery
            if (queued == null || !isRecoveryCurrent(queued) || queued.sessionId == capture.sessionId) {
                pendingRecovery = capture
            } else {
                // At most one Scene is admitted. A different still-current queued capture is
                // therefore impossible under the coordinator contract; keep it and expose the fault.
                reportRecoveryDispatchFailure(capture)
                return
            }
            if (!recoveryDrainScheduled) {
                recoveryDrainScheduled = true
                startDrain = true
            }
        }
        if (startDrain) try {
            recoveryExecutor.execute(::drainRecoveryQueue)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(recoveryDispatchLock) { recoveryDrainScheduled = false }
            reportRecoveryDispatchFailure(capture)
        }
    }

    private fun drainRecoveryQueue() {
        while (true) {
            val capture = synchronized(recoveryDispatchLock) {
                val next = pendingRecovery
                pendingRecovery = null
                if (next == null) recoveryDrainScheduled = false
                next
            } ?: return
            if (!isRecoveryCurrent(capture)) continue
            var wakeAttempted = false
            val result = try {
                gateway.recoverOwnedDisplay(capture.owner, capture.request, capture.task,
                    isCurrent = { isRecoveryCurrent(capture) },
                    onWakeAttempt = {
                        val decision = claimWakeAttempt(capture)
                        if (decision == SceneRecoveryWakeDecision.CLAIMED) wakeAttempted = true
                        decision
                    })
            } catch (_: Exception) { SceneRecoveryResult(SceneError.DISPLAY_UNAVAILABLE) }
            if (result.deferredByOverlay) preserveDeferredRecovery(capture, wakeAttempted)?.let(::scheduleRecovery)
            else if (result.error == null) clearPendingRecovery(capture)
            else {
                if (!wakeAttempted) releaseUnattemptedRecovery(capture)
                clearPendingRecovery(capture)
            }
            logRecoveryEdge(capture, "complete", wakeAttempted, result.error)
            if (result.error == SceneError.BACKEND_UNAVAILABLE) {
                synchronized(lock) {
                    val record = sessions[capture.sessionId]
                    if (record != null && !record.closed && record.generation == capture.generation &&
                        record.recoveryToken == capture.token) emit(capture.sessionId, record, SceneEvent.BackendLost)
                }
            }
        }
    }

    /** An inspection cancelled before wake must not consume the cause or leave it permanently scheduled. */
    private fun releaseUnattemptedRecovery(capture: RecoveryCapture) = synchronized(lock) {
        val record = sessions[capture.sessionId]
        if (record != null && !record.closed && record.generation == capture.generation &&
            record.recoveryToken == capture.token) {
            if (capture.fromScreenOff) record.screenOffRecoveryScheduled = false
            else record.coverRecoveryScheduled = false
        }
    }

    private fun preserveDeferredRecovery(capture: RecoveryCapture, wakeAttempted: Boolean): RecoveryCapture? = synchronized(lock) {
        val record = sessions[capture.sessionId] ?: return@synchronized null
        if (record.closed || record.generation != capture.generation || record.recoveryToken != capture.token ||
            !record.request.keepVisible || record.pendingRecoveryFromScreenOff != capture.fromScreenOff ||
            displayOwnershipEpoch() != capture.ownershipEpoch) return@synchronized null
        if (capture.fromScreenOff) record.screenOffRecoveryScheduled = false else record.coverRecoveryScheduled = false
        if (wakeAttempted) {
            if (capture.fromScreenOff) record.screenOffRecoveryAttempted = true else record.coverRecoveryAttempted = true
        }
        // The exact cause stays live regardless of whether cover began or ended before this
        // asynchronous reply was consumed. If the obstruction is already gone, resume once now.
        if (!record.covered && !temporaryOverlayActive) resumePendingRecoveryLocked(capture.sessionId, record) else null
    }

    private fun clearPendingRecovery(capture: RecoveryCapture) = synchronized(lock) {
        val record = sessions[capture.sessionId] ?: return@synchronized
        if (record.generation != capture.generation || record.recoveryToken != capture.token) return@synchronized
        if (record.pendingRecoveryFromScreenOff == capture.fromScreenOff) {
            record.pendingRecoveryFromScreenOff = null
            if (capture.fromScreenOff) record.screenOffRecoveryScheduled = false else record.coverRecoveryScheduled = false
        }
    }

    private fun reportRecoveryDispatchFailure(capture: RecoveryCapture) {
        logRecoveryEdge(capture, "dispatch_rejected", false, SceneError.BACKEND_UNAVAILABLE)
        synchronized(lock) {
            val record = sessions[capture.sessionId]
            if (record != null && !record.closed && record.generation == capture.generation &&
                record.recoveryToken == capture.token) {
                if (capture.fromScreenOff) record.screenOffRecoveryScheduled = false
                else record.coverRecoveryScheduled = false
                if (record.pendingRecoveryFromScreenOff == capture.fromScreenOff) record.pendingRecoveryFromScreenOff = null
                emit(capture.sessionId, record, SceneEvent.BackendLost)
            }
        }
    }

    private fun logRecoveryEdge(capture: RecoveryCapture, phase: String, wakeAttempted: Boolean, error: SceneError?) {
        runCatching {
            android.util.Log.i("RearSceneRecovery", "recovery phase=$phase cause=${if (capture.fromScreenOff) "screen_off" else "cover_far"} " +
                "generation=${capture.generation} token=${capture.token} wakeAttempted=$wakeAttempted " +
                "task=${capture.task.taskId} display=${capture.task.displayId} result=${error?.name ?: "pending"}")
        }
    }

    private fun finishLeaseResources(sessionId: String, record: Record, reason: SceneCloseReason) {
        runCatching { record.lifecycle?.close() }
        record.lifecycle = null
        legacyServiceLease?.release(sessionId, reason != SceneCloseReason.PREEMPTED)
        emit(sessionId, record, SceneEvent.Closed(reason))
    }

    override fun closeOwnerSessions(owner: SessionOwner, reason: SceneCloseReason) {
        val owned = synchronized(lock) {
            val result = sessions.filterValues { it.owner == owner && !it.closed }.toList()
            result.forEach { (id, record) -> closeLocked(id, record) }
            tickets.entries.removeAll { it.value.owner == owner }
            result
        }
        owned.forEach { (id, record) ->
            record.launch?.let { runCatching { gateway.finishOwnedTask(owner, it) } }
            finishLeaseResources(id, record, reason)
        }
    }

    private fun closeLocked(id: String, record: Record): Record {
        record.closed = true
        tickets.entries.removeAll { it.value.sessionId == id }
        return record
    }

    private fun SceneRequest.derivedCapabilities(): Set<CapabilityRequirement> = buildSet {
        add(CapabilityRequirement("rear.scene", 1))
        if (keepVisible) add(CapabilityRequirement("rear.scene.keep_visible", 1))
        if (region == RearRegion.FULL) add(CapabilityRequirement("rear.region", 1))
    }

    /** Atomically releases a launch reservation when its caller can no longer receive a handle. */
    private fun discardPendingLaunch(sessionId: String, launchTicket: String, reason: SceneCloseReason) {
        val record = synchronized(lock) {
            val ticket = tickets[launchTicket]
            if (ticket?.sessionId != sessionId) return
            tickets.remove(launchTicket)
            sessions[sessionId]?.takeIf { it.generation == ticket.generation && !it.closed }
                ?.let { closeLocked(sessionId, it) }
        } ?: return
        finishLeaseResources(sessionId, record, reason)
    }

    private fun expirePendingLaunch(sessionId: String, launchTicket: String) {
        val record = synchronized(lock) {
            val ticket = tickets[launchTicket] ?: return
            if (ticket.sessionId != sessionId || ticket.consumed || clock() < ticket.expiresAt) return
            tickets.remove(launchTicket)
            sessions[sessionId]?.takeIf { !it.closed }?.let { closeLocked(sessionId, it) }
        } ?: return
        record.launch?.let { runCatching { gateway.finishOwnedTask(record.owner, it) } }
        finishLeaseResources(sessionId, record, SceneCloseReason.LAUNCH_EXPIRED)
    }

    private fun emit(sessionId: String, record: Record, event: SceneEvent) {
        val observer = synchronized(lock) {
            if (event is SceneEvent.Closed) sessions.remove(sessionId, record)
            if (record.closed && event !is SceneEvent.Closed) null else record.observer
        }
        if (observer == null) return
        callbackExecutor.execute {
            runCatching { observer.onSceneEvent(sessionId, record.generation, event) }
        }
    }

    companion object {
        const val PLATFORM_CONTRACT = "remrss.platform/1"
        const val LAUNCH_TICKET_TTL_MS = 5_000L
        private const val RECOVERY_SETTLE_MS = 350L
        private val secureRandom = SecureRandom()
        private fun newToken(): String = ByteArray(24).also(secureRandom::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
