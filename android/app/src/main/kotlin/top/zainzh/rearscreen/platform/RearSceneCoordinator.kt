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
                            cachedRearOn: Boolean?, isCurrent: () -> Boolean,
                            onWakeAttempt: (physicalRearOn: Boolean?) -> SceneRecoveryWakeDecision): SceneRecoveryResult =
        if (!isCurrent()) SceneRecoveryResult(SceneError.STALE_SESSION) else when (onWakeAttempt(cachedRearOn)) {
            SceneRecoveryWakeDecision.STALE -> SceneRecoveryResult(SceneError.STALE_SESSION)
            SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY -> SceneRecoveryResult(SceneError.DISPLAY_UNAVAILABLE, awaitDisplayOn = true)
            SceneRecoveryWakeDecision.ALREADY_ON -> SceneRecoveryResult()
            SceneRecoveryWakeDecision.CLAIMED -> SceneRecoveryResult(prepareDisplay(owner, request))
        }
    fun launchNewTask(owner: SessionOwner, request: SceneRequest, launchTicket: String, expiresAtElapsedRealtimeMs: Long): LaunchedScene?
    fun verifyLaunchedTask(owner: SessionOwner, expected: LaunchedScene, report: SceneWindowReport): Boolean
    fun finishOwnedTask(owner: SessionOwner, task: LaunchedScene)
}

data class LaunchedScene(val component: android.content.ComponentName, val taskId: Int, val displayId: Int)

