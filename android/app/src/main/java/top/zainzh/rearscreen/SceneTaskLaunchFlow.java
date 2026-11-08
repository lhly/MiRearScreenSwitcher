package top.zainzh.rearscreen;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Task-owned Scene launch decision. Task identity, not am's human-readable status, is authoritative. */
final class SceneTaskLaunchFlow {
    static final long START_TIMEOUT_MS = 10_000L;
    static final long TASK_POLL_WINDOW_MS = 3_000L;
    static final long TASK_POLL_INTERVAL_MS = 50L;

    interface TaskSnapshots { TaskSnapshot read(); }
    interface ShellCommands { SceneTaskShellRunner.Result run(String command, long timeoutMs); }
    interface TaskCleanup { CleanupResult remove(int taskId, String component, int actualDisplayId); }
    static final class CleanupResult {
        final boolean removed; final String reason;
        CleanupResult(boolean removed, String reason) { this.removed = removed; this.reason = reason; }
    }
    interface Time { long elapsedRealtime(); void sleep(long millis) throws InterruptedException; }

    static final class TaskSnapshot {
        final List<SceneTaskIdentity.Record> records;
        final String source;
        final String failure;
        private TaskSnapshot(List<SceneTaskIdentity.Record> records, String source, String failure) {
            this.records = records; this.source = source; this.failure = failure;
        }
        static TaskSnapshot available(List<SceneTaskIdentity.Record> records, String source) {
            if (records == null || records.isEmpty()) return unavailable(source, "empty_snapshot");
            Set<Integer> ids = new HashSet<>();
            for (SceneTaskIdentity.Record record : records) {
                if (record == null || record.taskId < 0 || record.displayId < 0 || !ids.add(record.taskId))
                    return unavailable(source, "invalid_or_duplicate_task_identity");
            }
            return new TaskSnapshot(records, source, null);
        }
        static TaskSnapshot unavailable(String source, String failure) {
            return new TaskSnapshot(null, source, failure);
        }
        int targetCount(String component) {
            if (records == null) return 0;
            int count = 0;
            for (SceneTaskIdentity.Record record : records) if (component.equals(record.baseComponent)) count++;
            return count;
        }
    }

    static final class Search {
        final int taskId;
        final String status;
        final int targetCount;
        final int freshTargetCount;
        final int freshExpectedDisplayCount;
        final int freshOtherDisplayCount;
        final SceneTaskIdentity.Record uniqueFreshRecord;
        Search(int taskId, String status, int targetCount, int freshTargetCount,
               int freshExpectedDisplayCount, int freshOtherDisplayCount, SceneTaskIdentity.Record uniqueFreshRecord) {
            this.taskId = taskId; this.status = status; this.targetCount = targetCount;
            this.freshTargetCount = freshTargetCount; this.freshExpectedDisplayCount = freshExpectedDisplayCount;
            this.freshOtherDisplayCount = freshOtherDisplayCount; this.uniqueFreshRecord = uniqueFreshRecord;
        }
    }

