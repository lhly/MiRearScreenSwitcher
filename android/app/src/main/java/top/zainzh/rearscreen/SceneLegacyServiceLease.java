package top.zainzh.rearscreen;

/**
 * Excludes legacy global display keepers while a first-party Scene owns the rear display.
 * This is deliberately a process-local lease signal: it never starts a service and never
 * replaces ModuleDisplayRecoveryService's independent owner/lease contract.
 */
public final class SceneLegacyServiceLease {
    private static volatile String activeLeaseId;

    private SceneLegacyServiceLease() { }

    public static synchronized boolean acquire(String leaseId) {
        if (leaseId == null || leaseId.isEmpty()) return false;
        if (activeLeaseId != null && !activeLeaseId.equals(leaseId)) return false;
        activeLeaseId = leaseId;
        AlwaysWakeUpService.suspendForSceneLease(leaseId);
        RearScreenKeeperService.suspendForSceneLease(leaseId);
        return true;
    }

    /** Release is exact-match and idempotent; stale close callbacks cannot resume a newer lease. */
    public static synchronized void release(String leaseId, boolean restorePriorMode) {
        if (leaseId == null || !leaseId.equals(activeLeaseId)) return;
        activeLeaseId = null;
        AlwaysWakeUpService.resumeAfterSceneLease(leaseId, restorePriorMode);
        RearScreenKeeperService.resumeAfterSceneLease(leaseId, restorePriorMode);
    }

    public static boolean hasActiveLease() { return activeLeaseId != null; }

    public static String activeLeaseId() { return activeLeaseId; }

    public static boolean isCurrent(String leaseId) {
        return leaseId != null && leaseId.equals(activeLeaseId);
    }
}
