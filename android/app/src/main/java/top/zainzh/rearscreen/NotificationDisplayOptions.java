package top.zainzh.rearscreen;

import java.util.Objects;

/** Pure decisions shared by the notification UI contract and lifecycle implementation. */
final class NotificationDisplayOptions {
    private NotificationDisplayOptions() {}

    static int durationSeconds(int value, boolean minutes) {
        if (value < 1 || value > 60) throw new IllegalArgumentException("duration value must be 1..60");
        return minutes ? Math.multiplyExact(value, 60) : value;
    }

    static int durationAfterConfirmation(int storedSeconds, boolean wheelChanged, int value, boolean minutes) {
        return wheelChanged ? durationSeconds(value, minutes) : Math.max(1, storedSeconds);
    }

    static long configuredDurationMillis(int seconds) { return Math.max(1L, seconds) * 1000L; }

    static boolean hasAutomaticFinish(boolean ownedNotification, boolean keepUntilRemoved) {
        // Owned SDK/Mod presentations retain their bounded TTL even when ordinary notifications persist.
        return ownedNotification || !keepUntilRemoved;
    }

    static boolean shouldLaunchSourceApp(boolean ownedNotification, boolean dismissOnly) {
        return ownedNotification || !dismissOnly;
    }

    static boolean shouldKeepUntilRemoved(boolean preferenceEnabled, String notificationKey, long postTime) {
        return preferenceEnabled && notificationKey != null && !notificationKey.isEmpty() && postTime > 0;
    }

    static boolean matchesRemoval(String activeKey, long activePostTime, String removedKey, long removedPostTime,
                                  boolean persistent, long activeSession) {
        return persistent && activeSession != Long.MIN_VALUE && Objects.equals(activeKey, removedKey)
                && activePostTime == removedPostTime;
    }
}
