package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class PhysicalCastRequestFlowTest {
    private static final String PUBLISHER = "dev.remrss.sample.consumer";

    private static final class FakePort implements PhysicalCastRequestFlow.Port {
        final List<String> events = new ArrayList<>();
        final String[] foregroundByAttempt;
        boolean moveResult = true;
        boolean moveThrows;
        boolean launchThrows;
        boolean keeperThrows;
        boolean keeperAccepted = true;
        boolean publisherAlive = true;
        int keeperStarts;
        int projectionCommits;
        int launcherDisables;
        int rollbacks;

        FakePort(String... foregroundByAttempt) { this.foregroundByAttempt = foregroundByAttempt; }

        @Override public void prepare() { events.add("prepare"); }
        @Override public String launchAndObserve(int attempt) {
            events.add("launch:" + attempt);
            if (launchThrows) throw new IllegalStateException("target launch failed");
            return attempt < foregroundByAttempt.length ? foregroundByAttempt[attempt] : null;
        }
        @Override public boolean moveToRear(int taskId) throws Exception {
            events.add("move:" + taskId);
            if (moveThrows) throw new Exception("move failed");
            return moveResult;
        }
        @Override public void projectionCommitted(int taskId, String packageName) {
            events.add("projection:" + packageName + ":" + taskId);
            projectionCommits++;
            publisherAlive = false;
        }
        @Override public void disableLauncher(PhysicalCastRequestFlow.Target target) {
            events.add("launcher_disable:" + target.taskId); launcherDisables++;
        }
        @Override public boolean startKeeper(String packageTask) throws Exception {
            events.add("keeper:" + packageTask);
            if (keeperThrows) throw new Exception("startForegroundService rejected");
            if (keeperAccepted) keeperStarts++;
            return keeperAccepted;
        }
        @Override public void rollbackPreparation() { events.add("rollback"); rollbacks++; }
        @Override public void postCommitFailure(Exception failure) { events.add("post_commit_failure"); }
    }

    @Test public void livePublisherIsNotStoppedAndOnlyEndsAtSuccessfulProjectionCommit() {
        FakePort port = new FakePort(PUBLISHER + ":71");
        PhysicalCastRequestFlow.Result result = PhysicalCastRequestFlow.launchAndHandoff(PUBLISHER, 3, port);

        assertTrue(result.committed);
        assertEquals(PUBLISHER, result.target.packageName);
        assertEquals(71, result.target.taskId);
        assertFalse("the owner ends only after the verified move commit", port.publisherAlive);
        assertEquals(1, port.projectionCommits);
        assertEquals(1, port.keeperStarts);
        assertEquals(1, port.launcherDisables);
        assertEquals(0, port.rollbacks);
        assertEquals("prepare,launch:0,move:71,projection:dev.remrss.sample.consumer:71,keeper:dev.remrss.sample.consumer:71,launcher_disable:71",
                String.join(",", port.events));
    }

    @Test public void currentForegroundRequestUsesTheSamePostMoveCommitBoundary() {
        FakePort port = new FakePort(PUBLISHER + ":73");
        PhysicalCastRequestFlow.Result result = PhysicalCastRequestFlow.prepareAndHandoff(
                new PhysicalCastRequestFlow.Target(PUBLISHER, 73), port);

        assertTrue(result.committed);
        assertEquals(1, port.projectionCommits);
        assertEquals(1, port.keeperStarts);
        assertEquals(0, port.rollbacks);
        assertEquals("prepare,move:73,projection:dev.remrss.sample.consumer:73,keeper:dev.remrss.sample.consumer:73,launcher_disable:73",
                String.join(",", port.events));
    }

    @Test public void keeperCreationFailureKeepsCommittedTaskButLeavesLauncherAndReturnFallbackAvailable() {
        FakePort port = new FakePort(PUBLISHER + ":74");
        port.keeperThrows = true;
        PhysicalCastRequestFlow.Result result = PhysicalCastRequestFlow.launchAndHandoff(PUBLISHER, 3, port);

        assertTrue("a verified physical move remains committed", result.committed);
        assertFalse("service-start failure is explicit in the result", result.keeperStarted);
        assertEquals(1, port.projectionCommits);
        assertEquals(0, port.keeperStarts);
        assertEquals("without a Keeper, do not disable the launcher", 0, port.launcherDisables);
        assertFalse("the successful move committed ownership after preserving the prior publisher until then",
                port.publisherAlive);
        assertEquals("prepare,launch:0,move:74,projection:dev.remrss.sample.consumer:74,keeper:dev.remrss.sample.consumer:74,post_commit_failure",
                String.join(",", port.events));
    }

    @Test public void nullKeeperAcceptanceDoesNotDisableLauncher() {
        FakePort port = new FakePort(PUBLISHER + ":75");
        port.keeperAccepted = false;
        PhysicalCastRequestFlow.Result result = PhysicalCastRequestFlow.launchAndHandoff(PUBLISHER, 3, port);

        assertTrue(result.committed);
        assertFalse(result.keeperStarted);
        assertEquals(0, port.launcherDisables);
        assertEquals(1, port.projectionCommits);
    }

    @Test public void launchResolutionFailureRollsBackConfigWithoutTakingOwnershipOrStartingKeeper() {
        FakePort port = new FakePort(null, "com.android.launcher:1", "com.android.settings:9");
        PhysicalCastRequestFlow.Result result = PhysicalCastRequestFlow.launchAndHandoff(PUBLISHER, 3, port);

        assertFalse(result.committed);
        assertEquals("target_unresolved", result.failure);
        assertEquals(0, port.projectionCommits);
        assertEquals(0, port.keeperStarts);
        assertEquals(0, port.launcherDisables);
        assertEquals(1, port.rollbacks);
        assertTrue(port.publisherAlive);
        assertEquals(3, port.events.stream().filter(event -> event.startsWith("launch:")).count());
    }

    @Test public void launchFailureRollsBackBeforeAnyMoveOrOwnershipChange() {
        FakePort port = new FakePort();
        port.launchThrows = true;
        PhysicalCastRequestFlow.Result result = PhysicalCastRequestFlow.launchAndHandoff(PUBLISHER, 3, port);

        assertFalse(result.committed);
        assertEquals(0, port.events.stream().filter(event -> event.startsWith("move:")).count());
        assertEquals(0, port.projectionCommits);
        assertEquals(0, port.keeperStarts);
        assertEquals(0, port.launcherDisables);
        assertEquals(1, port.rollbacks);
        assertTrue(port.publisherAlive);
    }

    @Test public void moveRejectionAndExceptionDoNotCommitOrCreateKeeper() {
        for (boolean throwsFromMove : new boolean[] {false, true}) {
            FakePort port = new FakePort(PUBLISHER + ":72");
            port.moveResult = !throwsFromMove;
            port.moveThrows = throwsFromMove;
            if (!throwsFromMove) port.moveResult = false;

            PhysicalCastRequestFlow.Result result = PhysicalCastRequestFlow.launchAndHandoff(PUBLISHER, 3, port);
            assertFalse(result.committed);
            assertEquals(0, port.projectionCommits);
            assertEquals(0, port.keeperStarts);
            assertEquals(0, port.launcherDisables);
            assertEquals(1, port.rollbacks);
            assertTrue(port.publisherAlive);
        }
    }

    @Test public void mismatchedThirdPartyForegroundIsNeverStoppedAndOnlyRequestedTargetIsRetried() {
        FakePort port = new FakePort("com.android.launcher:4", "com.android.settings:5", null);
        PhysicalCastRequestFlow.Result result = PhysicalCastRequestFlow.launchAndHandoff(PUBLISHER, 3, port);

        assertFalse(result.committed);
        assertEquals(0, port.projectionCommits);
        assertEquals(0, port.keeperStarts);
        assertTrue(port.publisherAlive);
        assertTrue(port.events.contains("launch:0"));
        assertTrue(port.events.contains("launch:1"));
        assertTrue(port.events.contains("launch:2"));
        assertEquals("no unrelated package stop operation exists in the production flow", 0,
                port.events.stream().filter(event -> event.startsWith("force_stop:")).count());
        assertNotNull(result.failure);
    }
}
