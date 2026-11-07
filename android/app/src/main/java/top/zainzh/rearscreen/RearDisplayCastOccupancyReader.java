package top.zainzh.rearscreen;

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
            List<?> infos = taskInfos(RearSystemBridge.service("activity_task", "android.app.IActivityTaskManager$Stub"));
            return fromTaskInfos(infos, displayId, stablePower(beforePower, powerState(displayId)));
        } catch (Exception unavailable) {
            try {
                DisplayRotationController.Result shell = DisplayRotationController.runCommand("am", "stack", "list");
                if (shell.exitCode != 0 || shell.output.length() > 65536)
                    return RearDisplayCastOccupancy.unavailable(displayId, "am_stack_list", "query_failed");
                return fromStackList(shell.output, displayId, stablePower(beforePower, powerState(displayId)));
            } catch (Exception invalid) {
                if (invalid instanceof InterruptedException) Thread.currentThread().interrupt();
                return RearDisplayCastOccupancy.unavailable(displayId, "am_stack_list", "query_or_parse_failed");
            }
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

    static RearDisplayCastOccupancy.Snapshot fromTaskInfos(List<?> infos, int displayId, int power) throws Exception {
        if (infos == null || infos.size() >= 1000) throw new IllegalArgumentException("incomplete_task_query");
        List<RearDisplayCastOccupancy.Task> tasks = new ArrayList<>();
        for (Object info : infos) {
            if (info == null) continue;
            int display = ((Number) info.getClass().getField("displayId").get(info)).intValue();
            if (display != displayId) continue;
            int task = ((Number) info.getClass().getField("taskId").get(info)).intValue();
            String base = componentPackage(info.getClass().getField("baseActivity").get(info));
            String top = componentPackage(info.getClass().getField("topActivity").get(info));
            tasks.add(new RearDisplayCastOccupancy.Task(task, base, top, booleanProperty(info, "isVisible"),
                    booleanProperty(info, "isVisibleRequested"), booleanProperty(info, "isSleeping")));
        }
        return new RearDisplayCastOccupancy.Snapshot(displayId, power, tasks, "activity_task", null);
    }

    /** Root-level visibility lacks pending-show/sleep evidence, so this compatibility path stays conservative. */
    static RearDisplayCastOccupancy.Snapshot fromStackList(String output, int displayId, int power) {
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
        return new RearDisplayCastOccupancy.Snapshot(displayId, power, tasks, "am_stack_list", null);
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
