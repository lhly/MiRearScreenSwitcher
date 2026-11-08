package top.zainzh.rearscreen

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production token and decision policy with a deterministic elapsed clock. */
@RunWith(AndroidJUnit4::class)
class ModuleStartupHoldPolicyTest {
    private val registry = ModuleStartupHoldRegistry
    private val policy = ModuleStartupHoldPolicy(registry)

    @Test fun explicitValidGateAcquiresHoldOnlyForLiveMatchingModule() {
        val token = registry.issue(nowElapsedMs = 10_000L)

        assertEquals(15_000L, token.deadlineElapsedMs)
        assertTrue(policy.shouldAcquire(token, 10_000L, explicitGate = true, moduleValid = true, displayMatches = true))
        assertFalse(policy.shouldAcquire(token, 10_000L, explicitGate = false, moduleValid = true, displayMatches = true))
        assertFalse(policy.shouldAcquire(token, 10_000L, explicitGate = true, moduleValid = false, displayMatches = true))
        assertFalse(policy.shouldAcquire(token, 10_000L, explicitGate = true, moduleValid = true, displayMatches = false))
    }

    @Test fun holdExpiresAtItsOriginalAbsoluteDeadlineWithoutRenewal() {
        val token = registry.issue(nowElapsedMs = 20_000L)

        assertTrue(policy.shouldReapply(token, 24_999L))
        assertEquals(25_000L, token.deadlineElapsedMs)
        assertFalse(policy.shouldReapply(token, 25_000L))
        assertEquals(ModuleStartupHoldRegistry.State.STALE, registry.state(token, 25_000L))
        assertFalse(policy.shouldReapply(token, 25_001L))
    }

    @Test fun oldNonceAndDeadlineCannotRenewOrReleaseReplacementHold() {
        val old = registry.issue(nowElapsedMs = 30_000L)
        val oldIntent = registry.write(Intent("fixture.START"), old)
        val restoredOld = registry.read(oldIntent)
        val replacement = registry.issue(nowElapsedMs = 31_000L)

        assertEquals(35_000L, old.deadlineElapsedMs)
        assertEquals(36_000L, replacement.deadlineElapsedMs)
        assertEquals(ModuleStartupHoldRegistry.State.STALE, registry.state(old, 31_001L))
        assertFalse(policy.shouldReapply(old, 31_001L))
        assertFalse(registry.release(old))
        assertEquals(old, restoredOld)
        assertFalse(policy.shouldReapply(restoredOld, 31_001L))
        assertFalse(registry.release(restoredOld!!))
        assertEquals(ModuleStartupHoldRegistry.State.ACTIVE, registry.state(replacement, 31_001L))
        assertTrue(policy.shouldReapply(replacement, 31_001L))
    }

    @Test fun expiredOrStaleTokensCannotAcquireHold() {
        val expired = registry.issue(nowElapsedMs = 40_000L)
        assertEquals(ModuleStartupHoldRegistry.State.EXPIRED, registry.state(expired, 45_000L))
        assertFalse(policy.shouldAcquire(expired, 45_001L, explicitGate = true, moduleValid = true, displayMatches = true))

        val stale = registry.issue(nowElapsedMs = 45_002L)
        val current = registry.issue(nowElapsedMs = 45_003L)
        assertFalse(policy.shouldAcquire(stale, 45_004L, explicitGate = true, moduleValid = true, displayMatches = true))
        assertTrue(policy.shouldAcquire(current, 45_004L, explicitGate = true, moduleValid = true, displayMatches = true))
    }

    @Test fun runningTimerWithoutKeepVisibleReleasesTemporaryHoldImmediately() {
        val token = registry.issue(nowElapsedMs = 50_000L)

        assertFalse(policy.shouldRelease(token, 50_001L, timerRunning = false, keepVisible = false, leaseRequested = false))
        assertTrue(policy.shouldRelease(token, 50_002L, timerRunning = true, keepVisible = false, leaseRequested = false))
        assertTrue(registry.release(token))
        assertEquals(ModuleStartupHoldRegistry.State.STALE, registry.state(token, 50_003L))
    }

    @Test fun runningKeepVisibleTransfersHoldOnlyAfterLeaseRequest() {
        val token = registry.issue(nowElapsedMs = 60_000L)

        assertFalse(policy.shouldRelease(token, 60_001L, timerRunning = true, keepVisible = true, leaseRequested = false))
        assertEquals(ModuleStartupHoldRegistry.State.ACTIVE, registry.state(token, 60_001L))
        assertTrue(policy.shouldRelease(token, 60_002L, timerRunning = true, keepVisible = true, leaseRequested = true))
        assertTrue(registry.release(token))
        assertEquals(ModuleStartupHoldRegistry.State.STALE, registry.state(token, 60_003L))
    }

    @Test fun releaseInvalidatesTokenAndDoesNotChangeDeadlineOrIssueAnotherHold() {
        val token = registry.issue(nowElapsedMs = 70_000L)
        val originalDeadline = token.deadlineElapsedMs

        assertTrue(registry.release(token))
        assertFalse(policy.shouldReapply(token, 70_100L))
        assertEquals(originalDeadline, token.deadlineElapsedMs)
        assertEquals(ModuleStartupHoldRegistry.State.STALE, registry.state(token, 70_100L))
    }
}
