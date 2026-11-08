package top.zainzh.rearscreen;

import android.view.Display;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Privileged, read-only source for the cast gate. It never moves, focuses or removes a task. */
final class RearDisplayCastOccupancyReader {
    private static final Pattern ROOT = Pattern.compile("^\\s*(?:RootTask|Stack)\\s+id=\\d+\\b.*\\bdisplayId=(\\d+)\\b");
    private static final Pattern TASK = Pattern.compile("^\\s*taskId=(\\d+):\\s*([^\\s]+)");
    private static final Pattern VISIBLE = Pattern.compile("(?:^|\\s)visible=(true|false)(?=\\s|$)");
    private static final Pattern TOP = Pattern.compile("\\btopActivity=ComponentInfo\\{([^}]+)\\}");

    private RearDisplayCastOccupancyReader() {}

    static RearDisplayCastOccupancy.Snapshot read(int displayId) {
        if (displayId < 0) return RearDisplayCastOccupancy.unavailable(displayId, "input", "invalid_display");
        int beforePower = powerState(displayId);
        try {
            Object manager = RearSystemBridge.service("activity_task", "android.app.IActivityTaskManager$Stub");
            List<?> infos = taskInfos(manager);
            // Current-task routing on the main display must use the focused root task, not
            // TaskInfo list order. Keep this optional for ordinary rear occupancy callers.
            return readAfterFocus(manager, infos, displayId, beforePower,
                    RearDisplayCastOccupancyReader::powerState);
        } catch (Exception unavailable) {
            try {
                // Before Android Q the task manager lives behind IActivityManager. Its focused
                // StackInfo is the supported privileged focus signal; task-list order is not.
                if (displayId == 0) {
                    try {
                        RearDisplayCastOccupancy.Snapshot legacy = legacyFocusedStackSnapshot(beforePower);
                        if (legacy != null) return legacy;
                    } catch (Exception noLegacyFocus) {
                        if (noLegacyFocus instanceof InterruptedException) Thread.currentThread().interrupt();
                        CompatibilityDiagnostics.record("current_focus", "legacy_stack_unavailable="
                                + noLegacyFocus.getClass().getSimpleName());
                    }
                }
                DisplayRotationController.Result shell = DisplayRotationController.runCommand("am", "stack", "list");
                int afterPower = powerState(displayId);
                if (shell.exitCode != 0 || shell.output.length() > 65536)
                    return RearDisplayCastOccupancy.unavailable(displayId, "am_stack_list", "query_failed",
                            stablePower(beforePower, afterPower), beforePower, afterPower);
                return fromStackList(shell.output, displayId, stablePower(beforePower, afterPower), beforePower, afterPower);
            } catch (Exception invalid) {
                if (invalid instanceof InterruptedException) Thread.currentThread().interrupt();
                return RearDisplayCastOccupancy.unavailable(displayId, "am_stack_list", "query_or_parse_failed",
                        stablePower(beforePower, 0), beforePower, 0);
            }
        }
    }

    /**
     * Strict, task-manager-only snapshot for side effects that extend physical display activity.
     * Unlike cast admission, this never falls back to lossy shell text: missing visibility,
     * requested, or sleeping evidence must fail closed.
     */
    static RearDisplayCastOccupancy.Snapshot readTypedOnly(int displayId) {
        if (displayId < 0) return RearDisplayCastOccupancy.unavailable(displayId, "input", "invalid_display");
        int beforePower = powerState(displayId);
        try {
            List<?> infos = taskInfos(RearSystemBridge.service("activity_task", "android.app.IActivityTaskManager$Stub"));
            int afterPower = powerState(displayId);
            return fromTaskInfos(infos, displayId, stablePower(beforePower, afterPower), beforePower, afterPower);
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            int afterPower = powerState(displayId);
            return RearDisplayCastOccupancy.unavailable(displayId, "activity_task", "query_unavailable",
                    stablePower(beforePower, afterPower), beforePower, afterPower);
        }
    }

    private static int powerState(int displayId) {
        try {
            Object info = RearDisplay.info(displayId);
            return ((Number) info.getClass().getField("state").get(info)).intValue();
        }
        catch (Exception unavailable) { return 0; }
    }

    /** A sleep/wake transition during the potentially blocking snapshot is not proof of a hidden task. */
    static int stablePower(int before, int after) {
        return before == after ? after : 0;
    }

