package top.zainzh.rearscreen.platform.mods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.zainzh.rearscreen.platform.ScenePresentationSnapshot

class ModSceneWindowPolicyRecoveryTest {
    @Test fun windowPolicyDiagnosticsLogEdgesButDeduplicateStableCallbacks() {
        val trace = ModSceneWindowPolicyTraceGate()
        assertTrue(trace.shouldLog("visible|epoch=2|rev=4|expected=true", false, true))
        assertFalse("stable callbacks do not create repeated log lines",
            trace.shouldLog("visible|epoch=2|rev=4|expected=true", true, true))
        assertTrue("a platform-lost flag is recorded when the next apply reasserts it",
            trace.shouldLog("visible|epoch=2|rev=4|expected=true", false, true))
        assertFalse(trace.shouldLog("visible|epoch=2|rev=4|expected=true", true, true))
        assertTrue("a real release edge is recorded", trace.shouldLog("hidden|epoch=3|rev=5|expected=false", true, false))
        assertFalse(trace.shouldLog("hidden|epoch=3|rev=5|expected=false", false, false))
    }

    @Test fun acceptedSameCoreSnapshotReappliesCurrentKeepFlagEffect() {
        var windowHasKeepFlag = false
        var effectReconciliations = 0
        val gate = ModSceneWindowLifecycleGate("scene-refresh", 11L) { state ->
            effectReconciliations++
            windowHasKeepFlag = ModSceneWindowPolicy(
                windowReady = true,
                windowAttachedAndVisible = state.windowVisible,
                coordinatorVisible = state.coordinatorVisible,
                keepVisibleRequested = true,
            ).keepScreenOn
        }
        gate.onActivityStarted(true)
        gate.onWindowStateChanged(attached = true, viewShown = true, windowVisible = true)
        val epoch = gate.currentWindowRequestEpoch()
        val currentCoreSnapshot = ScenePresentationSnapshot(
            "scene-refresh", 11L, revision = 4L, visible = true, hiddenReason = null)

        assertTrue(gate.applyPresentationSnapshot(currentCoreSnapshot, epoch))
        assertTrue("fresh Core visibility enables the same Activity-scoped keep policy", windowHasKeepFlag)
        val reconciliationsAfterFirstApply = effectReconciliations

        // WindowManager can discard an Activity attribute across transient cover/return while
        // the authoritative Core snapshot and local gate state remain unchanged.
        windowHasKeepFlag = false
        assertTrue(gate.applyPresentationSnapshot(currentCoreSnapshot, epoch))
        assertTrue("an accepted same-revision snapshot reasserts, rather than assumes, the OS flag",
            windowHasKeepFlag)
        assertEquals(reconciliationsAfterFirstApply + 1, effectReconciliations)
    }

    @Test fun restoredActivityReappliesKeepScreenOnOnlyAfterFreshCurrentSceneWindowAndCoreVisibility() {
        val gate = ModSceneWindowLifecycleGate("scene-1", 7L) { }
        val policy: (ModSceneWindowLifecycleGate.State) -> ModSceneWindowPolicy = { state ->
            ModSceneWindowPolicy(
                windowReady = true,
                windowAttachedAndVisible = state.windowVisible,
                coordinatorVisible = state.coordinatorVisible,
                keepVisibleRequested = true,
            )
        }

        gate.onActivityStarted(true)
        gate.onWindowStateChanged(attached = true, viewShown = true, windowVisible = true)
        assertFalse("a resumed task alone cannot retain the display before Core revalidates its lease",
            policy(gate.currentState()).keepScreenOn)
        var epoch = gate.currentWindowRequestEpoch()
        assertTrue(gate.applyPresentationSnapshot(
            ScenePresentationSnapshot("scene-1", 7L, revision = 10L, visible = true, hiddenReason = null), epoch))
        assertTrue("current real window plus exact Core visibility re-applies the keep lease",
            policy(gate.currentState()).keepScreenOn)

        gate.onActivityStarted(false)
        gate.onWindowStateChanged(attached = true, viewShown = true, windowVisible = true)
        assertFalse("stopped parent window releases its local keep flag", policy(gate.currentState()).keepScreenOn)

        gate.onActivityStarted(true)
        assertFalse("restart invalidates the old Core snapshot until the current window epoch is queried",
            policy(gate.currentState()).keepScreenOn)
        epoch = gate.currentWindowRequestEpoch()
        assertTrue(gate.applyPresentationSnapshot(
            ScenePresentationSnapshot("scene-1", 7L, revision = 11L, visible = true, hiddenReason = null), epoch))
        assertTrue("the restored exact Scene reacquires keep only after fresh window/Core visibility",
            policy(gate.currentState()).keepScreenOn)
    }
}
