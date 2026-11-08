package top.zainzh.rearscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLegacyServiceLeasePolicyTest {
    @Test fun exactSceneLeasePausesOnlyExistingServicesAndIgnoresStaleRelease() {
        assertFalse(SceneLegacyServiceLease.hasActiveLease())
        assertFalse(RearScreenKeeperService.isRunning())
        assertTrue(SceneLegacyServiceLease.acquire("scene-1"))
        try {
            assertTrue(SceneLegacyServiceLease.hasActiveLease())
            assertFalse(SceneLegacyServiceLease.acquire("scene-2"))
            SceneLegacyServiceLease.release("scene-2", true)
            assertTrue("a stale close cannot release the active lease", SceneLegacyServiceLease.isCurrent("scene-1"))
            assertFalse("acquiring a scene lease never starts a global keeper", RearScreenKeeperService.isRunning())
        } finally {
            SceneLegacyServiceLease.release("scene-1", false)
        }
        assertFalse(SceneLegacyServiceLease.hasActiveLease())
    }

    @Test fun activeSceneBlackCoverCannotContinueOldGlobalWakeAndMatchingCloseRestoresOnlyPriorPreference() {
        // Both user-selected legacy keepers were already running before this Scene began.
        assertTrue(LegacyDisplayKeeperPolicy.mayWake(
            true, false, false, false, false))

        // A near/black Scene cover changes its renderer, not ownership; the old 100 ms loop and
        // keeper wake path remain suppressed for the entire exact scene lease.
        assertFalse(LegacyDisplayKeeperPolicy.mayWake(
            true, false, true, false, true))
        assertFalse(LegacyDisplayKeeperPolicy.mayWake(
            true, false, false, false, true))

        // Matching close restores only a mode that the user had enabled; stale close and a newer
        // lease cannot reclaim its loops.
        assertTrue(LegacyDisplayKeeperPolicy.mayRestoreAfterScene(
            true, true, true, false, false))
        assertFalse(LegacyDisplayKeeperPolicy.mayRestoreAfterScene(
            true, false, true, false, false))
        assertFalse(LegacyDisplayKeeperPolicy.mayRestoreAfterScene(
            true, true, false, false, false))
        assertFalse(LegacyDisplayKeeperPolicy.mayRestoreAfterScene(
            true, true, true, true, false))
    }
}
