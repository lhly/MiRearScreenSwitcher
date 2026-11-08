package top.zainzh.rearscreen;

import android.view.Display;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Cast admission only. Task existence/legacy monitoring must not use this visibility filter. */
final class RearDisplayCastOccupancy {
    enum OwnerDisposition { EMPTY, CORE_SCENE, HOST, SYSTEM_LAUNCHER, UNKNOWN }
    @FunctionalInterface interface SceneTaskMatcher { boolean matches(String foreground, int displayId); }
    static final class Task {
        final int taskId;
        final String basePackage, topPackage;
        final Boolean visible, requested, sleeping;

        Task(int taskId, String basePackage, String topPackage, Boolean visible, Boolean requested, Boolean sleeping) {
            this.taskId = taskId;
            this.basePackage = basePackage;
            this.topPackage = topPackage;
            this.visible = visible;
            this.requested = requested;
            this.sleeping = sleeping;
        }

        boolean clearlyHidden(int displayState) {
            // OFF/DOZE, a pending show, or missing evidence must never release another app's claim.
            return displayState == Display.STATE_ON && Boolean.FALSE.equals(visible)
                    && Boolean.FALSE.equals(requested) && Boolean.FALSE.equals(sleeping);
        }
    }

    static final class Snapshot {
        final int displayId, displayState, powerBefore, powerAfter;
        final int focusedTaskId;
        final List<Task> tasks;
        final String source, failure;

        Snapshot(int displayId, int displayState, List<Task> tasks, String source, String failure) {
            this(displayId, displayState, displayState, displayState, -1, tasks, source, failure);
        }

        Snapshot(int displayId, int displayState, int powerBefore, int powerAfter,
                 List<Task> tasks, String source, String failure) {
            this(displayId, displayState, powerBefore, powerAfter, -1, tasks, source, failure);
        }

        Snapshot(int displayId, int displayState, int powerBefore, int powerAfter, int focusedTaskId,
                 List<Task> tasks, String source, String failure) {
            this.displayId = displayId;
            this.displayState = displayState;
            this.powerBefore = powerBefore;
            this.powerAfter = powerAfter;
            this.focusedTaskId = focusedTaskId;
            this.tasks = tasks;
            this.source = source;
            this.failure = failure;
        }

        String toJson() {
            try {
                JSONObject value = new JSONObject().put("display", displayId).put("power", displayState)
                        .put("powerBefore", powerBefore).put("powerAfter", powerAfter)
                        .put("source", source).put("status", failure == null ? "ok" : "unavailable");
                if (focusedTaskId >= 0) value.put("focusedTask", focusedTaskId);
                if (failure != null) value.put("reason", failure);
                JSONArray rows = new JSONArray();
                for (Task task : tasks) rows.put(new JSONObject().put("task", task.taskId)
                        .put("base", task.basePackage).put("top", task.topPackage)
                        .put("visible", task.visible).put("requested", task.requested).put("sleeping", task.sleeping));
                return value.put("tasks", rows).toString();
            } catch (org.json.JSONException invalid) {
                return "{\"status\":\"unavailable\",\"reason\":\"encoding\"}";
            }
        }

        static Snapshot parse(String encoded, int expectedDisplay) {
            try {
                JSONObject value = new JSONObject(encoded);
                String source = safeSource(value.optString("source", "unavailable"));
                int power = value.optInt("power", 0);
                int powerBefore = value.optInt("powerBefore", power);
                int powerAfter = value.optInt("powerAfter", power);
                if (!"ok".equals(value.getString("status")))
                    return unavailable(expectedDisplay, source, safeFailure(value.optString("reason", "snapshot_unavailable")),
                            power, powerBefore, powerAfter);
                if (value.getInt("display") != expectedDisplay)
                    return unavailable(expectedDisplay, source, "wrong_display", power, powerBefore, powerAfter);
                JSONArray rows = value.getJSONArray("tasks");
                List<Task> tasks = new ArrayList<>();
                java.util.Set<Integer> ids = new java.util.HashSet<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    int id = row.getInt("task");
                    if (id < 0 || !ids.add(id)) throw new IllegalArgumentException("invalid_task_identity");
                    tasks.add(new Task(id, optionalPackage(row, "base"), optionalPackage(row, "top"),
                            optionalBoolean(row, "visible"), optionalBoolean(row, "requested"), optionalBoolean(row, "sleeping")));
                }
                return new Snapshot(expectedDisplay, power, powerBefore, powerAfter,
                        value.optInt("focusedTask", -1), tasks, source, null);
            } catch (Exception invalid) {
                return unavailable(expectedDisplay, "unavailable", "snapshot_unreadable");
            }
        }