    static final class Result {
        final int taskId;
        final String failure;
        final String commandStatus;
        final int commandExitCode;
        final boolean commandTimedOut;
        final boolean commandTruncated;
        final String commandMarker;
        final String commandErrorDetail;
        final String beforeSource;
        final int beforeTargetCount;
        final String afterSource;
        final int afterTargetCount;
        final String afterFailure;
        final Search lastSearch;
        final String cleanupStatus;
        Result(int taskId, String failure, String commandStatus, int commandExitCode,
               boolean commandTimedOut, boolean commandTruncated, String commandMarker, String commandErrorDetail, String beforeSource,
               int beforeTargetCount, String afterSource, int afterTargetCount, String afterFailure, Search lastSearch, String cleanupStatus) {
            this.taskId = taskId; this.failure = failure; this.commandStatus = commandStatus;
            this.commandExitCode = commandExitCode; this.commandTimedOut = commandTimedOut;
            this.commandTruncated = commandTruncated; this.commandMarker = commandMarker; this.commandErrorDetail = commandErrorDetail; this.beforeSource = beforeSource;
            this.beforeTargetCount = beforeTargetCount; this.afterSource = afterSource;
            this.afterTargetCount = afterTargetCount; this.afterFailure = afterFailure; this.lastSearch = lastSearch; this.cleanupStatus = cleanupStatus;
        }
        String diagnostic() {
            return "task=" + taskId + " result=" + (failure == null ? "accepted" : failure)
                    + " command_status=" + commandStatus + " command_exit=" + commandExitCode
                    + " command_timeout=" + commandTimedOut + " command_truncated=" + commandTruncated
                    + " command_marker=" + commandMarker
                    + " command_error_detail=" + safeField(commandErrorDetail)
                    + " before_source=" + safe(beforeSource) + " before_target_tasks=" + beforeTargetCount
                    + " after_source=" + safe(afterSource) + " after_target_tasks=" + afterTargetCount
                    + " after_failure=" + safe(afterFailure)
                    + " fresh_target_tasks=" + (lastSearch == null ? 0 : lastSearch.freshTargetCount)
                    + " fresh_expected_display=" + (lastSearch == null ? 0 : lastSearch.freshExpectedDisplayCount)
                    + " fresh_other_display=" + (lastSearch == null ? 0 : lastSearch.freshOtherDisplayCount)
                    + " unique_fresh_task=" + (lastSearch == null || lastSearch.uniqueFreshRecord == null ? -1 : lastSearch.uniqueFreshRecord.taskId)
                    + " unique_fresh_actual_display=" + (lastSearch == null || lastSearch.uniqueFreshRecord == null ? -1 : lastSearch.uniqueFreshRecord.displayId)
                    + " search=" + (lastSearch == null ? "not_run" : lastSearch.status)
                    + " cleanup=" + safe(cleanupStatus);
        }
        private static String safe(String value) { return value == null ? "unknown" : value; }
        private static String safeField(String value) {
            if (value == null || value.isEmpty()) return "none";
            return value.trim().replaceAll("\\s+", "_");
        }
    }

    private SceneTaskLaunchFlow() {}

    static Result launch(String component, int displayId, String launchTicket,
                         TaskSnapshots snapshots, ShellCommands commands, TaskCleanup cleanup, Time time) {
        TaskSnapshot before = snapshots.read();
        if (before == null || before.failure != null) {
            return new Result(-1, "before_snapshot_" + (before == null ? "null" : before.failure),
                    "not_run", -1, false, false, "not_run", null, before == null ? null : before.source,
                    before == null ? 0 : before.targetCount(component), null, 0,
                    before == null ? "null_snapshot" : before.failure, null, "not_needed");
        }

        // `am start` accepts Intent flags through -f; the apparent long-option spellings
        // `--activity-new-task/--activity-multiple-task` are not accepted by AOSP's parser.
        String command = "am start -W --display " + displayId + " --user current -f 0x18000000 -n "
                + PrivilegedShellArguments.quote(component) + " --es remrss_scene_ticket "
                + PrivilegedShellArguments.quote(launchTicket);
        SceneTaskShellRunner.Result started = commands.run(command, START_TIMEOUT_MS);
        String commandStatus = commandStatus(started);
        String commandMarker = commandMarker(started);
        String commandErrorDetail = commandErrorDetail(started, component, launchTicket);
        long deadline = time.elapsedRealtime() + TASK_POLL_WINDOW_MS;
        TaskSnapshot after = null;
        Search search = null;
        String lastQueryFailure = null;
        do {
            after = snapshots.read();
            if (after == null || after.failure != null) {
                lastQueryFailure = after == null ? "null_snapshot" : after.failure;
            } else {
                lastQueryFailure = null;
                search = findFreshTargetTask(before.records, after.records, component, displayId);
                if (search.taskId >= 0) {
                    return new Result(search.taskId, null, commandStatus, started.exitCode, started.timedOut,
                            started.truncated, commandMarker, commandErrorDetail, before.source, before.targetCount(component), after.source,
                            after.targetCount(component), null, search, "not_needed");
                }
                if (search.freshTargetCount > 1) break;
            }
            if (time.elapsedRealtime() >= deadline) break;
            try { time.sleep(TASK_POLL_INTERVAL_MS); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastQueryFailure = "interrupted";
                break;
            }
        } while (true);

        String failure;
        String cleanupStatus = "not_needed";
        if (search != null && search.freshTargetCount > 1) failure = "ambiguous_fresh_target_tasks";
        else if (search != null && search.freshOtherDisplayCount > 0) {
            failure = "fresh_target_wrong_display";
            SceneTaskIdentity.Record orphan = search.uniqueFreshRecord;
            if (orphan != null) {
                if (cleanup == null) cleanupStatus = "not_attempted_no_cleanup_handler";
                else {
                    try {
                        CleanupResult removed = cleanup.remove(orphan.taskId, component, orphan.displayId);
                        cleanupStatus = removed == null ? "failed_null_result" : (removed.removed ? "removed_" : "failed_") + (removed.reason == null ? "unknown" : removed.reason);
                    } catch (RuntimeException e) {
                        cleanupStatus = "failed_exception_" + e.getClass().getSimpleName();
                    }
                }
            }
        } else if (search == null && lastQueryFailure != null) failure = "after_snapshot_" + lastQueryFailure;
        else if (!"status_ok".equals(commandStatus)) failure = "start_" + commandStatus + "_no_unique_fresh_task";
        else failure = "no_unique_fresh_target_task";
        return new Result(-1, failure, commandStatus, started.exitCode, started.timedOut,
                started.truncated, commandMarker, commandErrorDetail, before.source, before.targetCount(component), after == null ? null : after.source,
                after == null ? 0 : after.targetCount(component), lastQueryFailure, search, cleanupStatus);
    }

