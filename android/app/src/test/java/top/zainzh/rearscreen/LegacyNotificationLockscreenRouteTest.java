package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.WindowManager;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;

public final class LegacyNotificationLockscreenRouteTest {
    private static final String COMPONENT = "top.zainzh.rearscreen/top.zainzh.rearscreen.RearScreenNotificationActivity";

    @Test public void lockedOnlyUsesHiddenMainPlaceholderRoute() {
        assertEquals(LegacyNotificationLaunchRoute.Route.HIDDEN_MAIN_THEN_REAR,
                LegacyNotificationLaunchRoute.forKeyguard(true));
        assertEquals(LegacyNotificationLaunchRoute.Route.DIRECT_REAR,
                LegacyNotificationLaunchRoute.forKeyguard(false));
    }

    @Test public void primaryPlaceholderHasNoLockscreenOrKeepOrTurnScreenOnFlags() {
        assertEquals(0, NotificationWindowPolicy.flags(false, false));
        assertFalse(NotificationWindowPolicy.showWhenLocked(false));
        assertFalse(NotificationWindowPolicy.turnScreenOn());
    }

    @Test public void rearWindowGetsLockscreenFlagAndLegacyKeepButNotTurnOn() {
        assertEquals(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                NotificationWindowPolicy.flags(true, false));
        assertEquals(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                NotificationWindowPolicy.flags(true, true));
        assertTrue(NotificationWindowPolicy.showWhenLocked(true));
        assertFalse(NotificationWindowPolicy.turnScreenOn());
    }

    @Test public void lockedPlaceholderMovesOnlyItsExactFreshPrimaryTask() throws Exception {
        FakeTasks tasks = new FakeTasks("taskId=57006: " + COMPONENT);
        tasks.sequences.add("taskId=57005: " + COMPONENT); // existing task before launch
        tasks.sequences.add("taskId=57006: " + COMPONENT); // fresh task after launch
        int moved = LockedNotificationTaskFlow.startAndMove(tasks, COMPONENT, "fixed command", 1, () -> true);
        assertEquals(57006, moved);
        assertEquals("fixed command", tasks.startedCommand);
        assertEquals(57006, tasks.movedTaskId);
        assertEquals(1, tasks.movedDisplayId);
    }

    @Test public void staleSessionAmbiguousTaskOrAlreadyRearTaskNeverMoves() throws Exception {
        FakeTasks stale = new FakeTasks("taskId=57006: " + COMPONENT);
        assertEquals(-1, LockedNotificationTaskFlow.startAndMove(stale, COMPONENT, "fixed command", 1, () -> false));
        assertEquals(-1, stale.movedTaskId);

        FakeTasks ambiguous = new FakeTasks("taskId=57006: " + COMPONENT + "\n"
                + "taskId=57007: " + COMPONENT);
        ambiguous.sequences.add("");
        ambiguous.sequences.add("taskId=57006: " + COMPONENT + "\ntaskId=57007: " + COMPONENT);
        assertEquals(-1, LockedNotificationTaskFlow.startAndMove(ambiguous, COMPONENT, "fixed command", 1, () -> true));
        assertEquals(-1, ambiguous.movedTaskId);

        FakeTasks alreadyRear = new FakeTasks("taskId=57006: " + COMPONENT);
        alreadyRear.onPrimary = false;
        alreadyRear.sequences.add("");
        alreadyRear.sequences.add("taskId=57006: " + COMPONENT);
        assertEquals(-1, LockedNotificationTaskFlow.startAndMove(alreadyRear, COMPONENT, "fixed command", 1, () -> true));
        assertEquals(-1, alreadyRear.movedTaskId);

        FakeTasks preexisting = new FakeTasks("taskId=57006: " + COMPONENT);
        preexisting.sequences.add("taskId=57006: " + COMPONENT);
        preexisting.sequences.add("taskId=57006: " + COMPONENT);
        assertEquals(-1, LockedNotificationTaskFlow.startAndMove(preexisting, COMPONENT, "fixed command", 1, () -> true));
        assertEquals(-1, preexisting.movedTaskId);
    }

