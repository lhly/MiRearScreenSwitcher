package top.zainzh.rearscreen;

/** Session-scoped handoff/finish arbitration for the single-instance notification Activity. */
final class NotificationActivitySessionGate {
    private long currentSession = Long.MIN_VALUE;
    private boolean finished;

    synchronized boolean accept(long sessionId, boolean activityFinishing) {
        if (activityFinishing) return false;
        if (currentSession != sessionId) {
            currentSession = sessionId;
            finished = false;
        }
        return !finished;
    }

    synchronized boolean finish(long sessionId) {
        if (currentSession != sessionId || finished) return false;
        finished = true;
        return true;
    }

    synchronized boolean isCurrent(long sessionId) {
        return currentSession == sessionId && !finished;
    }
}