        private static String safeSource(String source) {
            if ("activity_task".equals(source)) return source;
            if ("activity_task_focused_stack".equals(source)) return source;
            if ("activity_focused_stack".equals(source)) return source;
            if ("am_stack_list".equals(source)) return source;
            return "unavailable";
        }

        private static String safeFailure(String failure) {
            switch (failure) {
                case "invalid_display":
                case "query_failed":
                case "query_or_parse_failed":
                case "wrong_display":
                case "snapshot_unreadable":
                case "snapshot_unavailable":
                case "invalid_task_identity":
                case "invalid_visibility":
                case "encoding":
                    return failure;
                default:
                    return "snapshot_unavailable";
            }
        }

        private static String optionalPackage(JSONObject row, String key) throws org.json.JSONException {
            return row.isNull(key) ? null : VirtualCastRequest.packageName(row.getString(key));
        }

        private static Boolean optionalBoolean(JSONObject row, String key) throws org.json.JSONException {
            if (row.isNull(key)) return null;
            Object value = row.get(key);
            if (!(value instanceof Boolean)) throw new IllegalArgumentException("invalid_visibility");
            return (Boolean) value;
        }
    }

    static final class Result {
        final OwnerDisposition disposition;
        final String foreground;
        final int hiddenTasks;
        Result(OwnerDisposition disposition, String foreground, int hiddenTasks) {
            this.disposition = disposition;
            this.foreground = foreground;
            this.hiddenTasks = hiddenTasks;
        }
        boolean allowed() { return disposition != OwnerDisposition.UNKNOWN; }
    }

    private RearDisplayCastOccupancy() {}

    static Snapshot unavailable(int displayId, String source, String reason) {
        return unavailable(displayId, source, reason, 0, 0, 0);
    }

    static Snapshot unavailable(int displayId, String source, String reason,
                                int power, int powerBefore, int powerAfter) {
        return new Snapshot(displayId, power, powerBefore, powerAfter,
                java.util.Collections.emptyList(), Snapshot.safeSource(source), Snapshot.safeFailure(reason));
    }

    static Result query(ITaskService service, String hostPackage, int displayId) throws android.os.RemoteException {
        return query(service, hostPackage, displayId,
                top.zainzh.rearscreen.platform.RearCapabilityCore::isActiveSceneTaskForeground);
    }

    static Result query(ITaskService service, String hostPackage, int displayId,
                        SceneTaskMatcher scenes) throws android.os.RemoteException {
        Snapshot snapshot = Snapshot.parse(service.getDisplayCastOccupancy(displayId), displayId);
        if (snapshot.failure != null) throw new IllegalStateException("无法确认背屏占用状态，请稍后重试");
        return evaluate(snapshot, hostPackage, scenes);
    }

    static Result evaluate(Snapshot snapshot, String hostPackage, SceneTaskMatcher scenes) {
        Result selected = new Result(OwnerDisposition.EMPTY, null, 0);
        int hidden = 0;
        if (snapshot.failure != null) return new Result(OwnerDisposition.UNKNOWN, null, 0);
        for (Task task : snapshot.tasks) {
            if (task.clearlyHidden(snapshot.displayState)) { hidden++; continue; }
            String owner = task.basePackage != null ? task.basePackage : task.topPackage;
            if (owner == null) return new Result(OwnerDisposition.UNKNOWN, null, hidden);
            String foreground = owner + ":" + task.taskId;
            OwnerDisposition disposition = classify(foreground, hostPackage, snapshot.displayId, scenes);
            if (disposition == OwnerDisposition.UNKNOWN)
                return new Result(disposition, foreground, hidden);
            // An unrelated Activity hosted inside an otherwise permitted task is still protected.
            if (task.topPackage != null && !owner.equals(task.topPackage)) {
                String top = task.topPackage + ":" + task.taskId;
                if (classify(top, hostPackage, snapshot.displayId, scenes) == OwnerDisposition.UNKNOWN)
                    return new Result(OwnerDisposition.UNKNOWN, top, hidden);
            }
            if (selected.foreground == null) selected = new Result(disposition, foreground, hidden);
        }
        return new Result(selected.disposition, selected.foreground, hidden);
    }

    private static OwnerDisposition classify(String foreground, String hostPackage, int displayId, SceneTaskMatcher scenes) {
        if (scenes != null && scenes.matches(foreground, displayId)) return OwnerDisposition.CORE_SCENE;
        if (hostPackage != null && foreground.startsWith(hostPackage + ":")) return OwnerDisposition.HOST;
        if (foreground.startsWith("com.xiaomi.subscreencenter:")) return OwnerDisposition.SYSTEM_LAUNCHER;
        return OwnerDisposition.UNKNOWN;
    }
}