    @Test public void keyguardOrSessionChangeBeforeMoveFailsClosed() throws Exception {
        FakeTasks tasks = new FakeTasks("taskId=57006: " + COMPONENT);
        AtomicInteger checks = new AtomicInteger();
        tasks.sequences.add("");
        tasks.sequences.add("taskId=57006: " + COMPONENT);
        assertEquals(-1, LockedNotificationTaskFlow.startAndMove(tasks, COMPONENT, "fixed command", 1,
                () -> checks.getAndIncrement() < 3));
        assertEquals("placeholder was started but never moved after its authorization changed", -1, tasks.movedTaskId);
    }

    @Test public void staleFinishCannotCloseNewAcceptedSessionAndFinishedActivityRejectsDelivery() {
        NotificationActivitySessionGate gate = new NotificationActivitySessionGate();
        assertTrue(gate.accept(41L, false));
        assertTrue(gate.accept(42L, false));
        assertFalse(gate.finish(41L));
        assertTrue(gate.isCurrent(42L));
        assertTrue(gate.finish(42L));
        assertFalse(gate.accept(43L, true));
    }

    @Test public void configOnlyMoveReconcilesWithoutSecondResumeAndWatchdogWaitsForReadyContent() {
        NotificationDisplayReconciliation.Decision main = NotificationDisplayReconciliation.decide(false, false, false);
        assertTrue(main.hideContent);
        assertTrue(main.keepWatchdog);
        NotificationDisplayReconciliation.Decision rearNotReady = NotificationDisplayReconciliation.decide(true, false, false);
        assertTrue(rearNotReady.initializeContent);
        assertTrue(rearNotReady.keepWatchdog);
        NotificationDisplayReconciliation.Decision rearReady = NotificationDisplayReconciliation.decide(true, true, false);
        assertFalse(rearReady.keepWatchdog);
        assertTrue(rearReady.startLegacyWakeLoop);
        NotificationDisplayReconciliation.Decision returnedToMain = NotificationDisplayReconciliation.decide(false, true, false);
        assertTrue(returnedToMain.hideContent);
        assertFalse(returnedToMain.startLegacyWakeLoop);
    }

    @Test public void ownedLockedRouteKeepsOriginalElapsedDeadlineAndCancelEndsOnlyItsSession() {
        assertEquals(LegacyNotificationLaunchRoute.Route.HIDDEN_MAIN_THEN_REAR,
                LegacyNotificationLaunchRoute.forKeyguard(true));
        long originalDeadline = 9_000L;
        assertEquals(4_000L, NotificationService.remainingOwnedNotificationTtlMs(originalDeadline, 5_000L));
        assertEquals(0L, NotificationService.remainingOwnedNotificationTtlMs(originalDeadline, 9_000L));
        assertEquals(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                NotificationWindowPolicy.flags(true, true));
        assertEquals(0, NotificationWindowPolicy.flags(false, true));
        assertFalse(NotificationDisplayReconciliation.decide(true, true, true).startLegacyWakeLoop);
    }

    @Test public void claimedUiDeliveryCannotTimeoutIntoFreshFallbackOrDeliverTwice() throws Exception {
        NotificationIntentDelivery delivery = new NotificationIntentDelivery();
        AtomicInteger uiDeliveries = new AtomicInteger();
        CountDownLatch uiClaimed = new CountDownLatch(1);
        CountDownLatch releaseUi = new CountDownLatch(1);
        Thread ui = new Thread(() -> delivery.runClaimed(() -> {
            uiDeliveries.incrementAndGet();
            uiClaimed.countDown(); // controllable pause after the production claim, before commit/return
            try { releaseUi.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return -1; }
            return 57042;
        }, () -> { throw new AssertionError("successful handoff must not run terminal cleanup"); }));
        ui.start();
        try {
            assertTrue(uiClaimed.await(1, TimeUnit.SECONDS));
            assertFalse(delivery.await(0, TimeUnit.MILLISECONDS)); // worker's first bounded wait timed out
            assertFalse(delivery.cancelPending()); // claim won: a second Activity start is forbidden
            assertEquals(NotificationIntentDelivery.State.CLAIMED, delivery.state());
        } finally {
            releaseUi.countDown();
            ui.join(1000);
        }
        assertFalse(ui.isAlive());
        NotificationIntentDelivery.Result result = delivery.result();
        assertEquals(NotificationIntentDelivery.Outcome.ACCEPTED, result.outcome);
        assertEquals(57042, result.taskId);
        assertFalse(result.mayStartFreshPlaceholder());
        assertEquals(1, uiDeliveries.get());

        NotificationActivitySessionGate sessions = new NotificationActivitySessionGate();
        assertTrue(sessions.accept(57041L, false));
        assertTrue(sessions.accept(57042L, false));
        assertFalse(sessions.finish(57041L));
        assertTrue(sessions.isCurrent(57042L));
    }

