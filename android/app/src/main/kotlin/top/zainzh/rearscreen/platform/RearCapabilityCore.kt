package top.zainzh.rearscreen.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import org.json.JSONObject
import top.zainzh.rearscreen.ITaskService
import top.zainzh.rearscreen.RearDisplay
import top.zainzh.rearscreen.TaskServiceManager
import top.zainzh.rearscreen.platform.data.DataField
import top.zainzh.rearscreen.platform.data.DataSnapshot
import top.zainzh.rearscreen.platform.actions.ActionDispatchResult
import top.zainzh.rearscreen.platform.actions.ActionError
import top.zainzh.rearscreen.platform.actions.ActionResolveResult
import top.zainzh.rearscreen.platform.actions.PlatformRegisteredActionRegistry
import top.zainzh.rearscreen.platform.actions.RegisteredActionActivation
import top.zainzh.rearscreen.platform.actions.RegisteredActionActivationAuthority
import top.zainzh.rearscreen.platform.actions.RegisteredActionRef

/** Maps the privileged presentation IPC outcome without conflating transport loss with a stale lease. */
internal fun sceneRecoveryResultForPresentationStatus(status: String): SceneRecoveryResult = when (status) {
    "already_scene_visible", "scene_task_presented" -> SceneRecoveryResult()
    "cancelled_before_focus" -> SceneRecoveryResult(deferredByOverlay = true)
    "action_gate_unavailable" -> SceneRecoveryResult(SceneError.BACKEND_UNAVAILABLE)
    else -> if (status.startsWith("owner_") || status.startsWith("post_owner_"))
        SceneRecoveryResult(SceneError.STALE_SESSION)
    else SceneRecoveryResult(SceneError.DISPLAY_UNAVAILABLE)
}

/** Stable host composition root. This is the only scene coordinator instance in the app process. */
class RearCapabilityCore private constructor(context: Context) {
    private val appContext = context.applicationContext
    internal val activationAuthority = SceneActivationAuthority()
    private val registeredActionActivationAuthority = RegisteredActionActivationAuthority()
    private val modData = ModPlatformDataAccess(appContext)
    private val sceneGateway = PrivilegedRearSceneGateway(appContext)
    val displaySessions: DisplaySessionCoordinator = RearSceneCoordinator(
        gateway = sceneGateway,
        activationVerifier = activationAuthority,
        lifecycleMonitor = AndroidSceneDisplayLifecycleMonitor(appContext),
        legacyServiceLease = AppSceneLegacyLeasePort
    )

    /** The process-shared provider is internal; grants must be resolved by this Core facade. */
    internal val platformDataProvider get() = modData.provider

    /** Returns an owner only for an installed, enabled, author-trusted Mod. */
    internal fun modDataOwner(modId: String): SessionOwner? = modData.ownerForMod(modId)

    /** Host UI calls this synchronously from a native action-button click callback. */
    internal fun issueRegisteredActionActivationAfterHostClick(
        owner: SessionOwner,
        ref: RegisteredActionRef,
        hostGestureElapsedRealtimeMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): RegisteredActionActivation = registeredActionActivationAuthority.issueFromHostClick(owner, ref, hostGestureElapsedRealtimeMs)

    /** A single-use five-second host-click grant is consumed before resolving the opaque ref. */
    internal fun invokeRegisteredAction(
        owner: SessionOwner,
        ref: RegisteredActionRef,
        activation: RegisteredActionActivation,
    ): ActionDispatchResult {
        if (owner.principal is SessionPrincipal.SignedMod) {
            val principal = owner.principal as SessionPrincipal.SignedMod
            val installed = top.zainzh.rearscreen.platform.mods.ModPackageStore.installed(appContext, principal.modId)
                ?: return ActionDispatchResult.Rejected(ActionError.OWNER_MISMATCH)
            if (!installed.enabled || installed.authorFingerprint != principal.authorFingerprint ||
                installed.logicalSessionId != principal.logicalSessionId || "action.invoke" !in installed.grantedCapabilities ||
                !top.zainzh.rearscreen.platform.mods.ModPackageStore.isAuthorTrusted(appContext, installed.authorFingerprint) ||
                modData.ownerForMod(principal.modId) != owner
            ) return ActionDispatchResult.Rejected(ActionError.OWNER_MISMATCH)
        }
        if (!registeredActionActivationAuthority.consume(owner, ref, activation))
            return ActionDispatchResult.Rejected(ActionError.BACKGROUND_RESTRICTED)
        val registry = PlatformRegisteredActionRegistry.get(appContext)
        return when (val resolved = registry.resolve(owner, ref)) {
            is ActionResolveResult.Rejected -> ActionDispatchResult.Rejected(resolved.error)
            is ActionResolveResult.Resolved -> registry.launch(resolved.action)
        }
    }

