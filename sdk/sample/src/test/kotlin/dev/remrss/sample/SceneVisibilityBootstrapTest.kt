package dev.remrss.sample

import dev.remrss.platform.sdk.RearSceneWindowPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneVisibilityBootstrapTest {
    @Test fun proximityCoverHidesContentUntilCoreAndMeasuredWindowAreBothVisible() {
        assertTrue("Core covered state blackens an otherwise visible measured window",
            sceneBlackCoverVisible(coreVisible = false, windowActuallyVisible = true))
        assertFalse("far/restored Core state reveals content in the still-visible rear window",
            sceneBlackCoverVisible(coreVisible = true, windowActuallyVisible = true))
        assertTrue("a hidden window stays black even if Core has not yet published a hidden state",
            sceneBlackCoverVisible(coreVisible = true, windowActuallyVisible = false))
    }

    @Test fun windowBecomingVisibleBeforeReadyIsReportedAfterReadyAndUnlocksFlagPolicy() {
        val reporter = SceneVisibilityBootstrap()
        assertNull("actual window can become visible before Core consumes READY", reporter.onWindowVisibility(true))

        val initial = requireNotNull(reporter.onLeaseReady())
        assertTrue(initial.visible)
        reporter.onReportFinished(initial, accepted = true)
        assertNull("an unchanged ready window is not reported repeatedly", reporter.onWindowVisibility(true))

        assertFalse("window policy waits for Core's authoritative visible snapshot",
            RearSceneWindowPolicy.shouldKeepScreenOn(true, true, true, sceneVisible = false))
        assertTrue("the same measured window can keep the rear on after Core confirms it",
            RearSceneWindowPolicy.shouldKeepScreenOn(true, true, true, sceneVisible = true))
    }

    @Test fun sameWindowValueAfterNewStartEpochIsReportedAgainAndOldAckCannotResetIt() {
        val reporter = SceneVisibilityBootstrap()
        reporter.onWindowVisibility(true)
        val oldEpochReport = requireNotNull(reporter.onLeaseReady())

        reporter.beginWindowEpoch()
        reporter.onWindowVisibility(true)
        val currentEpochReport = requireNotNull(reporter.onLeaseReady())
        assertTrue(currentEpochReport.windowEpoch > oldEpochReport.windowEpoch)
        reporter.onReportFinished(oldEpochReport, accepted = false)
        assertNull("a stale result cannot reopen a duplicate report in the current epoch",
            reporter.onWindowVisibility(true))

        val hidden = requireNotNull(reporter.onWindowVisibility(false))
        assertFalse(hidden.visible)
        reporter.onReportFinished(hidden, accepted = true)
        assertNull(reporter.onWindowVisibility(false))
    }

    @Test fun revocationPreventsOldWindowCallbacksFromIssuingVisibilityReports() {
        val reporter = SceneVisibilityBootstrap()
        reporter.onWindowVisibility(true)
        val old = requireNotNull(reporter.onLeaseReady())
        reporter.revoke()
        assertNull(reporter.onWindowVisibility(true))
        reporter.onReportFinished(old, accepted = false)
        assertNull(reporter.onLeaseReady())
        assertTrue("the actual view can still be visible, but a revoked lease must not report it",
            reporter.currentWindowVisibility() == true)
    }
}
