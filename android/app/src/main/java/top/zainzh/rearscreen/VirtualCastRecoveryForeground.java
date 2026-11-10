package top.zainzh.rearscreen;

import android.os.RemoteException;
import android.view.Display;

/** Classifies recovery 57 foreground state without treating task-list order as visibility. */
final class VirtualCastRecoveryForeground {
    enum Disposition { HOST_ALREADY_FRONT, FOCUS_HOST, WAKE_ALLOWED, OTHER_TASK, EVIDENCE_UNAVAILABLE }

    static final class Decision {
        final Disposition disposition;
        final int hostTaskId;
        final int displayId;
        final String refusal;
        final Diagnostic diagnostic;

        Decision(Disposition disposition, int hostTaskId, int displayId, String refusal, Diagnostic diagnostic) {
            this.disposition = disposition;
            this.hostTaskId = hostTaskId;
            this.displayId = displayId;
            this.refusal = refusal;
            this.diagnostic = diagnostic;
        }
    }

    /** Safe one-line fields derived from the already-fetched snapshot; never contains package names. */
    static final class Diagnostic {
        final String source;
        final String availability;
        final String failure;
        final int displayId, targetTaskId;
        final int displayPower, powerBefore, powerAfter;
        final int rowCount, hiddenRows, visibleLauncherRows, visibleOtherRows, pendingRows, sleepingRows,
                sleepingLauncherRows, sleepingLauncherRequestedFalseRows, sleepingLauncherRequestedTrueRows,
                sleepingLauncherRequestedUnknownRows, sleepingOtherRows, sleepingUnknownRows, unknownRows;
        final boolean targetPresent;
        final String targetBase, targetTop, targetVisible, targetRequested, targetSleeping;

        Diagnostic(RearDisplayCastOccupancy.Snapshot snapshot, int hostTaskId, String hostPackage) {
            displayId = snapshot == null ? -1 : snapshot.displayId;
            targetTaskId = hostTaskId;
            source = sourceClass(snapshot == null ? null : snapshot.source);
            availability = snapshot == null || snapshot.failure != null ? "unavailable" : "ok";
            failure = failureClass(snapshot == null ? null : snapshot.failure);
            displayPower = snapshot == null ? 0 : snapshot.displayState;
            powerBefore = snapshot == null ? 0 : snapshot.powerBefore;
            powerAfter = snapshot == null ? 0 : snapshot.powerAfter;

            boolean found = false;
            String base = "missing", top = "missing", visible = "unknown", requested = "unknown", sleeping = "unknown";
            int rows = 0, hidden = 0, launchers = 0, visibleOther = 0, pending = 0, asleep = 0,
                    sleepingLauncher = 0, sleepingLauncherRequestedFalse = 0,
                    sleepingLauncherRequestedTrue = 0, sleepingLauncherRequestedUnknown = 0,
                    sleepingOther = 0, sleepingUnknown = 0, unknown = 0;
            if (snapshot != null && snapshot.tasks != null) {
                for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
                    if (task == null) { unknown++; continue; }
                    rows++;
                    if (task.taskId == hostTaskId) {
                        found = true;
                        base = packageClass(task.basePackage, hostPackage);
                        top = packageClass(task.topPackage, hostPackage);
                        visible = boolClass(task.visible);
                        requested = boolClass(task.requested);
                        sleeping = boolClass(task.sleeping);
                        continue;
                    }
                    if (task.clearlyHidden(snapshot.displayState)) {
                        hidden++;
                    } else if (Boolean.TRUE.equals(task.sleeping)) {
                        asleep++;
                        if (task.basePackage != null && task.basePackage.equals(task.topPackage)
                                && isKnownLauncher(task.topPackage)) {
                            sleepingLauncher++;
                            if (Boolean.FALSE.equals(task.requested)) sleepingLauncherRequestedFalse++;
                            else if (Boolean.TRUE.equals(task.requested)) sleepingLauncherRequestedTrue++;
                            else sleepingLauncherRequestedUnknown++;
                        } else if (task.basePackage != null && task.basePackage.equals(task.topPackage)) {
                            sleepingOther++;
                        } else {
                            sleepingUnknown++;
                        }
                    } else if (Boolean.TRUE.equals(task.visible)) {
                        if (isKnownLauncher(task.topPackage) && task.topPackage.equals(task.basePackage)) launchers++;
                        else visibleOther++;
                    } else if (Boolean.FALSE.equals(task.visible) && Boolean.TRUE.equals(task.requested)) {
                        pending++;
                    } else {
                        unknown++;
                    }
                }
            }
            targetPresent = found;
            targetBase = base;
            targetTop = top;
            targetVisible = visible;
            targetRequested = requested;
            targetSleeping = sleeping;
            rowCount = rows;
            hiddenRows = hidden;
            visibleLauncherRows = launchers;
            visibleOtherRows = visibleOther;
            pendingRows = pending;
            sleepingRows = asleep;
            sleepingLauncherRows = sleepingLauncher;
            sleepingLauncherRequestedFalseRows = sleepingLauncherRequestedFalse;
            sleepingLauncherRequestedTrueRows = sleepingLauncherRequestedTrue;
            sleepingLauncherRequestedUnknownRows = sleepingLauncherRequestedUnknown;
            sleepingOtherRows = sleepingOther;
            sleepingUnknownRows = sleepingUnknown;
            unknownRows = unknown;
        }

