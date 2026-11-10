package top.zainzh.rearscreen;

import android.view.WindowManager;

/** Notification content is eligible to cover keyguard only after its window is on the rear display. */
final class NotificationWindowPolicy {
    private NotificationWindowPolicy() {}

    static int flags(boolean onRearDisplay, boolean ownedNotification) {
        if (!onRearDisplay) return 0;
        return WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | (ownedNotification ? 0 : WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    static boolean showWhenLocked(boolean onRearDisplay) { return onRearDisplay; }

    /** Display wake is issued explicitly for the rear only; an Activity never wakes the primary. */
    static boolean turnScreenOn() { return false; }
}
