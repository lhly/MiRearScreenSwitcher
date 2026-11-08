package dev.remrss.sample

import dev.remrss.platform.sdk.RearScenePresentation
import dev.remrss.platform.sdk.RearScenePresentationRequestEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleLeasePresentationTest {
    @Test fun coveredAndFarSnapshotsDriveTheProductionBlackSurfaceDecision() {
        val lease = SampleLease("session-a", 9, "ticket")
        assertTrue(lease.applyPresentation(RearScenePresentation("session-a", 9, 3, true, null)))
        assertTrue(lease.presentationVisible())
        assertTrue(lease.applyPresentation(RearScenePresentation("session-a", 9, 4, false, "COVERED")))
        assertFalse(lease.presentationVisible())
        assertTrue(sceneBlackCoverVisible(lease.presentationVisible(), windowActuallyVisible = true))
        assertTrue(lease.applyPresentation(RearScenePresentation("session-a", 9, 5, true, null)))
        assertFalse(sceneBlackCoverVisible(lease.presentationVisible(), windowActuallyVisible = true))
    }

    @Test fun delayedRestoredSnapshotCannotOverrideCurrentCoveredRevision() {
        val lease = SampleLease("session-a", 9, "ticket")
        assertTrue(lease.applyPresentation(RearScenePresentation("session-a", 9, 3, true, null)))
        assertTrue(lease.applyPresentation(RearScenePresentation("session-a", 9, 4, false, "COVERED")))
        assertFalse(lease.applyPresentation(RearScenePresentation("session-a", 9, 3, true, null)))
        assertFalse(lease.presentationVisible())
        assertFalse(lease.applyPresentation(RearScenePresentation("session-b", 9, 5, true, null)))
    }

    @Test fun sameLeaseOnStartRequeryReappliesUnchangedCurrentPresentation() {
        val lease = SampleLease("session-a", 9, "ticket")
        val snapshot = RearScenePresentation("session-a", 9, 3, true, null)
        val epochs = RearScenePresentationRequestEpoch()

        assertTrue(lease.applyPresentation(snapshot))
        val stoppedWindowRequest = epochs.begin()
        epochs.invalidate() // onStop discards the old Activity window's pending callback
        val restartedWindowRequest = epochs.begin() // onStart requests the same current Core state
        assertFalse(epochs.isCurrent(stoppedWindowRequest))
        assertTrue(epochs.isCurrent(restartedWindowRequest))
        assertTrue("same lease and unchanged Core revision reapply idempotently to the restarted window", lease.applyPresentation(snapshot))
        assertTrue(lease.presentationVisible())
        assertEquals(3L, lease.presentationRevision())
    }
}
