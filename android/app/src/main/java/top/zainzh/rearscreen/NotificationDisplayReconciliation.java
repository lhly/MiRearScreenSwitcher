package top.zainzh.rearscreen;

/** Pure placement decision shared by resume, configuration, and display-move callbacks. */
final class NotificationDisplayReconciliation {
    static final class Decision {
        final boolean hideContent;
        final boolean initializeContent;
        final boolean keepWatchdog;
        final boolean startLegacyWakeLoop;
        Decision(boolean hideContent, boolean initializeContent, boolean keepWatchdog, boolean startLegacyWakeLoop) {
            this.hideContent = hideContent;
            this.initializeContent = initializeContent;
            this.keepWatchdog = keepWatchdog;
            this.startLegacyWakeLoop = startLegacyWakeLoop;
        }
    }
    private NotificationDisplayReconciliation() {}
    static Decision decide(boolean onRear, boolean contentReady, boolean owned) {
        if (!onRear) return new Decision(true, false, true, false);
        if (!contentReady) return new Decision(false, true, true, false);
        return new Decision(false, false, false, !owned);
    }
}
