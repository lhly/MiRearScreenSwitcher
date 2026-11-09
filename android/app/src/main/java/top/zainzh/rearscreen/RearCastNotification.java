package top.zainzh.rearscreen;

import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** In-process delivery to the existing cast host. Never creates an Activity or animation session. */
final class RearCastNotification {
    static final long OWNED_DEFERRED = Long.MIN_VALUE;
    private static final AtomicLong tokens = new AtomicLong();
    private static volatile LongSupplier clock = SystemClock::elapsedRealtime;

    interface Listener { void onDisplayed(); void onEnded(String reason); }
    interface Target {
        void show(Message message);
        default void clearNotification(long token) { }
        default void clearNotification(long token, String reason) { clearNotification(token); }
        default void clearCharging() { }
        default void presentationAvailable() { }
    }

    private static Target target;
    private static long activeNotificationToken;
    private static Message activeMessage;

    static synchronized void attach(Target next) {
        if (target != next) {
            activeNotificationToken = 0;
            activeMessage = null;
        }
        target = next;
    }

    static void detach(Target previous) {
        Message ended = null;
        synchronized (RearCastNotification.class) {
            if (target == previous) {
                target = null;
                activeNotificationToken = 0;
                ended = activeMessage;
                activeMessage = null;
            }
        }
        if (ended != null) ended.end("DISPLAY_UNAVAILABLE");
    }

    static synchronized boolean hasTarget() { return target != null; }

    static boolean offer(String pkg, String title, String text, long now, int seconds, boolean oled) {
        Message message = new Message(pkg, title, text,
                now + Math.max(1, Math.min(120, seconds)) * 1000L, oled);
        return offerSource(message);
    }

    /** Owned requests never replace a source notification or charging message. */
    static long offerOwned(String pkg, String title, String text, long expiresAtElapsedMs, boolean oled, Listener listener) {
        Target current;
        Message message;
        synchronized (RearCastNotification.class) {
            current = target;
            if (current == null) return 0;
            if (activeMessage != null) return OWNED_DEFERRED;
            message = new Message(pkg, title, text, expiresAtElapsedMs, oled, listener);
            activeMessage = message;
            activeNotificationToken = message.token;
        }
        current.show(message);
        return -message.token;
    }

    private static boolean offerSource(Message message) {
        Target current;
        Message replaced = null;
        Message covered = null;
        synchronized (RearCastNotification.class) {
            current = target;
            if (current == null) return false;
            if (activeMessage != null) {
                if (activeMessage.isOwned()) covered = activeMessage;
                else replaced = activeMessage;
            }
            activeMessage = message;
            activeNotificationToken = message.token;
        }
        if (replaced != null) replaced.end("REPLACED");
        if (covered != null) covered.cover();
        current.show(message);
        return true;
    }

    /** Charging is a first-class token so a late stop cannot erase a newer source message. */
    static long offerCharging(String pkg, String title, String text, long now, boolean oled) {
        Target current;
        Message previous;
        Message charge = new Message(pkg, title, text, now + 8_000L, oled, true);
        synchronized (RearCastNotification.class) {
            current = target;
            if (current == null) return 0;
            previous = activeMessage;
            activeMessage = charge;
            activeNotificationToken = charge.token;
        }
        if (previous != null) {
            if (previous.isOwned()) previous.cover();
            else previous.end("COVERED");
        }
        current.show(charge);
        return charge.token;
    }

    static void clearCharging(long chargingToken) {
        if (chargingToken <= 0) return;
        Target current;
        Message charge;
        synchronized (RearCastNotification.class) {
            if (activeNotificationToken != chargingToken || activeMessage == null ||
                    activeMessage.token != chargingToken || !activeMessage.charging) return;
            current = target;
            charge = activeMessage;
            activeNotificationToken = 0;
            activeMessage = null;
        }
        charge.end("CANCELLED");
        if (current != null) current.clearNotification(chargingToken, "CANCELLED");
        if (current != null) current.presentationAvailable();
    }

    /** Router validation is authoritative; covered capsules are never revived by the renderer. */
    static long resumeOwnedNotification(long token) {
        long messageToken = token < 0 ? -token : token;
        synchronized (RearCastNotification.class) {
            if (target == null) return 0;
            if (activeMessage != null && !activeMessage.isOwned()) return OWNED_DEFERRED;
            if (activeNotificationToken == messageToken && activeMessage != null && activeMessage.token == messageToken &&
                    activeMessage.isOwned() && activeMessage.remaining(clock.getAsLong()) > 0) return -messageToken;
            return 0;
        }
    }