    /** Request all displays; the exact selected display is filtered only after the Binder snapshot. */
    static List<?> taskInfos(Object manager) throws Exception {
        Method query = null;
        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("getTasks")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 4 && types[0] == int.class && types[1] == boolean.class
                    && types[2] == boolean.class && types[3] == int.class) {
                query = method;
                break;
            }
            if (types.length == 3 && types[0] == int.class && types[1] == boolean.class && types[2] == boolean.class) {
                if (query == null || query.getParameterTypes().length < 3) query = method;
            }
            if (types.length == 1 && types[0] == int.class && query == null) query = method;
        }
        if (query == null) throw new NoSuchMethodException("IActivityTaskManager.getTasks");
        int count = query.getParameterTypes().length;
        Object result = count == 4 ? query.invoke(manager, 1000, false, false, -1)
                : count == 3 ? query.invoke(manager, 1000, false, false) : query.invoke(manager, 1000);
        if (!(result instanceof List)) throw new IllegalArgumentException("task_query_not_list");
        return (List<?>) result;
    }

    /** Returns only the exact focused root task when the privileged Binder exposes it. */
    static int focusedRootTaskId(Object manager, int expectedDisplayId) {
        if (manager == null || expectedDisplayId < 0) return -1;
        try {
            Object focused = manager.getClass().getMethod("getFocusedRootTaskInfo").invoke(manager);
            if (focused == null) return -1;
            int display = ((Number) focused.getClass().getField("displayId").get(focused)).intValue();
            int task = ((Number) focused.getClass().getField("taskId").get(focused)).intValue();
            return display == expectedDisplayId && task >= 0 ? task : -1;
        } catch (Exception unavailable) {
            return -1;
        }
    }

    static final class FocusedStack {
        final int taskId;
        final String topComponent;
        final Boolean visible;
        final boolean fromStackInfo;
        FocusedStack(int taskId, String topComponent, Boolean visible, boolean fromStackInfo) {
            this.taskId = taskId;
            this.topComponent = topComponent;
            this.visible = visible;
            this.fromStackInfo = fromStackInfo;
        }
    }

    /** The cast/current shared source binds focus to the exact leaf row represented in the same task snapshot. */
    static RearDisplayCastOccupancy.Snapshot fromFocusedTaskInfos(Object manager, List<?> infos, int displayId,
            int power, int powerBefore, int powerAfter) throws Exception {
        FocusedStack focused = displayId == 0 ? focusedStack(manager, displayId, infos) : null;
        return buildFocusedSnapshot(infos, displayId, power, powerBefore, powerAfter, focused);
    }

    interface PowerStateReader { int read(int displayId); }

    /**
     * Production read order: gather all potentially blocking focus evidence first, then read
     * the display power state and form the snapshot consumed by the selector.
     */
    static RearDisplayCastOccupancy.Snapshot readAfterFocus(Object manager, List<?> infos, int displayId,
            int powerBefore, PowerStateReader powerReader) throws Exception {
        FocusedStack focused = displayId == 0 ? focusedStack(manager, displayId, infos) : null;
        int powerAfter = powerReader.read(displayId);
        return buildFocusedSnapshot(infos, displayId, stablePower(powerBefore, powerAfter),
                powerBefore, powerAfter, focused);
    }

    private static RearDisplayCastOccupancy.Snapshot buildFocusedSnapshot(List<?> infos, int displayId,
            int power, int powerBefore, int powerAfter, FocusedStack focused) throws Exception {
        String source = focused == null ? "activity_task"
                : focused.fromStackInfo ? "activity_task_focused_stack" : "activity_task";
        return fromTaskInfos(infos, displayId, power, powerBefore, powerAfter,
                focused == null ? null : focused, source);
    }

    /** Prefer RootTaskInfo, normalizing its container identity to the exact focused leaf task. */
    static FocusedStack focusedStack(Object manager, int expectedDisplayId, List<?> infos) {
        if (manager == null || expectedDisplayId < 0) return null;
        try {
            Object focused = manager.getClass().getMethod("getFocusedRootTaskInfo").invoke(manager);
            if (focused != null) {
                return focusedRootTask(focused, infos, expectedDisplayId);
            }
        } catch (ReflectiveOperationException ignored) { }
        try {
            Object focused = activityManagerFocusedStack(manager);
            FocusedStack stack = focusedStackInfo(focused, expectedDisplayId);
            return hasExactFocusedRow(infos, stack, expectedDisplayId) ? stack : null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static FocusedStack focusedRootTask(Object root, List<?> infos, int expectedDisplayId) {
        if (root == null || infos == null) return null;
        try {
            int display = intField(root, "displayId");
            int rootTaskId = intField(root, "taskId");
            String top = flattenComponent(root.getClass().getField("topActivity").get(root));
            if (display != expectedDisplayId || rootTaskId < 0 || top == null) return null;
            int[] children = null;
            try { children = (int[]) root.getClass().getField("childTaskIds").get(root); }
            catch (NoSuchFieldException ignored) { }

            RearDisplayCastOccupancy.Task match = null;
            for (Object row : infos) {
                if (row == null || intField(row, "displayId") != expectedDisplayId) continue;
                int id = intField(row, "taskId", "id");
                boolean child = false;
                if (children != null) for (int childId : children) if (childId == id) { child = true; break; }
                boolean rootIsLeaf = id == rootTaskId
                        && (children == null || children.length == 0 || child);
                if (!rootIsLeaf && !child) continue;
                String rowTop = flattenComponent(row.getClass().getField("topActivity").get(row));
                if (!top.equals(rowTop)) continue;
                if (match != null) return null;
                match = new RearDisplayCastOccupancy.Task(id,
                        componentPackage(componentField(row, "baseActivity")), componentPackage(rowTop),
                        booleanProperty(row, "isVisible"), booleanProperty(row, "isVisibleRequested"),
                        booleanProperty(row, "isSleeping"));
            }
            if (match == null) return null;
            Boolean visible = booleanProperty(root, "isVisible");
            if (visible == null) visible = match.visible;
            return new FocusedStack(match.taskId, top, visible, false);
        } catch (Exception invalid) {
            return null;
        }
    }

    static FocusedStack focusedStackInfo(Object focused, int expectedDisplayId) {
        if (focused == null || expectedDisplayId < 0) return null;
        try {
            int display = ((Number) focused.getClass().getField("displayId").get(focused)).intValue();
            boolean visible = Boolean.TRUE.equals(focused.getClass().getField("visible").get(focused));
            int[] taskIds = (int[]) focused.getClass().getField("taskIds").get(focused);
            Object top = focused.getClass().getField("topActivity").get(focused);
            if (display != expectedDisplayId || taskIds == null || taskIds.length == 0) return null;
            int taskId = taskIds[taskIds.length - 1];
            String topComponent = flattenComponent(top);
            return taskId >= 0 && topComponent != null
                    ? new FocusedStack(taskId, topComponent, visible, true) : null;
        } catch (ReflectiveOperationException | ClassCastException invalid) {
            return null;
        }
    }

    private static String flattenComponent(Object component) throws ReflectiveOperationException {
        return component == null ? null
                : (String) component.getClass().getMethod("flattenToString").invoke(component);
    }

    private static boolean hasExactFocusedRow(List<?> infos, FocusedStack focused, int displayId) {
        if (focused == null) return false;
        try {
            for (Object row : infos) {
                if (row == null) continue;
                int id = intField(row, "taskId", "id");
                int display = intField(row, "displayId");
                if (id != focused.taskId || display != displayId) continue;
                String top = flattenComponent(row.getClass().getField("topActivity").get(row));
                return focused.topComponent != null && focused.topComponent.equals(top);
            }
        } catch (Exception invalid) { return false; }
        return false;
    }

    /** Legacy IActivityManager path, cross-checked against the focused stack and exact task row. */
    private static RearDisplayCastOccupancy.Snapshot legacyFocusedStackSnapshot(int powerBefore) throws Exception {
        Object manager = RearSystemBridge.service("activity", "android.app.IActivityManager$Stub");
        Object stack = activityManagerFocusedStack(manager);
        int afterPower = powerState(0);
        return legacyFocusedStackSnapshot(manager, stack, powerBefore, afterPower);
    }

    static Object activityManagerFocusedStack(Object manager) throws Exception {
        try {
            return manager.getClass().getMethod("getFocusedStackInfo").invoke(manager);
        } catch (NoSuchMethodException oldApi) {
            // Older IActivityManager exposes the focused stack id plus all StackInfo rows.
            int focusedId = ((Number) manager.getClass().getMethod("getFocusedStackId").invoke(manager)).intValue();
            Object raw = manager.getClass().getMethod("getAllStackInfos").invoke(manager);
            if (!(raw instanceof List)) throw new IllegalStateException("stack_info_not_list");
            Object match = null;
            for (Object row : (List<?>) raw) {
                if (row == null) continue;
                int stackId = ((Number) row.getClass().getField("stackId").get(row)).intValue();
                if (stackId == focusedId) {
                    if (match != null) throw new IllegalStateException("duplicate_focused_stack");
                    match = row;
                }
            }
            return match;
        }
    }

    static RearDisplayCastOccupancy.Snapshot legacyFocusedStackSnapshot(
            Object manager, Object stack, int powerBefore, int afterPower) throws Exception {
        FocusedStack focused = focusedStackInfo(stack, 0);
        int stable = stablePower(powerBefore, afterPower);
        if (focused == null)
            return RearDisplayCastOccupancy.unavailable(0, "activity_focused_stack", "focus_unavailable",
                    stable, powerBefore, afterPower);
        Method query = legacyGetTasksMethod(manager);
        Object raw = query.getParameterTypes().length == 1
                ? query.invoke(manager, 1000) : query.invoke(manager, 1000, 0);
        if (!(raw instanceof List)) throw new IllegalArgumentException("legacy_task_query_not_list");
        RearDisplayCastOccupancy.Task selected = null;
        for (Object row : (List<?>) raw) {
            if (row == null) continue;
            int id = intField(row, "id", "taskId");
            if (id != focused.taskId) continue;
            String base = componentPackage(flattenComponent(componentField(row, "baseActivity")));
            String topComponent = flattenComponent(componentField(row, "topActivity"));
            String top = componentPackage(topComponent);
            if (top == null || !focused.topComponent.equals(topComponent))
                throw new IllegalStateException("focused_stack_task_mismatch");
            if (selected != null) throw new IllegalStateException("duplicate_focused_task");
            // StackInfo exposes focused-stack visibility on pre-Q; RunningTaskInfo is used
            // only to bind the exact id and base/top identity, never to choose list order.
            selected = new RearDisplayCastOccupancy.Task(id, base, top, focused.visible, null, null);
        }
        if (selected == null) throw new IllegalStateException("focused_task_missing");
        return new RearDisplayCastOccupancy.Snapshot(0, stable, powerBefore, afterPower,
                focused.taskId, java.util.Collections.singletonList(selected), "activity_focused_stack", null);
    }

    private static Method legacyGetTasksMethod(Object manager) throws NoSuchMethodException {
        Method twoArgs = null;
        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("getTasks")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 1 && types[0] == int.class) return method;
            if (types.length == 2 && types[0] == int.class && types[1] == int.class) twoArgs = method;
        }
        if (twoArgs != null) return twoArgs;
        throw new NoSuchMethodException("IActivityManager.getTasks");
    }

    private static Object componentField(Object row, String field) throws ReflectiveOperationException {
        return row.getClass().getField(field).get(row);
    }

    private static int intField(Object row, String... names) throws ReflectiveOperationException {
        for (String name : names) {
            try { return ((Number) row.getClass().getField(name).get(row)).intValue(); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(names[0]);
    }

    static RearDisplayCastOccupancy.Snapshot fromTaskInfos(List<?> infos, int displayId, int power) throws Exception {
        return fromTaskInfos(infos, displayId, power, power, power);
    }

    static RearDisplayCastOccupancy.Snapshot fromTaskInfos(List<?> infos, int displayId, int power,
                                                            int powerBefore, int powerAfter) throws Exception {
        return fromTaskInfos(infos, displayId, power, powerBefore, powerAfter, -1);
    }

    static RearDisplayCastOccupancy.Snapshot fromTaskInfos(List<?> infos, int displayId, int power,
                                                            int powerBefore, int powerAfter,
                                                            int focusedTaskId) throws Exception {
        return fromTaskInfos(infos, displayId, power, powerBefore, powerAfter, focusedTaskId, "activity_task");
    }

    static RearDisplayCastOccupancy.Snapshot fromTaskInfos(List<?> infos, int displayId, int power,
                                                            int powerBefore, int powerAfter, int focusedTaskId,
                                                            String source) throws Exception {
        return fromTaskInfos(infos, displayId, power, powerBefore, powerAfter, null, source,
                focusedTaskId);
    }

    private static RearDisplayCastOccupancy.Snapshot fromTaskInfos(List<?> infos, int displayId, int power,
            int powerBefore, int powerAfter, FocusedStack focused, String source) throws Exception {
        return fromTaskInfos(infos, displayId, power, powerBefore, powerAfter, focused, source,
                focused == null ? -1 : focused.taskId);
    }

    private static RearDisplayCastOccupancy.Snapshot fromTaskInfos(List<?> infos, int displayId, int power,
            int powerBefore, int powerAfter, FocusedStack focused, String source, int focusedTaskId) throws Exception {
        if (infos == null || infos.size() >= 1000) throw new IllegalArgumentException("incomplete_task_query");
        List<RearDisplayCastOccupancy.Task> tasks = new ArrayList<>();
        for (Object info : infos) {
            if (info == null) continue;
            int display = ((Number) info.getClass().getField("displayId").get(info)).intValue();
            if (display != displayId) continue;
            int task = ((Number) info.getClass().getField("taskId").get(info)).intValue();
            String base = componentPackage(info.getClass().getField("baseActivity").get(info));
            String top = componentPackage(info.getClass().getField("topActivity").get(info));
            Boolean visible = booleanProperty(info, "isVisible");
            if (focused != null && task == focused.taskId) {
                // A focused root/stack explicitly saying hidden must not be overridden by a
                // contradictory leaf row. A positive focus visibility fills only a missing leaf bit.
                if (Boolean.FALSE.equals(focused.visible)) visible = Boolean.FALSE;
                else if (visible == null) visible = focused.visible;
            }
            tasks.add(new RearDisplayCastOccupancy.Task(task, base, top, visible,
                    booleanProperty(info, "isVisibleRequested"), booleanProperty(info, "isSleeping")));
        }
        return new RearDisplayCastOccupancy.Snapshot(displayId, power, powerBefore, powerAfter,
                focusedTaskId, tasks, source, null);
    }

    /** Root-level visibility lacks pending-show/sleep evidence, so this compatibility path stays conservative. */
    static RearDisplayCastOccupancy.Snapshot fromStackList(String output, int displayId, int power) {
        return fromStackList(output, displayId, power, power, power);
    }

    static RearDisplayCastOccupancy.Snapshot fromStackList(String output, int displayId, int power,
                                                           int powerBefore, int powerAfter) {
        if (output == null) throw new IllegalArgumentException("empty_snapshot");
        List<RearDisplayCastOccupancy.Task> tasks = new ArrayList<>();
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        boolean sawRoot = false;
        int display = -1;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("RootTask ") || trimmed.startsWith("Stack ")) {
                Matcher root = ROOT.matcher(line);
                if (!root.find()) throw new IllegalArgumentException("invalid_root");
                display = Integer.parseInt(root.group(1));
                sawRoot = true;
                continue;
            }
            if (!trimmed.startsWith("taskId=")) continue;
            Matcher task = TASK.matcher(line);
            if (!task.find() || display < 0) throw new IllegalArgumentException("invalid_task");
            int taskId = Integer.parseInt(task.group(1));
            if (!ids.add(taskId)) throw new IllegalArgumentException("duplicate_task");
            String base = "unknown".equals(task.group(2)) ? null : validatedPackage(task.group(2));
            Matcher top = TOP.matcher(line);
            String topPackage = top.find() ? validatedPackage(top.group(1)) : null;
            if (display != displayId) continue;
            Matcher visible = VISIBLE.matcher(line);
            Boolean rootVisible = visible.find() ? Boolean.valueOf(visible.group(1)) : null;
            tasks.add(new RearDisplayCastOccupancy.Task(taskId, base, topPackage, rootVisible,
                    null, null));
        }
        if (!sawRoot) throw new IllegalArgumentException("no_root_snapshot");
        return new RearDisplayCastOccupancy.Snapshot(displayId, power, powerBefore, powerAfter,
                tasks, "am_stack_list", null);
    }

    private static Boolean booleanProperty(Object info, String name) {
        try {
            Object value;
            try { value = info.getClass().getMethod(name).invoke(info); }
            catch (NoSuchMethodException noMethod) { value = info.getClass().getField(name).get(info); }
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (ReflectiveOperationException unavailable) { return null; }
    }

    private static String componentPackage(Object component) throws Exception {
        return component == null ? null : componentPackage((String) component.getClass().getMethod("flattenToString").invoke(component));
    }

    private static String componentPackage(String component) {
        if (component == null) return null;
        int slash = component.indexOf('/');
        if (slash <= 0) throw new IllegalArgumentException("invalid_component");
        return VirtualCastRequest.packageName(component.substring(0, slash));
    }

    private static String validatedPackage(String component) {
        return componentPackage(VirtualCastRequest.component(component));
    }
}
