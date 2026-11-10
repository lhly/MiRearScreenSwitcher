package top.zainzh.rearscreen;

/** Separates cast-owner identity from newer non-ownership Service start requests. */
final class PhysicalKeeperStopPolicy {
    static final class Decision {
        final boolean allowed;
        final int latestStartId;
        private Decision(boolean allowed, int latestStartId) {
            this.allowed = allowed;
            this.latestStartId = latestStartId;
        }
    }

    private PhysicalKeeperStopPolicy() {}

    static long ownerGenerationAfterServiceStart(long currentOwnerGeneration, String requestedPhysicalTask,
                                                  long requestedOwnerGeneration, int startId) {
        if (requestedPhysicalTask == null) return currentOwnerGeneration;
        return requestedOwnerGeneration > 0L ? requestedOwnerGeneration : Math.max(startId, 0);
    }

    static Decision decide(String requestedTask, long requestedOwnerGeneration,
                           String currentTask, long currentOwnerGeneration, int latestStartId,
                           boolean physicalOwner, boolean explicitReturn) {
        boolean allowed = explicitReturn && physicalOwner && requestedTask != null
                && requestedTask.equals(currentTask) && requestedOwnerGeneration > 0L
                && requestedOwnerGeneration == currentOwnerGeneration && latestStartId > 0;
        return new Decision(allowed, allowed ? latestStartId : -1);
    }

    static boolean mayRestoreOnNaturalExit(boolean explicitReturn, boolean exclusiveLease,
                                           boolean virtualCastActive,
                                           RearDisplayCastOccupancy.OwnerDisposition disposition) {
        return !explicitReturn && !exclusiveLease && !virtualCastActive
                && (disposition == RearDisplayCastOccupancy.OwnerDisposition.EMPTY
                    || disposition == RearDisplayCastOccupancy.OwnerDisposition.SYSTEM_LAUNCHER);
    }
}
