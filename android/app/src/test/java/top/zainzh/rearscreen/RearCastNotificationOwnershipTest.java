package top.zainzh.rearscreen;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RearCastNotificationOwnershipTest {
    private static final class FakeTarget implements RearCastNotification.Target {
        final List<RearCastNotification.Message> shown = new ArrayList<>();
        int available;
        long cleared;
        @Override public void show(RearCastNotification.Message message) { shown.add(message); }
        @Override public void clearNotification(long token, String reason) { cleared = token; }
        @Override public void presentationAvailable() { available++; }
    }

    private FakeTarget target;
    private long now;

    @Before public void setUp() {
        now = 1_000;
        RearCastNotification.setClockForTesting(() -> now);
        target = new FakeTarget();
        RearCastNotification.attach(target);
    }

    @After public void tearDown() {
        RearCastNotification.detach(target);
        RearCastNotification.setClockForTesting(android.os.SystemClock::elapsedRealtime);
    }

    @Test public void sourceCoverRetiresCapsuleAndRouterAloneMayPublishItsFreshResume() {
        List<String> events = new ArrayList<>();
        long oldHandle = RearCastNotification.offerOwned("publisher", "owned", "body", now + 5_000, false,
                listener(events));
        assertTrue(oldHandle < 0);
        long sourceToken = RearCastNotification.captureNotificationToken();
        assertTrue(RearCastNotification.offer("source", "source", "body", now, 8, false));
        assertEquals(List.of("COVERED"), events);
        assertEquals(sourceToken + 1, RearCastNotification.captureNotificationToken());

        long coveredSource = RearCastNotification.captureNotificationToken();
        RearCastNotification.overlayEnded(coveredSource, "TIMEOUT");
        assertEquals(1, target.available);
        assertEquals(0, RearCastNotification.resumeOwnedNotification(oldHandle));

        long restored = RearCastNotification.offerOwned("publisher", "owned", "body", now + 4_000, false,
                listener(events));
        assertTrue("restoration must be a fresh router presentation", restored < 0 && restored != oldHandle);
    }

    @Test public void lowSuspendedCapsuleDoesNotBlockRouterSelectedHighPriorityCapsule() {
        List<String> lowEvents = new ArrayList<>();
        long low = RearCastNotification.offerOwned("low", "low", "", now + 5_000, false, listener(lowEvents));
        RearCastNotification.suspendOwnedNotification(-low);
        assertEquals(List.of("COVERED"), lowEvents);

        long high = RearCastNotification.offerOwned("high", "high", "", now + 5_000, false, listener(new ArrayList<>()));
        assertTrue("a retired low capsule cannot hold the active slot", high < 0 && high != low);
    }

    @Test public void chargeExpiryUnblocksOwnedAndStaleChargeStopCannotClearSource() {
        long charge = RearCastNotification.offerCharging("host", "Charging", "", now, false);
        assertTrue(charge > 0);
        assertTrue(target.shown.get(0).charging);
        RearCastNotification.overlayEnded(charge, "TIMEOUT");
        long owned = RearCastNotification.offerOwned("publisher", "owned", "", now + 5_000, false, listener(new ArrayList<>()));
        assertTrue("charge expiry clears only its own active slot", owned < 0);

        RearCastNotification.clearNotification(-owned, "REPLACED");
        long nextCharge = RearCastNotification.offerCharging("host", "Charging", "", now, false);
        assertTrue(nextCharge > charge);
        assertTrue(RearCastNotification.offer("source", "source", "", now, 8, false));
        long source = RearCastNotification.captureNotificationToken();
        RearCastNotification.clearCharging(nextCharge);
        assertEquals(source, RearCastNotification.captureNotificationToken());
        assertEquals(RearCastNotification.OWNED_DEFERRED,
                RearCastNotification.offerOwned("publisher", "owned", "", now + 5_000, false, listener(new ArrayList<>())));
    }

    @Test public void messageCallbacksNeverRunUnderTheMessageMonitor() {
        AtomicReference<RearCastNotification.Message> ref = new AtomicReference<>();
        List<String> events = new ArrayList<>();
        RearCastNotification.Message message = new RearCastNotification.Message("publisher", "owned", "", now + 5_000,
                false, new RearCastNotification.Listener() {
                    private void verifyOutsideLock(String event) {
                        assertFalse("listener callback must not retain the Message monitor", Thread.holdsLock(ref.get()));
                        events.add(event);
                    }
                    @Override public void onDisplayed() { verifyOutsideLock("DISPLAYED"); }
                    @Override public void onEnded(String reason) { verifyOutsideLock(reason); }
                });
        ref.set(message);
        message.display(); message.display();
        message.cover(); message.cover();
        message.end("CANCELLED"); message.end("CANCELLED");
        assertEquals(List.of("DISPLAYED", "COVERED", "CANCELLED"), events);
    }

    private static RearCastNotification.Listener listener(List<String> events) {
        return new RearCastNotification.Listener() {
            @Override public void onDisplayed() { events.add("DISPLAYED"); }
            @Override public void onEnded(String reason) { events.add(reason); }
        };
    }
}
