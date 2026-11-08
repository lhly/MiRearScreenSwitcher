package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RearDisplayCastOccupancyReaderFocusTest {
    public static final class RootTaskInfo {
        public final int displayId;
        public final int taskId;
        public final int[] childTaskIds;
        public final Component topActivity;
        public final boolean isVisible;
        RootTaskInfo(int displayId, int taskId) {
            this(displayId, taskId, new int[]{taskId}, "dev.remrss.sample/.MainActivity", true);
        }
        RootTaskInfo(int displayId, int taskId, int[] children, String top, boolean visible) {
            this.displayId = displayId; this.taskId = taskId; this.childTaskIds = children;
            this.topActivity = top == null ? null : new Component(top); this.isVisible = visible;
        }
    }

    public static final class FocusManager {
        private final RootTaskInfo focused;
        FocusManager(RootTaskInfo focused) { this.focused = focused; }
        public RootTaskInfo getFocusedRootTaskInfo() { return focused; }
    }

    public static final class RootManager {
        private final RootTaskInfo focused;
        private final Runnable onRead;
        RootManager(RootTaskInfo focused) { this(focused, null); }
        RootManager(RootTaskInfo focused, Runnable onRead) { this.focused = focused; this.onRead = onRead; }
        public RootTaskInfo getFocusedRootTaskInfo() {
            if (onRead != null) onRead.run();
            return focused;
        }
    }

    public static final class MissingFocusManager {}

    public static final class Component {
        private final String value;
        Component(String value) { this.value = value; }
        public String flattenToString() { return value; }
    }

    public static final class LegacyStackInfo {
        public final int stackId;
        public final int displayId;
        public final boolean visible;
        public final int[] taskIds = new int[]{13, 42};
        public final Component topActivity;
        LegacyStackInfo(int stackId, int displayId, boolean visible, String top) {
            this.stackId = stackId;
            this.displayId = displayId;
            this.visible = visible;
            this.topActivity = new Component(top);
        }
    }

    public static final class LegacyTask {
        public final int id;
        public final int displayId = 0;
        public final Component baseActivity, topActivity;
        LegacyTask(int id, String base, String top) {
            this.id = id;
            baseActivity = new Component(base);
            topActivity = new Component(top);
        }
    }

    /** Android 10/11 TaskInfo shape: no visibility/requested/sleeping fields. */
    public static final class Android10Task {
        public final int taskId, displayId;
        public final Component baseActivity, topActivity;
        Android10Task(int id, String base, String top) {
            taskId = id; displayId = 0;
            baseActivity = new Component(base); topActivity = new Component(top);
        }
    }

    /** Android 11 has the same relevant RunningTaskInfo visibility shape as Android 10. */
    public static final class Android11Task {
        public final int taskId, displayId;
        public final Component baseActivity, topActivity;
        Android11Task(int id, String base, String top) {
            taskId = id; displayId = 0;
            baseActivity = new Component(base); topActivity = new Component(top);
        }
    }

    public static final class RootTaskWithoutVisibility {
        public final int displayId = 0, taskId = 100;
        public final int[] childTaskIds = new int[]{42};
        public final Component topActivity = new Component("dev.remrss.sample/.MainActivity");
    }

    public static final class UnknownVisibilityManager {
        private final RootTaskWithoutVisibility focused = new RootTaskWithoutVisibility();
        public RootTaskWithoutVisibility getFocusedRootTaskInfo() { return focused; }
    }

    /** Android 12 TaskInfo has visible, but not requested or sleeping. */
    public static final class Android12Task {
        public final int taskId, displayId;
        public final Component baseActivity, topActivity;
        public final boolean isVisible;
        Android12Task(int id, String base, String top, boolean visible) {
            taskId = id; displayId = 0; baseActivity = new Component(base);
            topActivity = new Component(top); isVisible = visible;
        }
    }

    /** Android 13 TaskInfo adds sleeping but still has no requested field. */
    public static final class Android13Task {
        public final int taskId, displayId;
        public final Component baseActivity, topActivity;
        public final boolean isVisible, isSleeping;
        Android13Task(int id, String base, String top, boolean visible, boolean sleeping) {
            taskId = id; displayId = 0; baseActivity = new Component(base);
            topActivity = new Component(top); isVisible = visible; isSleeping = sleeping;
        }
    }

    public static final class Android14Task {
        public final int taskId, displayId;
        public final Component baseActivity, topActivity;
        public final boolean isVisible, isVisibleRequested, isSleeping;
        Android14Task(int id, String base, String top, boolean visible, boolean requested, boolean sleeping) {
            taskId = id; displayId = 0; baseActivity = new Component(base);
            topActivity = new Component(top); isVisible = visible;
            isVisibleRequested = requested; isSleeping = sleeping;
        }
    }

    public static final class LegacyManager {
        private final LegacyStackInfo stack;
        LegacyManager(LegacyStackInfo stack) { this.stack = stack; }
        public java.util.List<LegacyTask> getTasks(int max) {
            // Deliberately not focused-first: selection must follow StackInfo.taskIds/topActivity.
            return java.util.Arrays.asList(
                    new LegacyTask(7, "com.example.hidden/.Main", "com.example.hidden/.Main"),
                    new LegacyTask(42, "dev.remrss.sample/.MainActivity", "dev.remrss.sample/.MainActivity"),
                    new LegacyTask(13, "com.example.old/.Main", "com.example.old/.Main"));
        }
    }

    public static final class ActivityTaskStackManager {
        private final LegacyStackInfo stack;
        ActivityTaskStackManager(LegacyStackInfo stack) { this.stack = stack; }
        public LegacyStackInfo getFocusedStackInfo() { return stack; }
    }

    public static final class LegacyFlagsManager {
        public java.util.List<LegacyTask> getTasks(int max, int flags) {
            assertEquals(1000, max);
            assertEquals(0, flags);
            return java.util.Collections.singletonList(
                    new LegacyTask(42, "dev.remrss.sample/.MainActivity", "dev.remrss.sample/.MainActivity"));
        }
    }

    public static final class IdOnlyLegacyManager {
        private final java.util.List<LegacyStackInfo> stacks;
        IdOnlyLegacyManager(java.util.List<LegacyStackInfo> stacks) { this.stacks = stacks; }
        public int getFocusedStackId() { return 9; }
        public java.util.List<LegacyStackInfo> getAllStackInfos() { return stacks; }
    }

    @Test public void focusedRootTaskIsAcceptedOnlyOnRequestedDisplay() {
        FocusManager manager = new FocusManager(new RootTaskInfo(0, 42));
        assertEquals(42, RearDisplayCastOccupancyReader.focusedRootTaskId(manager, 0));
        assertEquals(-1, RearDisplayCastOccupancyReader.focusedRootTaskId(manager, 1));
    }

    @Test public void missingFocusedRootIsUnavailableNotTaskListFallback() {
        assertEquals(-1, RearDisplayCastOccupancyReader.focusedRootTaskId(
                new FocusManager(null), 0));
        assertEquals(-1, RearDisplayCastOccupancyReader.focusedRootTaskId(
                new MissingFocusManager(), 0));
    }

    @Test public void productionRootReaderNormalizesContainerToExactFocusedLeaf() throws Exception {
        String top = "dev.remrss.sample/.MainActivity";
        RootTaskInfo root = new RootTaskInfo(0, 100, new int[]{13, 42}, top, true);
        java.util.List<Android14Task> rows = java.util.Arrays.asList(
                // A container-shaped row is not a leaf when it lists different child task ids.
                new Android14Task(100, top, top, true, true, false),
                new Android14Task(13, "com.example.other/.Main", "com.example.other/.Main", true, true, false),
                new Android14Task(42, top, top, true, true, false));
        RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.fromFocusedTaskInfos(
                new RootManager(root), rows, 0, android.view.Display.STATE_ON,
                android.view.Display.STATE_ON, android.view.Display.STATE_ON);
        snapshot = RearDisplayCastOccupancy.Snapshot.parse(snapshot.toJson(), 0);
        assertEquals(42, snapshot.focusedTaskId);
        assertEquals("resolved", CurrentMainTaskSelector.resolve(snapshot, BuildConfig.APPLICATION_ID).reason);

        RootTaskInfo rootIsLeaf = new RootTaskInfo(0, 42, new int[]{42}, top, true);
        RearDisplayCastOccupancy.Snapshot leaf = RearDisplayCastOccupancyReader.fromFocusedTaskInfos(
                new RootManager(rootIsLeaf), rows, 0, android.view.Display.STATE_ON,
                android.view.Display.STATE_ON, android.view.Display.STATE_ON);
        assertEquals(42, leaf.focusedTaskId);
        assertEquals("resolved", CurrentMainTaskSelector.resolve(
                RearDisplayCastOccupancy.Snapshot.parse(leaf.toJson(), 0), BuildConfig.APPLICATION_ID).reason);
    }

    @Test public void rootLeafBindingRejectsUnknownDisplayTopAndAmbiguousChildren() throws Exception {
        String top = "dev.remrss.sample/.MainActivity";
        java.util.List<Android14Task> rows = java.util.Arrays.asList(
                new Android14Task(42, top, top, true, true, false),
                new Android14Task(43, top, top, true, true, false));
        for (RootTaskInfo root : java.util.Arrays.asList(
                new RootTaskInfo(1, 100, new int[]{42}, top, true),
                new RootTaskInfo(0, 100, new int[]{42}, "dev.remrss.sample/.Other", true),
                new RootTaskInfo(0, 100, new int[]{42, 43}, top, true))) {
            RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.fromFocusedTaskInfos(
                    new RootManager(root), rows, 0, android.view.Display.STATE_ON,
                    android.view.Display.STATE_ON, android.view.Display.STATE_ON);
            assertNull(CurrentMainTaskSelector.resolve(snapshot, BuildConfig.APPLICATION_ID).target);
        }
    }

    @Test public void android12And13MissingNewFlagsRemainUsableThroughProductionReaderAndJson() throws Exception {
        String top = "dev.remrss.sample/.MainActivity";
        RootTaskInfo root = new RootTaskInfo(0, 42, new int[]{42}, top, true);
        java.util.List<Object> rows = java.util.Arrays.asList(
                new Android12Task(42, top, top, true),
                new Android13Task(42, top, top, true, false));
        for (Object row : rows) {
            RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.fromFocusedTaskInfos(
                    new RootManager(root), java.util.Collections.singletonList(row), 0,
                    android.view.Display.STATE_ON, android.view.Display.STATE_ON, android.view.Display.STATE_ON);
            snapshot = RearDisplayCastOccupancy.Snapshot.parse(snapshot.toJson(), 0);
            assertEquals("resolved", CurrentMainTaskSelector.resolve(snapshot, BuildConfig.APPLICATION_ID).reason);
            assertNull(snapshot.tasks.get(0).requested);
        }
    }

    @Test public void android10And11StackVisibilityFlowsThroughProductionActivityTaskPath() throws Exception {
        String top = "dev.remrss.sample/.MainActivity";
        for (Object row : java.util.Arrays.asList(new Android10Task(42, top, top), new Android11Task(42, top, top))) {
            LegacyStackInfo stack = new LegacyStackInfo(9, 0, true, top);
            RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.fromFocusedTaskInfos(
                    new ActivityTaskStackManager(stack), java.util.Collections.singletonList(row), 0,
                    android.view.Display.STATE_ON, android.view.Display.STATE_ON, android.view.Display.STATE_ON);
            snapshot = RearDisplayCastOccupancy.Snapshot.parse(snapshot.toJson(), 0);
            assertEquals(42, snapshot.focusedTaskId);
            assertTrue(Boolean.TRUE.equals(snapshot.tasks.get(0).visible));
            assertEquals("resolved", CurrentMainTaskSelector.resolve(snapshot, BuildConfig.APPLICATION_ID).reason);
        }

        Android10Task row = new Android10Task(42, top, top);
        LegacyStackInfo hiddenStack = new LegacyStackInfo(9, 0, false, top);
        RearDisplayCastOccupancy.Snapshot hidden = RearDisplayCastOccupancyReader.fromFocusedTaskInfos(
                new ActivityTaskStackManager(hiddenStack), java.util.Collections.singletonList(row), 0,
                android.view.Display.STATE_ON, android.view.Display.STATE_ON, android.view.Display.STATE_ON);
        assertNull(CurrentMainTaskSelector.resolve(hidden, BuildConfig.APPLICATION_ID).target);
    }

    @Test public void missingVisibilityRemainsUnknownAndIsNotInventedAsVisible() throws Exception {
        String top = "dev.remrss.sample/.MainActivity";
        Android10Task row = new Android10Task(42, top, top);
        RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.fromFocusedTaskInfos(
                new UnknownVisibilityManager(), java.util.Collections.singletonList(row), 0,
                android.view.Display.STATE_ON, android.view.Display.STATE_ON, android.view.Display.STATE_ON);
        snapshot = RearDisplayCastOccupancy.Snapshot.parse(snapshot.toJson(), 0);
        assertNull(snapshot.tasks.get(0).visible);
        assertNull(CurrentMainTaskSelector.resolve(snapshot, BuildConfig.APPLICATION_ID).target);
    }

    @Test public void productionReadRechecksPowerAfterBlockingFocusEvidenceBeforeJsonSelection() throws Exception {
        String top = "dev.remrss.sample/.MainActivity";
        RootTaskInfo root = new RootTaskInfo(0, 42, new int[]{42}, top, true);
        java.util.List<Android14Task> rows = java.util.Collections.singletonList(
                new Android14Task(42, top, top, true, true, false));
        int[] power = {android.view.Display.STATE_ON};
        int[] powerReads = {0};
        RootManager dropsPowerWhileReadingFocus = new RootManager(root,
                () -> power[0] = android.view.Display.STATE_OFF);
        RearDisplayCastOccupancy.Snapshot wentOff = RearDisplayCastOccupancyReader.readAfterFocus(
                dropsPowerWhileReadingFocus, rows, 0, android.view.Display.STATE_ON, display -> {
                    assertEquals(0, display);
                    powerReads[0]++;
                    return power[0];
                });
        assertEquals(1, powerReads[0]);
        RearDisplayCastOccupancy.Snapshot encodedOff = RearDisplayCastOccupancy.Snapshot.parse(wentOff.toJson(), 0);
        assertEquals(android.view.Display.STATE_OFF, encodedOff.powerAfter);
        assertEquals("main_display_not_stably_on",
                CurrentMainTaskSelector.resolve(encodedOff, BuildConfig.APPLICATION_ID).reason);

        power[0] = android.view.Display.STATE_ON;
        RootManager stableFocus = new RootManager(root);
        RearDisplayCastOccupancy.Snapshot stayedOn = RearDisplayCastOccupancyReader.readAfterFocus(
                stableFocus, rows, 0, android.view.Display.STATE_ON, display -> power[0]);
        RearDisplayCastOccupancy.Snapshot encodedOn = RearDisplayCastOccupancy.Snapshot.parse(stayedOn.toJson(), 0);
        assertEquals("resolved", CurrentMainTaskSelector.resolve(encodedOn, BuildConfig.APPLICATION_ID).reason);
    }

    @Test public void explicitModernHiddenOrSleepingEvidenceStillRejects() throws Exception {
        String top = "dev.remrss.sample/.MainActivity";
        RootTaskInfo root = new RootTaskInfo(0, 42, new int[]{42}, top, true);
        for (Android14Task row : java.util.Arrays.asList(
                new Android14Task(42, top, top, false, true, false),
                new Android14Task(42, top, top, true, false, false),
                new Android14Task(42, top, top, true, true, true))) {
            RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.fromFocusedTaskInfos(
                    new RootManager(root), java.util.Collections.singletonList(row), 0,
                    android.view.Display.STATE_ON, android.view.Display.STATE_ON, android.view.Display.STATE_ON);
            assertNull(CurrentMainTaskSelector.resolve(snapshot, BuildConfig.APPLICATION_ID).target);
        }

        RootTaskInfo hiddenRoot = new RootTaskInfo(0, 100, new int[]{42}, top, false);
        RearDisplayCastOccupancy.Snapshot contradictory = RearDisplayCastOccupancyReader.fromFocusedTaskInfos(
                new RootManager(hiddenRoot), java.util.Collections.singletonList(
                        new Android14Task(42, top, top, true, true, false)), 0,
                android.view.Display.STATE_ON, android.view.Display.STATE_ON, android.view.Display.STATE_ON);
        assertNull(CurrentMainTaskSelector.resolve(contradictory, BuildConfig.APPLICATION_ID).target);
    }

    @Test public void legacyFocusedStackSelectsExactTopTaskWithoutRequiringUniqueVisibleTask() throws Exception {
        LegacyStackInfo stack = new LegacyStackInfo(9, 0, true, "dev.remrss.sample/.MainActivity");
        RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.legacyFocusedStackSnapshot(
                new LegacyManager(stack), stack, android.view.Display.STATE_ON, android.view.Display.STATE_ON);
        assertEquals("activity_focused_stack", snapshot.source);
        assertEquals(42, snapshot.focusedTaskId);
        assertEquals(1, snapshot.tasks.size());
        CurrentMainTaskSelector.Result selected = CurrentMainTaskSelector.resolve(snapshot, BuildConfig.APPLICATION_ID);
        assertEquals("resolved", selected.reason);
        assertEquals(42, selected.target.taskId);
        assertEquals("dev.remrss.sample", selected.target.packageName);
    }

    @Test public void legacyFocusedStackFailsClosedOnHiddenOrWrongDisplayFocus() throws Exception {
        LegacyStackInfo hidden = new LegacyStackInfo(9, 0, false, "dev.remrss.sample/.MainActivity");
        RearDisplayCastOccupancy.Snapshot hiddenSnapshot = RearDisplayCastOccupancyReader.legacyFocusedStackSnapshot(
                new LegacyManager(hidden), hidden, android.view.Display.STATE_ON, android.view.Display.STATE_ON);
        CurrentMainTaskSelector.Result hiddenResult = CurrentMainTaskSelector.resolve(
                RearDisplayCastOccupancy.Snapshot.parse(hiddenSnapshot.toJson(), 0), BuildConfig.APPLICATION_ID);
        assertNull(hiddenResult.target);
        assertEquals("focused_task_not_presented", hiddenResult.reason);
        assertTrue(hiddenResult.retryable);
        LegacyStackInfo rear = new LegacyStackInfo(9, 1, true, "dev.remrss.sample/.MainActivity");
        RearDisplayCastOccupancy.Snapshot wrongDisplay = RearDisplayCastOccupancyReader.legacyFocusedStackSnapshot(
                new LegacyManager(rear), rear, android.view.Display.STATE_ON, android.view.Display.STATE_ON);
        assertNull(CurrentMainTaskSelector.resolve(wrongDisplay, BuildConfig.APPLICATION_ID).target);
        assertTrue(wrongDisplay.failure != null);
    }

    @Test public void oldestFocusedStackIdApiResolvesMatchingStackInsteadOfFirstStack() throws Exception {
        LegacyStackInfo nonFocused = new LegacyStackInfo(4, 0, true, "com.example.other/.Main");
        LegacyStackInfo focused = new LegacyStackInfo(9, 0, true, "dev.remrss.sample/.MainActivity");
        Object selected = RearDisplayCastOccupancyReader.activityManagerFocusedStack(
                new IdOnlyLegacyManager(java.util.Arrays.asList(nonFocused, focused)));
        assertEquals(9, selected.getClass().getField("stackId").getInt(selected));
        RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.legacyFocusedStackSnapshot(
                new LegacyManager(focused), focused, android.view.Display.STATE_ON, android.view.Display.STATE_ON);
        assertEquals(42, snapshot.focusedTaskId);
    }

    @Test public void legacyTwoArgumentTaskQueryIsSupportedForOldActivityManager() throws Exception {
        LegacyStackInfo focused = new LegacyStackInfo(9, 0, true, "dev.remrss.sample/.MainActivity");
        RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.legacyFocusedStackSnapshot(
                new LegacyFlagsManager(), focused, android.view.Display.STATE_ON, android.view.Display.STATE_ON);
        assertEquals(42, snapshot.focusedTaskId);
    }
}
