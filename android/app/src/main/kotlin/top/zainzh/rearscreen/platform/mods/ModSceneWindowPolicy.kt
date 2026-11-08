package top.zainzh.rearscreen.platform.mods

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.zainzh.rearscreen.platform.ScenePresentationSnapshot

/** Derived Mod scene presentation state; Core visibility and the measured Activity window are independent inputs. */
internal data class ModSceneWindowPolicy(
    val windowReady: Boolean,
    val windowAttachedAndVisible: Boolean,
    val coordinatorVisible: Boolean,
    val keepVisibleRequested: Boolean,
) {
    val showSceneContent: Boolean
        get() = windowReady && windowAttachedAndVisible && coordinatorVisible

    val keepScreenOn: Boolean
        get() = showSceneContent && keepVisibleRequested
}

/** Shared by the real rear Activity and its instrumentation check: hidden scenes are fully black. */
@Composable
internal fun ModSceneVisibilitySurface(visible: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (visible) content() else Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

/**
 * Observes real Activity/window edges independently from input focus and Core's
 * effective visibility. Coordinator events only trigger a current snapshot read;
 * a snapshot request captured before a local start/stop edge cannot reopen the
 * window after that edge, even if its callback arrives late.
 */
internal class ModSceneWindowLifecycleGate(
    private val sessionId: String,
    private val generation: Long,
    private val onStateChanged: (State) -> Unit,
) {
    data class State(
        val activityStarted: Boolean,
        val windowVisible: Boolean,
        val coordinatorVisible: Boolean,
        val presentationValid: Boolean,
        val presentationRevision: Long?,
        val windowRequestEpoch: Long,
        val contentVisible: Boolean,
    )

    private var activityStarted = false
    private var attached = false
    private var viewShown = false
    private var windowVisible = false
    private var presentationValid = false
    private var presentationVisible = false
    private var presentationRevision: Long? = null
    private var windowRequestEpoch = 0L
    private var lastState: State? = null

    fun onActivityStarted(started: Boolean) {
        if (activityStarted != started) invalidatePresentationForWindowEpoch()
        activityStarted = started
        update()
    }

    fun onWindowStateChanged(attached: Boolean, viewShown: Boolean, windowVisible: Boolean) {
        val wasVisible = rawWindowVisible()
        this.attached = attached
        this.viewShown = viewShown
        this.windowVisible = windowVisible
        val isVisible = rawWindowVisible()
        if (wasVisible != isVisible) invalidatePresentationForWindowEpoch()
        update()
    }

    fun currentWindowRequestEpoch(): Long = windowRequestEpoch

    /** Apply only a fresh Core read for this exact Activity window epoch. Events never carry authority. */
    fun applyPresentationSnapshot(snapshot: ScenePresentationSnapshot?, requestEpoch: Long): Boolean {
        if (requestEpoch != windowRequestEpoch) return false
        if (snapshot == null) {
            // A missing/unauthorized lease is fail-closed and invalidates any
            // concurrent query that was captured against this local window state.
            windowRequestEpoch++
            presentationValid = false
            presentationVisible = false
            update()
            return true
        }
        if (snapshot.sessionId != sessionId || snapshot.generation != generation) {
            return false
        }
        if (presentationRevision != null && snapshot.revision < presentationRevision!!) return false
        if (presentationRevision == snapshot.revision && presentationValid && snapshot.visible != presentationVisible) return false
        presentationValid = true
        presentationVisible = snapshot.visible
        presentationRevision = snapshot.revision
        update()
        return true
    }

    fun currentState(): State = state()

    private fun rawWindowVisible(): Boolean = activityStarted && attached && viewShown && windowVisible

    private fun invalidatePresentationForWindowEpoch() {
        windowRequestEpoch++
        presentationValid = false
        presentationVisible = false
    }

    private fun state(): State {
        val actualWindowVisible = rawWindowVisible()
        val coordinatorVisible = presentationValid && presentationVisible
        val contentVisible = actualWindowVisible && coordinatorVisible
        return State(
            activityStarted = activityStarted,
            windowVisible = actualWindowVisible,
            coordinatorVisible = coordinatorVisible,
            presentationValid = presentationValid,
            presentationRevision = presentationRevision,
            windowRequestEpoch = windowRequestEpoch,
            contentVisible = contentVisible,
        )
    }

    private fun update() {
        val next = state()
        if (next != lastState) {
            lastState = next
            onStateChanged(next)
        }
    }
}
