package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.Display;
import java.util.Arrays;
import org.junit.Test;

public final class CurrentMainTaskSelectorTest {
    private static final String HOST = BuildConfig.APPLICATION_ID;
    private static final String SAMPLE = "dev.remrss.sample";
    private static final String OTHER = "com.example.other";
    private static final String HOME = "com.miui.home";

    @Test public void hiddenStackPredecessorDoesNotReplaceFocusedVisibleApp() {
        RearDisplayCastOccupancy.Snapshot snapshot = snapshot(22,
                task(18, OTHER, OTHER, false, false, false), // Older/hidden task listed first.
                task(22, SAMPLE, SAMPLE, true, true, false),
                task(25, HOST, HOST, false, false, false)); // Transparent URI receiver task.

        CurrentMainTaskSelector.Result result = CurrentMainTaskSelector.resolve(snapshot, HOST);

        assertEquals("resolved", result.reason);
        assertEquals(22, result.target.taskId);
        assertEquals(SAMPLE, result.target.packageName);
        assertEquals(SAMPLE + ":22", result.target.legacyForegroundValue());
    }

    @Test public void focusedTaskPreservesMultiWindowSelection() {
        RearDisplayCastOccupancy.Snapshot snapshot = snapshot(72,
                task(71, OTHER, OTHER, true, true, false),
                task(72, SAMPLE, SAMPLE, true, true, false));

        CurrentMainTaskSelector.Result result = CurrentMainTaskSelector.resolve(snapshot, HOST);

        assertEquals("resolved", result.reason);
        assertEquals(72, result.target.taskId);
        assertEquals(SAMPLE, result.target.packageName);
    }

    @Test public void uriHostOrLauncherFocusIsTransientAndNeverSelected() {
        for (RearDisplayCastOccupancy.Task focus : Arrays.asList(
                task(90, HOST, HOST, true, true, false),
                task(91, HOME, HOME, true, true, false))) {
            CurrentMainTaskSelector.Result result = CurrentMainTaskSelector.resolve(snapshot(focus.taskId, focus), HOST);
            assertNull(result.target);
            assertTrue(result.retryable);
        }
    }

    @Test public void hiddenSleepingUnknownOrMismatchedFocusedTaskIsNeverMoved() {
        for (RearDisplayCastOccupancy.Task focus : Arrays.asList(
                task(41, SAMPLE, SAMPLE, false, false, false),
                task(42, SAMPLE, SAMPLE, false, false, true),
                task(43, SAMPLE, null, true, true, false),
                task(44, SAMPLE, OTHER, true, true, false))) {
            CurrentMainTaskSelector.Result result = CurrentMainTaskSelector.resolve(snapshot(focus.taskId, focus), HOST);
            assertNull(result.target);
            assertFalse("identity or visibility ambiguity must not be polled into permission", result.retryable
                    && "focused_component_package_mismatch".equals(result.reason));
        }
    }

    @Test public void unavailableFocusAndCompatibilityStackSnapshotFailClosed() {
        RearDisplayCastOccupancy.Snapshot noFocus = new RearDisplayCastOccupancy.Snapshot(0,
                Display.STATE_ON,
                Arrays.asList(task(22, SAMPLE, SAMPLE, true, true, false)), "activity_task", null);
        assertEquals("focused_task_unavailable", CurrentMainTaskSelector.resolve(noFocus, HOST).reason);
        assertEquals("typed_snapshot_unavailable", CurrentMainTaskSelector.resolve(
                snapshot(22, task(22, SAMPLE, SAMPLE, true, true, false)), "com.example.untrusted.host").reason);

        RearDisplayCastOccupancy.Snapshot shell = new RearDisplayCastOccupancy.Snapshot(0,
                Display.STATE_ON, Display.STATE_ON, Display.STATE_ON,
                Arrays.asList(task(22, SAMPLE, SAMPLE, true, true, false)),
                "am_stack_list", null);
        assertEquals("typed_snapshot_unavailable", CurrentMainTaskSelector.resolve(shell, HOST).reason);

        RearDisplayCastOccupancy.Snapshot wrongDisplay = new RearDisplayCastOccupancy.Snapshot(1,
                Display.STATE_ON, Display.STATE_ON, Display.STATE_ON,
                Arrays.asList(task(22, SAMPLE, SAMPLE, true, true, false)),
                "activity_task", null);
        assertEquals("typed_snapshot_unavailable", CurrentMainTaskSelector.resolve(wrongDisplay, HOST).reason);
    }

    @Test public void queryReadsOnlyThePrimaryTypedSnapshot() throws Exception {
        int[] calls = {0};
        CurrentMainTaskSelector.Result result = CurrentMainTaskSelector.read(displayId -> {
            calls[0]++;
            assertEquals(0, displayId);
            return snapshot(22, task(22, SAMPLE, SAMPLE, true, true, false)).toJson();
        }, HOST);
        assertEquals(1, calls[0]);
        assertEquals(SAMPLE, result.target.packageName);
    }

    private static RearDisplayCastOccupancy.Snapshot snapshot(int focusedTask,
                                                               RearDisplayCastOccupancy.Task... tasks) {
        return new RearDisplayCastOccupancy.Snapshot(0, Display.STATE_ON, Display.STATE_ON,
                Display.STATE_ON, focusedTask, Arrays.asList(tasks), "activity_task", null);
    }

    private static RearDisplayCastOccupancy.Task task(int taskId, String base, String top,
                                                       Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(taskId, base, top, visible, requested, sleeping);
    }
}
