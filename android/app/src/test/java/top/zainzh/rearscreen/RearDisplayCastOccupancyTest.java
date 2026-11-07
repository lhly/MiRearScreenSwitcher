package top.zainzh.rearscreen;

import android.view.Display;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;
import static top.zainzh.rearscreen.RearDisplayCastOccupancy.OwnerDisposition.*;

public class RearDisplayCastOccupancyTest {
    private static final String HOST = "top.zainzh.rearscreen";
    private static final String HOME = "com.xiaomi.subscreencenter";
    private static final String APP = "com.example.reader";
    private static final RearDisplayCastOccupancy.SceneTaskMatcher NO_SCENE = (task, display) -> false;

    @Test public void hiddenFirstTaskDoesNotObscureActualLauncherOrKeepAnEmptyDisplayBusy() {
        RearDisplayCastOccupancy.Task hidden = task(10, APP, false, false, false);
        RearDisplayCastOccupancy.Result home = evaluate(hidden, task(11, HOME, true, true, false));
        assertTrue(home.allowed());
        assertEquals(SYSTEM_LAUNCHER, home.disposition);
        assertEquals(1, home.hiddenTasks);
        assertEquals(HOME + ":11", home.foreground);
        assertEquals(EMPTY, evaluate(hidden).disposition);
    }

    @Test public void everyVisibleThirdPartyIsProtectedEvenAfterAnAllowedHostOrLauncher() {
        for (String permitted : new String[]{HOME, HOST}) {
            RearDisplayCastOccupancy.Result result = evaluate(task(11, permitted, true, true, false),
                    task(10, APP, true, true, false));
            assertFalse(result.allowed());
            assertEquals(APP + ":10", result.foreground);
        }
    }

    @Test public void aPendingShowOrSleepingTaskIsNotStaleEvenIfCurrentlyInvisible() {
        assertFalse(evaluate(task(10, APP, false, true, false)).allowed());
        assertFalse(evaluate(task(10, APP, false, false, true)).allowed());
    }

    @Test public void missingAnyVisibilityEvidenceRemainsProtected() {
        assertFalse(evaluate(task(10, APP, null, false, false)).allowed());
        assertFalse(evaluate(task(10, APP, false, null, false)).allowed());
        assertFalse(evaluate(task(10, APP, false, false, null)).allowed());
        assertFalse(evaluate(task(10, APP, null, null, null)).allowed());
    }

    @Test public void offDozeOrUnknownDisplayDoesNotReleaseAnAppClaim() {
        for (int state : new int[]{Display.STATE_UNKNOWN, Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND}) {
            RearDisplayCastOccupancy.Snapshot snapshot = snapshot(7, state, task(10, APP, false, false, false));
            assertFalse(RearDisplayCastOccupancy.evaluate(snapshot, HOST, NO_SCENE).allowed());
        }
    }

    @Test public void sleepWakeDuringTheSnapshotDoesNotMakeATaskLookStale() {
        for (int[] edge : new int[][]{{Display.STATE_ON, Display.STATE_OFF},
                {Display.STATE_OFF, Display.STATE_ON}, {Display.STATE_ON, Display.STATE_UNKNOWN}}) {
            int power = RearDisplayCastOccupancyReader.stablePower(edge[0], edge[1]);
            assertFalse(RearDisplayCastOccupancy.evaluate(snapshot(7, power, task(10, APP, false, false, false)), HOST, NO_SCENE).allowed());
        }
        assertEquals(Display.STATE_ON, RearDisplayCastOccupancyReader.stablePower(Display.STATE_ON, Display.STATE_ON));
    }

    @Test public void exactLiveManagedSceneExceptionIsPreservedButNotWrongTaskDisplayOrClosedScene() {
        RearDisplayCastOccupancy.Snapshot snapshot = snapshot(7, Display.STATE_ON, task(42, APP, true, true, false));
        RearDisplayCastOccupancy.SceneTaskMatcher live = (foreground, display) -> (APP + ":42").equals(foreground) && display == 7;
        assertEquals(CORE_SCENE, RearDisplayCastOccupancy.evaluate(snapshot, HOST, live).disposition);
        assertFalse(RearDisplayCastOccupancy.evaluate(snapshot(8, Display.STATE_ON, task(42, APP, true, true, false)), HOST, live).allowed());
        assertFalse(RearDisplayCastOccupancy.evaluate(snapshot(7, Display.STATE_ON, task(43, APP, true, true, false)), HOST, live).allowed());
        assertFalse(RearDisplayCastOccupancy.evaluate(snapshot, HOST, NO_SCENE).allowed());
        assertFalse(RearDisplayCastOccupancy.evaluate(snapshot(7, Display.STATE_ON,
                task(42, APP, true, true, false), task(43, "com.example.other", true, true, false)), HOST, live).allowed());
    }

    @Test public void allowedTaskCannotHideAnUnrelatedTopActivity() {
        RearDisplayCastOccupancy.Task externalTop = new RearDisplayCastOccupancy.Task(10, HOST, APP, true, true, false);
        assertEquals(APP + ":10", evaluate(externalTop).foreground);
        assertFalse(evaluate(externalTop).allowed());
    }

