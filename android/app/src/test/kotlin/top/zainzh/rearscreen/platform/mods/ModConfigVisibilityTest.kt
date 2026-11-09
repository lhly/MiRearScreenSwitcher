package top.zainzh.rearscreen.platform.mods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModConfigVisibilityTest {
    @Test fun resumedVisibleSettingsWindowRemainsVisibleWithoutWindowFocus() {
        // A separate display may own global focus while this main-screen window stays visible.
        assertTrue(isModConfigSceneVisible(resumed = true, attached = true, shown = true, windowVisible = true))
    }

    @Test fun pausedDetachedHiddenOrInvisibleSettingsWindowCannotReceiveUserEvents() {
        assertFalse(isModConfigSceneVisible(resumed = false, attached = true, shown = true, windowVisible = true))
        assertFalse(isModConfigSceneVisible(resumed = true, attached = false, shown = true, windowVisible = true))
        assertFalse(isModConfigSceneVisible(resumed = true, attached = true, shown = false, windowVisible = true))
        assertFalse(isModConfigSceneVisible(resumed = true, attached = true, shown = true, windowVisible = false))
    }
}
