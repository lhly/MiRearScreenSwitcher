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
        repeat(15) { assertTrue(chain.addResultEvent()) }
        assertFalse(chain.addResultEvent())

        val independentClick = ModEffectChainBudget()
        assertTrue(independentClick.addResultEvent())
        assertTrue(independentClick.addCpu(90_000_000L))
        assertFalse(independentClick.addCpu(11_000_000L))
        assertTrue(ModEffectChainBudget().withinCpuBudget())
    }
}
