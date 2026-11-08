package top.zainzh.rearscreen

import top.zainzh.rearscreen.timer.StageTimerController

enum class AnimationRestoreDisposition {
    RECOVERY,
    RESTORE_EXISTING_MODULE_TASK,
    SUPPRESS_STALE_MODULE_TASK,
    LEGACY_FALLBACK
}

internal data class ModuleRearAnimationTaskIdentity(
    val taskId: Int, val moduleId: String, val versionCode: Int, val generation: String
)

internal data class ModuleRearAnimationTaskCapture(
    val taskId: Int, val identity: ModuleRearAnimationTaskIdentity?
)

/** Keeps the exact module generation associated with each task/animation callback. */
internal class ModuleRearAnimationTaskHistory {
    private val identities = LinkedHashMap<Int, ModuleRearAnimationTaskIdentity>()
    private val captures = LinkedHashMap<Long, ModuleRearAnimationTaskCapture>()

    @Synchronized fun attach(identity: ModuleRearAnimationTaskIdentity) {
        identities[identity.taskId] = identity
        while (identities.size > 32) identities.remove(identities.keys.first())
    }

    @Synchronized fun update(identity: ModuleRearAnimationTaskIdentity): Boolean {
        val current = identities[identity.taskId] ?: return false
        if (current.moduleId != identity.moduleId || current.versionCode != identity.versionCode) return false
        identities[identity.taskId] = identity
        return true
    }

    @Synchronized fun identityFor(taskId: Int) = identities[taskId]

    @Synchronized fun capture(animationId: Long, taskId: Int) {
        captures[animationId] = ModuleRearAnimationTaskCapture(taskId, identities[taskId])
        while (captures.size > 32) captures.remove(captures.keys.first())
    }

    @Synchronized fun captureFor(animationId: Long) = captures[animationId]
}

internal object ModuleRearCoverSessionPolicy {
    enum class Update { KEEP_BLACK, SHOW_FINAL_STATE, FINISH_STALE_SESSION }
    fun onSessionUpdate(isBlackCovered: Boolean, leaseGeneration: String?, stateGeneration: String,
                       running: Boolean, leaseCurrent: Boolean): Update {
        if (!isBlackCovered) return Update.KEEP_BLACK
        if (leaseGeneration.isNullOrBlank() || stateGeneration != leaseGeneration) return Update.FINISH_STALE_SESSION
        return if (running && leaseCurrent) Update.KEEP_BLACK else Update.SHOW_FINAL_STATE
    }
}

/** A notification may leave a timer Activity underneath its own rear-screen page. */
internal object ModuleRearAnimationRestorePolicy {
    fun canRestoreExistingTask(expectedTaskId: Int, currentTaskId: Int, physicalCastRunning: Boolean,
                               virtualCastRunning: Boolean) = expectedTaskId > 0 && currentTaskId == expectedTaskId &&
        !physicalCastRunning && !virtualCastRunning

    fun acceptedLeaseIdentity(
        existing: ModuleRearAnimationTaskIdentity?, taskId: Int, moduleId: String,
        versionCode: Int, generation: String
    ): ModuleRearAnimationTaskIdentity? {
        if (existing == null || existing.taskId != taskId || existing.moduleId != moduleId ||
            existing.versionCode != versionCode || generation.isBlank()) return null
        return existing.copy(generation = generation)
    }

    fun animationRestoreRoute(
        isModuleTask: Boolean,
        identityMatches: Boolean,
        moduleEnabled: Boolean,
        versionMatches: Boolean,
        generationMatches: Boolean,
        status: String,
        recoveryLeaseValid: Boolean
    ): AnimationRestoreDisposition {
        if (!isModuleTask) return AnimationRestoreDisposition.LEGACY_FALLBACK
        if (!identityMatches || !moduleEnabled || !versionMatches || !generationMatches || status == "STOPPED" || status == "IDLE") {
            return AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK
        }
        if (status == "RUNNING") return if (recoveryLeaseValid) AnimationRestoreDisposition.RECOVERY
            else AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK
        return if (status == "PAUSED" || status == "FINISHED" || status == "INTERRUPTED")
            AnimationRestoreDisposition.RESTORE_EXISTING_MODULE_TASK
        else AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK
    }

    fun shouldFinishAfterSessionChange(
        intent: android.content.Intent?, moduleId: String, leaseGeneration: String?,
        stateGeneration: String, status: String
    ): Boolean {
        if (intent?.action != StageTimerController.ACTION_SESSION_CHANGED ||
            intent.getStringExtra("module_id") != moduleId) return false
        return shouldFinishStaleTimerTask(leaseGeneration, stateGeneration, status)
    }

