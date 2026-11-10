package top.zainzh.rearscreen;

import android.view.Display;
import org.junit.Test;
import static org.junit.Assert.*;

public final class VirtualCastOutputRecoveryPolicyTest {
    @Test public void sameRecoveryEpisodeKeepsOneAttemptAndDeadlineAcrossSurfaceChurn() {
        VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate gate =
                new VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate();
        int episode = gate.beginLoss(); // power-off loss; same session waits for ON
        int deadline = VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, episode, false,
                (e, d, timeout) -> assertEquals(VirtualCastOutputRecoveryPolicy.RECONNECT_TIMEOUT_MILLIS, timeout));
        assertTrue(deadline != 0);
        int attempt = gate.beginAttempt(episode);
        assertTrue(gate.isCurrent(episode, attempt, true, false, true, true, true, true));
        assertTrue(gate.complete(episode, attempt)); // exact host focus returned

        // A same-episode SurfaceChanged can have a slow backend RPC. A second loss 251ms later
        // remains within the original deadline and cannot restart the one-shot presentation.
        int secondLoss = episode;
        for (int churn = 0; churn < 5; churn++) {
            secondLoss = gate.beginLoss();
            assertEquals(episode, secondLoss);
            assertEquals("same episode never reopens the one-shot focus budget", 0,
                    gate.beginAttempt(secondLoss));
            assertEquals("same continuous ON episode retains the original deadline", 0,
                    VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, secondLoss, false,
                            (e, d, timeout) -> fail("deadline must not be restarted")));
        }
        assertTrue(gate.isCurrentDeadline(episode, deadline));
        assertTrue(gate.isEpisodeCurrent(episode));

        int newPowerCycle = gate.beginPowerCycleLoss();
        assertTrue("a real power cycle opens a distinct episode", newPowerCycle != episode);
        assertFalse(gate.isCurrentDeadline(episode, deadline));
        assertTrue(gate.beginAttempt(newPowerCycle) != 0);
    }

    @Test public void surfaceRecoveryCommitsOnlyAfterCurrentBackendReplyAndIgnoresOldGenerationFailure() {
        VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate gate =
                new VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate();
        int episode = gate.beginLoss();
        int deadline = VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, episode, false,
                (e, d, timeout) -> { });
        // A valid holder alone is not backend ownership; the same pending episode/deadline remains.
        // Holder is valid, but an outstanding RPC has not acknowledged adoption: no commit,
        // no watchdog cancellation, and the original bound remains live.
        assertTrue(VirtualCastOutputRecoveryPolicy.isCurrentSurfaceRecovery(true, true, true, true,
                10, 10, true, gate.isEpisodeCurrent(episode)));
        assertFalse(VirtualCastOutputRecoveryPolicy.canCommitSurfaceRecovery(false, true, true, true,
                true, 10, 10, true, gate.isEpisodeCurrent(episode)));
        assertTrue(gate.isCurrentDeadline(episode, deadline));

        // A was in flight at generation 10; B has now been accepted at generation 11.
        assertFalse("A's late exception cannot terminate B", VirtualCastOutputRecoveryPolicy.isCurrentSurfaceRecovery(
                true, true, true, true, 10, 11, false, gate.isEpisodeCurrent(episode)));
        assertTrue("a real failure from current B remains terminally actionable",
                VirtualCastOutputRecoveryPolicy.isCurrentSurfaceRecovery(
                        true, true, true, true, 11, 11, true, gate.isEpisodeCurrent(episode)));
        assertFalse("a missing/ending session cannot commit a returned surface",
                VirtualCastOutputRecoveryPolicy.canCommitSurfaceRecovery(true, false, false, true,
                        true, 11, 11, true, gate.isEpisodeCurrent(episode)));
        assertTrue(VirtualCastOutputRecoveryPolicy.canCommitSurfaceRecovery(true, true, true, true,
                true, 11, 11, true, gate.isEpisodeCurrent(episode)));
        assertTrue(gate.commitRecovery(episode));
        assertFalse(gate.isCurrentDeadline(episode, deadline));

        int exitingEpisode = gate.beginLoss();
        assertFalse("explicit exit cannot launch recovery for a finishing Activity",
                VirtualCastOutputRecoveryPolicy.isCurrentSurfaceRecovery(true, false, true, true,
                        12, 12, true, gate.isEpisodeCurrent(exitingEpisode)));
    }

    @Test public void sleepingPowerSuspensionPausesDeadlineUntilRealResumeEdge() {
        VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate gate =
                new VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate();
        int episode = gate.beginLoss();
        int beforeSleep = VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, episode, false,
                (e, d, timeout) -> { });
        assertTrue(gate.pauseDeadlineForPowerSuspension(episode));
        assertFalse(gate.isCurrentDeadline(episode, beforeSleep));
        assertEquals("no timer is armed while the display is suspended", 0,
                VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, episode, false,
                        (e, d, timeout) -> fail("must wait for a real resume edge")));
        int resumed = VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, episode, true,
                (e, d, timeout) -> assertEquals(VirtualCastOutputRecoveryPolicy.RECONNECT_TIMEOUT_MILLIS, timeout));
        assertTrue(resumed != 0);
        assertTrue(gate.isCurrentDeadline(episode, resumed));
    }

    @Test public void duplicateOnWhileOwnedPresentationIsInFlightKeepsItsEpochAndOnceAllowance() throws Exception {
        VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate gate =
                new VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate();
        int loss = gate.beginLoss();
        int recoveryActionEpoch = 17;
        int attempt = gate.beginAttempt(loss);
        assertTrue(attempt != 0);

        java.util.concurrent.CountDownLatch queryBlocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseQuery = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger focusEffects = new java.util.concurrent.atomic.AtomicInteger();
        VirtualCastRecoveryGuard actionGate = new VirtualCastRecoveryGuard(() ->
                gate.isCurrent(loss, attempt, true, false, true, true, true, true)
                        && recoveryActionEpoch == 17);
        java.util.concurrent.ExecutorService remote = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Boolean> command = remote.submit(() -> {
                queryBlocked.countDown();
                if (!releaseQuery.await(2, java.util.concurrent.TimeUnit.SECONDS)) return false;
                if (!actionGate.isCurrent()) return false;
                focusEffects.incrementAndGet(); // The production TaskService's final focus boundary.
                return true;
            });
            assertTrue(queryBlocked.await(2, java.util.concurrent.TimeUnit.SECONDS));

            // DisplayListener, SCREEN_ON and USER_PRESENT may all reconcile the same loss.
            // Only the first ON owns the once token; later ONs must neither cancel its epoch nor
            // mark its queued attempt as already presented.
            assertEquals(0, gate.beginAttempt(loss));
            assertTrue(actionGate.isCurrent());
            assertEquals(17, recoveryActionEpoch);
            releaseQuery.countDown();
            assertTrue(command.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, focusEffects.get());
            assertTrue(gate.complete(loss, attempt));
            assertEquals(0, gate.beginAttempt(loss));

            int repeatedLoss = gate.beginLoss();
            assertEquals(loss, repeatedLoss);
            assertEquals("one loss episode never reopens the presentation budget", 0,
                    gate.beginAttempt(repeatedLoss));
            int nextLoss = gate.beginPowerCycleLoss();
            int staleAttempt = gate.beginAttempt(nextLoss);
            assertTrue(staleAttempt != 0);
            VirtualCastRecoveryGuard staleGate = new VirtualCastRecoveryGuard(() ->
                    gate.isCurrent(nextLoss, staleAttempt, true, false, true, true, true, true));
            gate.invalidate(); // Activity close/new owner invalidates queued effects.
            assertFalse(staleGate.isCurrent());
            assertFalse(gate.complete(nextLoss, staleAttempt));
            assertEquals(1, focusEffects.get());
        } finally {
            releaseQuery.countDown();
            remote.shutdownNow();
        }
    }

    @Test public void duplicateOnReconcileDoesNotExtendSameLossDeadlineButRealResumeCanRearm() {
        VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate gate =
                new VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate();
        int loss = gate.beginLoss();
        java.util.concurrent.atomic.AtomicInteger scheduled = new java.util.concurrent.atomic.AtomicInteger();
        int first = VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, loss, false,
                (epoch, id, timeout) -> { assertEquals(2300L, timeout); scheduled.incrementAndGet(); });
        assertTrue(first != 0);
        assertEquals(0, VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, loss, false,
                (epoch, id, timeout) -> scheduled.incrementAndGet())); // duplicate Primary/Rear ON
        assertEquals(1, scheduled.get());
        assertTrue(gate.isCurrentDeadline(loss, first));

        assertTrue(gate.finishDeadline(loss, first, true)); // still asleep/locked; await a real resume edge
        assertEquals(0, VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, loss, false,
                (epoch, id, timeout) -> scheduled.incrementAndGet())); // late already_on / duplicate USER_PRESENT
        int afterResume = VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, loss, true,
                (epoch, id, timeout) -> { assertEquals(2300L, timeout); scheduled.incrementAndGet(); });
        assertTrue(afterResume != 0);
        assertEquals(2, scheduled.get());
        assertFalse(gate.isCurrentDeadline(loss, first));
        assertEquals(0, VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, loss, true,
                (epoch, id, timeout) -> scheduled.incrementAndGet())); // the resume edge is also once-only
        assertTrue(gate.finishDeadline(loss, afterResume, false)); // awake failure is terminal at 2.3s
        assertEquals(0, VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, loss, true,
                (epoch, id, timeout) -> scheduled.incrementAndGet()));
    }

    @Test public void everyPrimaryScreenOffRevokesOldPresentationButWakeRemainsOncePerCycle() {
        VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate gate =
                new VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate();
        int firstLoss = gate.beginLoss();
        int attempt = gate.beginAttempt(firstLoss);
        VirtualCastRecoveryGuard oldAction = new VirtualCastRecoveryGuard(() ->
                gate.isCurrent(firstLoss, attempt, true, false, true, true, true, true));
        assertTrue(oldAction.isCurrent());

        VirtualCastOutputRecoveryPolicy.PrimaryScreenOffDecision duplicateOff =
                VirtualCastOutputRecoveryPolicy.onPrimaryScreenOff(gate, true, true);
        assertFalse(duplicateOff.scheduleWake); // wake for this primary cycle was already requested
        assertTrue(duplicateOff.newPrimaryCycle); // the primary OFF edge still advances its wake token
        assertFalse(oldAction.isCurrent()); // but every actual OFF revokes the previous present token
        assertTrue(duplicateOff.lossEpoch != firstLoss);

        VirtualCastOutputRecoveryPolicy.PrimaryScreenOffDecision nextCycle =
                VirtualCastOutputRecoveryPolicy.onPrimaryScreenOff(gate, false, true);
        assertTrue(nextCycle.scheduleWake);
        assertTrue(nextCycle.newPrimaryCycle);
        VirtualCastOutputRecoveryPolicy.PrimaryScreenOffDecision duplicateNextCycle =
                VirtualCastOutputRecoveryPolicy.onPrimaryScreenOff(gate, true, false);
        assertFalse(duplicateNextCycle.scheduleWake);
        assertFalse(duplicateNextCycle.newPrimaryCycle);
    }

    @Test public void offAfterWakeWasSpentReconcilesOnRearWithoutAnotherWake() {
        VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate gate =
                new VirtualCastOutputRecoveryPolicy.MissingSurfacePresentationGate();
        int oldLoss = gate.beginLoss();
        int oldAttempt = gate.beginAttempt(oldLoss);
        int oldDeadline = VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, oldLoss, false,
                (epoch, id, timeout) -> { });
        VirtualCastRecoveryGuard staleAction = new VirtualCastRecoveryGuard(() ->
                gate.isCurrent(oldLoss, oldAttempt, true, false, true, true, true, true));
        assertTrue(staleAction.isCurrent());
        assertTrue(gate.isCurrentDeadline(oldLoss, oldDeadline));

        VirtualCastOutputRecoveryPolicy.PrimaryScreenOffDecision decision =
                VirtualCastOutputRecoveryPolicy.onPrimaryScreenOff(gate, true, true);
        assertFalse(decision.scheduleWake);
        assertFalse(staleAction.isCurrent());
        assertFalse(gate.isCurrentDeadline(oldLoss, oldDeadline));

        java.util.concurrent.atomic.AtomicInteger wakeCount = new java.util.concurrent.atomic.AtomicInteger(1);
        java.util.concurrent.atomic.AtomicInteger deadlineCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong timeoutMillis = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicInteger presentCount = new java.util.concurrent.atomic.AtomicInteger();
        VirtualCastOutputRecoveryPolicy.PrimaryScreenOffEffects effects =
                new VirtualCastOutputRecoveryPolicy.PrimaryScreenOffEffects() {
                    @Override public void scheduleWake() { wakeCount.incrementAndGet(); }
                    @Override public void reconcileAlreadyOnRear() {
                        int currentLoss = decision.lossEpoch;
                        VirtualCastOutputRecoveryPolicy.scheduleReconnectDeadline(gate, currentLoss, false,
                                (epoch, id, timeout) -> {
                                    assertEquals(currentLoss, epoch);
                                    deadlineCount.incrementAndGet();
                                    timeoutMillis.set(timeout);
                                });
                        int attempt = gate.beginAttempt(currentLoss);
                        VirtualCastRecoveryGuard currentAction = new VirtualCastRecoveryGuard(() ->
                                gate.isCurrent(currentLoss, attempt, true, false, true, true, true, true));
                        assertTrue(currentAction.isCurrent()); // same validated remote focus boundary
                        presentCount.incrementAndGet();
                        assertTrue(gate.complete(currentLoss, attempt));
                    }
                };
        VirtualCastOutputRecoveryPolicy.dispatchPrimaryScreenOff(decision, true, effects);
        assertEquals(1, wakeCount.get());
        assertEquals(1, deadlineCount.get());
        assertEquals(VirtualCastOutputRecoveryPolicy.RECONNECT_TIMEOUT_MILLIS, timeoutMillis.get());
        assertEquals(1, presentCount.get());
        assertEquals(0, gate.beginAttempt(decision.lossEpoch));

        java.util.concurrent.atomic.AtomicInteger rearOffEffects = new java.util.concurrent.atomic.AtomicInteger();
        VirtualCastOutputRecoveryPolicy.dispatchPrimaryScreenOff(decision, false,
                new VirtualCastOutputRecoveryPolicy.PrimaryScreenOffEffects() {
                    @Override public void scheduleWake() { rearOffEffects.incrementAndGet(); }
                    @Override public void reconcileAlreadyOnRear() { rearOffEffects.incrementAndGet(); }
                });
        assertEquals(0, rearOffEffects.get()); // an OFF rear waits for its real ON edge
    }

    @Test public void secondSurfaceLossDuringLockedPowerCycleWaitsForSameSessionSurface() {
        boolean wakeRequested = false;
        int wakeCount = 0;
        for (int loss = 0; loss < 2; loss++) {
            if (VirtualCastOutputRecoveryPolicy.shouldScheduleWakeForPowerCycle(wakeRequested)) {
                wakeRequested = true;
                wakeCount++;
            }
            assertEquals(VirtualCastOutputRecoveryPolicy.SurfaceUpdate.LOST_TEMPORARILY,
                    VirtualCastOutputRecoveryPolicy.surfaceUpdate(false, false, false, true));
            assertTrue(VirtualCastOutputRecoveryPolicy.waitForDisplayResume(true, false, Display.STATE_ON));
            assertFalse(VirtualCastOutputRecoveryPolicy.failHostReconnectTimeout(true, false, Display.STATE_ON));
            // Backend Surface loss does not expire; its real owner lease and explicit close remain authoritative.
            assertFalse(VirtualCastOutputRecoveryPolicy.expireViewportChange(false, 0L, 60_000L));
        }
        assertEquals(1, wakeCount);
        wakeRequested = false; // A later SCREEN_OFF is a new event, not a reconnect timer.
        assertTrue(VirtualCastOutputRecoveryPolicy.shouldScheduleWakeForPowerCycle(wakeRequested));
        assertEquals(VirtualCastOutputRecoveryPolicy.SurfaceUpdate.ADOPT_REPLACEMENT,
                VirtualCastOutputRecoveryPolicy.surfaceUpdate(false, false, true, true));
    }

    @Test public void naturalRearSleepWaitsWithoutWakingPrimaryOrForcingAlwaysOn() {
        assertTrue(VirtualCastOutputRecoveryPolicy.waitForDisplayResume(false, true, Display.STATE_OFF));
        assertTrue(VirtualCastOutputRecoveryPolicy.waitForDisplayResume(false, true, Display.STATE_DOZE));
        assertTrue(VirtualCastOutputRecoveryPolicy.waitForDisplayResume(false, true, Display.STATE_DOZE_SUSPEND));
        assertFalse(VirtualCastOutputRecoveryPolicy.waitForDisplayResume(false, true, Display.STATE_UNKNOWN));
        assertFalse(VirtualCastOutputRecoveryPolicy.failHostReconnectTimeout(false, true, Display.STATE_DOZE_SUSPEND));
    }

    @Test public void awakeReconnectAndViewportFailuresRemainBounded() {
        assertFalse(VirtualCastOutputRecoveryPolicy.waitForDisplayResume(false, true, Display.STATE_ON));
        assertTrue(VirtualCastOutputRecoveryPolicy.failHostReconnectTimeout(false, true, Display.STATE_ON));
        assertFalse(VirtualCastOutputRecoveryPolicy.expireViewportChange(true, 5000L, 4999L));
        assertTrue(VirtualCastOutputRecoveryPolicy.expireViewportChange(true, 5000L, 5000L));
        assertFalse(VirtualCastOutputRecoveryPolicy.expireViewportChange(false, 5000L, 9000L));
    }

    @Test public void lateReconnectAfterCloseIsRejectedAndViewportInterruptionStillStops() {
        assertEquals(VirtualCastOutputRecoveryPolicy.SurfaceUpdate.SESSION_CLOSED,
                VirtualCastOutputRecoveryPolicy.surfaceUpdate(true, false, true, true));
        assertEquals(VirtualCastOutputRecoveryPolicy.SurfaceUpdate.VIEWPORT_INTERRUPTED,
                VirtualCastOutputRecoveryPolicy.surfaceUpdate(false, true, true, true));
        assertEquals(VirtualCastOutputRecoveryPolicy.SurfaceUpdate.INVALID_REPLACEMENT,
                VirtualCastOutputRecoveryPolicy.surfaceUpdate(false, false, true, false));
    }

    @Test public void displayResumeFocusesOnlyTheSameOwnedHostOverKnownLauncher() {
        assertTrue(VirtualCastOutputRecoveryPolicy.isRearDisplayResumeEdge(true, false,
                Display.STATE_DOZE, Display.STATE_ON));
        assertFalse(VirtualCastOutputRecoveryPolicy.isRearDisplayResumeEdge(true, false,
                Display.STATE_ON, Display.STATE_ON));
        assertFalse(VirtualCastOutputRecoveryPolicy.isRearDisplayResumeEdge(false, false,
                Display.STATE_OFF, Display.STATE_ON));
        assertFalse(VirtualCastOutputRecoveryPolicy.isRearDisplayResumeEdge(true, true,
                Display.STATE_OFF, Display.STATE_ON));
        assertTrue(VirtualCastOutputRecoveryPolicy.mayFocusExistingHost(true,
                "com.xiaomi.subscreencenter:42", 57, "top.zainzh.rearscreen"));
        assertFalse(VirtualCastOutputRecoveryPolicy.mayFocusExistingHost(true, null, 57, "top.zainzh.rearscreen"));
        assertFalse(VirtualCastOutputRecoveryPolicy.mayFocusExistingHost(true, "", 57, "top.zainzh.rearscreen"));
        assertFalse(VirtualCastOutputRecoveryPolicy.mayFocusExistingHost(false,
                "com.xiaomi.subscreencenter:42", 57, "top.zainzh.rearscreen"));
        assertFalse(VirtualCastOutputRecoveryPolicy.mayFocusExistingHost(true,
                "com.other.app:88", 57, "top.zainzh.rearscreen"));
        assertFalse(VirtualCastOutputRecoveryPolicy.mayFocusExistingHost(true,
                "top.zainzh.rearscreen:57", 57, "top.zainzh.rearscreen"));
    }

    @Test public void rearPowerCyclePausesAndReattachesExistingSurfaceWithoutSurfaceLoss() {
        assertTrue(VirtualCastOutputRecoveryPolicy.isRearDisplaySuspendEdge(true, false,
                Display.STATE_ON, Display.STATE_DOZE_SUSPEND));
        assertTrue(VirtualCastOutputRecoveryPolicy.isRearDisplayResumeEdge(true, false,
                Display.STATE_DOZE_SUSPEND, Display.STATE_ON));
        assertFalse(VirtualCastOutputRecoveryPolicy.isRearDisplaySuspendEdge(false, false,
                Display.STATE_ON, Display.STATE_OFF));
        assertFalse(VirtualCastOutputRecoveryPolicy.isRearDisplayResumeEdge(true, true,
                Display.STATE_OFF, Display.STATE_ON));
        // SCREEN_OFF may schedule its one-shot wake even when Android kept the Surface attached.
        assertTrue(VirtualCastOutputRecoveryPolicy.allowRecoveryAction(true, false, true, true,
                true, true, true));
        assertFalse(VirtualCastOutputRecoveryPolicy.allowRecoveryAction(true, false, true, true,
                false, true, true)); // a new power cycle invalidated the queued wake
        assertFalse(VirtualCastOutputRecoveryPolicy.allowRecoveryAction(false, false, true, true,
                true, true, true)); // close/new owner cannot be revived
        assertTrue(VirtualCastOutputRecoveryPolicy.allowRecoveryAction(true, false, true, true,
                true, false, false)); // focus/present is independent of a SCREEN_OFF wake token
    }

    @Test public void alreadyOnWakeWithMissingSurfaceDispatchesWaitThenOwnedPresentation() {
        assertTrue(VirtualCastOutputRecoveryPolicy.shouldReconcileAlreadyOnWake(
                "already_on", true, false, true, true));
        assertFalse(VirtualCastOutputRecoveryPolicy.shouldReconcileAlreadyOnWake(
                "already_on", false, false, true, true));
        assertFalse(VirtualCastOutputRecoveryPolicy.shouldReconcileAlreadyOnWake(
                "already_on", true, true, true, true));
        assertFalse(VirtualCastOutputRecoveryPolicy.shouldReconcileAlreadyOnWake(
                "wake_requested", true, false, true, true));

        java.util.List<String> effects = new java.util.ArrayList<>();
        VirtualCastOutputRecoveryPolicy.RearResumeEffects sink = new VirtualCastOutputRecoveryPolicy.RearResumeEffects() {
            @Override public void reattachOutputSurface() { effects.add("reattach"); }
            @Override public void presentOwnedHost() { effects.add("present-owned-host"); }
            @Override public void waitForReplacementSurface() { effects.add("bounded-surface-wait"); }
        };
        VirtualCastOutputRecoveryPolicy.dispatchRearResume(true, false, true, true, false,
                Display.STATE_ON, sink);
        assertEquals(java.util.Arrays.asList("bounded-surface-wait", "present-owned-host"), effects);

        effects.clear();
        VirtualCastOutputRecoveryPolicy.dispatchRearResume(true, false, true, true, false,
                Display.STATE_DOZE_SUSPEND, sink);
        assertTrue("sleeping display never receives a focus/presentation effect", effects.isEmpty());
        VirtualCastOutputRecoveryPolicy.dispatchRearResume(false, false, true, true, false,
                Display.STATE_ON, sink);
        assertTrue("closed or unowned session never receives a focus/presentation effect", effects.isEmpty());
    }

    @Test public void rearOnWithValidPausedSurfaceReattachesInsteadOfFocusingOrWaiting() {
        java.util.List<String> effects = new java.util.ArrayList<>();
        VirtualCastOutputRecoveryPolicy.dispatchRearResume(true, false, true, true, true,
                Display.STATE_ON, new VirtualCastOutputRecoveryPolicy.RearResumeEffects() {
                    @Override public void reattachOutputSurface() { effects.add("reattach"); }
                    @Override public void presentOwnedHost() { effects.add("present-owned-host"); }
                    @Override public void waitForReplacementSurface() { effects.add("bounded-surface-wait"); }
                });
        assertEquals(java.util.Collections.singletonList("reattach"), effects);
    }
}
