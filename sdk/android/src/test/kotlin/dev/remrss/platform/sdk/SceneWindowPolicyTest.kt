package dev.remrss.platform.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneWindowPolicyTest {
    @Test fun keepVisibleRequiresRequestLiveLeaseCoreVisibilityAndRealWindow() {
        assertTrue(RearSceneWindowPolicy.shouldKeepScreenOn(requested = true, leaseActive = true, windowActuallyVisible = true, sceneVisible = true))
        assertFalse(RearSceneWindowPolicy.shouldKeepScreenOn(requested = false, leaseActive = true, windowActuallyVisible = true, sceneVisible = true))
        assertFalse(RearSceneWindowPolicy.shouldKeepScreenOn(requested = true, leaseActive = false, windowActuallyVisible = true, sceneVisible = true))
        assertFalse(RearSceneWindowPolicy.shouldKeepScreenOn(requested = true, leaseActive = true, windowActuallyVisible = false, sceneVisible = true))
        assertFalse("black/suspended Scene releases the window's screen-on flag",
            RearSceneWindowPolicy.shouldKeepScreenOn(requested = true, leaseActive = true, windowActuallyVisible = true, sceneVisible = false))
    }

    @Test fun mainScreenPauseDoesNotClearFlagWhileRearWindowAndCoreLeaseRemainVisible() {
        val mainScreenLockedButRearVisible = RearSceneWindowPolicy.shouldKeepScreenOn(
            requested = true, leaseActive = true, windowActuallyVisible = true, sceneVisible = true)
        val actualBlackOrHiddenWindow = RearSceneWindowPolicy.shouldKeepScreenOn(
            requested = true, leaseActive = true, windowActuallyVisible = false, sceneVisible = false)
        val optionalLease = RearSceneWindowPolicy.shouldKeepScreenOn(
            requested = false, leaseActive = true, windowActuallyVisible = true, sceneVisible = true)
        assertTrue("main-screen pause/focus loss alone does not clear the rear window flag", mainScreenLockedButRearVisible)
        assertFalse("actual hidden/black state releases the Scene window flag", actualBlackOrHiddenWindow)
        assertFalse("the optional request remains off", optionalLease)
    }
}
