package top.zainzh.rearscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedActivityTransitionsTest {
    @Test fun onlyTheFourOwnedSettingsActivitiesReceiveWindowTransitions() {
        listOf(
            RearScreenshotActivity::class.java,
            TutorialActivity::class.java,
            DensityCompatibilityActivity::class.java,
            PermissionChecksActivity::class.java,
        ).forEach { type -> assertNotNull("$type is in the explicit self-owned allowlist", SharedActivityTransitions.profileFor(type)) }

        listOf(
            MainActivity::class.java,
            DataAccessConfirmationActivity::class.java,
            NotificationAccessConfirmationActivity::class.java,
            SceneActivationConfirmationActivity::class.java,
            top.zainzh.rearscreen.platform.actions.RegisteredActionsActivity::class.java,
            top.zainzh.rearscreen.platform.mods.ModSettingsActivity::class.java,
        ).forEach { type -> assertNull("$type keeps its existing system/host transition", SharedActivityTransitions.profileFor(type)) }
    }

    @Test fun profileUsesTheEstablishedDurationsAndSharedThemeBackground() {
        val profile = SharedActivityTransitions.profileFor(TutorialActivity::class.java)!!
        assertEquals(R.anim.miuix_activity_open_enter, profile.openEnter)
        assertEquals(R.anim.miuix_activity_open_exit, profile.openExit)
        assertEquals(R.anim.miuix_activity_close_enter, profile.closeEnter)
        assertEquals(R.anim.miuix_activity_close_exit, profile.closeExit)
        assertEquals(R.color.remrss_background, profile.backgroundColor)
        assertTrue(profile.openEnter != profile.openExit)
        assertTrue(profile.closeEnter != profile.closeExit)
        assertFalse("the process-wide easing resource is not an Activity allowlist entry",
            SharedActivityTransitions.profileFor(SharedActivityTransitions::class.java) != null)
    }
}