    /** Explicit rear-scene activation may wait for the selected backend on a Binder worker. */
    internal fun awaitSceneCapabilitiesForActivation(owner: SessionOwner): CapabilitySnapshot =
        sceneGateway.awaitCapabilitiesForActivation(owner)

    internal fun snapshotModData(owner: SessionOwner, fields: Set<DataField>): DataSnapshot =
        modData.snapshot(owner, fields)

    internal fun updateSdkDataConsent(owner: SessionOwner, requested: Set<DataField>, allowed: Set<DataField>) =
        modData.updateSdkDataConsent(owner, requested, allowed)

    internal fun grantedSdkDataFields(owner: SessionOwner): Set<DataField> = modData.grantedSdkDataFields(owner)

    internal fun snapshotPlatformData(owner: SessionOwner, fields: Set<DataField>): DataSnapshot =
        modData.snapshot(owner, fields)

    internal fun observePlatformData(
        owner: SessionOwner, fields: Set<DataField>, intervalMs: Long, callback: (DataSnapshot) -> Unit
    ): ModDataObserveResult = modData.observe(owner, fields, intervalMs, callback)

    internal fun closePlatformData(owner: SessionOwner) = modData.revoke(owner)

    internal fun observeModData(
        owner: SessionOwner,
        fields: Set<DataField>,
        intervalMs: Long = 5_000L,
        callback: (DataSnapshot) -> Unit
    ): ModDataObserveResult = modData.observe(owner, fields, intervalMs, callback)

    internal fun closeModDataSessions(owner: SessionOwner) = modData.revoke(owner)

    /** Package/grant/trust transitions use this to clear all visible Mod data immediately. */
    internal fun closeModDataSessionsForMod(modId: String) = modData.revokeMod(modId)

    /** Call only from the positive action of a host-owned, visible user confirmation UI. */
    fun issueSceneActivationAfterUserConfirmation(owner: SessionOwner, request: SceneRequest): UserActivationToken =
        activationAuthority.issueFromConfirmedHostAction(owner, request)

    /** Only host Activities resolve a ticket delivered by the privileged task launcher. */
    internal fun resolveSceneLaunchForHostActivity(ticket: String, component: ComponentName): SceneLaunchContext? =
        (displaySessions as? RearSceneCoordinator)?.resolveLaunchTicketForHostActivity(ticket, component)

    internal fun ownsActiveSceneSession(owner: SessionOwner, sessionId: String): Boolean =
        (displaySessions as? RearSceneCoordinator)?.ownsActiveSession(owner, sessionId) == true

