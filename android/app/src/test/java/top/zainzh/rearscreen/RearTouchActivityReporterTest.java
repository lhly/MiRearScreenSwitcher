package top.zainzh.rearscreen;

import android.view.Display;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class RearTouchActivityReporterTest {
    private static RearDisplayCastOccupancy.Snapshot presentedHost() {
        return new RearDisplayCastOccupancy.Snapshot(1, Display.STATE_ON, Display.STATE_ON, Display.STATE_ON,
                java.util.Arrays.asList(
                        new RearDisplayCastOccupancy.Task(42, "top.zainzh.rearscreen", "top.zainzh.rearscreen",
                                true, true, false),
                        new RearDisplayCastOccupancy.Task(8, "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter",
                                false, false, false)), "activity_task", null);
    }

    @Test public void acceptedRouterInteractionTargetsRearAndIsThrottledAfterFreshHostQuery() {
        List<String> calls = new ArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        RearTouchActivityReporter reporter = new RearTouchActivityReporter(
                (display, time, event) -> calls.add(display + ":" + time), (display, task, pkg) -> {
                    reads.incrementAndGet();
                    return RearTouchPresentationGate.inspect(presentedHost(), display, task, pkg, ignored -> true);
                });
        AtomicBoolean current = new AtomicBoolean(true), output = new AtomicBoolean(true);

        assertEquals(RearTouchActivityReporter.Result.REPORTED,
                reporter.acceptedInteraction(9000, 7, 1, 42, "top.zainzh.rearscreen", current::get, output::get));
        assertEquals(RearTouchActivityReporter.Result.THROTTLED,
                reporter.acceptedInteraction(9500, 7, 1, 42, "top.zainzh.rearscreen", current::get, output::get));
        assertEquals(RearTouchActivityReporter.Result.REPORTED,
                reporter.acceptedInteraction(10000, 7, 1, 42, "top.zainzh.rearscreen", current::get, output::get));
        assertEquals(java.util.Arrays.asList("1:9000", "1:10000"), calls);
        assertEquals("throttled MOVE bursts do not query task/power snapshots again", 2, reads.get());
    }

    @Test public void hiddenCoveredSleepingOrUnknownHostDoesNotExtendRearActivity() {
        List<Integer> calls = new ArrayList<>();
        AtomicBoolean current = new AtomicBoolean(true), output = new AtomicBoolean(true);
        RearTouchActivityReporter reporter = new RearTouchActivityReporter(
                (display, time, event) -> calls.add(display), (display, task, pkg) -> RearTouchPresentationGate.evaluate(
                        new RearDisplayCastOccupancy.Snapshot(display, Display.STATE_ON, Display.STATE_ON, Display.STATE_ON,
                                java.util.Arrays.asList(
                                        new RearDisplayCastOccupancy.Task(task, pkg, pkg, false, false, false),
                                        new RearDisplayCastOccupancy.Task(51, "com.example.other", "com.example.other",
                                                true, true, false)), "activity_task", null), display, task, pkg));
        assertEquals(RearTouchActivityReporter.Result.HOST_NOT_PRESENTED,
                reporter.acceptedInteraction(1000, 7, 1, 42, "top.zainzh.rearscreen", current::get, output::get));
        assertEquals("OTHER_TASK_VISIBLE", reporter.lastDecision());
        assertTrue(calls.isEmpty());
    }

    @Test public void closeOrPauseDuringFinalRearReadCancelsBeforePowerCall() throws Exception {
        assertFinalRearReadCancellation("close");
        assertFinalRearReadCancellation("pause");
        assertFinalRearReadCancellation("owner_death");
    }

    private static void assertFinalRearReadCancellation(String cancellation) throws Exception {
        List<Integer> calls = new ArrayList<>();
        AtomicBoolean current = new AtomicBoolean(true), output = new AtomicBoolean(true), ownerAlive = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        RearTouchActivityReporter reporter = new RearTouchActivityReporter(
                (display, time, event) -> calls.add(display),
                (display, task, pkg) -> RearTouchPresentationGate.inspect(presentedHost(), display, task, pkg,
                        ignored -> {
                            entered.countDown();
                            if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timeout");
                            return true;
                        }));
        AtomicInteger result = new AtomicInteger(-1);
        Thread touch = new Thread(() -> result.set(reporter.acceptedInteraction(1000, 7, 1, 42,
                "top.zainzh.rearscreen", () -> current.get() && ownerAlive.get(), output::get).ordinal()));
        touch.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        if ("close".equals(cancellation)) current.set(false);
        else if ("pause".equals(cancellation)) output.set(false);
        else ownerAlive.set(false);
        release.countDown();
        touch.join(1000);
        assertFalse(touch.isAlive());
        assertEquals(RearTouchActivityReporter.Result.SESSION_INACTIVE.ordinal(), result.get());
        assertTrue(calls.isEmpty());
    }

    @Test public void defaultPhysicalInputAndMissingActivityTaskSourceFailClosed() {
        RearTouchActivityReporter reporter = new RearTouchActivityReporter((display, time, event) -> fail("must not report"),
                (display, task, pkg) -> RearTouchPresentationGate.evaluate(
                        RearDisplayCastOccupancy.unavailable(display, "am_stack_list", "lossy"), display, task, pkg));
        assertEquals(RearTouchActivityReporter.Result.INPUT_NOT_VIRTUAL,
                reporter.acceptedInteraction(1000, 1, 1, 42, "top.zainzh.rearscreen", () -> true, () -> true));
        assertEquals(RearTouchActivityReporter.Result.HOST_NOT_PRESENTED,
                reporter.acceptedInteraction(2000, 7, 1, 42, "top.zainzh.rearscreen", () -> true, () -> true));
        assertEquals("SNAPSHOT_UNAVAILABLE", reporter.lastDecision());
    }
}
