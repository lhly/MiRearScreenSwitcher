package top.zainzh.rearscreen;

import org.json.JSONObject;
import android.os.RemoteException;

/** Presents only an already-owned Scene task on its existing display; it never launches or reparents. */
final class SceneTaskPresentationFlow {
    interface TaskSnapshots { SceneTaskLaunchFlow.TaskSnapshot read(); }
    interface TaskFocus { FocusResult focus(int taskId); }
    interface RearHome { boolean accepts(String component); }
    interface ActionGate { ActionGateResult check(); }
    interface RemoteActionGate { boolean isCurrent() throws RemoteException; }
    interface SuccessValidator { String validate(Result result); }
    enum ActionGateResult { ALLOWED, CANCELLED, UNAVAILABLE }

    static final class FocusResult {
        final boolean success;
        final int exitCode;
        final String failure;
        FocusResult(boolean success, int exitCode, String failure) {
            this.success = success; this.exitCode = exitCode; this.failure = failure;
        }
    }

    static final class Result {
        final String status;
        final String source;
        final int taskId;
        final int displayId;
        final String baseComponent;
        final String topComponent;
        final int commandExitCode;
        Result(String status, String source, int taskId, int displayId, String baseComponent,
               String topComponent, int commandExitCode) {
            this.status = status; this.source = source; this.taskId = taskId; this.displayId = displayId;
            this.baseComponent = baseComponent; this.topComponent = topComponent; this.commandExitCode = commandExitCode;
        }
        String diagnostic() {
            return "status=" + status + " source=" + safe(source) + " task=" + taskId + " display=" + displayId
                    + " base=" + safe(baseComponent) + " top=" + safe(topComponent) + " focus_exit=" + commandExitCode;
        }
        String toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("status", status).put("source", source == null ? JSONObject.NULL : source)
                        .put("taskId", taskId).put("displayId", displayId)
                        .put("baseComponent", baseComponent == null ? JSONObject.NULL : baseComponent)
                        .put("topComponent", topComponent == null ? JSONObject.NULL : topComponent)
                        .put("commandExitCode", commandExitCode);
            } catch (org.json.JSONException ignored) {
                return "{\"status\":\"encode_error\"}";
            }
            return json.toString();
        }
        private static String safe(String value) { return value == null ? "unknown" : value.replace(' ', '_'); }
    }

    private SceneTaskPresentationFlow() {}

    static Result present(int taskId, String component, int displayId, TaskSnapshots snapshots,
                          TaskFocus focus, RearHome rearHome) {
        return present(taskId, component, displayId, snapshots, focus, rearHome, () -> ActionGateResult.ALLOWED);
    }

    static Result present(int taskId, String component, int displayId, TaskSnapshots snapshots,
                          TaskFocus focus, RearHome rearHome, ActionGate actionGate) {
        return present(taskId, component, displayId, snapshots, focus, rearHome, actionGate, result -> null);
    }

    /**
     * Rechecks an external success authority after all potentially slow task reads/focus effects.
     * The validator is only used for Scene recovery; ordinary presentation callers keep their
     * existing task-only contract.
     */
    static Result present(int taskId, String component, int displayId, TaskSnapshots snapshots,
                          TaskFocus focus, RearHome rearHome, ActionGate actionGate,
                          SuccessValidator successValidator) {
        SceneTaskLaunchFlow.TaskSnapshot before = snapshots.read();
        if (before == null || before.failure != null)
            return result("snapshot_unavailable", before == null ? "unknown" : before.source, taskId, displayId, null, null, -1);
        SceneTaskIdentity.Result initial = SceneTaskIdentity.verifyOwner(before.records, taskId, component, displayId, before.source);
        if (!initial.verified())
            return result("owner_" + initial.status.name().toLowerCase(java.util.Locale.ROOT), before.source,
                    taskId, displayId, initial.actual == null ? null : initial.actual.baseComponent,
                    initial.actual == null ? null : initial.actual.topComponent, -1);
        if (initial.actual.visible == null) return result("task_visibility_unsupported", before.source, taskId, displayId,
                initial.actual.baseComponent, initial.actual.topComponent, -1);
        java.util.List<SceneTaskIdentity.Record> visible = visibleTasks(before.records, displayId);
        if (visible == null) return result("display_visibility_unknown", before.source, taskId, displayId,
                initial.actual.baseComponent, initial.actual.topComponent, -1);
        if (visible.size() != 1) return result("display_visibility_ambiguous", before.source, taskId, displayId,
                initial.actual.baseComponent, visible.isEmpty() ? null : visible.get(0).topComponent, -1);
        SceneTaskIdentity.Record visibleTop = visible.get(0);
        if (visibleTop.taskId == taskId && isOwnedPackageTop(component, visibleTop.topComponent))
            return validateSuccess(result("already_scene_visible", before.source, taskId, displayId,
                    initial.actual.baseComponent, visibleTop.topComponent, 0), successValidator);
        if (visibleTop.topComponent == null) return result("top_unavailable", before.source, taskId, displayId,
                initial.actual.baseComponent, null, -1);
        if (!rearHome.accepts(visibleTop.topComponent)) return result("unrelated_top_preserved", before.source, taskId, displayId,
                initial.actual.baseComponent, visibleTop.topComponent, -1);
        // This is deliberately after the potentially blocking task snapshot and launcher lookup,
        // and immediately before the focus side effect. The gate is backed by the owning Core lease.
        ActionGateResult gateResult = actionGate.check();
        if (gateResult == ActionGateResult.CANCELLED) return result("cancelled_before_focus", before.source, taskId, displayId,
                initial.actual.baseComponent, visibleTop.topComponent, -1);
        if (gateResult != ActionGateResult.ALLOWED) return result("action_gate_unavailable", before.source, taskId, displayId,
                initial.actual.baseComponent, visibleTop.topComponent, -1);

        FocusResult command = focus.focus(taskId);
        if (command == null || !command.success) return result("focus_rejected", before.source, taskId, displayId,
                initial.actual.baseComponent, visibleTop.topComponent, command == null ? -1 : command.exitCode);

        SceneTaskLaunchFlow.TaskSnapshot after = snapshots.read();
        if (after == null || after.failure != null)
            return result("post_snapshot_unavailable", after == null ? "unknown" : after.source,
                    taskId, displayId, initial.actual.baseComponent, null, command.exitCode);
        SceneTaskIdentity.Result verified = SceneTaskIdentity.verifyOwner(after.records, taskId, component, displayId, after.source);
        if (!verified.verified()) return result("post_owner_" + verified.status.name().toLowerCase(java.util.Locale.ROOT),
                after.source, taskId, displayId, verified.actual == null ? null : verified.actual.baseComponent,
                verified.actual == null ? null : verified.actual.topComponent, command.exitCode);
        java.util.List<SceneTaskIdentity.Record> visibleAfter = visibleTasks(after.records, displayId);
        if (visibleAfter == null || visibleAfter.size() != 1 || visibleAfter.get(0).taskId != taskId ||
                !isOwnedPackageTop(component, verified.actual.topComponent))
            return result("scene_not_visible_after_focus", after.source, taskId, displayId,
                    verified.actual.baseComponent, visibleAfter == null || visibleAfter.isEmpty() ? verified.actual.topComponent : visibleAfter.get(0).topComponent, command.exitCode);
        return validateSuccess(result("scene_task_presented", after.source, taskId, displayId,
                verified.actual.baseComponent, verified.actual.topComponent, command.exitCode), successValidator);
    }

    private static Result validateSuccess(Result success, SuccessValidator validator) {
        if (validator == null) return result("success_authority_unavailable", success.source,
                success.taskId, success.displayId, success.baseComponent, success.topComponent,
                success.commandExitCode);
        String rejection = validator.validate(success);
        return rejection == null ? success : result(rejection, success.source, success.taskId,
                success.displayId, success.baseComponent, success.topComponent, success.commandExitCode);
    }

    /** Maps Binder staleness separately from transport failure; only staleness is retryable by the coordinator. */
    static ActionGateResult invokeRemoteActionGate(RemoteActionGate gate) {
        if (gate == null) return ActionGateResult.UNAVAILABLE;
        try {
            return gate.isCurrent() ? ActionGateResult.ALLOWED : ActionGateResult.CANCELLED;
        } catch (RemoteException unavailable) {
            return ActionGateResult.UNAVAILABLE;
        }
    }

    /** null means the source cannot distinguish a hidden task from a foreground task. */
    private static java.util.List<SceneTaskIdentity.Record> visibleTasks(
            java.util.List<SceneTaskIdentity.Record> records, int displayId) {
        java.util.ArrayList<SceneTaskIdentity.Record> visible = new java.util.ArrayList<>();
        for (SceneTaskIdentity.Record record : records) {
            if (record.displayId != displayId) continue;
            if (record.visible == null) return null;
            if (record.visible) visible.add(record);
        }
        return visible;
    }

    private static boolean isOwnedPackageTop(String component, String top) {
        if (component == null || top == null) return false;
        int componentSlash = component.indexOf('/');
        int topSlash = top.indexOf('/');
        return componentSlash > 0 && topSlash > 0 && component.substring(0, componentSlash).equals(top.substring(0, topSlash));
    }

    private static Result result(String status, String source, int taskId, int displayId,
                                 String base, String top, int exitCode) {
        return new Result(status, source, taskId, displayId, base, top, exitCode);
    }

    static boolean matchesKnownRearHome(java.util.List<String> candidates, String actualComponent) {
        String normalizedActual = normalizeComponent(actualComponent);
        if (normalizedActual == null || candidates == null) return false;
        for (String candidate : candidates) {
            String normalizedCandidate = normalizeComponent(candidate);
            if (normalizedActual.equals(normalizedCandidate)) return true;
        }
        return false;
    }

    /** Canonicalizes ComponentName.flattenToShortString and flattenToString without broad package matching. */
    private static String normalizeComponent(String component) {
        if (component == null) return null;
        int slash = component.indexOf('/');
        if (slash <= 0 || slash == component.length() - 1 || component.indexOf('/', slash + 1) >= 0) return null;
        String packageName = component.substring(0, slash);
        String className = component.substring(slash + 1);
        if (className.startsWith(".")) className = packageName + className;
        else if (className.indexOf('.') < 0) className = packageName + "." + className;
        return packageName + "/" + className;
    }
}