    companion object {
        @Volatile private var instance: RearCapabilityCore? = null
        private val foregroundPackagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val sceneAnimationOverlayAdapter = SceneAnimationOverlayAdapter(
            coordinator = { instance?.displaySessions as? RearSceneCoordinator },
            legacyDisplayEpoch = LegacyDisplayOccupancy::epoch
        )
        internal fun isInitializedForTesting(): Boolean = instance != null
        @JvmStatic fun get(context: Context): RearCapabilityCore = instance ?: synchronized(this) {
            instance ?: RearCapabilityCore(context).also { instance = it }
        }

        /**
         * Cast occupancy exception for one exact foreground `package:task` tuple. This is a
         * read-only lookup of an already-created Core; it does not create a backend or session.
         */
        @JvmStatic fun isActiveSceneTaskForeground(foreground: String?, displayId: Int): Boolean =
            isActiveSceneTaskForeground(foreground, displayId, instance?.displaySessions)

        /** Same production lookup with an explicit coordinator, used by deterministic call-chain tests. */
        internal fun isActiveSceneTaskForeground(
            foreground: String?, displayId: Int, sessions: DisplaySessionCoordinator?
        ): Boolean {
            if (displayId <= 0 || foreground.isNullOrBlank()) return false
            val separator = foreground.lastIndexOf(':')
            if (separator <= 0 || separator == foreground.lastIndex) return false
            val packageName = foreground.substring(0, separator)
            if (!foregroundPackagePattern.matches(packageName)) return false
            val taskId = foreground.substring(separator + 1).toIntOrNull()?.takeIf { it > 0 } ?: return false
            return (sessions as? RearSceneCoordinator)?.ownsActiveSceneTask(packageName, taskId, displayId) == true
        }

        @JvmStatic fun isActiveSceneTask(taskId: Int, displayId: Int): Boolean =
            isActiveSceneTask(taskId, displayId, instance?.displaySessions)

        internal fun isActiveSceneTask(taskId: Int, displayId: Int, sessions: DisplaySessionCoordinator?): Boolean =
            (sessions as? RearSceneCoordinator)?.ownsActiveSceneTask(taskId, displayId) == true

        /** Read-only guard for a package-wide force-stop; unlike occupancy, this is lease based. */
        @JvmStatic fun hasActiveScenePackage(packageName: String?): Boolean =
            hasActiveScenePackage(packageName, instance?.displaySessions)

        internal fun hasActiveScenePackage(packageName: String?, sessions: DisplaySessionCoordinator?): Boolean =
            !packageName.isNullOrBlank() &&
                (sessions as? RearSceneCoordinator)?.ownsActiveScenePackage(packageName) == true

        /** Commit hook shared by the physical-move and virtual-host-running success points. */
        internal fun preemptActiveScenesForLegacyCast(sessions: DisplaySessionCoordinator?) {
            (sessions as? RearSceneCoordinator)?.closeAllScenes(SceneCloseReason.PREEMPTED)
        }

        /** Existing URI/Tile cast commits its ownership only after the task move succeeds. */
        @JvmStatic fun onLegacyProjectionStarted(context: Context, taskId: Int, packageName: String?) {
            if (taskId > 0 && !packageName.isNullOrBlank()) {
                runCatching { LegacyDisplayOccupancy.recordPhysicalProjection(context, taskId, packageName, RearDisplay.requireId()) }
            } else LegacyDisplayOccupancy.noteProjectionStarted()
            val mode = if (taskId > 0 && !packageName.isNullOrBlank()) "physical" else "virtual"
            val target = if (mode == "physical") "moved_task" else "virtual_host_running"
            android.util.Log.i("RearSceneCast", "cast_commit mode=$mode target=$target result=committed")
            instance?.let { core ->
                preemptActiveScenesForLegacyCast(core.displaySessions)
            }
        }

        /** Keeps the existing Root/Shizuku source from switching while a Scene task is live or closing. */
        @JvmStatic fun hasActiveSceneSessions(): Boolean =
            (instance?.displaySessions as? RearSceneCoordinator)?.hasActiveOrClosingSceneSessions() == true

        /** Production notification/charging animation start binds to the live Scene it covers. */
        @JvmStatic fun beginTransientOverlay(overlayId: Long): Boolean =
            sceneAnimationOverlayAdapter.begin(overlayId)

        /** Replacement animations inherit the old return-task revocation only when they inherit that task. */
        @JvmStatic fun beginTransientOverlay(overlayId: Long, replacesOverlayId: Long, inheritsLegacyReturn: Boolean): Boolean =
            sceneAnimationOverlayAdapter.begin(overlayId, replacesOverlayId, inheritsLegacyReturn)

        /** A stale end cannot uncover a replacement animation or restore an ended Scene. */
        @JvmStatic fun endTransientOverlay(overlayId: Long): Boolean =
            sceneAnimationOverlayAdapter.end(overlayId)
    }
}

