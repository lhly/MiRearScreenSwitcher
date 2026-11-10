package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PhysicalKeeperStopPolicyTest {
    private static final String OWNER = "dev.remrss.sample.consumer:81";

    @Test public void projectionThenSettingsCommandStillStopsSameOwnerUsingLatestServiceStartId() {
        long ownerGeneration = PhysicalKeeperStopPolicy.ownerGenerationAfterServiceStart(0L, OWNER, 410L, 410);
        int latestStartIdAfterProjection = 410;
        // The production start transition preserves owner generation when there is no new physical task.
        ownerGeneration = PhysicalKeeperStopPolicy.ownerGenerationAfterServiceStart(
                ownerGeneration, null, 0L, 411); // ACTION_SET_KEEP_SCREEN_ON_ENABLED / proximity
        latestStartIdAfterProjection = 411;

        PhysicalKeeperStopPolicy.Decision decision = PhysicalKeeperStopPolicy.decide(
                OWNER, ownerGeneration, OWNER, ownerGeneration, latestStartIdAfterProjection, true, true);
        assertTrue(decision.allowed);
        assertEquals("must pass current Service start id, not cast's old startId", 411, decision.latestStartId);
    }

    @Test public void staleReturnCannotStopAReplacementPhysicalOwner() {
        PhysicalKeeperStopPolicy.Decision decision = PhysicalKeeperStopPolicy.decide(
                OWNER, 410L, "com.example.newowner:82", 412L, 413, true, true);
        assertFalse(decision.allowed);
        assertEquals(-1, decision.latestStartId);
    }

    @Test public void explicitReturnSuppressesAllTeardownRestoreWhileNaturalExitKeepsSafeLegacyRestore() {
        assertFalse(PhysicalKeeperStopPolicy.mayRestoreOnNaturalExit(true, false, false,
                RearDisplayCastOccupancy.OwnerDisposition.EMPTY));
        assertFalse(PhysicalKeeperStopPolicy.mayRestoreOnNaturalExit(false, false, false,
                RearDisplayCastOccupancy.OwnerDisposition.UNKNOWN));
        assertFalse(PhysicalKeeperStopPolicy.mayRestoreOnNaturalExit(false, false, true,
                RearDisplayCastOccupancy.OwnerDisposition.EMPTY));
        assertFalse(PhysicalKeeperStopPolicy.mayRestoreOnNaturalExit(false, true, false,
                RearDisplayCastOccupancy.OwnerDisposition.EMPTY));
        assertTrue(PhysicalKeeperStopPolicy.mayRestoreOnNaturalExit(false, false, false,
                RearDisplayCastOccupancy.OwnerDisposition.EMPTY));
        assertTrue(PhysicalKeeperStopPolicy.mayRestoreOnNaturalExit(false, false, false,
                RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER));
    }
}
