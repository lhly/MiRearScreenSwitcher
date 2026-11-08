package dev.remrss.platform.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePresentationGateTest {
    @Test fun delayedOlderSnapshotCannotRestoreAnAlreadyHiddenScene() {
        val gate = RearScenePresentationGate("logical-session", 7)
        assertTrue(gate.apply(RearScenePresentation("logical-session", 7, 4, true, null)))
        assertTrue(gate.apply(RearScenePresentation("logical-session", 7, 5, false, "COVERED")))
        assertFalse("late far/Restored query cannot roll Core state backward", gate.apply(RearScenePresentation("logical-session", 7, 4, true, null)))
        assertFalse(gate.visible())
        assertEquals(5L, gate.revision())
    }

    @Test fun snapshotFromAnotherOwnerGenerationCannotAuthorizeThisWindow() {
        val gate = RearScenePresentationGate("logical-session", 7)
        assertFalse(gate.apply(RearScenePresentation("other-session", 7, 100, true, null)))
        assertFalse(gate.apply(RearScenePresentation("logical-session", 8, 100, true, null)))
        assertEquals(0L, gate.revision())
        assertFalse(gate.visible())
    }

    @Test fun sameCurrentSnapshotCanReapplyAfterWindowRestartButConflictingEqualRevisionCannot() {
        val gate = RearScenePresentationGate("logical-session", 7)
        val current = RearScenePresentation("logical-session", 7, 5, true, null)
        assertTrue(gate.apply(current))
        assertTrue("same Core state must restore a freshly reset window-local surface", gate.apply(current))
        assertFalse("equal revision cannot change the Core-authoritative value", gate.apply(current.copy(visible = false, hiddenReason = "COVERED")))
        assertFalse(gate.apply(RearScenePresentation("logical-session", 7, 4, false, "COVERED")))
        assertTrue(gate.visible())
        assertEquals(5L, gate.revision())
    }

    @Test fun stoppedOrRecreatedWindowInvalidatesItsPendingSnapshotReply() {
        val epoch = RearScenePresentationRequestEpoch()
        val oldWindowRequest = epoch.begin()
        epoch.invalidate() // onStop
        val newWindowRequest = epoch.begin() // next onStart
        assertFalse(epoch.isCurrent(oldWindowRequest))
        assertTrue(epoch.isCurrent(newWindowRequest))
    }
}
