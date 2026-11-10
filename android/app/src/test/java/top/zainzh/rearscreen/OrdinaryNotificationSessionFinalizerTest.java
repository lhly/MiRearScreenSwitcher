package top.zainzh.rearscreen;

import static org.junit.Assert.*;
import org.junit.Test;

public class OrdinaryNotificationSessionFinalizerTest {
    private static final class FakeHost implements OrdinaryNotificationSessionFinalizer.Host {
        long current = 1;
        int endCalls, finishBroadcasts, restores, chargingResumes, taskRestores;
        boolean resumeCharging;
        @Override public boolean isCurrent(long id) { return current == id; }
        @Override public boolean hasDifferentOwner(long id) { return current != Long.MIN_VALUE && current != id; }
        @Override public boolean end(long id) {
            endCalls++;
            if (current != id) return false;
            current = Long.MIN_VALUE;
            return true;
        }
        @Override public void finishActivity(long id) { finishBroadcasts++; }
        @Override public void restore(long id) {
            restores++;
            if (resumeCharging) chargingResumes++; else taskRestores++;
        }
    }

    private static OrdinaryNotificationSessionFinalizer newLifecycle(FakeHost host, long id) {
        OrdinaryNotificationSessionFinalizer lifecycle = new OrdinaryNotificationSessionFinalizer();
        lifecycle.register(id, host);
        return lifecycle;
    }

    @Test public void removalWaitsForBothActivityAndLaunchWorker() {
        FakeHost host = new FakeHost();
        host.resumeCharging = true;
        OrdinaryNotificationSessionFinalizer lifecycle = newLifecycle(host, 1);
        lifecycle.markPrepared(1);
        Object window = new Object();
        assertTrue(lifecycle.activityAttached(1, window));
        assertTrue(lifecycle.requestFinish(1));
        assertEquals(1, host.finishBroadcasts);
        assertTrue(lifecycle.activityDestroyed(1, window));
        assertEquals(0, host.restores);
        assertEquals(0, host.chargingResumes);

        lifecycle.launchFinished(1);
        assertEquals(1, host.restores);
        assertEquals(1, host.chargingResumes);
        assertFalse(lifecycle.activityDestroyed(1, window));
    }

    @Test public void removalDuringPreparationPreventsLateLaunchAndRestoresAfterWorkerExits() {
        FakeHost host = new FakeHost();
        OrdinaryNotificationSessionFinalizer lifecycle = newLifecycle(host, 1);
        assertTrue(lifecycle.requestFinish(1));
        assertFalse(host.isCurrent(1));
        lifecycle.markPrepared(1); // prepare() was already in flight; launch remains gated.
        assertFalse(host.isCurrent(1));
        lifecycle.launchFinished(1);
        assertEquals(0, host.finishBroadcasts);
        assertEquals(1, host.restores);
        assertFalse(lifecycle.isTracked(1));
    }

    @Test public void replacedSessionCannotFinishOrRestoreItsSuccessor() {
        FakeHost host = new FakeHost();
        OrdinaryNotificationSessionFinalizer lifecycle = newLifecycle(host, 1);
        lifecycle.markPrepared(1);
        Object windowA = new Object();
        assertTrue(lifecycle.activityAttached(1, windowA));
        host.current = 2;
        lifecycle.register(2, host);
        lifecycle.activityHandoff(1, windowA);
        assertTrue(lifecycle.activityAttached(2, new Object()));
        assertTrue(lifecycle.requestFinish(1));
        lifecycle.launchFinished(1);
        assertEquals(2, host.current);
        assertEquals(0, host.finishBroadcasts);
        assertEquals(0, host.restores);
        assertTrue(lifecycle.isTracked(2));
    }

    @Test public void activityOwnedCompletionWaitsForWorkerBeforeRestoring() {
        FakeHost host = new FakeHost();
        OrdinaryNotificationSessionFinalizer lifecycle = newLifecycle(host, 1);
        lifecycle.markPrepared(1);
        Object window = new Object();
        assertTrue(lifecycle.activityAttached(1, window));
        assertTrue(lifecycle.activityDestroyed(1, window));
        assertEquals(1, host.endCalls);
        assertEquals(0, host.restores);
        lifecycle.launchFinished(1);
        assertEquals(1, host.restores);
        assertEquals(0, host.finishBroadcasts);
    }

