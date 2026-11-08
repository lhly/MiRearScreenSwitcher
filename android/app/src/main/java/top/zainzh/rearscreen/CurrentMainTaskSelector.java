package top.zainzh.rearscreen;

import android.view.Display;
import android.os.SystemClock;

/** Resolves current=1 from the typed focused-root identity, never from task-list order. */
final class CurrentMainTaskSelector {
    interface SnapshotSource { String read(int displayId) throws Exception; }

    static final class Target {
        final int taskId;
        final String packageName;

        Target(int taskId, String packageName) {
            this.taskId = taskId;
            this.packageName = packageName;
        }

        String legacyForegroundValue() { return packageName + ":" + taskId; }
    }

    static final class Result {
        final Target target;
        final String reason;
        final boolean retryable;

        Result(Target target, String reason, boolean retryable) {
            this.target = target;
            this.reason = reason;
            this.retryable = retryable;
        }
    }

    private CurrentMainTaskSelector() {}

    /** Uses the existing occupancy Binder endpoint exactly once; caller may retry only transient focus handoff. */
    static Result read(SnapshotSource source, String hostPackage) throws Exception {
        if (source == null || hostPackage == null || hostPackage.isEmpty())
            return result("source_unavailable", false);
        return resolve(RearDisplayCastOccupancy.Snapshot.parse(source.read(0), 0), hostPackage);
    }

    static Result await(SnapshotSource source, String hostPackage, long timeoutMs, long retryDelayMs)
            throws Exception {
        long deadline = SystemClock.uptimeMillis() + Math.max(0, timeoutMs);
        Result latest = read(source, hostPackage);
        while (latest.target == null && latest.retryable && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(Math.max(1, retryDelayMs));
            latest = read(source, hostPackage);
        }
        return latest;
    }

    static Result resolve(RearDisplayCastOccupancy.Snapshot snapshot, String hostPackage) {
        boolean focusedStackFallback = snapshot != null && ("activity_task_focused_stack".equals(snapshot.source)
                || "activity_focused_stack".equals(snapshot.source));
        if (snapshot == null || snapshot.failure != null
                || !("activity_task".equals(snapshot.source) || focusedStackFallback)
                || snapshot.displayId != 0 || !BuildConfig.APPLICATION_ID.equals(hostPackage)
                || snapshot.tasks == null)
            return result("typed_snapshot_unavailable", false);
        if (snapshot.displayState != Display.STATE_ON || snapshot.powerBefore != Display.STATE_ON
                || snapshot.powerAfter != Display.STATE_ON)
            return result("main_display_not_stably_on", true);
        if (snapshot.focusedTaskId < 0) return result("focused_task_unavailable", false);

        RearDisplayCastOccupancy.Task focused = null;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            if (task == null || task.taskId < 0) return result("invalid_task_row", false);
            if (task.taskId == snapshot.focusedTaskId) {
                if (focused != null) return result("duplicate_focused_task", false);
                focused = task;
            }
        }
        if (focused == null) return result("focused_task_not_in_snapshot", true);
        if (focused.basePackage == null || focused.topPackage == null)
            return result("focused_identity_unavailable", false);

        // URI/QS receiver tasks are transparent, single-instance host plumbing. Let their finish
        // handoff complete, then query again for the still-focused underlying application.
        if (hostPackage.equals(focused.basePackage) && hostPackage.equals(focused.topPackage))
            return result("focused_host_handoff", true);
        if (isLauncher(focused.basePackage) && focused.basePackage.equals(focused.topPackage))
            return result("focused_launcher", true);
        if (!focused.basePackage.equals(focused.topPackage))
            return result("focused_component_package_mismatch", false);
        // The system-focused root/stack and its visible bit are the common denominator across
        // supported releases. requested/sleeping were added later; absence is not evidence of
        // hidden or unsafe, while an explicit contradictory value remains a refusal.
        if (!Boolean.TRUE.equals(focused.visible)
                || Boolean.FALSE.equals(focused.requested)
                || Boolean.TRUE.equals(focused.sleeping))
            return result("focused_task_not_presented", true);
        return new Result(new Target(focused.taskId, focused.topPackage), "resolved", false);
    }

    private static boolean isLauncher(String packageName) {
        return "com.miui.home".equals(packageName)
                || "com.xiaomi.subscreencenter".equals(packageName)
                || "com.xiaomi.mirror".equals(packageName);
    }

    private static Result result(String reason, boolean retryable) {
        return new Result(null, reason, retryable);
    }
}
