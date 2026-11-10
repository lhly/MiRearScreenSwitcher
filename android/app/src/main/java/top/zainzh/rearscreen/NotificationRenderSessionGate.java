package top.zainzh.rearscreen;

/** Separates an accepted intent handoff from content actually rendered by that Activity generation. */
final class NotificationRenderSessionGate {
    private long acceptedSession = Long.MIN_VALUE;
    private long renderedSession = Long.MIN_VALUE;
    private long displayedSession = Long.MIN_VALUE;

    synchronized void beginActivity(long sessionId) {
        acceptedSession = sessionId;
        renderedSession = Long.MIN_VALUE;
        displayedSession = Long.MIN_VALUE;
    }

    synchronized void acceptHandoff(long sessionId) {
        acceptedSession = sessionId;
        renderedSession = Long.MIN_VALUE;
        displayedSession = Long.MIN_VALUE;
    }

    synchronized boolean markRendered(long sessionId) {
        if (acceptedSession != sessionId) return false;
        renderedSession = sessionId;
        return true;
    }

    synchronized boolean isRenderedBy(long activitySessionId, long sessionId) {
        return activitySessionId == sessionId && acceptedSession == sessionId && renderedSession == sessionId;
    }

    synchronized boolean claimDisplayed(long activitySessionId, long sessionId) {
        if (!isRenderedBy(activitySessionId, sessionId) || displayedSession == sessionId) return false;
        displayedSession = sessionId;
        return true;
    }
}