/** Narrow adapter over the existing Root/Shizuku task service. It never calls URI/cast task migration. */
private class PrivilegedRearSceneGateway(private val context: Context) : RearSceneGateway {
    private val service = ServiceConnectionSignal<ITaskService>()
    private val connectionLock = Any()
    @Volatile private var serviceConnection: ServiceConnection? = null

    override fun queryCapabilities(owner: SessionOwner): CapabilitySnapshot {
        // A capability read is observational: request connection, but never block it. Directory
        // discovery and UI rendering must not wait for Shizuku/Root or a rear display.
        ensureConnection()
        return capabilitySnapshot(service.current()?.takeIf { it.asBinder().isBinderAlive })
    }

    fun awaitCapabilitiesForActivation(owner: SessionOwner): CapabilitySnapshot {
        // Only an explicit Scene action waits, on the remote Binder worker. A late ServiceConnection
        // callback completes this bounded wait; an in-progress connection remains CONNECTING rather
        // than being misreported as a permanently unsupported capability.
        val connected = awaitService()?.takeIf { it.asBinder().isBinderAlive }
        return capabilitySnapshot(connected)
    }

    private fun capabilitySnapshot(connected: ITaskService?): CapabilitySnapshot {
        val backendReady = connected != null
        // Hardware presence and privilege-backend connectivity are independent facts. A caller
        // can inspect the panel before Shizuku/Root connects; only scene operations need both.
        val displayAvailable = runCatching { RearDisplay.id() > 0 }.getOrDefault(false)
        val sceneAvailability = when {
            !backendReady -> pendingOrUnavailable()
            !displayAvailable -> CapabilityAvailability.DISPLAY_UNAVAILABLE
            else -> CapabilityAvailability.AVAILABLE
        }
        return CapabilitySnapshot(
            platformContract = RearSceneCoordinator.PLATFORM_CONTRACT,
            deviceAvailable = displayAvailable,
            backendConnected = backendReady,
            capabilities = listOf(
                CapabilityState("rear.scene", 1, sceneAvailability, granted = false,
                    reasonCode = if (sceneAvailability == CapabilityAvailability.AVAILABLE) null else sceneAvailability.name.lowercase()),
                CapabilityState("rear.scene.keep_visible", 1, sceneAvailability,
                    granted = false,
                    reasonCode = if (sceneAvailability == CapabilityAvailability.AVAILABLE) null else sceneAvailability.name.lowercase()),
                CapabilityState("rear.region", 1, CapabilityAvailability.UNSUPPORTED, granted = false,
                    reasonCode = "full_region_not_supported")
            )
        )
    }

    private fun pendingOrUnavailable(): CapabilityAvailability {
        val state = runCatching { TaskServiceManager.connectionStateName(context) }.getOrDefault("ERROR")
        if (state in setOf("BINDING", "AUTHORIZING", "READY", "SWITCHING")) return CapabilityAvailability.CONNECTING
        // acquire() is posted to TaskServiceManager's main looper. Once this gateway has registered
        // its consumer, DISCONNECTED means the manager is starting or retrying the selected backend.
        if (state == "DISCONNECTED" && serviceConnection != null) return CapabilityAvailability.CONNECTING
        return when (state) {
            "NEEDS_AUTHORIZATION", "DENIED" -> CapabilityAvailability.REQUIRES_AUTHORIZATION
            else -> CapabilityAvailability.BACKEND_UNAVAILABLE
        }
    }