    @Test public void claimedDeliveryTimeoutTerminatesExactlyAndCancelledPendingMayRetry() throws Exception {
        NotificationIntentDelivery delivery = new NotificationIntentDelivery();
        AtomicInteger exactTerminations = new AtomicInteger();
        CountDownLatch uiClaimed = new CountDownLatch(1);
        CountDownLatch releaseUi = new CountDownLatch(1);
        Thread ui = new Thread(() -> delivery.runClaimed(() -> {
            uiClaimed.countDown();
            try { releaseUi.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return 57043;
        }, exactTerminations::incrementAndGet));
        ui.start();
        try {
            assertTrue(uiClaimed.await(1, TimeUnit.SECONDS));
            assertFalse(delivery.await(0, TimeUnit.MILLISECONDS));
            assertFalse(delivery.cancelPending());
            assertTrue(delivery.terminateClaimed());
            assertFalse(delivery.result().mayStartFreshPlaceholder());
        } finally {
            releaseUi.countDown();
            ui.join(1000);
        }
        assertFalse(ui.isAlive());
        assertEquals(NotificationIntentDelivery.Outcome.TERMINATED, delivery.result().outcome);
        assertEquals(1, exactTerminations.get());

        NotificationIntentDelivery pending = new NotificationIntentDelivery();
        assertTrue(pending.cancelPending());
        assertFalse(pending.runClaimed(() -> 57044, () -> { throw new AssertionError(); }));
        assertEquals(NotificationIntentDelivery.Outcome.CANCELLED_BEFORE_START, pending.result().outcome);
        assertTrue(pending.result().mayStartFreshPlaceholder());

        NotificationIntentDelivery rejected = new NotificationIntentDelivery();
        assertTrue(rejected.claim());
        assertTrue(rejected.complete(-1));
        assertEquals(NotificationIntentDelivery.Outcome.REJECTED, rejected.result().outcome);
        assertFalse(rejected.result().mayStartFreshPlaceholder());
    }

    @Test public void staleRenderCannotSatisfyAcceptedSessionOrClaimOwnedDisplayedEvidence() {
        NotificationRenderSessionGate oldActivity = new NotificationRenderSessionGate();
        oldActivity.beginActivity(81L);
        assertTrue(oldActivity.markRendered(81L));
        assertTrue(oldActivity.isRenderedBy(81L, 81L));

        // A accepts B, but until B's Activity initializes content, A's config/focus/draw callbacks
        // are all the same rejected production predicate.
        oldActivity.acceptHandoff(82L);
        assertFalse(oldActivity.isRenderedBy(81L, 82L));
        assertFalse(oldActivity.claimDisplayed(81L, 82L));
        assertFalse(oldActivity.claimDisplayed(81L, 82L));

        NotificationRenderSessionGate newActivity = new NotificationRenderSessionGate();
        newActivity.beginActivity(82L);
        assertFalse(newActivity.isRenderedBy(82L, 82L));
        assertFalse(newActivity.claimDisplayed(82L, 82L));
        assertTrue(newActivity.markRendered(82L));
        assertTrue(newActivity.isRenderedBy(82L, 82L));
        assertTrue(newActivity.claimDisplayed(82L, 82L));
        assertFalse(newActivity.claimDisplayed(82L, 82L));
    }

    private static final class FakeTasks implements LockedNotificationTaskFlow.Tasks {
        final String stacks;
        final Queue<String> sequences = new ArrayDeque<>();
        String startedCommand;
        int movedTaskId = -1;
        int movedDisplayId = -1;
        boolean onPrimary = true;
        FakeTasks(String stacks) { this.stacks = stacks; }
        @Override public boolean start(String command) { startedCommand = command; return true; }
        @Override public String list() { return sequences.isEmpty() ? stacks : sequences.remove(); }
        @Override public boolean isOnDisplay(int taskId, int displayId) {
            return taskId >= 57006 && taskId <= 57007 && displayId == 0 && onPrimary;
        }
        @Override public boolean moveToDisplay(int taskId, int displayId) {
            movedTaskId = taskId; movedDisplayId = displayId; return true;
        }
        @Override public void sleep(long millis) { }
    }
}
