package top.zainzh.rearscreen;

import android.content.ComponentName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Exact, bounded identity result for one Scene task; never contains a full system task dump. */
final class SceneTaskIdentity {
    enum Status { VERIFIED, TASK_NOT_FOUND, COMPONENT_MISMATCH, DISPLAY_MISMATCH, QUERY_UNAVAILABLE, QUERY_TIMEOUT, PARSE_ERROR }

    static final class Record {
        final int taskId;
        final int displayId;
        final String baseComponent;
        final String topComponent;
        final Boolean visible;
        Record(int taskId, int displayId, String baseComponent, String topComponent) {
            this(taskId, displayId, baseComponent, topComponent, null);
        }
        Record(int taskId, int displayId, String baseComponent, String topComponent, Boolean visible) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.baseComponent = baseComponent;
            this.topComponent = topComponent;
            this.visible = visible;
        }
    }

    static final class Result {
        final Status status;
        final String source;
        final String detail;
        final Record actual;
        Result(Status status, String source, String detail, Record actual) {
            this.status = status; this.source = source; this.detail = detail; this.actual = actual;
        }
        boolean verified() { return status == Status.VERIFIED; }
        String toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("status", status.name()).put("source", source);
                if (detail != null) json.put("detail", detail);
                if (actual != null) {
                    json.put("taskId", actual.taskId).put("displayId", actual.displayId);
                    if (actual.baseComponent != null) json.put("baseComponent", actual.baseComponent);
                    if (actual.topComponent != null) json.put("topComponent", actual.topComponent);
                    if (actual.visible != null) json.put("visible", actual.visible);
                }
            } catch (org.json.JSONException ignored) {
                return "{\"status\":\"QUERY_UNAVAILABLE\",\"source\":\"identity_encoding\"}";
            }
            return json.toString();
        }
    }

    private static final Pattern ROOT = Pattern.compile("^\\s*(?:RootTask|Stack)\\s+id=(\\d+)\\b.*\\bdisplayId=(\\d+)\\b");
    private static final Pattern TASK = Pattern.compile("\\btaskId=(\\d+):\\s*([^\\s]+)");
    private static final Pattern TOP = Pattern.compile("\\btopActivity=ComponentInfo\\{([^}]+)\\}");

    private SceneTaskIdentity() {}

    static Result verify(List<Record> records, int taskId, String component, int displayId, String source) {
        return verifyReady(records, taskId, component, displayId, source);
    }

    /** Initial READY: task's stable base identity is authoritative; visibility is reported separately. */
    static Result verifyReady(List<Record> records, int taskId, String component, int displayId, String source) {
        Result owner = verifyOwner(records, taskId, component, displayId, source);
        if (!owner.verified()) return owner;
        // The SDK report itself is ticket-bound and names its actual Activity window. A different
        // task top can be a legal child page or the platform's temporary notification/charging cover;
        // it must not revoke task ownership. Include the task-local top only as a diagnostic.
        String detail = owner.actual.topComponent == null ? "top_activity_unavailable"
                : component.equals(owner.actual.topComponent) ? null : "top_activity_changed_visibility_is_separate";
        return new Result(Status.VERIFIED, source, detail, owner.actual);
    }

    /** Stable cleanup ownership: a child Activity may change topActivity without changing task owner. */
    static Result verifyOwner(List<Record> records, int taskId, String component, int displayId, String source) {
        Record found = null;
        for (Record record : records) {
            if (record.taskId != taskId) continue;
            if (found != null) return new Result(Status.PARSE_ERROR, source, "duplicate_task_id", null);
            found = record;
        }
        if (found == null) return new Result(Status.TASK_NOT_FOUND, source, null, null);
        if (found.baseComponent == null || !component.equals(found.baseComponent))
            return new Result(Status.COMPONENT_MISMATCH, source, null, found);
        if (found.displayId != displayId) return new Result(Status.DISPLAY_MISMATCH, source, null, found);
        return new Result(Status.VERIFIED, source, null, found);
    }

    /** Parses only task identity lines from `am stack list`; callers must reject truncated output. */
    static List<Record> parseStackList(String output) {
        if (output == null || output.isEmpty()) throw new IllegalArgumentException("empty_task_snapshot");
        List<Record> records = new ArrayList<>();
        int displayId = -1;
        for (String line : output.split("\\R")) {
            Matcher root = ROOT.matcher(line);
            if (root.find()) {
                try { displayId = Integer.parseInt(root.group(2)); }
                catch (NumberFormatException e) { displayId = -1; }
                continue;
            }
            Matcher task = TASK.matcher(line);
            if (!task.find()) continue;
            if (displayId < 0) throw new IllegalArgumentException("task_without_display");
            int id;
            try { id = Integer.parseInt(task.group(1)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("invalid_task_id", e); }
            String rawComponent = task.group(2);
            ComponentName base = ComponentName.unflattenFromString(rawComponent);
            if (base == null && !"unknown".equals(rawComponent)) throw new IllegalArgumentException("invalid_component");
            Matcher topMatcher = TOP.matcher(line);
            String top = null;
            if (topMatcher.find()) {
                ComponentName topName = ComponentName.unflattenFromString(topMatcher.group(1));
                if (topName == null) throw new IllegalArgumentException("invalid_top_component");
                top = topName.flattenToString();
            }
            records.add(new Record(id, displayId, base == null ? null : base.flattenToString(), top));
        }
        if (records.isEmpty()) throw new IllegalArgumentException("no_task_records");
        Map<Integer, Boolean> unique = new LinkedHashMap<>();
        for (Record record : records) if (unique.put(record.taskId, Boolean.TRUE) != null)
            throw new IllegalArgumentException("duplicate_task_id");
        return records;
    }

    static Result verifyShellSnapshot(String output, int exitCode, boolean timedOut, boolean truncated,
            int taskId, String component, int displayId) {
        return verifyShellSnapshot(output, exitCode, timedOut, truncated, taskId, component, displayId, true);
    }

    static Result verifyShellSnapshot(String output, int exitCode, boolean timedOut, boolean truncated,
            int taskId, String component, int displayId, boolean readyReport) {
        if (timedOut) return new Result(Status.QUERY_TIMEOUT, "am_stack_list", "timeout_3000ms", null);
        if (exitCode != 0 || truncated) return new Result(Status.QUERY_UNAVAILABLE, "am_stack_list",
                truncated ? "output_limit_65536" : "exit_" + exitCode, null);
        try {
            List<Record> records = parseStackList(output);
            return readyReport ? verifyReady(records, taskId, component, displayId, "am_stack_list")
                    : verifyOwner(records, taskId, component, displayId, "am_stack_list");
        } catch (IllegalArgumentException e) {
            return new Result(Status.PARSE_ERROR, "am_stack_list",
                    e.getMessage() == null ? "malformed_snapshot" : e.getMessage(), null);
        }
    }
}
