package top.zainzh.rearscreen.platform.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyNotificationPreferencePolicyTest {
    @Test fun dndIsOnlyAppliedWhenTheLegacyPreferenceEnablesIt() {
        assertEquals("dnd", legacyNotificationPreferenceBlock(true, true, false, false))
        assertNull(legacyNotificationPreferenceBlock(false, true, false, false))
        assertNull(legacyNotificationPreferenceBlock(true, false, false, false))
    }

    @Test fun onlyWhenLockedUsesTheExistingMainProximityState() {
        assertEquals("only_when_covered", legacyNotificationPreferenceBlock(false, false, true, false))
        assertNull(legacyNotificationPreferenceBlock(false, true, true, true))
        assertNull(legacyNotificationPreferenceBlock(false, false, false, false))
    }
}