    override fun authorize(owner: SessionOwner, request: SceneRequest): SceneError? {
        val expectedPackage = when (val principal = owner.principal) {
            is SessionPrincipal.AndroidClient -> principal.packageName
            is SessionPrincipal.SignedMod, is SessionPrincipal.HostFeature -> context.packageName
        }
        if (request.component.packageName != expectedPackage || owner.transportPackageName != expectedPackage) return SceneError.INVALID_COMPONENT
        try {
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getActivityInfo(request.component, PackageManager.ComponentInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") context.packageManager.getActivityInfo(request.component, 0)
            }
            // Scene Activities must use a dedicated standard task, never an existing affinity task.
            if (!SceneActivityLaunchPolicy.allows(info, request.component.packageName)) return SceneError.INVALID_COMPONENT
        } catch (_: PackageManager.NameNotFoundException) { return SceneError.INVALID_COMPONENT }
        val remote = awaitService() ?: return SceneError.BACKEND_UNAVAILABLE
        val snapshot = queryCapabilities(owner)
        if (!snapshot.deviceAvailable) return SceneError.UNSUPPORTED
        for (required in request.requiredCapabilities) {
            val state = snapshot.capabilities.firstOrNull { it.name == required.name }
                ?: return SceneError.CAPABILITY_DENIED
            if (state.version < required.minimumVersion) return SceneError.CAPABILITY_DENIED
            if (state.availability == CapabilityAvailability.UNSUPPORTED) return SceneError.UNSUPPORTED
            if (state.availability != CapabilityAvailability.AVAILABLE) return SceneError.CAPABILITY_DENIED
        }
        return try {
            // Preserve old casting and recovery semantics: scenes do not preempt an active cast.
            val castState = JSONObject(remote.getRearVirtualCastStatus()).optString("state", "unknown")
            if (castState !in setOf("idle", "stopped")) SceneError.DISPLAY_BUSY
            else if (LegacyDisplayOccupancy.hasPhysicalProjection(context, remote, RearDisplay.requireId())) SceneError.DISPLAY_BUSY
            else null
        } catch (_: Exception) { SceneError.BACKEND_UNAVAILABLE }
    }

    override fun prepareDisplay(owner: SessionOwner, request: SceneRequest): SceneError? {
        // Every explicitly confirmed new Scene must be visible at launch. keepVisible only
        // controls the Activity's subsequent screen-on flag and restoration eligibility.
        val displayId = runCatching { RearDisplay.requireId() }.getOrNull() ?: return SceneError.DISPLAY_UNAVAILABLE
        val remote = awaitService() ?: return SceneError.BACKEND_UNAVAILABLE
        return try {
            if (remote.wakeRearDisplayForScene(displayId)) null else SceneError.DISPLAY_UNAVAILABLE
        } catch (_: Exception) { SceneError.DISPLAY_UNAVAILABLE }
    }

    override fun targetDisplayId(): Int? = runCatching { RearDisplay.requireId() }.getOrNull()