    @Test public void similarPackageNamesAndUnidentifiedVisibleTasksAreNotWhitelisted() {
        assertFalse(evaluate(task(10, HOST + ".other", true, true, false)).allowed());
        assertFalse(evaluate(task(10, HOME + ".other", true, true, false)).allowed());
        assertFalse(evaluate(new RearDisplayCastOccupancy.Task(10, null, null, true, true, false)).allowed());
    }

    @Test public void anActuallyEmptySnapshotAndMissingBaseWithValidTopAreHandled() {
        assertTrue(evaluate().allowed());
        assertFalse(evaluate(new RearDisplayCastOccupancy.Task(10, null, APP, true, true, false)).allowed());
    }

    @Test public void snapshotRoundTripRetainsTaskAndVisibilityMeaning() {
        RearDisplayCastOccupancy.Snapshot original = snapshot(7, Display.STATE_ON,
                task(10, APP, false, false, false), task(11, HOME, true, true, false));
        RearDisplayCastOccupancy.Snapshot decoded = RearDisplayCastOccupancy.Snapshot.parse(original.toJson(), 7);
        assertNull(decoded.failure);
        assertEquals(2, decoded.tasks.size());
        assertTrue(RearDisplayCastOccupancy.evaluate(decoded, HOST, NO_SCENE).allowed());
        assertEquals(1, RearDisplayCastOccupancy.evaluate(decoded, HOST, NO_SCENE).hiddenTasks);
    }

    @Test public void unreadableWrongDisplayDuplicateAndFailedQueriesAreNotEmpty() {
        String valid = snapshot(7, Display.STATE_ON, task(10, APP, true, true, false)).toJson();
        for (String encoded : new String[]{null, "", "{}", "not-json", valid.replace("\"display\":7", "\"display\":17"),
                valid.replace("\"visible\":true", "\"visible\":\"false\""),
                snapshot(7, Display.STATE_ON, task(10, APP, true, true, false), task(10, HOME, true, true, false)).toJson(),
                RearDisplayCastOccupancy.unavailable(7, "activity_task", "permission").toJson()}) {
            RearDisplayCastOccupancy.Snapshot decoded = RearDisplayCastOccupancy.Snapshot.parse(encoded, 7);
            assertNotNull("unreadable input must not be an empty display: " + encoded, decoded.failure);
            assertFalse(RearDisplayCastOccupancy.evaluate(decoded, HOST, NO_SCENE).allowed());
        }
    }

