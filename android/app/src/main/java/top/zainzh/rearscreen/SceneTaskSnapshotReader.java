package top.zainzh.rearscreen;

import java.util.List;

/** All-display scene task snapshot: typed ActivityTaskManager first, bounded shell fallback only if unavailable. */
final class SceneTaskSnapshotReader {
    private SceneTaskSnapshotReader() {}

    static SceneTaskLaunchFlow.TaskSnapshot read() {
        try {
            List<SceneTaskIdentity.Record> records = RearSystemBridge.runningTaskSnapshot();
            SceneTaskLaunchFlow.TaskSnapshot snapshot = SceneTaskLaunchFlow.TaskSnapshot.available(records,
                    "activity_task/" + RearSystemBridge.lastTaskSnapshotQueryForDiagnostics());
            if (snapshot.failure == null) return snapshot;
            CompatibilityDiagnostics.record("scene_task_query_unavailable", "source=activity_task reason=" + snapshot.failure);
        } catch (Exception e) {
            CompatibilityDiagnostics.record("scene_task_query_unavailable", "source=activity_task type=" + e.getClass().getSimpleName());
        }

        SceneTaskShellRunner.Result shell = SceneTaskShellRunner.run("am stack list", 3000L);
        if (shell.failureCode != null) return SceneTaskLaunchFlow.TaskSnapshot.unavailable("am_stack_list", "runner_" + shell.failureCode);
        if (shell.timedOut) return SceneTaskLaunchFlow.TaskSnapshot.unavailable("am_stack_list", "timeout_3000ms");
        if (shell.exitCode != 0) return SceneTaskLaunchFlow.TaskSnapshot.unavailable("am_stack_list", "exit_" + shell.exitCode);
        if (shell.truncated) return SceneTaskLaunchFlow.TaskSnapshot.unavailable("am_stack_list", "truncated_65536");
        try {
            return SceneTaskLaunchFlow.TaskSnapshot.available(SceneTaskIdentity.parseStackList(shell.output), "am_stack_list");
        } catch (IllegalArgumentException e) {
            return SceneTaskLaunchFlow.TaskSnapshot.unavailable("am_stack_list",
                    e.getMessage() == null ? "parse_error" : e.getMessage());
        }
    }
}
