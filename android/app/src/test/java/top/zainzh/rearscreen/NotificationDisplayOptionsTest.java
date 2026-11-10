package top.zainzh.rearscreen;

import static org.junit.Assert.*;
import org.junit.Test;

public class NotificationDisplayOptionsTest {
    @Test public void durationUnitsStayWithinThePickerContract() {
        assertEquals(1, NotificationDisplayOptions.durationSeconds(1, false));
        assertEquals(60, NotificationDisplayOptions.durationSeconds(60, false));
        assertEquals(60, NotificationDisplayOptions.durationSeconds(1, true));
        assertEquals(3600, NotificationDisplayOptions.durationSeconds(60, true));
        try {
            NotificationDisplayOptions.durationSeconds(61, true);
            fail("picker values above 60 must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void confirmingWithoutChangingWheelsPreservesLegacyDurations() {
        assertEquals(90, NotificationDisplayOptions.durationAfterConfirmation(90, false, 2, true));
        assertEquals(3_661, NotificationDisplayOptions.durationAfterConfirmation(3_661, false, 60, true));
        assertEquals(120, NotificationDisplayOptions.durationAfterConfirmation(90, true, 2, true));
        assertEquals(2_147_483_647_000L, NotificationDisplayOptions.configuredDurationMillis(Integer.MAX_VALUE));
    }

    @Test public void persistentModeIsExclusiveToOrdinaryNotifications() {
        assertTrue(NotificationDisplayOptions.hasAutomaticFinish(false, false));
        assertFalse(NotificationDisplayOptions.hasAutomaticFinish(false, true));
        assertTrue(NotificationDisplayOptions.hasAutomaticFinish(true, true));
        assertTrue(NotificationDisplayOptions.hasAutomaticFinish(true, false));
    }

    @Test public void tapDefaultsToOpeningAndCanDismissOnlyTheRearReminder() {
        assertTrue(NotificationDisplayOptions.shouldLaunchSourceApp(false, false));
        assertFalse(NotificationDisplayOptions.shouldLaunchSourceApp(false, true));
        assertTrue(NotificationDisplayOptions.shouldLaunchSourceApp(true, true));
    }

    @Test public void permanentModeRequiresARealRemovalIdentity() {
        assertTrue(NotificationDisplayOptions.shouldKeepUntilRemoved(true, "key", 1));
        assertFalse(NotificationDisplayOptions.shouldKeepUntilRemoved(true, null, 1));
        assertFalse(NotificationDisplayOptions.shouldKeepUntilRemoved(true, "", 1));
        assertFalse(NotificationDisplayOptions.shouldKeepUntilRemoved(true, "key", 0));
        assertFalse(NotificationDisplayOptions.shouldKeepUntilRemoved(false, "key", 1));
    }

    @Test public void removalMustMatchTheCurrentPersistentGeneration() {
        assertTrue(NotificationDisplayOptions.matchesRemoval("k2", 22, "k2", 22, true, 6));
        assertFalse(NotificationDisplayOptions.matchesRemoval("k2", 22, "k1", 11, true, 6));
        assertFalse(NotificationDisplayOptions.matchesRemoval("k2", 22, "k2", 21, true, 6));
        assertFalse(NotificationDisplayOptions.matchesRemoval("k2", 22, "k2", 22, false, 6));
        assertFalse(NotificationDisplayOptions.matchesRemoval("k2", 22, "k2", 22, true, Long.MIN_VALUE));
    }
}