    fun shouldFinishStaleTimerTask(leaseGeneration: String?, stateGeneration: String, status: String): Boolean {
        if (leaseGeneration.isNullOrBlank()) return false
        if (stateGeneration != leaseGeneration) return true
        return status == "STOPPED"
    }
}

/** Immutable identity captured by every asynchronous display operation. */
data class ModuleDisplayLease(
    val moduleId: String,
    val versionCode: Int,
    val timerGeneration: String,
    val taskId: Int,
    val displayId: Int,
    val nonce: Long
)

internal object RecoveryLeaseIntents {
    const val MODULE = "module_id"
    const val VERSION = "module_version"
    const val GENERATION = "generation"
    const val TASK = "rear_task_id"
    const val DISPLAY = "display_id"
    const val NONCE = "display_nonce"

    fun create(action: String, lease: ModuleDisplayLease, packageName: String) =
        android.content.Intent(action).setPackage(packageName)
            .putExtra(MODULE, lease.moduleId).putExtra(VERSION, lease.versionCode)
            .putExtra(GENERATION, lease.timerGeneration).putExtra(TASK, lease.taskId)
            .putExtra(DISPLAY, lease.displayId).putExtra(NONCE, lease.nonce)

    fun read(intent: android.content.Intent): ModuleDisplayLease? {
        val module = intent.getStringExtra(MODULE)?.takeIf { it.isNotBlank() } ?: return null
        val version = intent.getIntExtra(VERSION, -1)
        val generation = intent.getStringExtra(GENERATION)?.takeIf { it.isNotBlank() } ?: return null
        val task = intent.getIntExtra(TASK, -1)
        val display = intent.getIntExtra(DISPLAY, -1)
        val nonce = intent.getLongExtra(NONCE, -1L)
        if (version < 0 || task <= 0 || display <= 0 || nonce <= 0L) return null
        return ModuleDisplayLease(module, version, generation, task, display, nonce)
    }

    fun matches(intent: android.content.Intent, lease: ModuleDisplayLease) =
        intent.getStringExtra(MODULE) == lease.moduleId && intent.getIntExtra(VERSION, -1) == lease.versionCode &&
            intent.getStringExtra(GENERATION) == lease.timerGeneration && intent.getIntExtra(TASK, -1) == lease.taskId &&
            intent.getIntExtra(DISPLAY, -1) == lease.displayId &&
            intent.getLongExtra(NONCE, -1L) == lease.nonce
}

/** Shared production routing and lease-adoption gate for ModuleRearActivity's receiver. */
internal object ModuleRearReceiverRouting {
    fun moduleIdFor(intent: android.content.Intent): String? = when (intent.action) {
        StageTimerController.ACTION_SESSION_CHANGED,
        ModuleDisplayRecoveryService.ACTION_VISIBILITY,
        ModuleDisplayRecoveryService.ACTION_CLOSE_ACTIVITY,
        ModuleDisplayRecoveryService.ACTION_LEASE_CHANGED -> intent.getStringExtra(RecoveryLeaseIntents.MODULE)
        else -> intent.getStringExtra("id")
    }

    fun acceptedLeaseChanged(
        intent: android.content.Intent,
        moduleId: String,
        versionCode: Int,
        generation: String,
        taskId: Int,
        displayId: Int,
        running: Boolean,
        ownsActivity: Boolean,
        leaseRequested: Boolean
    ): ModuleDisplayLease? {
        if (intent.action != ModuleDisplayRecoveryService.ACTION_LEASE_CHANGED ||
            moduleIdFor(intent) != moduleId || !running || !ownsActivity || !leaseRequested) return null
        val lease = RecoveryLeaseIntents.read(intent) ?: return null
        if (!RecoveryLeaseIntents.matches(intent, lease) || lease.moduleId != moduleId ||
            lease.versionCode != versionCode || lease.timerGeneration != generation ||
            lease.taskId != taskId || lease.displayId != displayId) return null
        return lease
    }
}

/** Small deterministic state machine shared by the service and unit tests. */
internal class ModuleDisplayRecoveryState(private val lease: ModuleDisplayLease) {
    enum class FarAction { IGNORE, RESTORE_VISIBLE_UI }
    data class Cover(val lease: ModuleDisplayLease, val serial: Long)

    private var serial = 0L
    private var covered: Cover? = null
    private var lockRecoveryPending = 0L
    private var active = true

    @Synchronized fun stableNear(ownerStillAllowed: Boolean): Cover? {
        if (!active || !ownerStillAllowed || covered != null) return null
        return Cover(lease, ++serial).also { covered = it; lockRecoveryPending = 0L }
    }

