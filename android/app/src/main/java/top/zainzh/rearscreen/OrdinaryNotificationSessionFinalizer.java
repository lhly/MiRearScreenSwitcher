package top.zainzh.rearscreen;

import java.util.HashMap;
import java.util.Map;

/** Owns one end-and-restore decision for an ordinary notification session. */
final class OrdinaryNotificationSessionFinalizer {
    interface Host {
        boolean isCurrent(long sessionId);
        boolean hasDifferentOwner(long sessionId);
        boolean end(long sessionId);
        void finishActivity(long sessionId);
        void restore(long sessionId);
    }

    private static final class Entry {
        final long id;
        final Host host;
        boolean prepared;
        boolean launchFinished;
        boolean activityAttached;
        boolean activityDestroyed;
        boolean endAttempted;
        boolean endedByThisSession;
        boolean finishBroadcastSent;
        boolean restoreClaimed;
        boolean superseded;
        Object attachedWindow;

        Entry(long id, Host host) { this.id = id; this.host = host; }
    }

    private final Map<Long, Entry> entries = new HashMap<>();

    synchronized void register(long id, Host host) {
        if (id == Long.MIN_VALUE) throw new IllegalArgumentException("invalid notification session");
        entries.put(id, new Entry(id, host));
    }

    /** Returns false only when a registered session is already ending. Unregistered legacy activity is allowed. */
    synchronized boolean activityAttached(long id, Object window) {
        Entry entry = entries.get(id);
        if (entry == null) return true;
        if (entry.endAttempted && entry.endedByThisSession) return false;
        entry.activityAttached = true;
        entry.attachedWindow = window;
        return true;
    }

    synchronized void markPrepared(long id) {
        Entry entry = entries.get(id);
        if (entry != null) entry.prepared = true;
    }

    synchronized void launchFinished(long id) {
        Entry entry = entries.get(id);
        if (entry == null) return;
        entry.launchFinished = true;
        // Replacement can invalidate a launch without a source-removal callback.
        if (entry.host.hasDifferentOwner(id)) entry.superseded = true;
        if (!entry.endAttempted && !entry.host.isCurrent(id)) {
            entry.endAttempted = true;
            entry.endedByThisSession = false;
        }
        finishIfReady(entry);
    }

    synchronized boolean requestFinish(long id) {
        Entry entry = entries.get(id);
        if (entry == null) return false;
        endIfNeeded(entry);
        if (entry.endedByThisSession && entry.activityAttached && !entry.finishBroadcastSent) {
            entry.finishBroadcastSent = true;
            entry.host.finishActivity(id);
        }
        finishIfReady(entry);
        return true;
    }

    synchronized boolean activityDestroyed(long id, Object window) {
        Entry entry = entries.get(id);
        if (entry == null) return false;
        // A stale Activity from a replaced/recreated window cannot detach or end its successor.
        if (entry.attachedWindow != window) return true;
        endIfNeeded(entry);
        entry.activityAttached = false;
        entry.attachedWindow = null;
        entry.activityDestroyed = true;
        finishIfReady(entry);
        return true;
    }

    /** Configuration teardown detaches this window without ending a still-live notification session. */
    synchronized void activityDetachedForRecreation(long id, Object window) {
        Entry entry = entries.get(id);
        if (entry == null || entry.attachedWindow != window) return;
        entry.activityAttached = false;
        entry.attachedWindow = null;
        finishIfReady(entry);
    }

    synchronized void activityHandoff(long oldId, Object window) {
        Entry entry = entries.get(oldId);
        if (entry == null) return;
        if (entry.attachedWindow != window) return;
        entry.activityAttached = false;
        entry.attachedWindow = null;
        if (entry.host.hasDifferentOwner(oldId)) entry.superseded = true;
        if (!entry.endAttempted && !entry.host.isCurrent(oldId)) {
            entry.endAttempted = true;
            entry.endedByThisSession = false;
        }
        finishIfReady(entry);
    }

    synchronized boolean isTracked(long id) { return entries.containsKey(id); }

    private void endIfNeeded(Entry entry) {
        if (entry.endAttempted) return;
        entry.endAttempted = true;
        entry.endedByThisSession = entry.host.end(entry.id);
    }

    private void finishIfReady(Entry entry) {
        if (entry.host.hasDifferentOwner(entry.id)) entry.superseded = true;
        if (entry.endedByThisSession && !entry.superseded && entry.prepared && !entry.activityAttached
                && entry.launchFinished && !entry.restoreClaimed) {
            entry.restoreClaimed = true;
            entry.host.restore(entry.id);
        }
        if (entry.restoreClaimed || (entry.superseded && entry.launchFinished && !entry.activityAttached)
                || (!entry.endedByThisSession && entry.endAttempted
                && entry.launchFinished && !entry.activityAttached)
                || (entry.endedByThisSession && !entry.prepared && entry.launchFinished && !entry.activityAttached)) {
            if (entries.get(entry.id) == entry) entries.remove(entry.id);
        }
    }
}