    override fun recoverOwnedDisplay(
        owner: SessionOwner,
        request: SceneRequest,
        task: LaunchedScene,
        isCurrent: () -> Boolean,
        onWakeAttempt: () -> SceneRecoveryWakeDecision
    ): SceneRecoveryResult {
        val remote = awaitService() ?: return SceneRecoveryResult(SceneError.BACKEND_UNAVAILABLE)
        fun stillOwnsTask(): Boolean = runCatching {
            val json = JSONObject(remote.inspectSceneActivityTask(
                task.taskId, task.component.flattenToString(), task.displayId))
            json.optString("status") == "VERIFIED" && json.optInt("taskId", -1) == task.taskId &&
                json.optInt("displayId", -1) == task.displayId &&
                json.optString("baseComponent") == task.component.flattenToString()
        }.getOrDefault(false)
        if (!isCurrent()) return SceneRecoveryResult(deferredByOverlay = true)
        if (!stillOwnsTask()) return SceneRecoveryResult(SceneError.STALE_SESSION)
        if (!isCurrent()) return SceneRecoveryResult(deferredByOverlay = true)
        // The cause is consumed only after the blocking task inspection and immediately before
        // an actual wake. If the display already woke, present the same task without another wake.
        when (onWakeAttempt()) {
            SceneRecoveryWakeDecision.STALE -> return if (!isCurrent()) SceneRecoveryResult(deferredByOverlay = true)
                else SceneRecoveryResult(SceneError.STALE_SESSION)
            SceneRecoveryWakeDecision.ALREADY_ON -> Unit
            SceneRecoveryWakeDecision.CLAIMED -> {
                val awakened = runCatching { remote.wakeRearDisplayForScene(task.displayId) }.getOrDefault(false)
                if (!awakened) return SceneRecoveryResult(SceneError.DISPLAY_UNAVAILABLE)
            }
        }
        // Power listeners can report ON inside wakeRearDisplayForScene. Lease/cause validity
        // deliberately does not require the display to remain OFF after that successful wake.
        if (!isCurrent()) return SceneRecoveryResult(deferredByOverlay = true)
        if (!stillOwnsTask()) return SceneRecoveryResult(SceneError.STALE_SESSION)
        if (!isCurrent()) return SceneRecoveryResult(deferredByOverlay = true)
        val recoveryStillCurrent = isCurrent
        val actionGate = object : top.zainzh.rearscreen.ISceneTaskActionGate.Stub() {
            override fun isCurrent(): Boolean = recoveryStillCurrent()
        }
        val presentation = runCatching {
            JSONObject(remote.presentSceneActivityTask(task.taskId, task.component.flattenToString(), task.displayId, actionGate))
        }.getOrNull() ?: return SceneRecoveryResult(SceneError.DISPLAY_UNAVAILABLE)
        val presentationStatus = presentation.optString("status")
        val presentationResult = sceneRecoveryResultForPresentationStatus(presentationStatus)
        if (presentationResult.deferredByOverlay || presentationResult.error != null) {
            android.util.Log.w("RearSceneRecovery", "present rejected status=$presentationStatus task=${task.taskId} display=${task.displayId} top=${presentation.optString("topComponent", "unknown")}")
            return presentationResult
        }
        if (!isCurrent()) return SceneRecoveryResult(deferredByOverlay = true)
        if (!stillOwnsTask()) return SceneRecoveryResult(SceneError.STALE_SESSION)
        if (!isCurrent()) return SceneRecoveryResult(deferredByOverlay = true)
        android.util.Log.i("RearSceneRecovery", "task_presented status=$presentationStatus task=${task.taskId} display=${task.displayId}; SDK window visibility remains separately reported")
        return SceneRecoveryResult()
    }

    override fun launchNewTask(owner: SessionOwner, request: SceneRequest, launchTicket: String, expiresAtElapsedRealtimeMs: Long): LaunchedScene? {
        val remote = awaitService() ?: return null
        val displayId = runCatching { RearDisplay.requireId() }.getOrNull() ?: return null
        val taskId = remote.launchSceneActivity(request.component.flattenToString(), displayId, launchTicket)
        if (taskId < 0) return null
        return LaunchedScene(request.component, taskId, displayId)
    }

    override fun verifyLaunchedTask(owner: SessionOwner, expected: LaunchedScene, report: SceneWindowReport): Boolean {
        if (report.component != expected.component || report.taskId != expected.taskId || report.viewport.displayId != expected.displayId) return false
        return runCatching {
            val raw = awaitService()?.inspectSceneActivityTask(expected.taskId, expected.component.flattenToString(), expected.displayId)
                ?: return@runCatching false
            val result = JSONObject(raw)
            result.optString("status", "QUERY_UNAVAILABLE") == "VERIFIED"
        }.getOrDefault(false)
    }

    override fun finishOwnedTask(owner: SessionOwner, task: LaunchedScene) {
        runCatching { awaitService()?.finishSceneActivityTask(task.taskId, task.component.flattenToString(), task.displayId) }
    }

    private fun awaitService(): ITaskService? {
        service.current()?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) return null // Never block Android's UI thread waiting for Binder.
        ensureConnection()
        // acquire() may connect synchronously if the existing privileged service is already ready.
        service.current()?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        // A second waiter can reuse the connection. It is bounded to avoid leaking a binder wait indefinitely.
        return service.await(SCENE_CONNECT_WAIT_MS)?.takeIf { it.asBinder().isBinderAlive }
    }

    private fun ensureConnection() {
        synchronized(connectionLock) {
            if (serviceConnection != null) return
            val created = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service.onConnected(ITaskService.Stub.asInterface(binder))
                }
                override fun onServiceDisconnected(name: ComponentName) { service.onDisconnected() }
            }
            serviceConnection = created
            TaskServiceManager.acquire(context, created)
        }
    }

    private companion object {
        const val SCENE_CONNECT_WAIT_MS = 6_000L
    }
}
