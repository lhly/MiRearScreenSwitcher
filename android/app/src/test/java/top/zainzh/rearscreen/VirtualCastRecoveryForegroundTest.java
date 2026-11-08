package top.zainzh.rearscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Display;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class VirtualCastRecoveryForegroundTest {
    private static final int DISPLAY = 7;
    private static final int HOST_TASK = 42;
    private static final String HOST = BuildConfig.APPLICATION_ID;
    private static final String HOME = "com.xiaomi.subscreencenter";
    private static final String THIRD_PARTY = "com.example.other";

    @Test public void hiddenOwnedHostBeforeVisibleLauncherFocusesOnlyExactOwnedTaskOnce() throws Exception {
        RearDisplayCastOccupancy.Snapshot snapshot = snapshot("activity_task",
                task(HOST_TASK, HOST, HOST, false, false, false),
                task(51, HOME, HOME, true, true, false));
        VirtualCastRecoveryForeground.Decision decision = resolve(snapshot);

        assertEquals(VirtualCastRecoveryForeground.Disposition.FOCUS_HOST, decision.disposition);
        AtomicInteger focuses = new AtomicInteger();
        String result = VirtualCastRecoveryForeground.perform(decision, HOST_TASK, DISPLAY, taskId -> {
            assertEquals(HOST_TASK, taskId);
            focuses.incrementAndGet();
            return true;
        });
        assertEquals("focused", result);
        assertEquals(1, focuses.get());
    }

    @Test public void exactOwnedPendingOrSleepingTargetCanBeFocusedBehindUniqueVisibleLauncher() throws Exception {
        for (RearDisplayCastOccupancy.Task target : Arrays.asList(
                task(HOST_TASK, HOST, HOST, false, true, false),
                task(HOST_TASK, HOST, HOST, false, false, true))) {
            RearDisplayCastOccupancy.Snapshot snapshot = snapshot("activity_task", target,
                    task(51, HOME, HOME, true, true, false));
            VirtualCastRecoveryForeground.Decision decision = resolve(snapshot);
            AtomicInteger focuses = new AtomicInteger();

            assertEquals(VirtualCastRecoveryForeground.Disposition.FOCUS_HOST, decision.disposition);
            assertEquals("focused", VirtualCastRecoveryForeground.perform(decision, HOST_TASK, DISPLAY, taskId -> {
                assertEquals(HOST_TASK, taskId);
                focuses.incrementAndGet();
                return true;
            }));
            assertEquals("exact pending/sleeping target should receive one guarded focus", 1, focuses.get());
            assertEquals("host", decision.diagnostic.targetBase);
            assertEquals("host", decision.diagnostic.targetTop);
            assertEquals(Boolean.TRUE.equals(target.requested) ? "true" : "false", decision.diagnostic.targetRequested);
            assertEquals(Boolean.TRUE.equals(target.sleeping) ? "true" : "false", decision.diagnostic.targetSleeping);
            String summary = decision.diagnostic.format(decision.refusal, "focused", Display.STATE_ON);
            assertTrue(summary.contains(Boolean.TRUE.equals(target.sleeping)
                    ? "refusal=visible_launcher_target_sleeping effect=focused"
                    : "refusal=visible_launcher_target_pending effect=focused"));
            assertFalse(summary.contains(HOME));
            assertFalse(summary.contains("{\""));
        }
    }

    @Test public void recoverySummaryIsRedactedAndUsesExistingSnapshotOnly() throws Exception {
        RearDisplayCastOccupancy.Snapshot snapshot = snapshot("activity_task",
                task(HOST_TASK, HOST, HOST, false, true, false),
                task(63, THIRD_PARTY, THIRD_PARTY, false, true, false),
                task(51, HOME, HOME, true, true, false));
        VirtualCastRecoveryForeground.Decision decision = resolve(snapshot);
        AtomicInteger focuses = new AtomicInteger();

        assertEquals(VirtualCastRecoveryForeground.Disposition.EVIDENCE_UNAVAILABLE, decision.disposition);
        String effect = VirtualCastRecoveryForeground.perform(decision, HOST_TASK, DISPLAY,
                taskId -> { focuses.incrementAndGet(); return true; });
        assertEquals("foreground_unverified", effect);
        assertEquals(0, focuses.get());
        String summary = decision.diagnostic.format(decision.refusal, effect, Display.STATE_ON);
        assertTrue(summary.contains("source=activity_task availability=ok"));
        assertTrue(summary.contains("display=7 target_task=42"));
        assertTrue(summary.contains("power_before=2 power_after=2"));
        assertTrue(summary.contains("target_present=true target_base=host target_top=host"));
        assertTrue(summary.contains("target_visible=false target_requested=true target_sleeping=false"));
        assertTrue(summary.contains("pending=1"));
        assertFalse(summary.contains(THIRD_PARTY));
        assertFalse(summary.contains(HOME));
        assertFalse(summary.contains("{\""));
        assertFalse(summary.contains("tasks"));
    }

    @Test public void recoverySummarySeparatesSleepingLauncherOtherAndUnknownWithoutNames() throws Exception {
        RearDisplayCastOccupancy.Snapshot snapshot = snapshot("activity_task",
                task(HOST_TASK, HOST, HOST, false, false, true),
                task(61, HOME, HOME, false, false, true),
                task(62, THIRD_PARTY, THIRD_PARTY, false, false, true),
                task(63, THIRD_PARTY, null, false, false, true));

        VirtualCastRecoveryForeground.Decision decision = resolve(snapshot);
        String summary = decision.diagnostic.format(decision.refusal, "foreground_unverified", Display.STATE_ON);

        assertEquals(VirtualCastRecoveryForeground.Disposition.EVIDENCE_UNAVAILABLE, decision.disposition);
        assertTrue(summary.contains("sleeping=3 sleeping_launcher=1 sleeping_other=1 sleeping_unknown=1"));
        assertFalse(summary.contains(HOME));
        assertFalse(summary.contains(THIRD_PARTY));
        assertFalse(summary.contains("tasks"));
    }

    @Test public void hiddenThirdPartyDoesNotObscureVisibleLauncherButVisibleThirdPartyDoes() throws Exception {
        RearDisplayCastOccupancy.Snapshot hidden = snapshot("activity_task",
                task(61, THIRD_PARTY, THIRD_PARTY, false, false, false),
                task(51, HOME, HOME, true, false, false),
                task(HOST_TASK, HOST, HOST, false, false, false));
        VirtualCastRecoveryForeground.Decision hiddenDecision = resolve(hidden);
        assertEquals(VirtualCastRecoveryForeground.Disposition.FOCUS_HOST, hiddenDecision.disposition);

        AtomicInteger focuses = new AtomicInteger();
        assertEquals("focused", VirtualCastRecoveryForeground.perform(hiddenDecision, HOST_TASK, DISPLAY,
                taskId -> { focuses.incrementAndGet(); return true; }));
        assertEquals(1, focuses.get());

        RearDisplayCastOccupancy.Snapshot visible = snapshot("activity_task",
                task(61, THIRD_PARTY, THIRD_PARTY, true, true, false),
                task(51, HOME, HOME, true, false, false),
                task(HOST_TASK, HOST, HOST, false, false, false));
        VirtualCastRecoveryForeground.Decision visibleDecision = resolve(visible);
        assertEquals(VirtualCastRecoveryForeground.Disposition.OTHER_TASK, visibleDecision.disposition);
        assertEquals("preserved_other_task", VirtualCastRecoveryForeground.perform(
                visibleDecision, HOST_TASK, DISPLAY, taskId -> { focuses.incrementAndGet(); return true; }));
        assertEquals(1, focuses.get());
    }

    @Test public void hiddenFocusTargetMustStillHaveExactHostTopPackage() throws Exception {
        for (RearDisplayCastOccupancy.Task hiddenTarget : Arrays.asList(
                task(HOST_TASK, HOST, THIRD_PARTY, false, false, false),
                task(HOST_TASK, HOST, null, false, false, false))) {
            RearDisplayCastOccupancy.Snapshot snapshot = snapshot("activity_task", hiddenTarget,
                    task(51, HOME, HOME, true, true, false));
            VirtualCastRecoveryForeground.Decision decision = resolve(snapshot);
            AtomicInteger focuses = new AtomicInteger();

            if (hiddenTarget.topPackage == null) {
                assertEquals(VirtualCastRecoveryForeground.Disposition.EVIDENCE_UNAVAILABLE,
                        decision.disposition);
                assertEquals("foreground_unverified", VirtualCastRecoveryForeground.perform(
                        decision, HOST_TASK, DISPLAY, taskId -> { focuses.incrementAndGet(); return true; }));
            } else {
                assertEquals(VirtualCastRecoveryForeground.Disposition.OTHER_TASK, decision.disposition);
                assertEquals("other", decision.diagnostic.targetTop);
                assertEquals("preserved_other_task", VirtualCastRecoveryForeground.perform(
                        decision, HOST_TASK, DISPLAY, taskId -> { focuses.incrementAndGet(); return true; }));
            }
            assertEquals("a hidden non-host top must never be focused", 0, focuses.get());
        }
    }

    @Test public void ownVisibleTopIsAlreadyFrontAndDoesNotIssueFocus() throws Exception {
        RearDisplayCastOccupancy.Snapshot snapshot = snapshot("activity_task",
                task(HOST_TASK, HOST, HOST, true, true, false),
                task(51, HOME, HOME, false, false, false));
        VirtualCastRecoveryForeground.Decision decision = resolve(snapshot);
        AtomicInteger focuses = new AtomicInteger();
        assertEquals("already_front", VirtualCastRecoveryForeground.perform(
                decision, HOST_TASK, DISPLAY, taskId -> { focuses.incrementAndGet(); return true; }));
        assertEquals(0, focuses.get());

        RearDisplayCastOccupancy.Snapshot ambiguous = snapshot("activity_task",
                task(HOST_TASK, HOST, HOST, true, true, false),
                task(51, HOME, HOME, true, true, false));
        assertEquals(VirtualCastRecoveryForeground.Disposition.EVIDENCE_UNAVAILABLE,
                resolve(ambiguous).disposition);
        RearDisplayCastOccupancy.Snapshot ambiguousReverseOrder = snapshot("activity_task",
                task(51, HOME, HOME, true, true, false),
                task(HOST_TASK, HOST, HOST, true, true, false));
        assertEquals(VirtualCastRecoveryForeground.Disposition.EVIDENCE_UNAVAILABLE,
                resolve(ambiguousReverseOrder).disposition);
    }

    @Test public void pendingSleepingAndUnknownRowsFailClosed() throws Exception {
        for (RearDisplayCastOccupancy.Task uncertain : Arrays.asList(
                task(61, THIRD_PARTY, THIRD_PARTY, false, true, false),
                task(61, THIRD_PARTY, THIRD_PARTY, false, false, true),
                task(61, THIRD_PARTY, THIRD_PARTY, null, false, false))) {
            RearDisplayCastOccupancy.Snapshot snapshot = snapshot("activity_task", uncertain,
                    task(51, HOME, HOME, true, false, false),
                    task(HOST_TASK, HOST, HOST, false, false, false));
            VirtualCastRecoveryForeground.Decision decision = resolve(snapshot);
            AtomicInteger focuses = new AtomicInteger();
            assertEquals(VirtualCastRecoveryForeground.Disposition.EVIDENCE_UNAVAILABLE, decision.disposition);
            assertEquals("foreground_unverified", VirtualCastRecoveryForeground.perform(
                    decision, HOST_TASK, DISPLAY, taskId -> { focuses.incrementAndGet(); return true; }));
            assertEquals(0, focuses.get());
        }
    }

    @Test public void compatibilitySnapshotUnknownDisplayAndOwnerMismatchNeverFocus() throws Exception {
        RearDisplayCastOccupancy.Snapshot fallback = snapshot("am_stack_list",
                task(HOST_TASK, HOST, HOST, true, null, null), task(51, HOME, HOME, true, null, null));
        RearDisplayCastOccupancy.Snapshot off = new RearDisplayCastOccupancy.Snapshot(
                DISPLAY, Display.STATE_OFF,
                Collections.singletonList(task(HOST_TASK, HOST, HOST, false, false, true)), "activity_task", null);
        RearDisplayCastOccupancy.Snapshot wrongDisplay = snapshot("activity_task",
                task(HOST_TASK, HOST, HOST, false, false, false));
        RearDisplayCastOccupancy.Snapshot wrongOwner = snapshot("activity_task",
                task(HOST_TASK, THIRD_PARTY, THIRD_PARTY, false, false, false));
        for (VirtualCastRecoveryForeground.Decision decision : Arrays.asList(
                resolve(fallback), resolve(off),
                VirtualCastRecoveryForeground.resolve(wrongDisplay, DISPLAY + 1, HOST_TASK, HOST),
                resolve(wrongOwner))) {
            AtomicInteger focuses = new AtomicInteger();
            assertEquals(VirtualCastRecoveryForeground.Disposition.EVIDENCE_UNAVAILABLE, decision.disposition);
            assertFalse("focus result must remain unavailable", "focused".equals(
                    VirtualCastRecoveryForeground.perform(decision, HOST_TASK, DISPLAY,
                            taskId -> { focuses.incrementAndGet(); return true; })));
            assertEquals(0, focuses.get());
        }
    }

    @Test public void decisionCannotBeReplayedForDifferentTaskOrDisplay() throws Exception {
        VirtualCastRecoveryForeground.Decision decision = resolve(snapshot("activity_task",
                task(HOST_TASK, HOST, HOST, false, false, false),
                task(51, HOME, HOME, true, false, false)));
        AtomicInteger focuses = new AtomicInteger();
        assertEquals("stale_owner", VirtualCastRecoveryForeground.perform(
                decision, HOST_TASK + 1, DISPLAY, taskId -> { focuses.incrementAndGet(); return true; }));
        assertEquals("stale_owner", VirtualCastRecoveryForeground.perform(
                decision, HOST_TASK, DISPLAY + 1, taskId -> { focuses.incrementAndGet(); return true; }));
        assertEquals(0, focuses.get());
    }

    @Test public void sleepingRearWakeAllowsOnlyExactHostAndKnownLauncher() throws Exception {
        RearDisplayCastOccupancy.Snapshot safe = new RearDisplayCastOccupancy.Snapshot(DISPLAY,
                Display.STATE_DOZE_SUSPEND, Arrays.asList(
                task(HOST_TASK, HOST, HOST, false, false, true),
                task(51, HOME, HOME, false, false, true)), "activity_task", null);
        VirtualCastRecoveryForeground.Decision allowed = VirtualCastRecoveryForeground.resolveWake(
                safe, DISPLAY, HOST_TASK, HOST);
        AtomicInteger focus = new AtomicInteger();
        assertEquals(VirtualCastRecoveryForeground.Disposition.WAKE_ALLOWED, allowed.disposition);
        assertEquals("wake_allowed", VirtualCastRecoveryForeground.perform(allowed, HOST_TASK, DISPLAY,
                taskId -> { focus.incrementAndGet(); return true; }));
        assertEquals(0, focus.get());

        RearDisplayCastOccupancy.Snapshot blocked = new RearDisplayCastOccupancy.Snapshot(DISPLAY,
                Display.STATE_DOZE_SUSPEND, Arrays.asList(
                task(HOST_TASK, HOST, HOST, false, false, true),
                task(61, THIRD_PARTY, THIRD_PARTY, false, false, true),
                task(51, HOME, HOME, false, false, true)), "activity_task", null);
        assertEquals(VirtualCastRecoveryForeground.Disposition.OTHER_TASK,
                VirtualCastRecoveryForeground.resolveWake(blocked, DISPLAY, HOST_TASK, HOST).disposition);
    }

    private static VirtualCastRecoveryForeground.Decision resolve(RearDisplayCastOccupancy.Snapshot snapshot) {
        return VirtualCastRecoveryForeground.resolve(snapshot, DISPLAY, HOST_TASK, HOST);
    }

    private static RearDisplayCastOccupancy.Snapshot snapshot(String source,
                                                               RearDisplayCastOccupancy.Task... tasks) {
        return new RearDisplayCastOccupancy.Snapshot(DISPLAY, Display.STATE_ON,
                Arrays.asList(tasks), source, null);
    }

    private static RearDisplayCastOccupancy.Task task(int taskId, String base, String top,
                                                       Boolean visible, Boolean requested, Boolean sleeping) {
        return new RearDisplayCastOccupancy.Task(taskId, base, top, visible, requested, sleeping);
    }
}