    static Search findFreshTargetTask(List<SceneTaskIdentity.Record> before,
                                      List<SceneTaskIdentity.Record> observed,
                                      String component, int expectedDisplayId) {
        Set<Integer> beforeIds = new HashSet<>();
        for (SceneTaskIdentity.Record record : before) beforeIds.add(record.taskId);
        int targetCount = 0, freshCount = 0, freshExpected = 0, freshOther = 0, candidate = -1;
        SceneTaskIdentity.Record uniqueFresh = null;
        for (SceneTaskIdentity.Record record : observed) {
            if (!component.equals(record.baseComponent)) continue;
            targetCount++;
            if (beforeIds.contains(record.taskId)) continue;
            freshCount++;
            uniqueFresh = record;
            if (record.displayId == expectedDisplayId) {
                freshExpected++;
                candidate = record.taskId;
            } else freshOther++;
        }
        String status;
        int taskId = -1;
        if (freshCount > 1) status = "ambiguous_fresh_target_tasks";
        else if (freshExpected == 1) { status = "unique_fresh_target"; taskId = candidate; }
        else if (freshOther > 0) status = "fresh_target_wrong_display";
        else status = "no_fresh_target_task";
        return new Search(taskId, status, targetCount, freshCount, freshExpected, freshOther, freshCount == 1 ? uniqueFresh : null);
    }

    private static String commandStatus(SceneTaskShellRunner.Result result) {
        if (result.failureCode != null) return "runner_" + result.failureCode;
        if (result.timedOut) return "timeout";
        if (result.truncated) return "truncated";
        if (result.exitCode != 0) return "exit_" + result.exitCode;
        if (result.output != null && (result.output.contains("Error:") || result.output.contains("Exception")
                || result.output.contains("Permission Denial"))) return "reported_error";
        if (result.output != null && result.output.contains("Status: ok")) return "status_ok";
        return "status_unconfirmed";
    }