internal enum class SceneRecoveryWakeDecision { STALE, WAIT_FOR_DISPLAY, ALREADY_ON, CLAIMED }
internal data class SceneRecoveryResult(
    val error: SceneError? = null,
    val deferredByOverlay: Boolean = false,
    /** The one allowed wake did not reach rear ON; retain the cause until a real ON edge. */
    val awaitDisplayOn: Boolean = false,
    /** Focus was accepted but the exact Activity/task snapshot has not caught up; wait for a bounded confirmation. */
    val awaitSceneVisible: Boolean = false,
    /** The rear task snapshot was not yet uniquely classifiable; permit one same-cause recheck. */
    val awaitTaskVisibilityClassification: Boolean = false,
    /** A returned task-flow result proves it passed initial classification/focus before waiting on power. */
    val taskVisibilityClassificationComplete: Boolean = false,
    /** TaskService confirmed selected physical rear ON and this exact task was presented on it. */
    val verifiedPhysicalPresentation: Boolean = false,
)

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
    private val legacyServiceLease: SceneLegacyLeasePort? = null,
    private val onSceneCommitted: () -> Unit = {}
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
        /** Increments only on a real hidden→visible Activity-window report. */
        var activityVisibilitySequence: Long = 0L,
        var targetDisplayOn: Boolean? = null,
        /** Exact recovery backend verified ON when the monitor cache was stale; cleared by any power signal. */
        var backendVerifiedRearOn: Boolean = false,
        /** Monotonic count of physical power callbacks, used to preserve one ON edge racing a reply. */
        var rearPowerSignalSequence: Long = 0L,
        var primaryScreenOff: Boolean = false,
        var covered: Boolean = false,
        /** A Scene that was actually visible can be returned while the rear display is already ON. */
        var sceneRestorationEligible: Boolean = false,
        /** Separate permission for a keepVisible lease to wake an OFF rear display once. */
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
        /** Monotonic identity for one return cause; capture tokens may change across temporary overlays. */
        var recoveryCauseSequence: Long = 0L,
        /** Same-cause phase: once focus needs presentation confirmation, every resume keeps that mode. */
        var recoveryPresentationPhase: Boolean = false,
        /** One stable reclassification may follow an ambiguous all-display snapshot; survives cover deferrals. */
        var taskVisibilityRecheckPending: Boolean = false,
        /** Spent is distinct from pending: a later R7 window confirmation cannot regrant the same-cause retry. */
        var taskVisibilityRecheckSpent: Boolean = false,
        /** Physical rear backend rejected ON; wait for a later real display-power edge, never repoll/retry from cache. */
        var awaitFreshRearOn: Boolean = false,
        /** A non-visible post-focus snapshot parks until one bounded check or a new real window-visible edge. */
        var awaitSceneVisibleAfterPresentation: Boolean = false,
        var presentationAwaitVisibilitySequence: Long = 0L,
        var lastPresentationRetryVisibilitySequence: Long? = null,
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
        val causeSequence: Long,
        val needsWake: Boolean,
        val rearPowerSignalSequence: Long,
        val activityVisibilitySequence: Long,
        val taskVisibilityRecheck: Boolean
    )
    private data class OverlayLease(
        val overlayId: Long,
        val sceneSessionIdAtStart: String?,
        val sceneGenerationAtStart: Long?,
        val sceneOwnershipEpochAtStart: Long,
        val displayOwnershipEpochAtStart: Long,
        val suppressLegacyRestore: Boolean,
        /** Exact live Scene had previously rendered; wake eligibility is checked separately. */
        val sceneHadVisibleLeaseAtStart: Boolean
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
        if ((!isLiveReadyScene && clock() >= ticket.expiresAt) || !sameComponent(ticket.component, component) ||
            record.closed || record.generation != ticket.generation || !sameComponent(record.request.component, component)) return@synchronized null
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
        if (launched == null || !sameComponent(launched.component, request.component) || launched.taskId < 0 || launched.displayId <= 0) {
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
        onSceneCommitted()
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
            if (!sameComponent(ticket.component, window.component)) return SceneOperationResult(false, SceneError.CAPABILITY_DENIED)
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
            if (current.generation != generation || !sameComponent(launch.component, window.component) ||
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
            if (!previousActivityVisible && visible) current.activityVisibilitySequence++
            if (visible) current.visibleAtScreenOff = current.visibleAtScreenOff || current.primaryScreenOff
            updateEffectiveVisibilityLocked(sessionId, current)
            if (visible && current.awaitSceneVisibleAfterPresentation &&
                current.activityVisibilitySequence > current.presentationAwaitVisibilitySequence) {
                current.awaitSceneVisibleAfterPresentation = false
                current.lastPresentationRetryVisibilitySequence = current.activityVisibilitySequence
                recovery = resumePendingRecoveryLocked(sessionId, current, presentationOnly = true)
            }
            if (!visible && current.primaryScreenOff && current.visibleAtScreenOff && current.targetDisplayOn == true &&
                current.request.keepVisible && current.keepVisibleRestorationEligible) {
                if (current.pendingRecoveryFromScreenOff == null) beginPendingRecoveryCauseLocked(current, true)
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
    internal fun beginTransientOverlay(
        overlayId: Long,
        replacesOverlayId: Long? = null,
        inheritedReturnWasSuppressed: Boolean = false
    ): Boolean {
        val suppressLegacyRestore = synchronized(lock) {
            val prior = activeOverlayLease?.takeIf {
                it.overlayId == replacesOverlayId && activeTransientOverlayId == replacesOverlayId &&
                    !overlayOwnershipWasRevokedLocked(it)
            }
            val priorEntry = prior?.let { lease ->
                val id = lease.sceneSessionIdAtStart
                val record = id?.let(sessions::get)
                if (id != null && record != null && !record.closed && record.generation == lease.sceneGenerationAtStart)
                    Triple(lease, id, record)
                else null
            }
            val entry: Pair<String, Record>? = priorEntry?.let { it.second to it.third }
                ?: if (replacesOverlayId == null) sessions.entries.firstOrNull { (_, record) -> !record.closed }?.let { it.key to it.value }
                else null
            val sceneOwnsAtStart = entry != null
            val priorOverlayStillSuppresses = activeOverlayLease?.let(::overlaySuppressesLegacyRestoreLocked) == true
            val record = entry?.second
            val recoveryEligible = priorEntry?.first?.sceneHadVisibleLeaseAtStart ?: (
                record != null && record.sceneRestorationEligible &&
                    record.viewport != null && record.launch != null &&
                    record.displayOwnershipEpoch == displayOwnershipEpoch())
            activeOverlayLease = OverlayLease(
                overlayId, entry?.first, entry?.second?.generation, sceneOwnershipEpoch, displayOwnershipEpoch(),
                sceneOwnsAtStart || priorOverlayStillSuppresses || inheritedReturnWasSuppressed,
                recoveryEligible
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
            val endedLease = if (!active) activeOverlayLease?.takeIf { it.overlayId == overlayId } else null
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
                // A notification/charging Activity can stop the Scene Activity while it owns
                // the top task. Proximity's visibleBeforeCover does not describe that overlay.
                // Re-arm only the exact previously visible, still-live Scene lease; its normal
                // recovery gateway handles ON presentation and OFF one-shot recovery separately.
                if (!active && endedLease?.sceneHadVisibleLeaseAtStart == true &&
                    endedLease.sceneSessionIdAtStart == sessionId &&
                    endedLease.sceneGenerationAtStart == record.generation &&
                    !overlayOwnershipWasRevokedLocked(endedLease) && record.sceneRestorationEligible &&
                    record.viewport != null && record.launch != null &&
                    record.pendingRecoveryFromScreenOff == null) {
                    when (record.targetDisplayOn) {
                        // Even if Activity/window callbacks say visible, the task may only be
                        // resumed behind the sleeping/covering rear host. The exact-task gateway
                        // decides whether any present is needed; window visibility cannot consume
                        // this overlay's return lease on its own.
                        true -> beginPendingRecoveryCauseLocked(record, false)
                        false -> beginPendingRecoveryCauseLocked(record, (record.primaryScreenOff && record.visibleAtScreenOff))
                        null -> Unit
                    }
                }
                if (!active && !record.covered && record.pendingRecoveryFromScreenOff == null && record.request.keepVisible) {
                    val needsRecovery = record.targetDisplayOn == false || (record.targetDisplayOn == true && !record.activityVisible)
                    if (needsRecovery) beginPendingRecoveryCauseLocked(record, when {
                        record.primaryScreenOff && record.visibleAtScreenOff -> true
                        record.visibleBeforeCover -> false
                        else -> null
                    } ?: return@forEach)
                }
                if (!active && !record.covered) resumePendingRecoveryLocked(sessionId, record)?.let(recoveryCandidates::add)
            }
        }
        recoveryCandidates.forEach(::scheduleRecovery)
    }

    private fun overlaySuppressesLegacyRestoreLocked(overlay: OverlayLease): Boolean =
        overlay.suppressLegacyRestore || sceneOwnershipEpoch != overlay.sceneOwnershipEpochAtStart ||
            displayOwnershipEpoch() != overlay.displayOwnershipEpochAtStart

    private fun overlayOwnershipWasRevokedLocked(overlay: OverlayLease): Boolean =
        sceneOwnershipEpoch != overlay.sceneOwnershipEpochAtStart ||
            displayOwnershipEpoch() != overlay.displayOwnershipEpochAtStart

    /** Applies one authoritative visibility calculation for Activity, rear power, occlusion and overlay state. */
    private fun updateEffectiveVisibilityLocked(
        sessionId: String,
        record: Record,
        hiddenReason: SceneSuspendReason = if (record.covered || temporaryOverlayActive) SceneSuspendReason.COVERED else SceneSuspendReason.DISPLAY_OFF
    ) {
        val rearAvailable = record.targetDisplayOn != false || record.backendVerifiedRearOn
        val visible = record.activityVisible && rearAvailable && !record.covered && !temporaryOverlayActive
        val nextReason = if (visible) null else hiddenReason
        if (record.visible == visible && record.hiddenReason == nextReason) return
        val visibilityChanged = record.visible != visible
        record.visible = visible
        record.hiddenReason = nextReason
        record.presentationRevision++
        if (visible) {
            record.sceneRestorationEligible = true
            if (record.request.keepVisible) record.keepVisibleRestorationEligible = true
            // Window visibility is not task-level foreground proof: on rear displays an Activity
            // may resume while the physical panel is in DOZE or another system task is actually
            // presented. Leave an already-qualified return cause to the exact-task recovery
            // gateway, which has an authoritative `already_scene_visible` no-op result.
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
                        if (record.pendingRecoveryFromScreenOff == null) record.screenOffRecoveryAttempted = false
                    }
                    if (record.request.keepVisible && record.keepVisibleRestorationEligible && record.visibleAtScreenOff &&
                        record.targetDisplayOn == true && !record.activityVisible && record.pendingRecoveryFromScreenOff == null) {
                        beginPendingRecoveryCauseLocked(record, true)
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
                        // Keep the same cause's consumed one-shot budget across callbacks.
                    }
                    updateEffectiveVisibilityLocked(sessionId, record)
                    if (record.pendingRecoveryFromScreenOff != null) recovery = resumePendingRecoveryLocked(sessionId, record)
                }
                is SceneDisplaySignal.RearDisplayPower -> {
                    val wasOn = record.targetDisplayOn
                    record.rearPowerSignalSequence++
                    record.targetDisplayOn = signal.on
                    record.backendVerifiedRearOn = false
                    if (signal.on) record.awaitFreshRearOn = false
                    if (!signal.on && wasOn == true && record.pendingRecoveryFromScreenOff == null) {
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
                        runCatching { android.util.Log.i("RearSceneProximity", "core_edge state=covered source=rear_proximity visibleBefore=${record.visible} revision=${record.presentationRevision + 1}") }
                        invalidateScheduledRecoveryLocked(record)
                        record.coverRecoveryScheduled = false
                        if (record.pendingRecoveryFromScreenOff == null) record.coverRecoveryAttempted = false
                        updateEffectiveVisibilityLocked(sessionId, record, SceneSuspendReason.COVERED)
                    } else if (!signal.near && record.covered) {
                        record.covered = false
                        runCatching { android.util.Log.i("RearSceneProximity", "core_edge state=uncovered source=rear_proximity visibleBefore=${record.visible} revision=${record.presentationRevision + 1}") }
                        updateEffectiveVisibilityLocked(sessionId, record)
                        if (record.pendingRecoveryFromScreenOff == null && record.request.keepVisible && !record.visible) {
                            beginPendingRecoveryCauseLocked(record, record.primaryScreenOff && record.visibleAtScreenOff)
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
            beginPendingRecoveryCauseLocked(record, fromScreenOff)
        }
        if (record.pendingRecoveryFromScreenOff != fromScreenOff) return null
        return resumePendingRecoveryLocked(sessionId, record)
    }

    /** Start one cause identity and reset only the budget/phase belonging to the previous cause. */
    private fun beginPendingRecoveryCauseLocked(record: Record, fromScreenOff: Boolean?) {
        if (fromScreenOff == null) return
        if (record.pendingRecoveryFromScreenOff != null) return
        record.recoveryCauseSequence++
        record.pendingRecoveryFromScreenOff = fromScreenOff
        record.recoveryPresentationPhase = false
        record.taskVisibilityRecheckPending = false
        record.taskVisibilityRecheckSpent = false
        record.awaitFreshRearOn = false
        record.awaitSceneVisibleAfterPresentation = false
        record.presentationAwaitVisibilitySequence = 0L
        record.lastPresentationRetryVisibilitySequence = null
        record.screenOffRecoveryScheduled = false
        record.coverRecoveryScheduled = false
        record.screenOffRecoveryAttempted = false
        record.coverRecoveryAttempted = false
    }

    /** Reuses one exact Scene intent; keepVisible is required only for an OFF-screen wake. */
    private fun resumePendingRecoveryLocked(
        sessionId: String,
        record: Record,
        presentationOnly: Boolean = false,
    ): RecoveryCapture? {
        val fromScreenOff = record.pendingRecoveryFromScreenOff ?: return null
        if (presentationOnly) record.recoveryPresentationPhase = true
        val presentationPhase = record.recoveryPresentationPhase
        if (!record.sceneRestorationEligible || record.closed ||
            record.viewport == null || record.launch == null || record.covered || temporaryOverlayActive) return null
        val displayOn = record.targetDisplayOn ?: return null
        if (record.awaitFreshRearOn) return null
        if (record.awaitSceneVisibleAfterPresentation) return null
        // keepVisible=false may return only after the rear display is independently ON. Preserve
        // its same-lease pending cause while OFF so a real ON edge can resume exact-task restore.
        val wakeEligible = record.request.keepVisible && record.keepVisibleRestorationEligible
        if (!displayOn && !presentationPhase && !wakeEligible) return null
        // Activity/window visibility is not proof that this exact task is in front of the
        // physical rear display (the panel may be in DOZE while Android still reports the
        // Activity resumed). Keep the return cause until the exact-task gateway confirms the
        // task is already visible or presents it. That gateway is also the no-duplicate fast path.
        val scheduled = if (fromScreenOff) record.screenOffRecoveryScheduled else record.coverRecoveryScheduled
        if (scheduled) return null
        val attempted = if (fromScreenOff) record.screenOffRecoveryAttempted else record.coverRecoveryAttempted
        // Presentation confirmation is a persistent same-cause phase, not a blanket ban on
        // an unused one-shot wake: ALREADY_ON can race a later OFF before any wake was claimed.
        val needsWake = !displayOn && wakeEligible && !attempted
        if (needsWake && attempted) return null
        if (fromScreenOff) record.screenOffRecoveryScheduled = true else record.coverRecoveryScheduled = true
        val task = record.launch ?: return null
        return RecoveryCapture(sessionId, record.generation, ++record.recoveryToken,
            record.displayOwnershipEpoch, record.owner, record.request, task, fromScreenOff,
            record.recoveryCauseSequence, needsWake,
            record.rearPowerSignalSequence, record.activityVisibilitySequence,
            record.taskVisibilityRecheckPending)
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
            record.owner == capture.owner && record.launch == capture.task && record.request == capture.request &&
            record.recoveryToken == capture.token && record.sceneRestorationEligible &&
            (!capture.needsWake || record.request.keepVisible && record.keepVisibleRestorationEligible) &&
            record.pendingRecoveryFromScreenOff == capture.fromScreenOff &&
            record.recoveryCauseSequence == capture.causeSequence &&
            !record.covered && !temporaryOverlayActive &&
            displayOwnershipEpoch() == capture.ownershipEpoch
    }

    /** Consume a cause only at the gateway's exact pre-wake boundary, after all blocking inspection. */
    private fun claimWakeAttempt(capture: RecoveryCapture, physicalRearOn: Boolean?): SceneRecoveryWakeDecision = synchronized(lock) {
        if (!isRecoveryCurrent(capture)) return@synchronized SceneRecoveryWakeDecision.STALE
        val record = sessions[capture.sessionId] ?: return@synchronized SceneRecoveryWakeDecision.STALE
        // This exact capture is only a read/presentation recheck. A fresh OFF or unknown
        // observation must wait for a real ON edge instead of consuming the unused wake budget.
        if (capture.taskVisibilityRecheck && physicalRearOn != true)
            return@synchronized SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY
        val rearIsOn = physicalRearOn ?: record.targetDisplayOn
        if (rearIsOn == true) {
            if (capture.fromScreenOff) {
                if (!record.screenOffRecoveryScheduled) return@synchronized SceneRecoveryWakeDecision.STALE
            } else {
                if (!record.coverRecoveryScheduled) return@synchronized SceneRecoveryWakeDecision.STALE
            }
            return@synchronized SceneRecoveryWakeDecision.ALREADY_ON
        }
        if (rearIsOn != false) return@synchronized SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY
        if (!record.request.keepVisible || !record.keepVisibleRestorationEligible)
            return@synchronized SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY
        if (capture.fromScreenOff) {
            if (!record.screenOffRecoveryScheduled) return@synchronized SceneRecoveryWakeDecision.STALE
            if (record.screenOffRecoveryAttempted) return@synchronized SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY
            record.screenOffRecoveryAttempted = true
        } else {
            if (!record.coverRecoveryScheduled) return@synchronized SceneRecoveryWakeDecision.STALE
            if (record.coverRecoveryAttempted) return@synchronized SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY
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
                    cachedRearOn = synchronized(lock) { sessions[capture.sessionId]?.targetDisplayOn },
                    isCurrent = { isRecoveryCurrent(capture) },
                    onWakeAttempt = { physicalRearOn ->
                        val decision = claimWakeAttempt(capture, physicalRearOn)
                        if (decision == SceneRecoveryWakeDecision.CLAIMED) wakeAttempted = true
                        decision
                    })
            } catch (_: Exception) { SceneRecoveryResult(SceneError.DISPLAY_UNAVAILABLE) }
            if (result.deferredByOverlay) preserveDeferredRecovery(capture, wakeAttempted)?.let(::scheduleRecovery)
            else if (result.awaitTaskVisibilityClassification) {
                if (capture.taskVisibilityRecheck || taskVisibilityRecheckSpent(capture)) {
                    logRecoveryEdge(capture, "task_visibility_recheck_exhausted", wakeAttempted, null)
                    clearPendingRecovery(capture)
                } else scheduleTaskVisibilityRecheck(capture)?.let(::scheduleRecovery)
            } else {
                // Spend only when the TaskFlow result says it crossed the actual task
                // classification/action boundary. awaitDisplayOn alone is insufficient:
                // it covers both a preflight power refusal (unclassified) and a post-focus
                // physical-authority refusal (classified and acted on).
                if (capture.taskVisibilityRecheck && result.taskVisibilityClassificationComplete)
                    finishTaskVisibilityRecheck(capture)
                if (result.awaitSceneVisible) retryOrAwaitSceneVisibility(capture)?.let(::scheduleRecovery)
                else if (result.error == null) {
                    if (result.verifiedPhysicalPresentation && !acceptVerifiedPhysicalPresentation(capture)) {
                        // A newer real OFF observation beats an older exact-task ON reply. Keep the
                        // original cause parked for that display's next real ON edge.
                        retainRecoveryUntilDisplayOn(capture, wakeAttempted)?.let(::scheduleRecovery)
                    } else clearPendingRecovery(capture)
                }
                else if (result.awaitDisplayOn && result.error == SceneError.DISPLAY_UNAVAILABLE) {
                    // A physical rear query may reject a no-wake presentation while Core still
                    // caches ON. Keep the exact cause until a new real ON edge; never requeue from
                    // that stale cache. If this call attempted a wake, that one-shot remains spent.
                    retainRecoveryUntilDisplayOn(capture, wakeAttempted)?.let(::scheduleRecovery)
                }
                else {
                    if (!wakeAttempted) releaseUnattemptedRecovery(capture)
                    clearPendingRecovery(capture)
                }
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

    /** Preserve this exact return cause while granting one delayed snapshot reclassification. */
    private fun scheduleTaskVisibilityRecheck(capture: RecoveryCapture): RecoveryCapture? = synchronized(lock) {
        val record = sessions[capture.sessionId] ?: return@synchronized null
        if (record.closed || record.generation != capture.generation || record.owner != capture.owner ||
            record.request != capture.request || record.launch != capture.task ||
            record.recoveryToken != capture.token || !record.sceneRestorationEligible ||
            record.pendingRecoveryFromScreenOff != capture.fromScreenOff ||
            record.recoveryCauseSequence != capture.causeSequence ||
            displayOwnershipEpoch() != capture.ownershipEpoch || record.covered || temporaryOverlayActive) return@synchronized null
        if (record.taskVisibilityRecheckPending || record.taskVisibilityRecheckSpent) return@synchronized null
        if (capture.fromScreenOff) record.screenOffRecoveryScheduled = false else record.coverRecoveryScheduled = false
        record.taskVisibilityRecheckPending = true
        record.recoveryPresentationPhase = true
        logRecoveryEdge(capture, "task_visibility_recheck_scheduled", false, null)
        resumePendingRecoveryLocked(capture.sessionId, record, presentationOnly = true)
    }

    /** A non-deferred response to the one recheck has classified the current snapshot. */
    private fun finishTaskVisibilityRecheck(capture: RecoveryCapture) = synchronized(lock) {
        val record = sessions[capture.sessionId] ?: return@synchronized
        if (record.generation == capture.generation && record.owner == capture.owner &&
            record.pendingRecoveryFromScreenOff == capture.fromScreenOff &&
            record.recoveryCauseSequence == capture.causeSequence && record.recoveryToken == capture.token) {
            record.taskVisibilityRecheckPending = false
            record.taskVisibilityRecheckSpent = true
        }
    }

    private fun taskVisibilityRecheckSpent(capture: RecoveryCapture): Boolean = synchronized(lock) {
        val record = sessions[capture.sessionId] ?: return@synchronized false
        record.generation == capture.generation && record.owner == capture.owner &&
            record.pendingRecoveryFromScreenOff == capture.fromScreenOff &&
            record.recoveryCauseSequence == capture.causeSequence && record.recoveryToken == capture.token &&
            record.taskVisibilityRecheckSpent
    }

    /** Retry once per real window-visible epoch; otherwise wait for Activity evidence, never poll/focus-loop. */
    private fun retryOrAwaitSceneVisibility(capture: RecoveryCapture): RecoveryCapture? = synchronized(lock) {
        val record = sessions[capture.sessionId] ?: return@synchronized null
        if (record.closed || record.generation != capture.generation ||
            !record.sceneRestorationEligible || record.pendingRecoveryFromScreenOff != capture.fromScreenOff ||
            record.recoveryCauseSequence != capture.causeSequence || record.owner != capture.owner ||
            record.request != capture.request || record.launch != capture.task ||
            displayOwnershipEpoch() != capture.ownershipEpoch) return@synchronized null
        // Focus completed but the exact window is not ready. Keep this phase on the cause itself,
        // even if a temporary overlay already invalidated this capture token; its eventual end
        // will resume the same cause in presentation mode rather than reverting to a cache-OFF gate.
        record.recoveryPresentationPhase = true
        if (record.recoveryToken != capture.token || record.covered || temporaryOverlayActive) return@synchronized null
        if (capture.fromScreenOff) record.screenOffRecoveryScheduled = false else record.coverRecoveryScheduled = false
        val visibilitySequence = record.activityVisibilitySequence
        if (record.lastPresentationRetryVisibilitySequence != visibilitySequence) {
            record.lastPresentationRetryVisibilitySequence = visibilitySequence
            record.awaitSceneVisibleAfterPresentation = false
            return@synchronized resumePendingRecoveryLocked(capture.sessionId, record, presentationOnly = true)
        }
        record.awaitSceneVisibleAfterPresentation = true
        record.presentationAwaitVisibilitySequence = visibilitySequence
        logRecoveryEdge(capture, "awaiting_scene_window_visible", wakeAttempted = false, error = null)
        null
    }

    /** Apply physical-ON evidence only to this still-current exact recovery capture. */
    private fun acceptVerifiedPhysicalPresentation(capture: RecoveryCapture): Boolean = synchronized(lock) {
        val record = sessions[capture.sessionId] ?: return@synchronized false
        if (!isRecoveryCurrent(capture)) return@synchronized false
        if (record.rearPowerSignalSequence != capture.rearPowerSignalSequence && record.targetDisplayOn != true)
            return@synchronized false
        record.backendVerifiedRearOn = true
        updateEffectiveVisibilityLocked(capture.sessionId, record)
        true
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

    private fun retainRecoveryUntilDisplayOn(capture: RecoveryCapture, wakeAttempted: Boolean): RecoveryCapture? =
        synchronized(lock) {
            val record = sessions[capture.sessionId]
            if (record == null || record.closed || record.generation != capture.generation ||
                record.recoveryToken != capture.token || !record.sceneRestorationEligible ||
                record.pendingRecoveryFromScreenOff != capture.fromScreenOff ||
                record.recoveryCauseSequence != capture.causeSequence ||
                displayOwnershipEpoch() != capture.ownershipEpoch) return@synchronized null
            if (capture.fromScreenOff) {
                record.screenOffRecoveryScheduled = false
                if (wakeAttempted) record.screenOffRecoveryAttempted = true
            } else {
                record.coverRecoveryScheduled = false
                if (wakeAttempted) record.coverRecoveryAttempted = true
            }
            // If a real ON callback arrived after this capture began, it may have raced ahead
            // of the backend's final OFF reply. Admit exactly one no-wake confirmation using
            // that newer observation. A rejection with no newer ON remains parked, avoiding
            // a stable-DOZE retry loop driven by Core's stale logical ON cache.
            val lateOnObserved = record.rearPowerSignalSequence > capture.rearPowerSignalSequence &&
                record.targetDisplayOn == true
            record.awaitFreshRearOn = !lateOnObserved
            logRecoveryEdge(capture, "awaiting_real_display_on", wakeAttempted, SceneError.DISPLAY_UNAVAILABLE)
            if (lateOnObserved) resumePendingRecoveryLocked(capture.sessionId, record) else null
        }

    private fun preserveDeferredRecovery(capture: RecoveryCapture, wakeAttempted: Boolean): RecoveryCapture? = synchronized(lock) {
        val record = sessions[capture.sessionId] ?: return@synchronized null
        if (record.closed || record.generation != capture.generation || record.recoveryToken != capture.token ||
            !record.sceneRestorationEligible ||
            (capture.needsWake && (!record.request.keepVisible || !record.keepVisibleRestorationEligible)) ||
            record.pendingRecoveryFromScreenOff != capture.fromScreenOff ||
            record.recoveryCauseSequence != capture.causeSequence ||
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
        if (record.pendingRecoveryFromScreenOff == capture.fromScreenOff &&
            record.recoveryCauseSequence == capture.causeSequence) {
            record.pendingRecoveryFromScreenOff = null
            record.recoveryCauseSequence++
            record.recoveryPresentationPhase = false
            record.taskVisibilityRecheckPending = false
            record.taskVisibilityRecheckSpent = false
            record.screenOffRecoveryAttempted = false
            record.coverRecoveryAttempted = false
            record.awaitFreshRearOn = false
            record.awaitSceneVisibleAfterPresentation = false
            record.presentationAwaitVisibilitySequence = 0L
            record.lastPresentationRetryVisibilitySequence = null
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
                if (record.pendingRecoveryFromScreenOff == capture.fromScreenOff &&
                    record.recoveryCauseSequence == capture.causeSequence) {
                    record.pendingRecoveryFromScreenOff = null
                    record.recoveryCauseSequence++
                    record.recoveryPresentationPhase = false
                    record.taskVisibilityRecheckPending = false
                    record.taskVisibilityRecheckSpent = false
                    record.screenOffRecoveryAttempted = false
                    record.coverRecoveryAttempted = false
                    record.awaitFreshRearOn = false
                    record.awaitSceneVisibleAfterPresentation = false
                    record.presentationAwaitVisibilitySequence = 0L
                    record.lastPresentationRetryVisibilitySequence = null
                }
                emit(capture.sessionId, record, SceneEvent.BackendLost)
            }
        }
    }

    private fun logRecoveryEdge(capture: RecoveryCapture, phase: String, wakeAttempted: Boolean, error: SceneError?) {
        runCatching {
            val state = synchronized(lock) {
                sessions[capture.sessionId]?.takeIf { it.generation == capture.generation }?.let { record ->
                    " targetOn=${record.targetDisplayOn} activityVisible=${record.activityVisible} " +
                        "effectiveVisible=${record.visible} covered=${record.covered} " +
                        "pending=${record.pendingRecoveryFromScreenOff}"
                } ?: " state=stale"
            }
            android.util.Log.i("RearSceneRecovery", "recovery phase=$phase cause=${if (capture.fromScreenOff) "screen_off" else "cover_far"} " +
                "generation=${capture.generation} token=${capture.token} wakeAttempted=$wakeAttempted " +
                "task=${capture.task.taskId} display=${capture.task.displayId} result=${error?.name ?: "pending"}$state")
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

    /** Fast exact-object path keeps pure JVM fakes independent of Android's mocked equals(). */
    private fun sameComponent(left: android.content.ComponentName, right: android.content.ComponentName): Boolean =
        left === right || left == right

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
