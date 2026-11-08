package top.zainzh.rearscreen;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolve platform methods and exported launcher components on the running ROM. */
final class RearSystemBridge {
    private RearSystemBridge() {}
    private static volatile String lastTaskSnapshotQuery = "unavailable";
    static String lastTaskSnapshotQueryForDiagnostics() { return lastTaskSnapshotQuery; }
    static Object service(String name, String stub) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        if (binder == null) throw new IllegalStateException("Missing system service: " + name);
        return Class.forName(stub).getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    static final class TaskLocation {
        final int rootTask, displayId;
        TaskLocation(int rootTask, int displayId) { this.rootTask = rootTask; this.displayId = displayId; }
    }

    /**
     * Uses ActivityTaskManager's existing task snapshot rather than starting `am` for every
     * final-window READY. The returned records contain only task id, stable base component,
     * current top component and display.
     */
    static List<SceneTaskIdentity.Record> runningTaskSnapshot() throws Exception {
        Object manager = service("activity_task", "android.app.IActivityTaskManager$Stub");
        return runningTaskSnapshot(manager);
    }

    /** Package-visible for deterministic task-query contract tests using a binder-shaped fake. */
    static List<SceneTaskIdentity.Record> runningTaskSnapshot(Object manager) throws Exception {
        Method query = null;
        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("getTasks")) continue;
            Class<?>[] types = method.getParameterTypes();
            // The Binder contract accepts a display selector. Always request all displays so
            // scene ownership and cleanup compare against the same global task identities.
            if (types.length == 4 && types[0] == int.class && types[1] == boolean.class
                    && types[2] == boolean.class && types[3] == int.class) {
                query = method; break;
            }
            if (types.length == 3 && types[0] == int.class && types[1] == boolean.class && types[2] == boolean.class) {
                if (query == null) query = method;
            }
            if (types.length == 1 && types[0] == int.class && query == null) query = method;
        }
        if (query == null) throw new NoSuchMethodException("IActivityTaskManager.getTasks");
        Class<?>[] parameters = query.getParameterTypes();
        lastTaskSnapshotQuery = "getTasks/" + parameters.length
                + " display=" + (parameters.length == 4 ? "INVALID_DISPLAY" : "legacy");
        Object raw;
        if (parameters.length == 4) raw = query.invoke(manager, 1000, false, false, -1); // INVALID_DISPLAY: all displays
        else if (parameters.length == 3) raw = query.invoke(manager, 1000, false, false);
        else raw = query.invoke(manager, 1000);
        CompatibilityDiagnostics.record("scene_task_snapshot", "method=" + lastTaskSnapshotQuery
                + " records=" + (raw instanceof List ? ((List<?>) raw).size() : "invalid"));
        if (!(raw instanceof List)) throw new IllegalStateException("task_snapshot_not_list");
        List<SceneTaskIdentity.Record> result = new ArrayList<>();
        for (Object info : (List<?>) raw) {
            if (info == null) continue;
            int task = ((Number) info.getClass().getField("taskId").get(info)).intValue();
            int display = ((Number) info.getClass().getField("displayId").get(info)).intValue();
            Object base = info.getClass().getField("baseActivity").get(info);
            Object top = info.getClass().getField("topActivity").get(info);
            String baseComponent = base == null ? null : (String) base.getClass().getMethod("flattenToString").invoke(base);
            String component = top == null ? null : (String) top.getClass().getMethod("flattenToString").invoke(top);
            Boolean visible = null;
            try {
                Object value = info.getClass().getMethod("isVisible").invoke(info);
                if (value instanceof Boolean) visible = (Boolean) value;
            } catch (NoSuchMethodException oldApi) {
                // Older platform TaskInfo has no visibility bit. Scene task ownership remains
                // queryable, but foreground presentation will fail closed without this evidence.
            }
            result.add(new SceneTaskIdentity.Record(task, display, baseComponent, component, visible));
        }
        return result;
    }
    static TaskLocation taskLocation(String stacks, int task) {
        int root = -1, display = -1;
        for (String line : stacks.split("\n")) {
            Matcher header = Pattern.compile("^\\s*(?:RootTask|Stack)\\s+id=(\\d+)\\b").matcher(line);
            if (header.find()) {
                root = Integer.parseInt(header.group(1));
                Matcher id = Pattern.compile("\\bdisplayId=(\\d+)\\b").matcher(line);
                display = id.find() ? Integer.parseInt(id.group(1)) : -1;
            }
            if (root >= 0 && Pattern.compile("\\btaskId=" + task + ":").matcher(line).find())
                return new TaskLocation(root, display);
        }
        return null;
    }
    static int rootTask(String stacks, int task) {
        TaskLocation location = taskLocation(stacks, task);
        return location == null ? -1 : location.rootTask;
    }
    static boolean moveTask(int task, int display) throws Exception {
        if (task < 0 || display < 0) throw new IllegalArgumentException("Invalid task/display");
        DisplayRotationController.Result stacks = DisplayRotationController.runCommand("am", "stack", "list");
        if (stacks.exitCode != 0) throw new IllegalStateException("Cannot read root tasks: " + stacks.output);
        TaskLocation location = taskLocation(stacks.output, task);
        if (location == null) throw new IllegalStateException("Root task not found for task " + task);
        int root = location.rootTask;
        if (location.displayId == display) {
            // Moving a root to its current display is rejected; restore its foreground instead.
            DisplayRotationController.Result focused = DisplayRotationController.runCommand("am", "task", "focus", Integer.toString(task));
            boolean ok = commandSucceeded(focused);
            CompatibilityDiagnostics.record("task_move", "method=am_task_focus task=" + task + " display=" + display
                    + " success=" + ok + " response=" + focused.output);
            return ok;
        }
        try {
            Object manager = service("activity_task", "android.app.IActivityTaskManager$Stub");
            manager.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(manager, root, display);
            CompatibilityDiagnostics.record("task_move", "method=moveRootTaskToDisplay root=" + root + " display=" + display);
            return true;
        } catch (ReflectiveOperationException e) {
            // The system command resolves its own Binder interface, including vendor transaction order.
            DisplayRotationController.Result result = DisplayRotationController.runCommand("am", "display", "move-stack", Integer.toString(root), Integer.toString(display));
            boolean ok = commandSucceeded(result);
            CompatibilityDiagnostics.record("task_move", "method=am_display_move_stack root=" + root + " display=" + display
                    + " success=" + ok + " reflection=" + e + " response=" + result.output);
            return ok;
        }
    }
    static boolean commandSucceeded(DisplayRotationController.Result result) {
        return result.exitCode == 0 && !Pattern.compile("(?im)\\b(?:Error:|Exception|Permission Denial|does not exist|not found)").matcher(result.output).find();
    }
    static Context systemContext() throws Exception {
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object instance = thread.getMethod("currentActivityThread").invoke(null);
        if (instance == null) throw new IllegalStateException("System context unavailable");
        return (Context) thread.getMethod("getSystemContext").invoke(instance);
    }
    static List<String> launcherCandidates() {
        List<String> result = new ArrayList<>();
        try {
            PackageManager pm = systemContext().getPackageManager();
            for (String pkg : new String[]{"com.xiaomi.subscreencenter", "com.xiaomi.mirror"}) {
                try {
                    PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                    if (info.activities == null) continue;
                    for (ActivityInfo activity : info.activities) {
                        String name = activity.name.toLowerCase(java.util.Locale.ROOT);
                        if (activity.exported && activity.enabled && name.contains("subscreen") && name.contains("launcher"))
                            result.add(pkg + "/" + activity.name);
                    }
                } catch (PackageManager.NameNotFoundException ignored) { }
            }
        } catch (Exception e) { CompatibilityDiagnostics.record("launcher_discovery", e.toString()); }
        if (result.isEmpty()) {
            // Some shell hosts lack an ActivityThread context. Resolve explicit platform entrances
            // through PackageManager's command before trying them; never report an absent component as restored.
            for (String component : new String[]{"com.xiaomi.subscreencenter/.SubScreenLauncher",
                    "com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity", "com.xiaomi.mirror/.SubscreenLauncher"}) {
                try {
                    DisplayRotationController.Result resolved = DisplayRotationController.runCommand("cmd", "package", "resolve-activity", "--brief", "-n", component);
                    String pkg = component.substring(0, component.indexOf('/'));
                    if (resolved.exitCode == 0 && resolved.output.contains(pkg + "/")) result.add(component);
                } catch (Exception e) { CompatibilityDiagnostics.record("launcher_resolve", component + ": " + e); }
            }
        }
        // Keep the established entrance first when the ROM still exposes it.
        result.sort((a, b) -> Boolean.compare(!a.endsWith(".SubScreenLauncher"), !b.endsWith(".SubScreenLauncher")));
        return result;
    }
    static boolean restoreLauncher() throws Exception {
        int display = RearDisplay.requireId();
        List<String> candidates = launcherCandidates();
        for (String component : candidates) {
            DisplayRotationController.Result result = DisplayRotationController.runCommand("am", "start", "--display", Integer.toString(display), "-n", component);
            boolean ok = commandSucceeded(result);
            CompatibilityDiagnostics.record("launcher_restore", "component=" + component + " display=" + display + " success=" + ok + " response=" + result.output);
            if (ok) return true;
        }
        CompatibilityDiagnostics.record("launcher_restore", "NO_EXPORTED_REAR_LAUNCHER candidates=" + candidates.size());
        return false;
    }
}