    @Test public void servicePolicyShutdownEndsPreparedSessionWithoutAnActivity() {
        FakeHost host = new FakeHost();
        OrdinaryNotificationSessionFinalizer lifecycle = newLifecycle(host, 1);
        lifecycle.markPrepared(1);
        lifecycle.launchFinished(1);
        assertTrue(lifecycle.requestFinish(1));
        assertEquals(1, host.restores);
        assertEquals(1, host.taskRestores);
        assertFalse(lifecycle.isTracked(1));
    }

    @Test public void previouslyEndedSessionCannotRestoreAfterSuccessorTakesOwnership() {
        FakeHost host = new FakeHost();
        OrdinaryNotificationSessionFinalizer lifecycle = newLifecycle(host, 1);
        lifecycle.markPrepared(1);
        Object windowA = new Object();
        assertTrue(lifecycle.activityAttached(1, windowA));
        assertTrue(lifecycle.requestFinish(1));
        host.current = 2;
        lifecycle.register(2, host);
        lifecycle.activityHandoff(1, windowA);
        assertTrue(lifecycle.activityAttached(2, new Object()));
        assertTrue(lifecycle.activityDestroyed(1, windowA));
        lifecycle.launchFinished(1);
        assertEquals(2, host.current);
        assertEquals(0, host.restores);
        assertFalse(lifecycle.isTracked(1));
        assertTrue(lifecycle.isTracked(2));
    }

    @Test public void configurationDetachThenRemovalRestoresWithoutReplacementWindow() {
        FakeHost host = new FakeHost();
        OrdinaryNotificationSessionFinalizer lifecycle = newLifecycle(host, 1);
        lifecycle.markPrepared(1);
        Object oldWindow = new Object();
        assertTrue(lifecycle.activityAttached(1, oldWindow));
        lifecycle.launchFinished(1);
        lifecycle.activityDetachedForRecreation(1, oldWindow);
        assertEquals(0, host.restores); // Configuration change alone does not end the session.
        assertTrue(lifecycle.requestFinish(1)); // No replacement Activity was created.
        assertEquals(1, host.restores);
        assertFalse(lifecycle.isTracked(1));
    }

    @Test public void removalThenActivityExitWaitsUntilLaunchWorkerFinishes() {
        FakeHost host = new FakeHost();
        host.resumeCharging = true;
        OrdinaryNotificationSessionFinalizer lifecycle = newLifecycle(host, 1);
        lifecycle.markPrepared(1);
        Object window = new Object();
        assertTrue(lifecycle.activityAttached(1, window));
        assertTrue(lifecycle.requestFinish(1));
        assertTrue(lifecycle.activityDestroyed(1, window));
        assertEquals(0, host.restores);
        assertEquals(0, host.chargingResumes);
        lifecycle.launchFinished(1);
        assertEquals(1, host.restores);
        assertEquals(1, host.chargingResumes);
    }

    @Test public void staleConfigurationWindowCannotDetachSameSessionReplacement() {
        FakeHost host = new FakeHost();
        OrdinaryNotificationSessionFinalizer lifecycle = newLifecycle(host, 1);
        lifecycle.markPrepared(1);
        Object oldWindow = new Object();
        Object newWindow = new Object();
        assertTrue(lifecycle.activityAttached(1, oldWindow));
        lifecycle.activityDetachedForRecreation(1, oldWindow);
        assertTrue(lifecycle.activityAttached(1, newWindow));
        assertTrue(lifecycle.activityDestroyed(1, oldWindow));
        assertTrue(lifecycle.requestFinish(1));
        assertEquals(1, host.finishBroadcasts);
        assertEquals(0, host.restores);
        assertTrue(lifecycle.activityDestroyed(1, newWindow));
        assertEquals(0, host.restores);
        lifecycle.launchFinished(1);
        assertEquals(1, host.restores);
    }
}
