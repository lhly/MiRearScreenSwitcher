package top.zainzh.rearscreen;

/** Exact owner-token gate for handing a live charging display session to a persistent notice. */
final class PersistentNotificationChargeGate {
    private PersistentNotificationChargeGate() {}

    static boolean canTransfer(boolean currentSessionIsCharging,
            long currentSessionId, long expectedChargeSessionId) {
        return currentSessionIsCharging && expectedChargeSessionId != Long.MIN_VALUE
                && currentSessionId == expectedChargeSessionId;
    }
}