    @Synchronized fun stableFar(): Pair<Cover, FarAction>? {
        if (!active) return null
        val cover = covered ?: return null // Initial UNKNOWN -> FAR never wakes.
        covered = null
        return cover to FarAction.RESTORE_VISIBLE_UI
    }

    @Synchronized fun cancelCover(cover: Cover) {
        if (active && covered == cover) covered = null
    }

    @Synchronized fun screenOff(frontModuleWasVisible: Boolean, coveredNow: Boolean): Long? {
        if (!active || !frontModuleWasVisible || (coveredNow && covered == null)) return null
        return (++serial).also { lockRecoveryPending = it }
    }

    @Synchronized fun lockRecoveryValid(token: Long) = active && token != 0L && lockRecoveryPending == token
    @Synchronized fun validBlackCoverLockRecovery(expectedLease: ModuleDisplayLease, token: Long) =
        expectedLease == lease && active && covered != null && lockRecoveryValid(token)
    @Synchronized fun shouldRetainPendingLockExit(
        expectedLease: ModuleDisplayLease,
        token: Long,
        mainDisplayOn: Boolean,
        keyguardLocked: Boolean
    ) = expectedLease == lease && lockRecoveryValid(token) && (!mainDisplayOn || keyguardLocked)
    @Synchronized fun consumeLockRecovery(token: Long) {
        if (lockRecoveryPending == token) lockRecoveryPending = 0L
    }

    @Synchronized fun manualRearOff() {
        if (covered != null) return // Our own near-induced sleep is not an explicit user exit.
        lockRecoveryPending = 0L
        active = false
    }

    @Synchronized fun isCurrent(cover: Cover) = active && cover.lease == lease && covered == cover
    @Synchronized fun isCovered() = covered != null
    @Synchronized fun isActive() = active
    @Synchronized fun invalidate() { active = false; covered = null; lockRecoveryPending = 0L; serial++ }
}

/** Production side-effect seam: every cover/far decision is made through the same tested path. */
internal class ModuleDisplayRecoveryActions(
    private val state: ModuleDisplayRecoveryState,
    private val port: Port
) {
    interface Port {
        fun ownerEligible(cover: ModuleDisplayRecoveryState.Cover? = null): Boolean
        fun coverRear(cover: ModuleDisplayRecoveryState.Cover): Boolean
        fun ownerEligibleForVisibleRestore(cover: ModuleDisplayRecoveryState.Cover): Boolean = ownerEligible(cover)
        fun onUncovered(cover: ModuleDisplayRecoveryState.Cover)
        fun rearDisplayIsOn(): Boolean
        fun wakeRearOnce(): Boolean
        fun wakeRearOnce(cover: ModuleDisplayRecoveryState.Cover): Boolean = wakeRearOnce()
        fun focusExistingTask(): Boolean
        fun showAndRefresh()
        fun ownerEligibleForAnimationRestore(coveredBlack: Boolean): Boolean = ownerEligible()
        fun wakeRearOnce(coveredBlack: Boolean): Boolean = wakeRearOnce()
        fun focusExistingTask(coveredBlack: Boolean): Boolean = focusExistingTask()
        fun showAndRefresh(coveredBlack: Boolean) = showAndRefresh()
    }
    @Synchronized fun stableNear(): ModuleDisplayRecoveryState.Cover? {
        if (!port.ownerEligible()) return null
        val cover = state.stableNear(ownerStillAllowed = true) ?: return null
        if (!port.coverRear(cover)) {
            state.cancelCover(cover)
            return null
        }
        return cover
    }

    @Synchronized fun stableFar(): ModuleDisplayRecoveryState.FarAction {
        val transition = state.stableFar() ?: return ModuleDisplayRecoveryState.FarAction.IGNORE
        val (cover, action) = transition
        port.onUncovered(cover)
        if (!port.ownerEligible(cover)) return ModuleDisplayRecoveryState.FarAction.IGNORE
        if (action == ModuleDisplayRecoveryState.FarAction.RESTORE_VISIBLE_UI &&
            port.ownerEligibleForVisibleRestore(cover) && port.rearDisplayIsOn())
            port.showAndRefresh()
        return action
    }

    @Synchronized fun restoreAnimation(): Boolean {
        val black = state.isCovered()
        if (!state.isActive() || !port.ownerEligibleForAnimationRestore(black)) return false
        if (!port.wakeRearOnce(black) || !port.ownerEligibleForAnimationRestore(black) || !port.focusExistingTask(black)) return false
        port.showAndRefresh(black)
        return true
    }

    @Synchronized fun covered() = state.isCovered()
    @Synchronized fun stop() = state.invalidate()
}
