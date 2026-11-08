package dev.remrss.sample

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplePresentationSafetyGateTest {
    @Test fun connectionLossInvalidatesOldQueriesAndOnlyFreshCoreSnapshotCanRevealScene() {
        val gate = SamplePresentationSafetyGate()

        val inFlightBeforeLoss = requireNotNull(gate.beginQuery(connected = true))
        gate.requireCurrentSnapshot()
        assertFalse("a late pre-disconnect query cannot restore the old visible value", gate.isCurrent(inFlightBeforeLoss))
        assertFalse("the scene stays covered during disconnect", gate.canPresent(coreVisible = true))
        assertFalse("the disconnected client cannot start a recovery query", gate.beginQuery(connected = false) != null)

        val reconnectQuery = requireNotNull(gate.beginQuery(connected = true))
        assertTrue(gate.isCurrent(reconnectQuery))
        assertFalse("reconnect alone does not restore content", gate.canPresent(coreVisible = true))

        assertFalse("a stale or rejected lease snapshot cannot clear the safety latch",
            gate.acceptCurrentSnapshot(reconnectQuery, leaseIsCurrent = false, snapshotAccepted = true))
        assertTrue("only an accepted current lease snapshot clears the safety latch",
            gate.acceptCurrentSnapshot(reconnectQuery, leaseIsCurrent = true, snapshotAccepted = true))
        assertTrue("an accepted current visible snapshot may reveal the scene", gate.canPresent(coreVisible = true))
        assertFalse("a current hidden snapshot still keeps content covered", gate.canPresent(coreVisible = false))
    }

    @Test fun aSecondDisconnectBlocksEvenAfterAnEarlierSnapshotWasAccepted() {
        val gate = SamplePresentationSafetyGate()
        gate.requireCurrentSnapshot()
        val query = requireNotNull(gate.beginQuery(connected = true))
        gate.acceptCurrentSnapshot(query, leaseIsCurrent = true, snapshotAccepted = true)
        assertTrue(gate.canPresent(coreVisible = true))

        gate.requireCurrentSnapshot()
        assertFalse(gate.canPresent(coreVisible = true))
    }

    @Test fun observersReceiveTheGatedValueRatherThanAnUngatedCoreValue() {
        val gate = SamplePresentationSafetyGate()
        val updates = mutableListOf<Boolean>()
        val observer: (Boolean) -> Unit = { updates.add(it) }
        gate.requireCurrentSnapshot()

        val published = gate.publishVisibility(coreVisible = true, observers = listOf(observer))

        assertFalse(published)
        assertTrue(updates.isNotEmpty())
        assertFalse("rear observers must not reopen black cover from an ungated event", updates.single())
    }
}