    /** Retires an owned capsule; the router keeps the item and may issue a fresh render. */
    static void suspendOwnedNotification(long token) { clearNotification(token, "COVERED"); }

    static synchronized long captureNotificationToken() { return activeNotificationToken; }
    static synchronized boolean isCurrentOwnedToken(long token) {
        return activeNotificationToken == token && activeMessage != null && activeMessage.token == token && activeMessage.isOwned();
    }

    static void clearNotification(long token) { clearNotification(token, "CANCELLED"); }
    static void clearNotification(long token, String reason) {
        Target current;
        Message ended;
        synchronized (RearCastNotification.class) {
            if (token == 0 || activeNotificationToken != token || activeMessage == null || activeMessage.token != token) return;
            ended = activeMessage;
            activeMessage = null;
            activeNotificationToken = 0;
            current = target;
        }
        if ("COVERED".equals(reason)) ended.cover(); else ended.end(reason);
        if (current != null) current.clearNotification(token, reason);
    }

    /** A source/charging surface ended; only the router may choose the next owned item. */
    static void overlayEnded(long token, String reason) {
        Target current;
        Message ended;
        synchronized (RearCastNotification.class) {
            if (activeNotificationToken != token || activeMessage == null || activeMessage.token != token) return;
            ended = activeMessage;
            activeMessage = null;
            activeNotificationToken = 0;
            current = target;
        }
        ended.end(reason);
        if (current != null) current.presentationAvailable();
    }

    static void markDisplayed(long token) {
        Message current;
        synchronized (RearCastNotification.class) { current = activeNotificationToken == token ? activeMessage : null; }
        if (current != null && current.isOwned()) current.display();
    }

    static void setClockForTesting(LongSupplier testClock) { clock = testClock; }

    static final class Message {
        final String packageName, title, text;
        final long expiresAt;
        final long token = tokens.incrementAndGet();
        final boolean oled;
        final boolean charging;
        final Listener listener;
        private boolean displayed, covered, ended;

        Message(String pkg, String title, String text, long expiresAt, boolean oled) {
            this(pkg, title, text, expiresAt, oled, false, null);
        }
        Message(String pkg, String title, String text, long expiresAt, boolean oled, Listener listener) {
            this(pkg, title, text, expiresAt, oled, false, listener);
        }
        /** Legacy source-notification duration constructor. */
        Message(String pkg, String title, String text, long nowElapsedMs, int seconds, boolean oled) {
            this(pkg, title, text, nowElapsedMs + Math.max(1, Math.min(120, seconds)) * 1000L, oled, false, null);
        }
        Message(String pkg, String title, String text, long expiresAt, boolean oled, boolean charging) {
            this(pkg, title, text, expiresAt, oled, charging, null);
        }
        private Message(String pkg, String title, String text, long expiresAt, boolean oled, boolean charging, Listener listener) {
            packageName = pkg;
            this.title = line(title);
            this.text = line(text);
            this.expiresAt = expiresAt;
            this.oled = oled;
            this.charging = charging;
            this.listener = listener;
        }
        synchronized boolean isOwned() { return listener != null; }
        void display() {
            Listener notify;
            synchronized (this) {
                if (ended || covered || displayed) return;
                displayed = true;
                notify = listener;
            }
            if (notify != null) notify.onDisplayed();
        }
        void cover() {
            Listener notify;
            synchronized (this) {
                if (ended || covered) return;
                covered = true;
                notify = listener;
            }
            if (notify != null) notify.onEnded("COVERED");
        }
        void end(String reason) {
            if ("COVERED".equals(reason)) { cover(); return; }
            Listener notify;
            synchronized (this) {
                if (ended) return;
                ended = true;
                notify = listener;
            }
            if (notify != null) notify.onEnded(reason);
        }
        synchronized boolean isEnded() { return ended; }
        long remaining(long now) { return Math.max(0, expiresAt - now); }
        private static String line(String text) {
            if (text == null) return "";
            String result = text.replace('\n', ' ').replace('\r', ' ').trim();
            if (result.length() <= 512) return result;
            int end = Character.isHighSurrogate(result.charAt(511)) ? 511 : 512;
            return result.substring(0, end);
        }
    }
}
