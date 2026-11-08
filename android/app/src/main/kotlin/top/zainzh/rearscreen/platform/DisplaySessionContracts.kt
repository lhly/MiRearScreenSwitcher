package top.zainzh.rearscreen.platform

import android.content.ComponentName

/** Coordinates all display occupancy; callers own only sessions issued to their identity. */
interface DisplaySessionCoordinator {
    fun queryCapabilities(owner: SessionOwner): CapabilitySnapshot

    /** Scene opens a caller-owned Activity as a new task; it never adopts or moves an existing task. */
    fun openScene(owner: SessionOwner, request: SceneRequest, activation: UserActivationToken, observer: SceneObserver): SceneOpenResult

    fun closeScene(owner: SessionOwner, sessionId: String): SceneOperationResult

    /** Called only after the launched Activity measured its real window. */
    fun reportSceneWindowReady(
        owner: SessionOwner,
        launchTicket: String,
        window: SceneWindowReport
    ): SceneOperationResult

    /** Rebuild/configuration changes authenticate by owner + session generation, not the consumed launch ticket. */
    fun reportSceneViewport(owner: SessionOwner, sessionId: String, generation: Long, window: SceneWindowReport): SceneOperationResult

    fun attachSceneObserver(owner: SessionOwner, sessionId: String, generation: Long, observer: SceneObserver): SceneOperationResult

    fun reportSceneVisibility(owner: SessionOwner, sessionId: String, generation: Long, visible: Boolean): SceneOperationResult

    /** Authoritative, revisioned presentation state; queued observer events are only reconcile triggers. */
    fun queryScenePresentation(owner: SessionOwner, sessionId: String, generation: Long): ScenePresentationSnapshot?

    /** End only the exact logical principal. Mod worker hide/restart must not invoke this operation. */
    fun closeOwnerSessions(owner: SessionOwner, reason: SceneCloseReason)
}

/** Android caller identity is always resolved by the broker; Mod identity comes from the verified installed manifest. */
data class SessionOwner(
    val transportUid: Int,
    val transportPackageName: String,
    val transportSigningCertificateDigest: String,
    val principal: SessionPrincipal
)

sealed interface SessionPrincipal {
    /** SDK process/binder connection identity. Binder death closes only this epoch's sessions. */
    data class AndroidClient(
        val uid: Int,
        val packageName: String,
        val signingCertificateDigest: String,
        val connectionEpoch: Long
    ) : SessionPrincipal

    /** Stable logical Mod owner; intentionally independent from QuickJS worker/epoch lifetime. */
    data class SignedMod(
        val authorFingerprint: String,
        val modId: String,
        val logicalSessionId: String
    ) : SessionPrincipal

    /** Explicitly named first-party host feature; never use a shared generic host principal. */
    data class HostFeature(val featureId: String, val logicalSessionId: String) : SessionPrincipal
}

data class SceneRequest(
    val component: ComponentName,
    val region: RearRegion = RearRegion.CAMERA_SAFE,
    val keepVisible: Boolean = true,
    val requiredCapabilities: Set<CapabilityRequirement> = emptySet()
)

data class CapabilityRequirement(val name: String, val minimumVersion: Int)

/** Issued by host UI after a real user gesture/confirmation; a caller boolean cannot replace it. */
@JvmInline value class UserActivationToken(val opaqueValue: String)

enum class RearRegion { CAMERA_SAFE, FULL }

enum class CapabilityAvailability { AVAILABLE, CONNECTING, REQUIRES_AUTHORIZATION, BACKEND_UNAVAILABLE, DISPLAY_UNAVAILABLE, UNSUPPORTED }

data class CapabilityState(
    val name: String,
    val version: Int,
    val availability: CapabilityAvailability,
    val granted: Boolean,
    val reasonCode: String? = null
)

data class CapabilitySnapshot(
    val platformContract: String,
    val deviceAvailable: Boolean,
    val backendConnected: Boolean,
    val capabilities: List<CapabilityState>
)

data class SceneSessionHandle(
    val sessionId: String,
    val generation: Long,
    /** Opaque, single-use ticket for the exact registered component; expires quickly. */
    val launchTicket: String,
    val launchTicketExpiresAtElapsedRealtimeMs: Long
)

/** Internal host-only resolution of a shell-launched ticket back to the identity minted by the broker. */
data class SceneLaunchContext(
    val owner: SessionOwner,
    val sessionId: String,
    val generation: Long,
    val request: SceneRequest
)

sealed interface SceneOpenResult {
    data class Accepted(val handle: SceneSessionHandle) : SceneOpenResult
    data class Rejected(val code: SceneError, val retryable: Boolean) : SceneOpenResult
}

enum class SceneError {
    CAPABILITY_DENIED, UNSUPPORTED, BACKEND_UNAVAILABLE, DISPLAY_BUSY, STALE_SESSION,
    DISPLAY_UNAVAILABLE, INVALID_COMPONENT, INVALID_STATE, LIMIT_EXCEEDED, STORAGE_FAILED
}

data class SceneOperationResult(
    val success: Boolean,
    val error: SceneError? = null,
    val retryable: Boolean = false
)

data class ScenePresentationSnapshot(
    val sessionId: String,
    val generation: Long,
    val revision: Long,
    val visible: Boolean,
    val hiddenReason: SceneSuspendReason?
)

interface SceneObserver {
    /** Events are serialized by the coordinator and carry the current generation. */
    fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent)
}

sealed interface SceneEvent {
    data class Ready(val viewport: RearViewportSnapshot) : SceneEvent
    data class ViewportChanged(val revision: Long, val viewport: RearViewportSnapshot) : SceneEvent
    data class VisibilityChanged(val visible: Boolean) : SceneEvent
    data class Suspended(val reason: SceneSuspendReason) : SceneEvent
    data object Restored : SceneEvent
    data object BackendLost : SceneEvent
    data class Closed(val reason: SceneCloseReason) : SceneEvent
}

enum class SceneSuspendReason { COVERED, DISPLAY_OFF, DISPLAY_RECONFIGURED, PREEMPTED }
enum class SceneCloseReason { CLIENT_CLOSED, CLIENT_DIED, COMPONENT_FINISHED, LAUNCH_EXPIRED, BACKEND_LOST, OWNER_REVOKED, HOST_DIED, PREEMPTED }

data class PixelSize(val width: Int, val height: Int)
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class DpSize(val width: Float, val height: Float)

/** Values are reported by the Activity that owns the real window; service verifies component/task/display before accepting. */
data class SceneWindowReport(
    val component: ComponentName,
    val taskId: Int,
    val viewport: RearViewportSnapshot
)

/** All dimensions name their coordinate space and unit; nullable hardware data is not fabricated. */
data class RearViewportSnapshot(
    val revision: Long,
    val displayId: Int,
    val physicalDisplayPx: PixelSize?,
    /** Absolute rectangle in display-local pixel coordinates, origin at the display's top-left. */
    val contentBoundsPx: PixelRect,
    /** Activity window's measured decor bounds, in pixels. */
    val windowPx: PixelSize,
    /** Activity window size in density-independent pixels; fractional values are preserved. */
    val windowDp: DpSize,
    val densityDpi: Int,
    val renderBufferPx: PixelSize?,
    val displayRotation: Int,
    val contentRotation: Int,
    /** Maps window-local input pixels into the content coordinate space. */
    val inputTransform: CoordinateTransform
)

data class CoordinateTransform(
    val scaleX: Float,
    val skewX: Float,
    val translateX: Float,
    val skewY: Float,
    val scaleY: Float,
    val translateY: Float
)
