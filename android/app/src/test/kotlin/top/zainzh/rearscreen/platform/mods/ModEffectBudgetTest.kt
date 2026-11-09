package top.zainzh.rearscreen.platform.mods

import org.junit.Assert.*
import org.junit.Test

class ModEffectBudgetTest {
    @Test fun publicEffectsAllowSixteenCallBurstThenRefillAtTenPerSecond() {
        val limiter = ModEffectRateLimiter()
        repeat(16) { assertTrue(limiter.tryAcquire(10_000L)) }
        assertFalse(limiter.tryAcquire(10_000L))
        assertTrue(limiter.tryAcquire(10_100L))
        assertFalse(limiter.tryAcquire(10_100L))
    }

    @Test fun resultChainsAreBoundedWithoutSharingBudgetsAcrossIndependentEvents() {
        val chain = ModEffectChainBudget()
        repeat(15) { assertEquals(ResultEventReservation.ACCEPTED, chain.reserveResultEvent()) }
        assertEquals(ResultEventReservation.EVENT_LIMIT, chain.reserveResultEvent())

        val independentClick = ModEffectChainBudget()
        assertEquals(ResultEventReservation.ACCEPTED, independentClick.reserveResultEvent())
        assertTrue(independentClick.addCpu(90_000_000L))
        assertFalse(independentClick.addCpu(11_000_000L))
        assertTrue(ModEffectChainBudget().withinCpuBudget())
    }

    @Test fun asyncNotificationFeedbackUsesThePublishingChainAndStopsAtItsEventBudget() {
        val chain = ModEffectChainBudget()
        // Initial publish's immediate accepted result.
        assertEquals(ResultEventReservation.ACCEPTED, chain.reserveResultEvent())

        var asyncResults = 0
        while (chain.reserveResultEvent() == ResultEventReservation.ACCEPTED) {
            asyncResults++ // host.notificationResult runs the reducer.
            if (chain.reserveResultEvent() != ResultEventReservation.ACCEPTED) break // recursive publish's effectResult
        }
        assertEquals("one causal chain permits seven async feedback reducers after the initial publish", 7, asyncResults)
        assertEquals(ResultEventReservation.EVENT_LIMIT, chain.reserveResultEvent())

        val finitePublish = ModEffectChainBudget()
        assertEquals(ResultEventReservation.ACCEPTED, finitePublish.reserveResultEvent()) // accepted
        assertEquals(ResultEventReservation.ACCEPTED, finitePublish.reserveResultEvent()) // displayed
        assertEquals(ResultEventReservation.ACCEPTED, finitePublish.reserveResultEvent()) // terminal
    }

    @Test fun asyncNotificationResultsCannotOutliveCpuBudgetOrReviveAnOldRuntimeEpoch() {
        val exhausted = ModEffectChainBudget()
        assertFalse(exhausted.addCpu(ModEffectChainBudget.MAX_CPU_NANOS + 1))
        assertEquals(ResultEventReservation.CPU_LIMIT, exhausted.reserveResultEvent())

        val runtime = ModRuntimeEpochGate(41L)
        runtime.advance(42L) // closing/rebinding the slot invalidates an observer captured by epoch 41.
        assertFalse(runtime.accepts(41L))
        assertTrue(runtime.accepts(42L))
    }
}
