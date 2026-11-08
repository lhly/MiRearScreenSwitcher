package top.zainzh.rearscreen;

import android.content.ComponentName;
import android.os.Process;
import android.os.SystemClock;
import java.util.List;

/** Snapshot-only privileged-shell probe of the production scene launch/task-ownership path. */
public final class SceneTaskLaunchShellProbe {
    private SceneTaskLaunchShellProbe() {}

    public static void main(String[] args) {
        long started = SystemClock.elapsedRealtime();
        int launchedTask = -1;
        int primaryTask = -1;
        String component = new ComponentName(BuildConfig.APPLICATION_ID, RearSceneM0RearActivity.class.getName())
                .flattenToString();
        String primary = new ComponentName(BuildConfig.APPLICATION_ID, MainActivity.class.getName()).flattenToString();
        try {
            SceneTaskLaunchFlow.TaskSnapshot initial = snapshot();
            // A prior interrupted probe may have left only this unique debug fixture task behind.
            // Remove at most that exact owned task; never clear or move the host app by package.
            SceneTaskIdentity.Record stale = uniqueRecordForBase(initial.records, component);
            if (stale != null) {
                SceneTaskTaskRemover.Result cleanup = SceneTaskTaskRemover.remove(stale.taskId, component, 0);
                if (!cleanup.removed) throw new IllegalStateException("stale_scene_cleanup_" + cleanup.reason);
            }
            SceneTaskLaunchFlow.TaskSnapshot before = snapshot();
            primaryTask = uniqueTaskForBase(before.records, primary);
            if (primaryTask < 0) throw new IllegalStateException("primary_task_not_unique_before_launch");

            String ticket = "probeTicket_0123456789abcdef";
            SceneTaskLaunchFlow.Result result = SceneTaskLaunchFlow.launch(component, 0, ticket,
                    SceneTaskLaunchShellProbe::snapshot, SceneTaskShellRunner::run,
                    (taskId, sceneComponent, actualDisplayId) -> {
                        SceneTaskTaskRemover.Result cleanup = SceneTaskTaskRemover.remove(taskId, sceneComponent, actualDisplayId);
                        return new SceneTaskLaunchFlow.CleanupResult(cleanup.removed, cleanup.reason);
                    },
                    new SceneTaskLaunchFlow.Time() {
                        @Override public long elapsedRealtime() { return SystemClock.elapsedRealtime(); }
                        @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
                    });
            launchedTask = result.taskId;
            if (launchedTask < 0) throw new IllegalStateException("launch_rejected " + result.diagnostic());

            SceneTaskLaunchFlow.TaskSnapshot after = snapshot();
            if (uniqueTaskForBase(after.records, primary) != primaryTask)
                throw new IllegalStateException("primary_task_changed_after_launch");
            SceneTaskIdentity.Result owner = SceneTaskIdentity.verifyOwner(after.records, launchedTask,
                    component, 0, after.source);
            if (!owner.verified()) throw new IllegalStateException("fresh_scene_owner_mismatch_" + owner.status);

            System.out.println("SCENE_LAUNCH status=VERIFIED method=" + RearSystemBridge.lastTaskSnapshotQueryForDiagnostics()
                    + " uid=" + Process.myUid() + " primaryTask=" + primaryTask
                    + " sceneTask=" + launchedTask + " base=" + component
                    + " display=0 " + result.diagnostic()
                    + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
        } catch (Throwable error) {
            System.out.println("SCENE_LAUNCH status=ERROR type=" + error.getClass().getSimpleName()
                    + " reason=" + safe(error.getMessage())
                    + " method=" + RearSystemBridge.lastTaskSnapshotQueryForDiagnostics()
                    + " uid=" + Process.myUid()
                    + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
            System.exit(1);
        } finally {
            if (launchedTask >= 0) {
                SceneTaskTaskRemover.Result removal = SceneTaskTaskRemover.remove(launchedTask, component, 0);
                boolean primaryPreserved = false;
                try { primaryPreserved = uniqueTaskForBase(snapshot().records, primary) == primaryTask; }
                catch (Exception ignored) { }
                System.out.println("SCENE_LAUNCH_CLEANUP removed=" + removal.removed
                        + " reason=" + removal.reason + " sceneTask=" + launchedTask
                        + " primaryPreserved=" + primaryPreserved);
                if (!removal.removed || !primaryPreserved) System.exit(2);
            }
        }
    }

    private static SceneTaskLaunchFlow.TaskSnapshot snapshot() { return SceneTaskSnapshotReader.read(); }

    private static int uniqueTaskForBase(List<SceneTaskIdentity.Record> records, String base) {
        SceneTaskIdentity.Record record = uniqueRecordForBase(records, base);
        return record == null ? -1 : record.taskId;
    }

    private static SceneTaskIdentity.Record uniqueRecordForBase(List<SceneTaskIdentity.Record> records, String base) {
        SceneTaskIdentity.Record found = null;
        for (SceneTaskIdentity.Record record : records) if (base.equals(record.baseComponent)) {
            if (found != null) return null;
            found = record;
        }
        return found;
    }

    private static String safe(String message) {
        if (message == null || message.isEmpty()) return "unspecified";
        // Avoid arbitrary exception/Intent text; only a stable reason token is emitted.
        String token = message.replaceAll("[^A-Za-z0-9_:/.-]", "_");
        return token.substring(0, Math.min(100, token.length()));
    }
}