        String format(String refusal, String effect, int finalPower) {
            return "source=" + source + " availability=" + availability + " failure=" + failure
                    + " display=" + displayId + " target_task=" + targetTaskId
                    + " power=" + displayPower + " power_before=" + powerBefore + " power_after=" + powerAfter
                    + " final_power=" + finalPower
                    + " target_present=" + targetPresent + " target_base=" + targetBase + " target_top=" + targetTop
                    + " target_visible=" + targetVisible + " target_requested=" + targetRequested
                    + " target_sleeping=" + targetSleeping + " rows=" + rowCount + " hidden=" + hiddenRows
                    + " visible_launcher=" + visibleLauncherRows + " visible_other=" + visibleOtherRows
                    + " pending=" + pendingRows + " sleeping=" + sleepingRows
                    + " sleeping_launcher=" + sleepingLauncherRows
                    + " sleeping_launcher_requested_false=" + sleepingLauncherRequestedFalseRows
                    + " sleeping_launcher_requested_true=" + sleepingLauncherRequestedTrueRows
                    + " sleeping_launcher_requested_unknown=" + sleepingLauncherRequestedUnknownRows
                    + " sleeping_other=" + sleepingOtherRows
                    + " sleeping_unknown=" + sleepingUnknownRows + " unknown=" + unknownRows
                    + " refusal=" + safeReason(refusal) + " effect=" + safeEffect(effect);
        }
    }

    interface FocusEffect { boolean focus(int taskId) throws RemoteException; }

    private VirtualCastRecoveryForeground() {}

    /**
     * The target is independently proven by the live session task id, exact display, and matching
     * base/top Host package. Such a target may itself be hidden, pending-show, or sleeping: focus
     * is the operation that can resume that exact task. Every non-target candidate still needs a
     * unique, visible known rear launcher, unless the remaining row is an invisible known
     * sleeping system launcher explicitly marked not requested. Unknown, pending, sleeping
     * third-party, or visible third-party rows remain fail-closed.
     */
    static Decision resolve(RearDisplayCastOccupancy.Snapshot snapshot, int expectedDisplayId,
                            int hostTaskId, String hostPackage) {
        Diagnostic diagnostic = new Diagnostic(snapshot, hostTaskId, hostPackage);
        if (!validSnapshot(snapshot, expectedDisplayId, hostTaskId, hostPackage))
            return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "snapshot_unavailable", diagnostic);
        if (snapshot.displayState != Display.STATE_ON)
            return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "display_not_on", diagnostic);

        RearDisplayCastOccupancy.Task foreground = null;
        RearDisplayCastOccupancy.Task target = null;
        boolean inactiveSleepingLauncher = false;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            if (task == null || task.taskId < 0)
                return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "invalid_row", diagnostic);
            if (task.taskId == hostTaskId) {
                target = task;
                if (!hostPackage.equals(task.basePackage))
                    return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "target_base_unverified", diagnostic);
                if (task.topPackage == null)
                    return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "target_top_unverified", diagnostic);
                if (!hostPackage.equals(task.topPackage))
                    return decision(Disposition.OTHER_TASK, hostTaskId, expectedDisplayId, "target_top_other", diagnostic);

                // This exact, still-owned Host task is the recovery target, not an external
                // foreground candidate. Its pending/sleep state does not prevent a guarded focus.
                if (Boolean.TRUE.equals(task.visible) && Boolean.FALSE.equals(task.sleeping)) {
                    if (foreground != null)
                        return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId,
                                "multiple_visible_candidates", diagnostic);
                    foreground = task;
                } else if (task.clearlyHidden(snapshot.displayState)
                        || (Boolean.FALSE.equals(task.visible)
                        && (Boolean.TRUE.equals(task.requested) || Boolean.TRUE.equals(task.sleeping)))) {
                    // Hidden/pending/sleeping owned target; require the external foreground below.
                } else {
                    return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId,
                            "target_visibility_unverified", diagnostic);
                }
                continue;
            }

            if (task.clearlyHidden(snapshot.displayState)) continue;
            if (Boolean.FALSE.equals(task.visible) && Boolean.FALSE.equals(task.requested)
                    && Boolean.TRUE.equals(task.sleeping)
                    && task.topPackage != null
                    && task.topPackage.equals(task.basePackage) && isKnownLauncher(task.topPackage)) {
                // A dormant system launcher is not a competing foreground owner. Focusing the
                // exact live Host remains safe when this stale sleep row is the only candidate.
                inactiveSleepingLauncher = true;
                continue;
            }
            if (Boolean.TRUE.equals(task.sleeping))
                return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "external_sleeping", diagnostic);
            if (Boolean.FALSE.equals(task.visible) && Boolean.TRUE.equals(task.requested))
                return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "external_pending", diagnostic);
            if (!Boolean.TRUE.equals(task.visible) || !Boolean.FALSE.equals(task.sleeping)
                    || task.topPackage == null)
                return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "external_visibility_unverified", diagnostic);

            boolean knownLauncher = isKnownLauncher(task.topPackage)
                    && task.topPackage.equals(task.basePackage);
            if (!knownLauncher)
                return decision(Disposition.OTHER_TASK, hostTaskId, expectedDisplayId, "external_visible_other", diagnostic);
            if (foreground != null)
                return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId,
                        "multiple_visible_candidates", diagnostic);
            foreground = task;
        }

        if (target == null)
            return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "target_missing", diagnostic);
        if (foreground == null && inactiveSleepingLauncher)
            return decision(Disposition.FOCUS_HOST, hostTaskId, expectedDisplayId,
                    target.sleeping != null && target.sleeping ? "sleeping_launcher_target_sleeping"
                            : target.requested != null && target.requested ? "sleeping_launcher_target_pending"
                            : "sleeping_launcher_target_hidden", diagnostic);
        if (foreground == null)
            return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "no_visible_candidate", diagnostic);
        if (foreground.taskId == hostTaskId)
            return decision(Disposition.HOST_ALREADY_FRONT, hostTaskId, expectedDisplayId, "target_already_front", diagnostic);
        return decision(Disposition.FOCUS_HOST, hostTaskId, expectedDisplayId,
                target.visible ? "visible_launcher_target_visible" : target.sleeping != null && target.sleeping
                        ? "visible_launcher_target_sleeping" : target.requested != null && target.requested
                        ? "visible_launcher_target_pending" : "visible_launcher_target_hidden", diagnostic);
    }

    /** Wake remains stricter: the exact target identity and all other rear owners must be known. */
    static Decision resolveWake(RearDisplayCastOccupancy.Snapshot snapshot, int expectedDisplayId,
                                int hostTaskId, String hostPackage) {
        Diagnostic diagnostic = new Diagnostic(snapshot, hostTaskId, hostPackage);
        if (!validSnapshot(snapshot, expectedDisplayId, hostTaskId, hostPackage))
            return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "snapshot_unavailable", diagnostic);
        if (snapshot.displayState == Display.STATE_ON) {
            Decision visible = resolve(snapshot, expectedDisplayId, hostTaskId, hostPackage);
            return visible.disposition == Disposition.HOST_ALREADY_FRONT || visible.disposition == Disposition.FOCUS_HOST
                    ? decision(Disposition.WAKE_ALLOWED, hostTaskId, expectedDisplayId, "wake_allowed", visible.diagnostic)
                    : visible;
        }
        if (!isSleeping(snapshot.displayState))
            return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "display_state_unverified", diagnostic);

        boolean hostFound = false;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            if (task == null || task.taskId < 0 || task.basePackage == null || task.topPackage == null)
                return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "row_unverified", diagnostic);
            if (task.taskId == hostTaskId) {
                if (!hostPackage.equals(task.basePackage) || !hostPackage.equals(task.topPackage))
                    return decision(Disposition.EVIDENCE_UNAVAILABLE, hostTaskId, expectedDisplayId, "target_identity_unverified", diagnostic);
                hostFound = true;
            } else if (!(isKnownLauncher(task.topPackage) && task.topPackage.equals(task.basePackage))) {
                return decision(Disposition.OTHER_TASK, hostTaskId, expectedDisplayId, "external_owner", diagnostic);
            }
        }
        return decision(hostFound ? Disposition.WAKE_ALLOWED : Disposition.EVIDENCE_UNAVAILABLE,
                hostTaskId, expectedDisplayId, hostFound ? "wake_allowed" : "target_missing", diagnostic);
    }

    /** Called only after the owning Session's final action-gate check. */
    static String perform(Decision decision, int expectedHostTask, int expectedDisplay,
                          FocusEffect effect) throws RemoteException {
        if (decision == null || decision.hostTaskId != expectedHostTask || decision.displayId != expectedDisplay)
            return "stale_owner";
        switch (decision.disposition) {
            case HOST_ALREADY_FRONT: return "already_front";
            case OTHER_TASK: return "preserved_other_task";
            case EVIDENCE_UNAVAILABLE: return "foreground_unverified";
            case FOCUS_HOST:
                if (effect == null) return "focus_rejected";
                return effect.focus(expectedHostTask) ? "focused" : "focus_rejected";
            case WAKE_ALLOWED: return "wake_allowed";
            default: return "foreground_unverified";
        }
    }

    private static boolean validSnapshot(RearDisplayCastOccupancy.Snapshot snapshot, int expectedDisplayId,
                                         int hostTaskId, String hostPackage) {
        return snapshot != null && snapshot.failure == null && snapshot.displayId == expectedDisplayId
                && expectedDisplayId >= 1 && hostTaskId >= 0 && BuildConfig.APPLICATION_ID.equals(hostPackage)
                && "activity_task".equals(snapshot.source) && snapshot.tasks != null;
    }

    private static boolean isSleeping(int state) {
        return state == Display.STATE_OFF || state == Display.STATE_DOZE
                || state == Display.STATE_DOZE_SUSPEND || state == Display.STATE_ON_SUSPEND;
    }

    private static boolean isKnownLauncher(String packageName) {
        return "com.xiaomi.subscreencenter".equals(packageName) || "com.xiaomi.mirror".equals(packageName);
    }

    private static Decision decision(Disposition disposition, int hostTaskId, int displayId,
                                     String refusal, Diagnostic diagnostic) {
        return new Decision(disposition, hostTaskId, displayId, refusal, diagnostic);
    }

    private static String sourceClass(String source) {
        if ("activity_task".equals(source)) return source;
        if ("am_stack_list".equals(source)) return source;
        return "unavailable";
    }

    private static String failureClass(String failure) {
        if (failure == null) return "none";
        switch (failure) {
            case "invalid_display": case "query_failed": case "query_or_parse_failed": case "wrong_display":
            case "snapshot_unreadable": case "snapshot_unavailable": case "invalid_task_identity":
            case "invalid_visibility": case "encoding": case "snapshot":
                return failure;
            default: return "other";
        }
    }

    private static String packageClass(String value, String hostPackage) {
        if (value == null) return "null";
        if (value.equals(hostPackage)) return "host";
        return "other";
    }

    private static String boolClass(Boolean value) { return value == null ? "unknown" : value.toString(); }

    private static String safeReason(String reason) {
        return reason != null && reason.matches("[a-z_]{1,48}") ? reason : "other";
    }

    private static String safeEffect(String effect) {
        if ("focused".equals(effect) || "already_front".equals(effect) || "preserved_other_task".equals(effect)
                || "foreground_unverified".equals(effect) || "focus_rejected".equals(effect)
                || "wake_allowed".equals(effect) || "wake_requested".equals(effect) || "wake_rejected".equals(effect)
                || "rear_not_on".equals(effect) || "host_not_on_rear".equals(effect)
                || "cancelled".equals(effect) || "stale_session".equals(effect)
                || "stale_owner".equals(effect) || "rear_state_unknown".equals(effect))
            return effect;
        return "other";
    }
}
