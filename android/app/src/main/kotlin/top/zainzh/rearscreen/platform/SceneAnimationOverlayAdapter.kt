package top.zainzh.rearscreen.platform

import java.util.concurrent.atomic.AtomicLong

/** Process-wide reservation epoch survives lazy creation of RearCapabilityCore. */
internal object SceneOwnershipEpoch {
    private val value = AtomicLong(0L)
    fun current(): Long = value.get()
    fun noteSceneReservation() { value.incrementAndGet() }
}

/**
 * Production bridge used by legacy notification/charging animation sessions. It deliberately
 * records an animation even when the Core singleton does not exist yet; a later Scene reservation
 * or legacy cast changes the process epoch and permanently cancels that animation's saved return.
 */
internal class SceneAnimationOverlayAdapter(
    private val coordinator: () -> RearSceneCoordinator?,
    private val legacyDisplayEpoch: () -> Long
) {
    private val lock = Any()
    private var activeId: Long? = null
    private var sceneEpochAtStart = 0L
    private var displayEpochAtStart = 0L
    private var suppressAtStart = false

    fun begin(overlayId: Long, replacesOverlayId: Long? = null, inheritsLegacyReturn: Boolean = false): Boolean = synchronized(lock) {
        val sceneEpoch = SceneOwnershipEpoch.current()
        val displayEpoch = legacyDisplayEpoch()
        // When the animation manager transfers an existing return task to a replacement
        // animation, transfer its revocation provenance as well. A new animation ID must not
        // make an old task eligible again after Scene/cast ownership changed while Core was lazy.
        val inheritedReturnWasSuppressed = inheritsLegacyReturn && activeId == replacesOverlayId &&
            (suppressAtStart || sceneEpoch != sceneEpochAtStart || displayEpoch != displayEpochAtStart)
        val coordinatorReplacement = replacesOverlayId?.takeUnless { it == Long.MIN_VALUE }
        val suppress = (coordinator()?.beginTransientOverlay(overlayId, coordinatorReplacement, inheritedReturnWasSuppressed) == true) ||
            inheritedReturnWasSuppressed
        activeId = overlayId
        sceneEpochAtStart = sceneEpoch
        displayEpochAtStart = displayEpoch
        suppressAtStart = suppress
        suppress
    }

    /** Only the matching current animation may end its Core overlay and consume its token. */
    fun end(overlayId: Long): Boolean = synchronized(lock) {
        if (activeId != overlayId) return@synchronized false
        val coordinatorSuppress = coordinator()?.endTransientOverlay(overlayId) == true
        val suppressLegacyRestore = suppressAtStart || coordinatorSuppress ||
            SceneOwnershipEpoch.current() != sceneEpochAtStart || legacyDisplayEpoch() != displayEpochAtStart
        activeId = null
        suppressAtStart = false
        suppressLegacyRestore
    }
}
