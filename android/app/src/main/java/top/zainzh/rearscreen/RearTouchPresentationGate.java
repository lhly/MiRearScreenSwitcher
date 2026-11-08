package top.zainzh.rearscreen;

/** Fail-closed proof that this exact cast host is currently presenting on the physical rear. */
final class RearTouchPresentationGate {
    @FunctionalInterface interface RearPowerRead { boolean stillOn(int displayId) throws Exception; }
    enum Decision {
        ALLOW,
        SNAPSHOT_UNAVAILABLE,
        DISPLAY_MISMATCH,
        DISPLAY_NOT_ON,
        HOST_MISSING,
        HOST_IDENTITY_MISMATCH,
        HOST_NOT_VISIBLE,
        OTHER_TASK_VISIBLE
    }

    private RearTouchPresentationGate() {}

    static Decision evaluate(RearDisplayCastOccupancy.Snapshot snapshot, int expectedDisplay,
                             int expectedTask, String hostPackage) {
        if (snapshot == null || snapshot.failure != null || !"activity_task".equals(snapshot.source))
            return Decision.SNAPSHOT_UNAVAILABLE;
        if (snapshot.displayId != expectedDisplay) return Decision.DISPLAY_MISMATCH;
        if (snapshot.displayState != android.view.Display.STATE_ON
                || snapshot.powerBefore != android.view.Display.STATE_ON
                || snapshot.powerAfter != android.view.Display.STATE_ON) return Decision.DISPLAY_NOT_ON;

        RearDisplayCastOccupancy.Task host = null;
        for (RearDisplayCastOccupancy.Task task : snapshot.tasks) {
            if (task.taskId == expectedTask) {
                if (host != null) return Decision.HOST_IDENTITY_MISMATCH;
                host = task;
                continue;
            }
            if (!task.clearlyHidden(snapshot.displayState)) return Decision.OTHER_TASK_VISIBLE;
        }
        if (host == null) return Decision.HOST_MISSING;
        if (hostPackage == null || !hostPackage.equals(host.basePackage)
                || !hostPackage.equals(host.topPackage)) return Decision.HOST_IDENTITY_MISMATCH;
        if (!Boolean.TRUE.equals(host.visible) || !Boolean.TRUE.equals(host.requested)
                || !Boolean.FALSE.equals(host.sleeping)) return Decision.HOST_NOT_VISIBLE;
        return Decision.ALLOW;
    }

    /** All display/Binder reads happen here, before the caller's final local lease fence. */
    static Decision inspect(RearDisplayCastOccupancy.Snapshot snapshot, int expectedDisplay,
                            int expectedTask, String hostPackage, RearPowerRead finalPowerRead) throws Exception {
        Decision snapshotDecision = evaluate(snapshot, expectedDisplay, expectedTask, hostPackage);
        if (snapshotDecision != Decision.ALLOW) return snapshotDecision;
        return finalPowerRead != null && finalPowerRead.stillOn(expectedDisplay)
                ? Decision.ALLOW : Decision.DISPLAY_NOT_ON;
    }
}
