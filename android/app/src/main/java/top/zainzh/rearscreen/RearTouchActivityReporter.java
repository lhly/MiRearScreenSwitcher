package top.zainzh.rearscreen;

import java.util.function.BooleanSupplier;

/** Throttles accepted real input into physical-rear activity, never wake. */
final class RearTouchActivityReporter {
    static final long MIN_REPORT_INTERVAL_MS = 1000;

    interface Presentation {
        RearTouchPresentationGate.Decision inspect(int rearDisplayId, int hostTaskId, String hostPackage) throws Exception;
    }

    enum Result { REPORTED, THROTTLED, SESSION_INACTIVE, INPUT_NOT_VIRTUAL, HOST_NOT_PRESENTED, UNAVAILABLE }

    private final RearDisplayUserActivity.Sink sink;
    private final Presentation presentation;
    private long lastAttemptUptime = Long.MIN_VALUE;
    private long firstReportedUptime = -1, lastReportedUptime = -1;
    private long reportCount;
    private RearTouchPresentationGate.Decision lastDecision;

    RearTouchActivityReporter(RearDisplayUserActivity.Sink sink, Presentation presentation) {
        this.sink = sink;
        this.presentation = presentation;
    }

    /** Called after an accepted real contact frame or a matched final UP has completed its router path. */
    synchronized Result acceptedInteraction(long now, int inputDisplayId, int rearDisplayId,
                                      int hostTaskId, String hostPackage,
                                      BooleanSupplier sessionCurrent, BooleanSupplier outputReady) {
        if (inputDisplayId < 1 || inputDisplayId == rearDisplayId) return Result.INPUT_NOT_VIRTUAL;
        if (sessionCurrent == null || outputReady == null || !sessionCurrent.getAsBoolean()
                || !outputReady.getAsBoolean() || sink == null || presentation == null)
            return Result.SESSION_INACTIVE;
        if (lastAttemptUptime != Long.MIN_VALUE && now >= lastAttemptUptime
                && now - lastAttemptUptime < MIN_REPORT_INTERVAL_MS) return Result.THROTTLED;
        // Reserve before querying/calling: concurrent frames cannot create a Binder burst,
        // and an unavailable backend is retried only on a later user touch after the interval.
        lastAttemptUptime = now;
        try {
            RearTouchPresentationGate.Decision decision = presentation.inspect(rearDisplayId, hostTaskId, hostPackage);
            lastDecision = decision;
            if (decision != RearTouchPresentationGate.Decision.ALLOW) return Result.HOST_NOT_PRESENTED;
            // The task/power query is Binder-backed and may block. Recheck the volatile lease/output
            // fence after it, immediately before the display side effect; do not take Session locks.
            if (!sessionCurrent.getAsBoolean() || !outputReady.getAsBoolean()) return Result.SESSION_INACTIVE;
            sink.report(rearDisplayId, now, RearDisplayUserActivity.EVENT_TOUCH);
            if (firstReportedUptime < 0) firstReportedUptime = now;
            lastReportedUptime = now;
            reportCount++;
            return Result.REPORTED;
        } catch (Exception unavailable) {
            return Result.UNAVAILABLE;
        }
    }

    synchronized String summary() {
        return "reports=" + reportCount + " first=" + firstReportedUptime + " last=" + lastReportedUptime;
    }

    synchronized String lastDecision() { return lastDecision == null ? "not_checked" : lastDecision.name(); }
}
