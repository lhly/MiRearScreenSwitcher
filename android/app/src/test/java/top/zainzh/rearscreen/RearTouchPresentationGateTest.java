package top.zainzh.rearscreen;

import android.view.Display;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class RearTouchPresentationGateTest {
    private static final String HOST = "top.zainzh.rearscreen";
    private static RearDisplayCastOccupancy.Task task(int id, String base, String top,
                                                       Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(id, base, top, visible, requested, sleeping);
    }
    private static RearDisplayCastOccupancy.Snapshot snapshot(RearDisplayCastOccupancy.Task... tasks) {
        return new RearDisplayCastOccupancy.Snapshot(1, Display.STATE_ON, Display.STATE_ON, Display.STATE_ON,
                Arrays.asList(tasks), "activity_task", null);
    }

    @Test public void onlyExactVisibleHostOnStableOnRearIsEligible() {
        assertEquals(RearTouchPresentationGate.Decision.ALLOW, RearTouchPresentationGate.evaluate(
                snapshot(task(42, HOST, HOST, true, true, false),
                        task(5, "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter", false, false, false)),
                1, 42, HOST));
        assertEquals(RearTouchPresentationGate.Decision.HOST_NOT_VISIBLE, RearTouchPresentationGate.evaluate(
                snapshot(task(42, HOST, HOST, false, false, false)), 1, 42, HOST));
        assertEquals(RearTouchPresentationGate.Decision.HOST_NOT_VISIBLE, RearTouchPresentationGate.evaluate(
                snapshot(task(42, HOST, HOST, true, true, true)), 1, 42, HOST));
        assertEquals(RearTouchPresentationGate.Decision.HOST_IDENTITY_MISMATCH, RearTouchPresentationGate.evaluate(
                snapshot(task(42, HOST, "com.example.cover", true, true, false)), 1, 42, HOST));
    }

    @Test public void visibleOrUncertainOtherTaskAndPowerTransitionsFailClosed() {
        assertEquals(RearTouchPresentationGate.Decision.OTHER_TASK_VISIBLE, RearTouchPresentationGate.evaluate(
                snapshot(task(42, HOST, HOST, true, true, false),
                        task(51, "com.example.cover", "com.example.cover", true, true, false)), 1, 42, HOST));
        assertEquals(RearTouchPresentationGate.Decision.OTHER_TASK_VISIBLE, RearTouchPresentationGate.evaluate(
                snapshot(task(42, HOST, HOST, true, true, false),
                        task(51, null, null, null, null, null)), 1, 42, HOST));
        assertEquals(RearTouchPresentationGate.Decision.DISPLAY_NOT_ON, RearTouchPresentationGate.evaluate(
                new RearDisplayCastOccupancy.Snapshot(1, Display.STATE_ON, Display.STATE_ON, Display.STATE_DOZE,
                        Arrays.asList(task(42, HOST, HOST, true, true, false)), "activity_task", null), 1, 42, HOST));
        assertEquals(RearTouchPresentationGate.Decision.HOST_IDENTITY_MISMATCH, RearTouchPresentationGate.evaluate(
                snapshot(task(42, HOST, HOST, true, true, false)), 1, 42, HOST + ".debug"));
    }

    @Test public void finalRearReadRunsOnlyAfterTypedHostProof() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        RearDisplayCastOccupancy.Snapshot valid = snapshot(task(42, HOST, HOST, true, true, false));
        assertEquals(RearTouchPresentationGate.Decision.ALLOW,
                RearTouchPresentationGate.inspect(valid, 1, 42, HOST, display -> {
                    reads.incrementAndGet(); return true;
                }));
        assertEquals(1, reads.get());
        assertEquals(RearTouchPresentationGate.Decision.DISPLAY_NOT_ON,
                RearTouchPresentationGate.inspect(valid, 1, 42, HOST, display -> false));
        assertEquals(RearTouchPresentationGate.Decision.OTHER_TASK_VISIBLE,
                RearTouchPresentationGate.inspect(snapshot(task(42, HOST, HOST, true, true, false),
                        task(51, "com.example.other", "com.example.other", true, true, false)), 1, 42, HOST,
                        display -> { fail("untrusted snapshot must not perform a final display query"); return true; }));
    }
}
