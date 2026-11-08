package top.zainzh.rearscreen;

import java.lang.reflect.Method;

/** Removes only a currently verified Scene-owned task through ActivityTaskManager's task API. */
final class SceneTaskTaskRemover {
    static final class Result {
        final boolean removed;
        final String reason;
        Result(boolean removed, String reason) { this.removed = removed; this.reason = reason; }
    }

    private SceneTaskTaskRemover() {}

    static Result remove(int taskId, String component, int displayId) {
        SceneTaskLaunchFlow.TaskSnapshot before = SceneTaskSnapshotReader.read();
        if (before.failure != null) return new Result(false, "snapshot_" + before.failure);
        SceneTaskIdentity.Result owner = SceneTaskIdentity.verifyOwner(before.records, taskId, component,
                displayId, before.source);
        if (!owner.verified()) return new Result(false, "owner_" + owner.status.name().toLowerCase());

        final boolean accepted;
        try {
            Object manager = RearSystemBridge.service("activity_task", "android.app.IActivityTaskManager$Stub");
            Method remove = manager.getClass().getMethod("removeTask", int.class);
            Object result = remove.invoke(manager, taskId);
            accepted = Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return new Result(false, "remove_task_" + e.getClass().getSimpleName());
        }
        if (!accepted) return new Result(false, "remove_task_rejected");

        SceneTaskLaunchFlow.TaskSnapshot after = SceneTaskSnapshotReader.read();
        if (after.failure != null) return new Result(false, "post_snapshot_" + after.failure);
        for (SceneTaskIdentity.Record record : after.records) {
            if (record.taskId == taskId && component.equals(record.baseComponent) && record.displayId == displayId)
                return new Result(false, "owned_task_still_present");
        }
        return new Result(true, "removed_and_absent");
    }
}
