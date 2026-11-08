package top.zainzh.rearscreen;

import android.view.Display;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.Assert.*;

public final class VirtualCastOutputPowerGateTest {
    @Test public void blockedOldOnRestoreCannotReattachOrResumeAfterNewOffEpoch() throws Exception {
        ReentrantLock backendSessionLock = new ReentrantLock();
        AtomicBoolean actionEpochCurrent = new AtomicBoolean(true);
        AtomicBoolean surfaceAttached = new AtomicBoolean(false);
        AtomicBoolean inputResumed = new AtomicBoolean(false);
        AtomicBoolean allowed = new AtomicBoolean(true);
        CountDownLatch binderCallArrived = new CountDownLatch(1);
        CountDownLatch continueAfterSessionLock = new CountDownLatch(1);
        backendSessionLock.lock();
        Thread oldRestore = new Thread(() -> {
            binderCallArrived.countDown();
            backendSessionLock.lock();
            try {
                try { assertTrue(continueAfterSessionLock.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                boolean commit = VirtualCastOutputPowerGate.mayCommit(true, true, true,
                        Display.STATE_DOZE_SUSPEND, true, () -> actionEpochCurrent.get()
                                && VirtualCastOutputPowerGate.isSleeping(Display.STATE_DOZE_SUSPEND));
                allowed.set(commit);
                if (commit) { surfaceAttached.set(true); inputResumed.set(true); }
            } finally { backendSessionLock.unlock(); }
        });
        oldRestore.start();
        assertTrue(binderCallArrived.await(2, TimeUnit.SECONDS));
        // The real Host increments its current action epoch as the OFF event is accepted;
        // the blocked call reaches this production gate only after the backend Session lock.
        actionEpochCurrent.set(false);
        continueAfterSessionLock.countDown();
        backendSessionLock.unlock();
        oldRestore.join(2000);
        assertFalse(oldRestore.isAlive());
        assertFalse("stale ON action cannot attach the retained Surface during the newer OFF", allowed.get());
        assertFalse(surfaceAttached.get());
        assertFalse(inputResumed.get());
    }

    @Test public void offPeriodReplacementSurfaceWinsOverReleasedRetainedSurface() {
        assertTrue(VirtualCastOutputPowerGate.deferSurfaceChangeWhilePowerSuspended(true));
        assertFalse(VirtualCastOutputPowerGate.deferSurfaceChangeWhilePowerSuspended(false));
        assertEquals(VirtualCastOutputPowerGate.SurfaceChoice.REPLACEMENT,
                VirtualCastOutputPowerGate.chooseRestoreSurface(false, true, true));
        assertEquals(VirtualCastOutputPowerGate.SurfaceChoice.REPLACEMENT,
                VirtualCastOutputPowerGate.chooseRestoreSurface(true, true, true));
        assertEquals(VirtualCastOutputPowerGate.SurfaceChoice.RETAINED,
                VirtualCastOutputPowerGate.chooseRestoreSurface(true, false, false));
        assertEquals(VirtualCastOutputPowerGate.SurfaceChoice.NONE,
                VirtualCastOutputPowerGate.chooseRestoreSurface(false, false, false));
        assertEquals(VirtualCastOutputPowerGate.SurfaceChoice.INVALID_REPLACEMENT,
                VirtualCastOutputPowerGate.chooseRestoreSurface(true, true, false));
    }

    @Test public void cancelledPostGateRestorePausesInputClockAndDetachesOutputBeforeNextOff() {
        AtomicBoolean vdAttached = new AtomicBoolean(true);
        AtomicBoolean inputGrabbed = new AtomicBoolean(true);
        AtomicBoolean gestureClockRunning = new AtomicBoolean(true);
        List<String> order = new ArrayList<>();
        boolean cleanupOk = VirtualCastOutputPowerGate.rollbackCancelledRestore(
                () -> {
                    inputGrabbed.set(false);
                    gestureClockRunning.set(false);
                    order.add("input-paused");
                },
                () -> {
                    vdAttached.set(false);
                    order.add("surface-detached");
                });
        assertTrue(cleanupOk);
        assertFalse(inputGrabbed.get());
        assertFalse(gestureClockRunning.get());
        assertFalse(vdAttached.get());
        assertEquals(java.util.Arrays.asList("input-paused", "surface-detached"), order);
        // The following OFF edge sees an already-detached VD, but its idempotent input pause
        // remains required by Session.setOutputAvailable(false).
    }
}
