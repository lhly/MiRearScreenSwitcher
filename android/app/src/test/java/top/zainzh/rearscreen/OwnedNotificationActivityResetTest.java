package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class OwnedNotificationActivityResetTest {
    @Test public void freshDisplayedTokenRenewsOnceWhenPreviousIdleDeadlineIsNear() {
        long previousActivity = 5_000L;
        long normalIdleTimeoutMs = 10_000L;
        long previousIdleDeadline = previousActivity + normalIdleTimeoutMs;
        long drawAt = 14_900L;
        long expiresAt = 25_000L;
        OwnedNotificationActivityReset reset = new OwnedNotificationActivityReset(819L, expiresAt);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicBoolean fakeDisplayAwake = new AtomicBoolean(true);

        OwnedNotificationActivityReset.Result result = reset.afterDraw(819L, current::get, true,
                4, 4, fakeDisplayAwake.get(), drawAt, drawAt,
                (display, session, deadline, eventUptime) -> display == 4 && session == 819L &&
                        deadline == expiresAt && eventUptime == drawAt && calls.incrementAndGet() == 1);

        assertEquals(OwnedNotificationActivityReset.Result.REPORTED, result);
        assertTrue(drawAt + normalIdleTimeoutMs > previousIdleDeadline);
        assertEquals(1, calls.get());
        assertTrue("refresh never turns the display on", fakeDisplayAwake.get());
        assertEquals(OwnedNotificationActivityReset.Result.DUPLICATE,
                reset.afterDraw(819L, current::get, true, 4, 4, true, drawAt + 1,
                        drawAt, (display, session, deadline, eventUptime) -> { calls.incrementAndGet(); return true; }));
        assertEquals("duplicate draw does not renew again", 1, calls.get());
    }

    @Test public void staleCancelledUndrawnOffDisplayAndExpiredPresentationsNeverRenew() {
        AtomicInteger calls = new AtomicInteger();
        OwnedNotificationActivityReset.Reporter reporter = (display, session, deadline, eventUptime) -> { calls.incrementAndGet(); return true; };
        AtomicBoolean current = new AtomicBoolean(true);

        OwnedNotificationActivityReset stale = new OwnedNotificationActivityReset(820, 10_000);
        assertEquals(OwnedNotificationActivityReset.Result.STALE_SESSION,
                stale.afterDraw(819, current::get, true, 4, 4, true, 1_000, 1_000, reporter));
        current.set(false); // exact owner/renderer was cancelled before the queued callback ran
        assertEquals(OwnedNotificationActivityReset.Result.STALE_SESSION,
                stale.afterDraw(820, current::get, true, 4, 4, true, 1_000, 1_000, reporter));

        OwnedNotificationActivityReset undrawn = new OwnedNotificationActivityReset(821, 10_000);
        current.set(true);
        assertEquals(OwnedNotificationActivityReset.Result.NOT_DRAWN,
                undrawn.afterDraw(821, current::get, false, 4, 4, true, 1_000, 1_000, reporter));
        OwnedNotificationActivityReset off = new OwnedNotificationActivityReset(822, 10_000);
        assertEquals(OwnedNotificationActivityReset.Result.DISPLAY_OFF,
                off.afterDraw(822, current::get, true, 4, 4, false, 1_000, 1_000, reporter));
        OwnedNotificationActivityReset wrongDisplay = new OwnedNotificationActivityReset(823, 10_000);
        assertEquals(OwnedNotificationActivityReset.Result.WRONG_DISPLAY,
                wrongDisplay.afterDraw(823, current::get, true, 4, 8, true, 1_000, 1_000, reporter));
        OwnedNotificationActivityReset expired = new OwnedNotificationActivityReset(824, 1_000);
        assertEquals(OwnedNotificationActivityReset.Result.EXPIRED,
                expired.afterDraw(824, current::get, true, 4, 4, true, 1_000, 1_000, reporter));
        assertEquals(0, calls.get());
    }

    @Test public void failedBackendIsConsumedWithoutWakeFallbackOrRetry() {
        OwnedNotificationActivityReset reset = new OwnedNotificationActivityReset(825, 10_000);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger wakeFallbacks = new AtomicInteger();
        AtomicBoolean current = new AtomicBoolean(true);
        OwnedNotificationActivityReset.Reporter unavailable = (display, session, deadline, eventUptime) -> {
            calls.incrementAndGet();
            return false;
        };

        assertEquals(OwnedNotificationActivityReset.Result.BACKEND_REJECTED,
                reset.afterDraw(825, current::get, true, 4, 4, true, 1_000, 950, unavailable));
        assertEquals(OwnedNotificationActivityReset.Result.DUPLICATE,
                reset.afterDraw(825, current::get, true, 4, 4, true, 1_001,
                        950, (display, session, deadline, eventUptime) -> {
                            calls.incrementAndGet();
                            assertEquals("original draw uptime, not worker time", 14_999, eventUptime);
                            return true;
                        }));
        assertEquals(1, calls.get());
        assertEquals(0, wakeFallbacks.get());
    }

    @Test public void restoredPresentationUsesOriginalMonotonicDeadline() {
        long expiresAt = 15_000L;
        OwnedNotificationActivityReset restored = new OwnedNotificationActivityReset(826, expiresAt);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(OwnedNotificationActivityReset.Result.REPORTED,
                restored.afterDraw(826, () -> true, true, 4, 4, true, 14_999,
                        14_900, (display, session, deadline, eventUptime) -> {
                            calls.incrementAndGet();
                            assertEquals(14_900, eventUptime);
                            return true;
                        }));
        OwnedNotificationActivityReset resumedAfterExpiry = new OwnedNotificationActivityReset(827, expiresAt);
        assertEquals(OwnedNotificationActivityReset.Result.EXPIRED,
                resumedAfterExpiry.afterDraw(827, () -> true, true, 4, 4, true, 15_000,
                        14_900, (display, session, deadline, eventUptime) -> { calls.incrementAndGet(); return true; }));
        assertEquals(1, calls.get());
    }
}
