package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class PhysicalCastReturnFlowTest {
    private static final String TARGET = "dev.remrss.sample.consumer:81";

    private static final class FakePort implements PhysicalCastReturnFlow.Port {
        final List<String> events = new ArrayList<>();
        boolean onRear = true;
        boolean moveSucceeds = true;
        boolean launcherSafe = true;
        boolean launcherRestoreSucceeds = true;
        boolean launcherRestoreThrows;
        boolean keeperStopSucceeds = true;

        @Override public boolean isExactTaskOnRear(int taskId, String packageName) {
            events.add("check:" + taskId + ":" + packageName);
            return onRear;
        }
        @Override public boolean moveExactTaskToMain(int taskId) {
            events.add("move:" + taskId);
            if (moveSucceeds) onRear = false;
            return moveSucceeds;
        }
        @Override public boolean mayRestoreLauncher() { events.add("restore_gate"); return launcherSafe; }
        @Override public boolean restoreLauncher() {
            events.add("restore_launcher");
            if (launcherRestoreThrows) throw new IllegalStateException("ITaskService transaction failed");
            return launcherRestoreSucceeds;
        }
        @Override public boolean stopKeeperForExactTask(String task, long generation) {
            events.add("stop_keeper:" + task + ":" + generation);
            return keeperStopSucceeds;
        }
        @Override public void clearExactOwnership(int taskId, long generation) { events.add("clear:" + taskId + ":" + generation); }
    }

    @Test public void committedTaskRemainsReturnableWhenKeeperWasNeverCreated() throws Exception {
        FakePort port = new FakePort();
        PhysicalCastReturnFlow.Result result = PhysicalCastReturnFlow.stop(TARGET, false, false, 14L, port);

        assertEquals(PhysicalCastReturnFlow.Result.STOPPED, result);
        assertEquals("check:81:dev.remrss.sample.consumer,move:81,clear:81:14",
                String.join(",", port.events));
        assertFalse("fallback return does not touch Launcher state it never disabled",
                port.events.contains("restore_launcher"));
    }

    @Test public void launcherIsRestoredOnlyForRecordedOwnerAndOnlyWhenRearHasNoNewOwner() throws Exception {
        FakePort owned = new FakePort();
        assertEquals(PhysicalCastReturnFlow.Result.STOPPED,
                PhysicalCastReturnFlow.stop(TARGET, false, true, 14L, owned));
        assertTrue(owned.events.contains("restore_launcher"));

        FakePort newerOwner = new FakePort();
        newerOwner.launcherSafe = false;
        assertEquals(PhysicalCastReturnFlow.Result.STOPPED,
                PhysicalCastReturnFlow.stop(TARGET, false, true, 14L, newerOwner));
        assertFalse("a newer rear owner keeps its Launcher state", newerOwner.events.contains("restore_launcher"));
        assertFalse("teardown will not make a second restore decision", newerOwner.events.contains("restore_launcher"));
    }

    @Test public void failedMovePreservesKeeperAndCommittedReturnRecord() throws Exception {
        FakePort port = new FakePort();
        port.moveSucceeds = false;
        assertEquals(PhysicalCastReturnFlow.Result.MOVE_FAILED,
                PhysicalCastReturnFlow.stop(TARGET, true, true, 14L, port));
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("stop_keeper:")));
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("clear:")));
        assertFalse(port.events.contains("restore_launcher"));
    }

    @Test public void vanishedTaskCanStillReleaseOwnedLauncherButCannotMoveAReusedTask() throws Exception {
        FakePort port = new FakePort();
        port.onRear = false;
        assertEquals(PhysicalCastReturnFlow.Result.STOPPED,
                PhysicalCastReturnFlow.stop(TARGET, false, true, 14L, port));
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("move:")));
        assertTrue(port.events.contains("restore_launcher"));
        assertTrue(port.events.contains("clear:81:14"));
    }

    @Test public void failedRestoreStillStopsExactKeeperButRetainsCleanupRecordForRetry() throws Exception {
        FakePort port = new FakePort();
        port.launcherRestoreSucceeds = false;
        assertEquals(PhysicalCastReturnFlow.Result.RESTORE_FAILED,
                PhysicalCastReturnFlow.stop(TARGET, true, true, 14L, port));
        assertTrue("authorized same-owner Keeper teardown still runs", port.events.contains("stop_keeper:" + TARGET + ":14"));
        assertFalse("failed launcher restore keeps the precise cleanup record", port.events.stream().anyMatch(e -> e.startsWith("clear:")));
    }

    @Test public void thrownPrivilegedLauncherRestoreStillStopsKeeperAndRetainsExactCleanupRecord() throws Exception {
        FakePort port = new FakePort();
        port.launcherRestoreThrows = true;
        assertEquals(PhysicalCastReturnFlow.Result.RESTORE_FAILED,
                PhysicalCastReturnFlow.stop(TARGET, true, true, 14L, port));
        assertTrue("restore RemoteException must not bypass same-generation Keeper teardown",
                port.events.contains("stop_keeper:" + TARGET + ":14"));
        assertFalse("the exact launcher cleanup record remains available for retry",
                port.events.stream().anyMatch(event -> event.startsWith("clear:")));
    }

    @Test public void failedActualKeeperStopDoesNotClearEffectiveOwnerRecordOrReportStopped() throws Exception {
        FakePort port = new FakePort();
        port.keeperStopSucceeds = false;
        assertEquals(PhysicalCastReturnFlow.Result.STOP_FAILED,
                PhysicalCastReturnFlow.stop(TARGET, true, false, 14L, port));
        assertFalse(port.events.stream().anyMatch(e -> e.startsWith("clear:")));
    }

    @Test public void refusedLauncherRestoreIsCarriedThroughSameOwnerTeardownWithoutClearingRetryRecord() throws Exception {
        FakePort port = new FakePort();
        port.launcherSafe = false;
        assertEquals(PhysicalCastReturnFlow.Result.STOPPED,
                PhysicalCastReturnFlow.stop(TARGET, true, true, 14L, port));
        assertTrue(port.events.contains("stop_keeper:" + TARGET + ":14"));
        assertFalse("preserve newer owner and its launcher state", port.events.contains("restore_launcher"));
        assertFalse("retain a generation-scoped launcher cleanup record for a later safe return", port.events.stream().anyMatch(e -> e.startsWith("clear:")));
    }
}