    @Test public void actualRemoteQueryUsesTheRequestedDisplayAndFailsClosedBeforeClassification() throws Exception {
        int[] calls = {0};
        String encoded = snapshot(7, Display.STATE_ON, task(10, APP, false, false, false)).toJson();
        ITaskService service = remote((display) -> { assertEquals(7, display); calls[0]++; return encoded; });
        assertTrue(RearDisplayCastOccupancy.query(service, HOST, 7, NO_SCENE).allowed());
        assertEquals(1, calls[0]);
        try {
            RearDisplayCastOccupancy.query(remote(display -> ""), HOST, 7,
                    (foreground, display) -> { fail("classification must not run on a failed snapshot"); return true; });
            fail("failed query was allowed");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("无法确认")); }
    }

    @Test public void typedReaderUsesExactDisplayAndIndividualChildVisibilityNotRootOrder() throws Exception {
        RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.fromTaskInfos(Arrays.asList(
                info(3, 0, APP, true, true, false), info(4, 17, APP, true, true, false),
                info(10, 7, APP, false, false, false), info(11, 7, HOME, true, true, false)), 7, Display.STATE_ON);
        assertEquals(2, snapshot.tasks.size());
        RearDisplayCastOccupancy.Result result = RearDisplayCastOccupancy.evaluate(snapshot, HOST, NO_SCENE);
        assertTrue(result.allowed());
        assertEquals(HOME + ":11", result.foreground);
        assertEquals(1, result.hiddenTasks);
    }

    @Test public void taskQuerySelectsTheAllDisplayBinderSignatureAndSupportsOldSignatures() throws Exception {
        for (Object manager : new Object[]{new Manager4(), new Manager3(), new Manager1()}) {
            assertEquals(Collections.singletonList("snapshot"), RearDisplayCastOccupancyReader.taskInfos(manager));
        }
    }

    @Test public void missingOrMalformedTaskQueryIsNotAnEmptyDisplay() {
        assertThrows(NoSuchMethodException.class, () -> RearDisplayCastOccupancyReader.taskInfos(new Object()));
        assertThrows(IllegalArgumentException.class, () -> RearDisplayCastOccupancyReader.taskInfos(new InvalidManager()));
    }

    @Test public void typedReaderRetainsPendingShowSleepAndUnknownFields() throws Exception {
        for (Object info : new Object[]{info(10, 7, APP, false, true, false), info(10, 7, APP, false, false, true),
                new OldTaskInfo(10, 7, APP)}) {
            RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.fromTaskInfos(Collections.singletonList(info), 7, Display.STATE_ON);
            assertFalse(RearDisplayCastOccupancy.evaluate(snapshot, HOST, NO_SCENE).allowed());
        }
    }

    @Test public void shellCompatibilityKeepsUnknownPendingAndSleepStateProtected() {
        String stack = "RootTask id=1 displayId=0\n taskId=1: com.example.main/.Main visible=true\n"
                + "RootTask id=7 displayId=7\n taskId=10: com.example.reader/.Main visible=false\n"
                + " taskId=11: com.xiaomi.subscreencenter/.Home visible=true\n";
        RearDisplayCastOccupancy.Snapshot snapshot = RearDisplayCastOccupancyReader.fromStackList(stack, 7, Display.STATE_ON);
        assertEquals(2, snapshot.tasks.size());
        assertFalse("root visibility cannot establish a child task is not pending or asleep",
                RearDisplayCastOccupancy.evaluate(snapshot, HOST, NO_SCENE).allowed());
        assertTrue(RearDisplayCastOccupancy.evaluate(RearDisplayCastOccupancyReader.fromStackList(stack, 8, Display.STATE_ON), HOST, NO_SCENE).allowed());
    }

    @Test public void shellMissingVisibilityAndMalformedSnapshotsCannotWeakenTheGate() {
        assertFalse(RearDisplayCastOccupancy.evaluate(RearDisplayCastOccupancyReader.fromStackList(
                "Stack id=7 displayId=7\n taskId=10: com.example.reader/.Main\n", 7, Display.STATE_ON), HOST, NO_SCENE).allowed());
        for (String stack : new String[]{"", "Permission denied", "taskId=10: com.example.reader/.Main", "RootTask id=7\n",
                "RootTask id=7 displayId=7\n taskId=bad\n",
                "RootTask id=7 displayId=7\n taskId=10: com.example.reader/.Main\n taskId=10: com.example.reader/.Main\n"}) {
            assertThrows(IllegalArgumentException.class, () -> RearDisplayCastOccupancyReader.fromStackList(stack, 7, Display.STATE_ON));
        }
    }

    private interface RemoteQuery { String read(int display) throws Exception; }
    private static ITaskService remote(RemoteQuery query) {
        return (ITaskService) Proxy.newProxyInstance(ITaskService.class.getClassLoader(), new Class<?>[]{ITaskService.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getDisplayCastOccupancy")) throw new AssertionError("unexpected side effect: " + method.getName());
                    return query.read((Integer) args[0]);
                });
    }
    private static RearDisplayCastOccupancy.Task task(int id, String pkg, Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(id, pkg, pkg, visible, requested, sleeping);
    }
    private static RearDisplayCastOccupancy.Snapshot snapshot(int display, int power, RearDisplayCastOccupancy.Task... tasks) {
        return new RearDisplayCastOccupancy.Snapshot(display, power, Arrays.asList(tasks), "test", null);
    }
    private static RearDisplayCastOccupancy.Result evaluate(RearDisplayCastOccupancy.Task... tasks) {
        return RearDisplayCastOccupancy.evaluate(snapshot(7, Display.STATE_ON, tasks), HOST, NO_SCENE);
    }
    public static class Component {
        private final String pkg;
        Component(String pkg) { this.pkg = pkg; }
        public String flattenToString() { return pkg + "/.Main"; }
    }
    public static class Manager1 {
        public java.util.List<?> getTasks(int max) {
            assertEquals(1000, max);
            return Collections.singletonList("snapshot");
        }
    }
    public static class Manager3 extends Manager1 {
        @Override public java.util.List<?> getTasks(int max) { throw new AssertionError("three-argument API is available"); }
        public java.util.List<?> getTasks(int max, boolean filter, boolean extras) {
            assertEquals(1000, max); assertFalse(filter); assertFalse(extras);
            return Collections.singletonList("snapshot");
        }
    }
    public static class Manager4 extends Manager3 {
        @Override public java.util.List<?> getTasks(int max, boolean filter, boolean extras) { throw new AssertionError("all-display API is available"); }
        public java.util.List<?> getTasks(int max, boolean filter, boolean extras, int display) {
            assertEquals(1000, max); assertFalse(filter); assertFalse(extras); assertEquals(-1, display);
            return Collections.singletonList("snapshot");
        }
    }
    public static class InvalidManager {
        public String getTasks(int max) { return "not a task snapshot"; }
    }
    public static class OldTaskInfo {
        public final int taskId, displayId;
        public final Component baseActivity, topActivity;
        OldTaskInfo(int id, int display, String pkg) {
            taskId = id; displayId = display;
            baseActivity = topActivity = new Component(pkg);
        }
    }
    public static class TaskInfo extends OldTaskInfo {
        private final boolean visible;
        public final boolean isVisibleRequested, isSleeping;
        TaskInfo(int id, int display, String pkg, boolean visible, boolean requested, boolean sleeping) {
            super(id, display, pkg);
            this.visible = visible; isVisibleRequested = requested; isSleeping = sleeping;
        }
        public boolean isVisible() { return visible; }
    }
    private static TaskInfo info(int id, int display, String pkg, boolean visible, boolean requested, boolean sleeping) {
        return new TaskInfo(id, display, pkg, visible, requested, sleeping);
    }
}
