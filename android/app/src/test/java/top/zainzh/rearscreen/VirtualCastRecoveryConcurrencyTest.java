package top.zainzh.rearscreen;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class VirtualCastRecoveryConcurrencyTest {
    @Test public void healthProbeCannotCloseAfterSurfacePauseAdvancedSessionGeneration() throws Exception {
        VirtualCastHealthGeneration generation = new VirtualCastHealthGeneration();
        CountDownLatch captured = new CountDownLatch(1), continueProbe = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        Thread health = new Thread(() -> {
            long snapshot = generation.capture();
            captured.countDown();
            try { assertTrue(continueProbe.await(2, TimeUnit.SECONDS)); }
            catch (Throwable e) { workerError.set(e); return; }
            try { generation.runIfCurrent(snapshot, () -> closed.set(true)); }
            catch (Throwable e) { workerError.set(e); }
        });
        health.start();
        assertTrue(captured.await(2, TimeUnit.SECONDS));
        // This is the same generation advance performed under Session's updateSurface lock,
        // after the atomic outputAvailable/surface transition.
        generation.advance();
        continueProbe.countDown(); health.join(2000);
        assertFalse("stale health failure must not close a newly paused session", closed.get());
        assertFalse("health worker must terminate", health.isAlive());
        assertNull(workerError.get());
    }

    @Test public void leaseRenewalInvalidatesOnlyLeaseExpiryNotStructuralFailure() {
        VirtualCastHealthGeneration generation = new VirtualCastHealthGeneration();
        long structure = generation.capture();
        long lease = generation.captureLease();
        generation.renewLease();
        AtomicBoolean structuralFailureClosed = new AtomicBoolean();
        AtomicBoolean staleLeaseExpiredClosed = new AtomicBoolean();
        assertTrue("ordinary renewal must not erase a host/display structural failure",
                generation.runIfCurrent(structure, () -> structuralFailureClosed.set(true)));
        assertTrue(structuralFailureClosed.get());
        assertFalse("renewal must invalidate an old lease-expired snapshot",
                generation.runIfLeaseCurrent(lease, () -> staleLeaseExpiredClosed.set(true)));
        assertFalse(staleLeaseExpiredClosed.get());
    }

    @Test public void recoveryActionGateRejectsBlockedQueryAfterCloseOrPowerEpochChange() throws Exception {
        for (String invalidation : new String[] {"close", "new_power_epoch"}) {
            AtomicBoolean valid = new AtomicBoolean(true), performed = new AtomicBoolean();
            AtomicReference<Throwable> workerError = new AtomicReference<>();
            VirtualCastRecoveryGuard gate = new VirtualCastRecoveryGuard(valid::get);
            CountDownLatch queryEntered = new CountDownLatch(1), unblockQuery = new CountDownLatch(1);
            Thread recovery = new Thread(() -> {
                queryEntered.countDown();
                try { assertTrue(unblockQuery.await(2, TimeUnit.SECONDS)); }
                catch (Throwable e) { workerError.set(e); return; }
                try { if (gate.isCurrent()) performed.set(true); }
                catch (Throwable e) { workerError.set(e); }
            });
            recovery.start();
            assertTrue("recovery query entered", queryEntered.await(2, TimeUnit.SECONDS));
            valid.set(false); // Activity ending or a new power/session generation
            unblockQuery.countDown(); recovery.join(2000);
            assertFalse("late focus/wake side effect must be denied after " + invalidation, performed.get());
            assertFalse("recovery worker must terminate", recovery.isAlive());
            assertNull(workerError.get());
        }
    }

    @Test public void gestureClockStopsDuringSurfacePauseAndOnlyResumesForSameLiveClock() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger ticks = new AtomicInteger();
        PausableGestureClock clock = new PausableGestureClock(executor, ticks::incrementAndGet, 10);
        try {
            clock.resume();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ticks.get() < 3 && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue("clock should tick while output is available", ticks.get() >= 3);
            clock.pause();
            int pausedAt = ticks.get();
            Thread.sleep(120);
            assertEquals("gesture ticks must stop during a long output pause", pausedAt, ticks.get());
            clock.resume();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ticks.get() == pausedAt && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue("same session clock resumes after output returns", ticks.get() > pausedAt);
            clock.close();
            int closedAt = ticks.get(); Thread.sleep(80);
            assertEquals("closed clock cannot be restarted by a late resume", closedAt, ticks.get());
            clock.resume(); Thread.sleep(40);
            assertEquals(closedAt, ticks.get());
        } finally { clock.close(); }
    }

    @Test public void readerAckEpochRejectsBufferedOldReadyAndPauseBeforeLatestResume() {
        long currentEpoch = 4, resumeEpoch = 4;
        assertFalse("old R1 READY buffered before S2/R2 cannot satisfy R2",
                RearInputReaderAckGate.acceptReady(2, currentEpoch, resumeEpoch, false, false));
        assertFalse("old S2 pause ACK cannot overwrite a newer R2",
                RearInputReaderAckGate.acceptPaused(3, currentEpoch, false, false));
        assertTrue("the matching S4 PAUSED ACK confirms the grab is released",
                RearInputReaderAckGate.acceptPaused(4, currentEpoch, false, false));
        assertTrue("only the matching R2 READY re-enables input and gesture clock",
                RearInputReaderAckGate.acceptReady(4, currentEpoch, resumeEpoch, false, false));
        assertFalse("closed input session rejects even matching buffered READY",
                RearInputReaderAckGate.acceptReady(4, currentEpoch, resumeEpoch, false, true));
        assertFalse("closed input session rejects buffered PAUSED",
                RearInputReaderAckGate.acceptPaused(4, currentEpoch, false, true));
    }
}
