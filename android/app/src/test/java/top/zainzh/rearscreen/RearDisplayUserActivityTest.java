package top.zainzh.rearscreen;

import org.junit.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class RearDisplayUserActivityTest {
    public static final class DisplayTargetedPower {
        final List<String> calls = new ArrayList<>();
        public void userActivity(int displayId, long time, int event, int flags) {
            calls.add(displayId + ":" + time + ":" + event + ":" + flags);
        }
        public void userActivity(long time, int event, int flags) {
            calls.add("default:" + time + ":" + event + ":" + flags);
        }
    }
    public static final class LegacyDefaultOnlyPower {
        public void userActivity(long time, int event, int flags) {}
    }

    private static RearDisplayUserActivity.DisplaySnapshot snapshot(boolean on, String selected, String actual) {
        return new RearDisplayUserActivity.DisplaySnapshot(4, selected, actual, on);
    }

    private static RearDisplayUserActivity.SinkProvider sinkProvider(DisplayTargetedPower power) {
        return () -> (display, uptime, event) -> {
            RearDisplayUserActivity.invokeDisplayTargeted(power,
                    RearDisplayUserActivity.displayTargetedMethod(DisplayTargetedPower.class), display, uptime, event);
        };
    }

    private static RearDisplayUserActivity.Result run(DisplayTargetedPower power,
                                                       RearDisplayUserActivity.SnapshotProvider snapshot,
                                                       RearDisplayUserActivity.CurrentGate gate,
                                                       RearDisplayUserActivity.ElapsedClock clock,
                                                       int display, long session, long deadline, long drawUptime) throws Exception {
        return RearDisplayUserActivity.reportOwnedNotification(sinkProvider(power), snapshot, gate, clock,
                display, session, deadline, drawUptime);
    }

    @Test public void resolvesDisplayTargetedMethodAndPreservesTouchAsEventTwo() throws Exception {
        DisplayTargetedPower service = new DisplayTargetedPower();
        Method method = RearDisplayUserActivity.displayTargetedMethod(DisplayTargetedPower.class);
        RearDisplayUserActivity.invokeDisplayTargeted(service, method, 4, 3210);
        assertEquals(Collections.singletonList("4:3210:2:0"), service.calls);
    }

    @Test public void legacyDefaultDisplaySignatureIsNotAcceptedAsFallback() {
        try {
            RearDisplayUserActivity.displayTargetedMethod(LegacyDefaultOnlyPower.class);
            fail("default-display activity must not be used for a physical rear panel");
        } catch (NoSuchMethodException expected) { }
    }

    @Test public void invalidDisplayTimeAndEventAreRejectedBeforeInvocation() throws Exception {
        DisplayTargetedPower service = new DisplayTargetedPower();
        Method method = RearDisplayUserActivity.displayTargetedMethod(DisplayTargetedPower.class);
        try { RearDisplayUserActivity.invokeDisplayTargeted(service, method, 0, 3210); fail(); }
        catch (IllegalArgumentException expected) { }
        try { RearDisplayUserActivity.invokeDisplayTargeted(service, method, 4, -1); fail(); }
        catch (IllegalArgumentException expected) { }
        try { RearDisplayUserActivity.invokeDisplayTargeted(service, method, 4, 3210, 1); fail(); }
        catch (IllegalArgumentException expected) { }
        assertTrue(service.calls.isEmpty());
    }

    @Test public void ownedDrawReportsAospOtherZeroWithOriginalDrawUptime() throws Exception {
        DisplayTargetedPower service = new DisplayTargetedPower();
        assertEquals(RearDisplayUserActivity.Result.REPORTED,
                run(service, display -> snapshot(true, "local:rear-panel", "local:rear-panel"),
                        () -> true, () -> 20_000, 4, 819, 25_000, 14_900));
        // Literal 0 is intentionally independent of EVENT_OTHER so the test catches enum drift.
        assertEquals(Collections.singletonList("4:14900:0:0"), service.calls);
        assertEquals("AOSP PowerManager.USER_ACTIVITY_EVENT_OTHER", 0, RearDisplayUserActivity.EVENT_OTHER);
    }

    @Test public void preparedBackendReplacementDuringSlowSetupNeverReachesPowerSink() throws Exception {
        DisplayTargetedPower service = new DisplayTargetedPower();
        CountDownLatch prepareEntered = new CountDownLatch(1), releasePrepare = new CountDownLatch(1);
        AtomicLong currentSession = new AtomicLong(820);
        AtomicInteger result = new AtomicInteger(-1);
        Thread request = new Thread(() -> {
            try {
                RearDisplayUserActivity.Result value = RearDisplayUserActivity.reportOwnedNotification(() -> {
                    prepareEntered.countDown();
                    if (!releasePrepare.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("setup timeout");
                    return sinkProvider(service).prepare();
                }, display -> snapshot(true, "local:rear-panel", "local:rear-panel"),
                        () -> currentSession.get() == 820,
                        () -> 12_000, 4, 820, 15_000, 11_500);
                result.set(value.ordinal());
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        request.start();
        assertTrue(prepareEntered.await(1, TimeUnit.SECONDS));
        currentSession.set(821); // a replacement generation commits while backend preparation is blocked
        releasePrepare.countDown();
        request.join(1000);
        assertFalse(request.isAlive());
        assertEquals(RearDisplayUserActivity.Result.STALE_SESSION.ordinal(), result.get());
        assertTrue("stale token never reaches userActivity", service.calls.isEmpty());
    }

    @Test public void slowDisplayQueryRechecksDeadlineAndNeverSubstitutesWorkerTimeForDrawTime() throws Exception {
        DisplayTargetedPower service = new DisplayTargetedPower();
        CountDownLatch queryEntered = new CountDownLatch(1), releaseQuery = new CountDownLatch(1);
        AtomicLong elapsed = new AtomicLong(14_999);
        AtomicInteger result = new AtomicInteger(-1);
        Thread request = new Thread(() -> {
            try {
                RearDisplayUserActivity.Result value = run(service, display -> {
                    queryEntered.countDown();
                    if (!releaseQuery.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("query timeout");
                    elapsed.set(15_000);
                    return snapshot(true, "local:rear-panel", "local:rear-panel");
                }, () -> true, elapsed::get, 4, 821, 15_000, 10_000);
                result.set(value.ordinal());
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        request.start();
        assertTrue(queryEntered.await(1, TimeUnit.SECONDS));
        releaseQuery.countDown();
        request.join(1000);
        assertFalse(request.isAlive());
        assertEquals(RearDisplayUserActivity.Result.EXPIRED.ordinal(), result.get());
        assertTrue(service.calls.isEmpty());
    }

    @Test public void rearPowerAndPhysicalIdentityAreRecheckedAfterSlowQuery() throws Exception {
        DisplayTargetedPower service = new DisplayTargetedPower();
        assertEquals(RearDisplayUserActivity.Result.DISPLAY_OFF,
                runAfterBlockedQuery(service, snapshot(false, "local:rear-panel", "local:rear-panel"), 822));
        assertEquals(RearDisplayUserActivity.Result.WRONG_REAR,
                runAfterBlockedQuery(service, new RearDisplayUserActivity.DisplaySnapshot(
                        8, "external:other", "external:other", true), 823));
        assertTrue("screen-off or a changed target must not refresh a new display", service.calls.isEmpty());
    }

    private static RearDisplayUserActivity.Result runAfterBlockedQuery(DisplayTargetedPower power,
                                                                       RearDisplayUserActivity.DisplaySnapshot after,
                                                                       long session) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<RearDisplayUserActivity.DisplaySnapshot> current = new AtomicReference<>(
                snapshot(true, "local:rear-panel", "local:rear-panel"));
        AtomicInteger result = new AtomicInteger(-1);
        Thread request = new Thread(() -> {
            try {
                RearDisplayUserActivity.Result value = run(power, display -> {
                    entered.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("query timeout");
                    return current.get();
                }, () -> true, () -> 1_000, 4, session, 10_000, 900);
                result.set(value.ordinal());
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        request.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        current.set(after); // display power or selected physical rear changes while the query is blocked
        release.countDown();
        request.join(1000);
        assertFalse(request.isAlive());
        return RearDisplayUserActivity.Result.values()[result.get()];
    }
}
