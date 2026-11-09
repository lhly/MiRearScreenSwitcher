package top.zainzh.rearscreen;

import java.util.function.BooleanSupplier;

/** One non-waking display-idle refresh per valid physical notification presentation. */
final class OwnedNotificationActivityReset {
    enum Result {
        REPORTED, BACKEND_REJECTED, BACKEND_FAILED, STALE_SESSION, NOT_DRAWN, INVALID_DRAW_TIME, EXPIRED,
        WRONG_DISPLAY, DISPLAY_OFF, DUPLICATE
    }

    interface Reporter {
        boolean report(int displayId, long notificationSessionId, long expiresAtElapsedRealtimeMs,
                       long drawEventUptime) throws Exception;
    }

    private final long sessionId;
    private final long expiresAtElapsedRealtimeMs;
    private boolean attempted;

    OwnedNotificationActivityReset(long sessionId, long expiresAtElapsedRealtimeMs) {
        if (sessionId <= 0 || expiresAtElapsedRealtimeMs < 0) throw new IllegalArgumentException("Invalid notification session");
        this.sessionId = sessionId;
        this.expiresAtElapsedRealtimeMs = expiresAtElapsedRealtimeMs;
    }

    Result afterDraw(long reportedSessionId, BooleanSupplier stillCurrent, boolean actualDrawValidated,
                     int drawnDisplayId, int selectedRearDisplayId, boolean displayOn,
                     long nowElapsedRealtimeMs, long drawEventUptime, Reporter reporter) {
        synchronized (this) {
            if (reportedSessionId != sessionId || stillCurrent == null || !stillCurrent.getAsBoolean())
                return Result.STALE_SESSION;
            if (!actualDrawValidated) return Result.NOT_DRAWN;
            if (drawnDisplayId < 1 || drawnDisplayId != selectedRearDisplayId) return Result.WRONG_DISPLAY;
            if (!displayOn) return Result.DISPLAY_OFF;
            if (drawEventUptime < 0 || nowElapsedRealtimeMs < 0) return Result.INVALID_DRAW_TIME;
            if (nowElapsedRealtimeMs >= expiresAtElapsedRealtimeMs) return Result.EXPIRED;
            if (attempted) return Result.DUPLICATE;
            // Consume before dispatch: a failed Binder call never turns into a hidden retry loop.
            attempted = true;
        }
        if (!stillCurrent.getAsBoolean()) return Result.STALE_SESSION;
        if (reporter == null) return Result.BACKEND_REJECTED;
        try {
            return reporter.report(drawnDisplayId, sessionId, expiresAtElapsedRealtimeMs, drawEventUptime)
                    ? Result.REPORTED : Result.BACKEND_REJECTED;
        } catch (Exception unavailable) {
            return Result.BACKEND_FAILED;
        }
    }
}
