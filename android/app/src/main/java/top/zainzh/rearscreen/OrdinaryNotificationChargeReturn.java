package top.zainzh.rearscreen;

/** Resumes only the latest still-valid persistent ordinary notice after its covering charge session exits. */
final class OrdinaryNotificationChargeReturn {
    static final long NO_SESSION = Long.MIN_VALUE;

    static final class Notice {
        final String key;
        final long postTime;
        final String packageName;
        final String title;
        final String text;
        final long when;
        final long sessionId;

        Notice(String key, long postTime, String packageName, String title, String text, long when, long sessionId) {
            this.key = key;
            this.postTime = postTime;
            this.packageName = packageName;
            this.title = title;
            this.text = text;
            this.when = when;
            this.sessionId = sessionId;
        }

        Notice withContent(String nextTitle, String nextText, long nextWhen) {
            return new Notice(key, postTime, packageName, nextTitle, nextText, nextWhen, sessionId);
        }

        Notice withSessionId(long nextSessionId) {
            return new Notice(key, postTime, packageName, title, text, when, nextSessionId);
        }
    }

    /** Binds one current ordinary notice to only the charge session that actually covered it. */
    static final class Lease {
        private Notice current;
        private long coveringChargeSessionId = NO_SESSION;

        synchronized void noticePresented(Notice notice) {
            current = notice;
            coveringChargeSessionId = NO_SESSION;
        }

        synchronized void chargingStarted(long chargeSessionId, long replacedSessionId,
                boolean replacedNotification, boolean replacedCharging) {
            if (chargeSessionId == NO_SESSION) {
                coveringChargeSessionId = NO_SESSION;
                return;
            }
            boolean firstCover = replacedNotification && current != null
                    && current.sessionId == replacedSessionId && hasValidSource(current);
            boolean continuedCover = replacedCharging && coveringChargeSessionId == replacedSessionId
                    && current != null && hasValidSource(current);
            coveringChargeSessionId = firstCover || continuedCover ? chargeSessionId : NO_SESSION;
        }

        synchronized Notice noticeCoveredBy(long chargeSessionId) {
            return chargeSessionId != NO_SESSION && coveringChargeSessionId == chargeSessionId
                    && hasValidSource(current) ? current : null;
        }

        synchronized Notice noticeForSession(long sessionId) {
            return current != null && current.sessionId == sessionId ? current : null;
        }

        synchronized boolean ordinaryActivityDestroyed(long sessionId, long stillCoveringChargeSessionId) {
            if (current == null || current.sessionId != sessionId) return false;
            if (coveringChargeSessionId != NO_SESSION && coveringChargeSessionId == stillCoveringChargeSessionId) return false;
            current = null;
            coveringChargeSessionId = NO_SESSION;
            return true;
        }

        synchronized void sourceRemoved(String key, long postTime) {
            if (current != null && current.postTime == postTime && java.util.Objects.equals(current.key, key)) clear();
        }

        /** A real user action consumes this exact notice before finish/launch can race with charge takeover. */
        synchronized boolean userHandled(long sessionId) {
            if (current == null || current.sessionId != sessionId) return false;
            clear();
            return true;
        }

        synchronized void clear() {
            current = null;
            coveringChargeSessionId = NO_SESSION;
        }

        synchronized boolean isCurrent(Notice expected) { return sameIdentity(current, expected); }

        synchronized void resumed(Notice expected, Notice refreshedNotice, long resumedSessionId) {
            if (!sameIdentity(current, expected) || resumedSessionId == NO_SESSION) return;
            current = (refreshedNotice == null ? current : refreshedNotice).withSessionId(resumedSessionId);
            coveringChargeSessionId = NO_SESSION;
        }

        synchronized void updateContent(Notice refreshedNotice) {
            if (sameIdentity(current, refreshedNotice)) current = refreshedNotice;
        }

        private static boolean hasValidSource(Notice notice) {
            return notice != null && notice.key != null && !notice.key.isEmpty() && notice.postTime > 0;
        }

        private static boolean sameIdentity(Notice a, Notice b) {
            return hasValidSource(a) && hasValidSource(b) && a.postTime == b.postTime
                    && a.sessionId == b.sessionId && a.key.equals(b.key)
                    && java.util.Objects.equals(a.packageName, b.packageName);
        }
    }

    static final class ResumedSession {
        final long id;
        final int returnTaskId;
        final Notice notice;
        ResumedSession(long id, int returnTaskId) { this(id, returnTaskId, null); }
        ResumedSession(long id, int returnTaskId, Notice notice) {
            this.id = id; this.returnTaskId = returnTaskId; this.notice = notice;
        }
    }

    interface Host {
        Notice currentPersistentNotice(long chargeSessionId);
        Notice refreshForCurrentPolicy(Notice notice);
        void discardIfCurrent(Notice notice);
        ResumedSession beginAfterCharge(Notice notice, long chargeSessionId, int returnTaskId);
        void launch(Notice notice, ResumedSession resumedSession);
    }

    private OrdinaryNotificationChargeReturn() {}

    static boolean resumeAfterCharge(Host host, long chargeSessionId, int returnTaskId) {
        Notice boundNotice = host.currentPersistentNotice(chargeSessionId);
        if (boundNotice == null) return false;
        Notice notice = host.refreshForCurrentPolicy(boundNotice);
        if (notice == null) {
            host.discardIfCurrent(boundNotice);
            return false;
        }
        ResumedSession resumedSession = host.beginAfterCharge(notice, chargeSessionId, returnTaskId);
        if (resumedSession == null || resumedSession.id == NO_SESSION) return false;
        host.launch(resumedSession.notice == null ? notice : resumedSession.notice, resumedSession);
        return true;
    }
}
