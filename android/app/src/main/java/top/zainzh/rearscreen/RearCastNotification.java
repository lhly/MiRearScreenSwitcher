package top.zainzh.rearscreen;

/** In-process delivery to the existing cast host. Never creates an Activity or animation session. */
final class RearCastNotification {
    private static final java.util.concurrent.atomic.AtomicLong tokens = new java.util.concurrent.atomic.AtomicLong();
    interface Target {
        void show(Message message);
        default void clearNotification(long token) { }
        default void clearCharging() { }
    }
    private static Target target;
    private static long activeNotificationToken;

    static synchronized void attach(Target next) {
        if (target != next) activeNotificationToken = 0;
        target = next;
    }
    static synchronized void detach(Target previous) { if (target == previous) { target = null; activeNotificationToken = 0; } }
    static synchronized boolean hasTarget() { return target != null; }

    static boolean offer(String pkg, String title, String text, long now, int seconds, boolean oled) {
        Target current;
        Message message = new Message(pkg, title, text, now, seconds, oled);
        synchronized (RearCastNotification.class) {
            current = target;
            if (current != null) activeNotificationToken = message.token;
        }
        if (current == null) return false;
        current.show(message);
        return true;
    }
    static boolean offerCharging(String pkg, String title, String text, long now, boolean oled) {
        Target current;
        synchronized (RearCastNotification.class) { current = target; }
        if (current == null) return false;
        current.show(new Message(pkg, title, text, now, 8, oled, true));
        return true;
    }
    static void clearCharging() {
        Target current;
        synchronized (RearCastNotification.class) { current = target; }
        if (current != null) current.clearCharging();
    }

    static synchronized long captureNotificationToken() { return activeNotificationToken; }

    static void clearNotification(long token) {
        Target current;
        synchronized (RearCastNotification.class) {
            if (token == 0 || activeNotificationToken != token) return;
            activeNotificationToken = 0;
            current = target;
        }
        if (current != null) current.clearNotification(token);
    }

    static final class Message {
        final String packageName, title, text;
        final long expiresAt;
        final long token = tokens.incrementAndGet();
        final boolean oled;
        final boolean charging;
        Message(String pkg, String title, String text, long now, int seconds, boolean oled) {
            this(pkg, title, text, now, seconds, oled, false);
        }
        private Message(String pkg, String title, String text, long now, int seconds, boolean oled, boolean charging) {
            packageName = pkg;
            this.title = line(title);
            this.text = line(text);
            expiresAt = now + Math.max(1, Math.min(120, seconds)) * 1000L;
            this.oled = oled;
            this.charging = charging;
        }
        long remaining(long now) { return Math.max(0, expiresAt - now); }
        private static String line(String text) {
            if (text == null) return "";
            String result = text.replace('\n', ' ').replace('\r', ' ').trim();
            // Bound retained notification content even when an App posts a very large payload.
            if (result.length() <= 512) return result;
            int end = Character.isHighSurrogate(result.charAt(511)) ? 511 : 512;
            return result.substring(0, end);
        }
    }
}