    /** First useful error from this one owned am invocation; never returns the raw output or command. */
    static String commandErrorDetail(SceneTaskShellRunner.Result result, String component, String launchTicket) {
        if (result == null || result.output == null || component == null) return null;
        String[] lines = result.output.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.matches("(?i)^Error type \\d+\\b.*")) {
                String detail = line;
                if (i + 1 < lines.length && isErrorExplanation(lines[i + 1]))
                    detail += " | " + lines[i + 1].trim();
                return sanitizeCommandError(detail, component, launchTicket);
            }
            if (!isErrorExplanation(line)) continue;
            String detail = line;
            // Android's am often prints an exception header followed immediately by its actual cause.
            if (isExceptionHeader(line) && i + 1 < lines.length && isExceptionCause(lines[i + 1]))
                detail += " | " + lines[i + 1].trim();
            return sanitizeCommandError(detail, component, launchTicket);
        }
        return null;
    }

    private static boolean isErrorExplanation(String value) {
        String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("unknown option") || lower.startsWith("error:")
                || lower.startsWith("exception") || lower.startsWith("permission denial")
                || lower.startsWith("java.lang.") && (lower.contains("exception") || lower.contains("denial"))
                || lower.startsWith("android.content.activitynotfoundexception");
    }

    private static boolean isExceptionHeader(String value) {
        String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("exception") || lower.startsWith("java.lang.")
                || lower.startsWith("android.content.activitynotfoundexception");
    }

    private static boolean isExceptionCause(String value) {
        String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("java.lang.") || lower.startsWith("android.content.activitynotfoundexception")
                || lower.startsWith("permission denial") || lower.startsWith("error:");
    }

    private static String sanitizeCommandError(String value, String component, String launchTicket) {
        String safe = value;
        if (launchTicket != null && !launchTicket.isEmpty()) safe = safe.replace(launchTicket, "<ticket>");
        safe = safe.replaceAll("(?i)\\(has extras\\)", "(extras redacted)")
                .replaceAll("(?i)\\bextras?\\s*[:=]\\s*\\{[^}]*}", "extras=<redacted>")
                .replaceAll("(?i)\\b(password|secret|token|nonce|key)\\s*[:=]\\s*[^\\s,}]+", "$1=<redacted>")
                .replaceAll("(?i)\\b(data|dat|uri)\\s*[:=]\\s*[^\\s,}]+", "$1=<redacted>")
                .replaceAll("(?i)\\b(?:[a-z][a-z0-9+.-]*://)[^\\s,}]+", "<uri>")
                .replaceAll("(?i)\\b(package|pkg|packageName)\\s*[:=]\\s*[A-Za-z0-9_.]+", "$1=<redacted>")
                .replaceAll("(?i)/(?:data/app|data/user)/[^\\s,}]+", "<app-path>");
        String ownPackage = component.contains("/") ? component.substring(0, component.indexOf('/')) : component;
        java.util.regex.Pattern componentPattern = java.util.regex.Pattern.compile(
                "(?<![A-Za-z0-9_])(?:[A-Za-z][A-Za-z0-9_]*\\.)+[A-Za-z0-9_]+/(?:\\.?[A-Za-z0-9_.$]+)");
        java.util.regex.Matcher matcher = componentPattern.matcher(safe);
        StringBuffer redacted = new StringBuffer();
        while (matcher.find()) {
            String candidate = matcher.group();
            String replacement = candidate.startsWith(ownPackage + "/") ? candidate : "<other-component>";
            matcher.appendReplacement(redacted, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(redacted);
        safe = redacted.toString().replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").replaceAll("\\s+", " ").trim();
        return safe.length() > 240 ? safe.substring(0, 240) : safe;
    }

    /** One bounded, non-sensitive marker from am's output; never logs the command or ticket. */
    private static String commandMarker(SceneTaskShellRunner.Result result) {
        if (result.failureCode != null) return "runner_" + result.failureCode;
        if (result.timedOut) return "timeout";
        if (result.truncated) return "truncated";
        if (result.output == null || result.output.isEmpty()) return "empty";
        for (String line : result.output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Error type ")) {
                String type = trimmed.substring("Error type ".length()).split("\\s", 2)[0];
                return type.matches("[0-9]+") ? "am_error_type_" + type : "am_error_type_unknown";
            }
            if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("unknown option")) return "unknown_option";
            if (trimmed.startsWith("Permission Denial")) return "permission_denial";
            if (trimmed.startsWith("Error:")) return "reported_error";
            if (trimmed.startsWith("Exception")) return "reported_exception";
            if (trimmed.startsWith("Status: ok")) return "status_ok";
            if (trimmed.startsWith("Status:")) return "status_non_ok";
            if (trimmed.startsWith("Warning: Activity not started")) return "activity_not_started_warning";
        }
        return "no_known_marker";
    }
}
