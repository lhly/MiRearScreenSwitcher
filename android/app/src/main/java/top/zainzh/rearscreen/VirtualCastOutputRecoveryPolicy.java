package top.zainzh.rearscreen;

import android.view.Display;

/** Power-aware policy for a live virtual-cast owner whose host Surface temporarily disappears. */
final class VirtualCastOutputRecoveryPolicy {
    private VirtualCastOutputRecoveryPolicy() {}
    static final long RECONNECT_TIMEOUT_MILLIS = 2300L;

    interface RearResumeEffects {
        void reattachOutputSurface();
        void presentOwnedHost();
        void waitForReplacementSurface();
    }

    /**
     * Deduplicates one missing-Surface presentation per real loss edge. A queued command is
     * only in-flight; it consumes the once allowance after a result returns. New loss/close
     * edges invalidate old command gates, while repeated ON notifications do not.
     */
    static final class MissingSurfacePresentationGate {
        private static final int WAIT_INITIAL = 0;
        private static final int WAIT_ARMED = 1;
        private static final int WAIT_FOR_RESUME_EDGE = 2;
        private static final int WAIT_TERMINAL = 3;
        private int lossEpoch;
        private int nextAttempt;
        private int activeAttempt;
        private boolean attemptResolved;
        private int waitPhase = WAIT_INITIAL;
        private int nextDeadline;
        private int activeDeadline;

        synchronized int beginLoss() {
            lossEpoch++;
            activeAttempt = 0;
            attemptResolved = false;
            waitPhase = WAIT_INITIAL;
            activeDeadline = 0;
            return lossEpoch;
        }

        synchronized int beginAttempt(int expectedLossEpoch) {
            if (expectedLossEpoch != lossEpoch || attemptResolved || activeAttempt != 0) return 0;
            activeAttempt = ++nextAttempt;
            return activeAttempt;
        }

        synchronized boolean isCurrent(int expectedLossEpoch, int expectedAttempt,
                                       boolean activeSession, boolean ending, boolean sameInstance,
                                       boolean sameSession, boolean reconnecting, boolean rearOn) {
            return activeSession && !ending && sameInstance && sameSession && reconnecting && rearOn
                    && expectedLossEpoch == lossEpoch && expectedAttempt != 0
                    && expectedAttempt == activeAttempt && !attemptResolved;
        }

        synchronized boolean complete(int expectedLossEpoch, int expectedAttempt) {
            if (expectedLossEpoch != lossEpoch || expectedAttempt == 0 || expectedAttempt != activeAttempt
                    || attemptResolved) return false;
            activeAttempt = 0;
            attemptResolved = true;
            return true;
        }

        synchronized int beginDeadline(int expectedLossEpoch, boolean actualResumeEdge) {
            if (expectedLossEpoch != lossEpoch || waitPhase == WAIT_ARMED || waitPhase == WAIT_TERMINAL)
                return 0;
            if (waitPhase == WAIT_FOR_RESUME_EDGE && !actualResumeEdge) return 0;
            waitPhase = WAIT_ARMED;
            activeDeadline = ++nextDeadline;
            return activeDeadline;
        }

        synchronized boolean isCurrentDeadline(int expectedLossEpoch, int expectedDeadline) {
            return expectedLossEpoch == lossEpoch && expectedDeadline != 0
                    && expectedDeadline == activeDeadline && waitPhase == WAIT_ARMED;
        }

        synchronized boolean finishDeadline(int expectedLossEpoch, int expectedDeadline,
                                             boolean stillWaitingForPowerResume) {
            if (!isCurrentDeadline(expectedLossEpoch, expectedDeadline)) return false;
            activeDeadline = 0;
            waitPhase = stillWaitingForPowerResume ? WAIT_FOR_RESUME_EDGE : WAIT_TERMINAL;
            return true;
        }

        synchronized void invalidate() {
            lossEpoch++;
            activeAttempt = 0;
            attemptResolved = false;
            waitPhase = WAIT_TERMINAL;
            activeDeadline = 0;
        }

        synchronized int currentLossEpoch() { return lossEpoch; }
    }

    interface ReconnectDeadlineEffect {
        void schedule(int lossEpoch, int deadlineId, long timeoutMillis);
    }

    /** Schedule once for a loss; only a real later resume edge can re-arm after a sleep wait. */
    static int scheduleReconnectDeadline(MissingSurfacePresentationGate gate, int lossEpoch,
                                         boolean actualResumeEdge, ReconnectDeadlineEffect effect) {
        if (gate == null || effect == null) return 0;
        int deadlineId = gate.beginDeadline(lossEpoch, actualResumeEdge);
        if (deadlineId != 0) effect.schedule(lossEpoch, deadlineId, RECONNECT_TIMEOUT_MILLIS);
        return deadlineId;
    }

    static final class PrimaryScreenOffDecision {
        final int lossEpoch;
        final boolean scheduleWake;
        final boolean newPrimaryCycle;
        PrimaryScreenOffDecision(int lossEpoch, boolean scheduleWake, boolean newPrimaryCycle) {
            this.lossEpoch = lossEpoch;
            this.scheduleWake = scheduleWake;
            this.newPrimaryCycle = newPrimaryCycle;
        }
    }

    interface PrimaryScreenOffEffects {
        void scheduleWake();
        void reconcileAlreadyOnRear();
    }

    /** Every primary OFF invalidates presentation; wake remains one-shot per locked cycle. */
    static PrimaryScreenOffDecision onPrimaryScreenOff(MissingSurfacePresentationGate gate,
                                                       boolean wakeAlreadyRequested,
                                                       boolean wasPrimaryInteractive) {
        return new PrimaryScreenOffDecision(gate.beginLoss(),
                shouldScheduleWakeForPowerCycle(wakeAlreadyRequested), wasPrimaryInteractive);
    }

    /** When the one wake allowance is already spent, do not strand an ON rear without reconcile. */
    static void dispatchPrimaryScreenOff(PrimaryScreenOffDecision decision, boolean rearIsOn,
                                        PrimaryScreenOffEffects effects) {
        if (decision == null || effects == null) return;
        if (decision.scheduleWake) effects.scheduleWake();
        else if (rearIsOn) effects.reconcileAlreadyOnRear();
    }

    /** Route only a live reconnect to an ON rear; presentation is an action, not a wake side effect. */
    static void dispatchRearResume(boolean activeSession, boolean ending, boolean reconnecting,
                                   boolean powerSuspendedOutput, boolean surfaceValid, int rearState,
                                   RearResumeEffects effects) {
        if (!activeSession || ending || !reconnecting || rearState != Display.STATE_ON || effects == null) return;
        if (powerSuspendedOutput && surfaceValid) {
            effects.reattachOutputSurface();
            return;
        }
        if (!surfaceValid) effects.waitForReplacementSurface();
        effects.presentOwnedHost();
    }

    /** A wake command's already_on result still needs the ordinary ON/Surface reconciliation. */
    static boolean shouldReconcileAlreadyOnWake(String result, boolean activeSession, boolean ending,
                                                boolean sameSession, boolean reconnecting) {
        return "already_on".equals(result) && activeSession && !ending && sameSession && reconnecting;
    }

    enum SurfaceUpdate {
        SESSION_CLOSED,
        VIEWPORT_INTERRUPTED,
        LOST_TEMPORARILY,
        ADOPT_REPLACEMENT,
        INVALID_REPLACEMENT
    }

    static SurfaceUpdate surfaceUpdate(boolean sessionClosed, boolean viewportChangePending,
                                       boolean replacementPresent, boolean replacementValid) {
        if (sessionClosed) return SurfaceUpdate.SESSION_CLOSED;
        if (viewportChangePending) return SurfaceUpdate.VIEWPORT_INTERRUPTED;
        if (!replacementPresent) return SurfaceUpdate.LOST_TEMPORARILY;
        return replacementValid ? SurfaceUpdate.ADOPT_REPLACEMENT : SurfaceUpdate.INVALID_REPLACEMENT;
    }

    static boolean waitForDisplayResume(boolean mainLocked, boolean mainInteractive, int rearDisplayState) {
        boolean rearSleeping = rearDisplayState == Display.STATE_OFF
                || rearDisplayState == Display.STATE_DOZE
                || rearDisplayState == Display.STATE_DOZE_SUSPEND
                || rearDisplayState == Display.STATE_ON_SUSPEND;
        return mainLocked || !mainInteractive || rearSleeping;
    }

    static boolean failHostReconnectTimeout(boolean mainLocked, boolean mainInteractive, int rearDisplayState) {
        return !waitForDisplayResume(mainLocked, mainInteractive, rearDisplayState);
    }

    static boolean shouldScheduleWakeForPowerCycle(boolean alreadyRequested) {
        return !alreadyRequested;
    }

    static boolean mayFocusExistingHost(boolean hostStillOwned, String foreground, int hostTask, String hostPackage) {
        if (!hostStillOwned) return false;
        if (foreground == null || foreground.isEmpty()) return false;
        if (foreground.equals(hostPackage + ":" + hostTask)) return false;
        int separator = foreground.lastIndexOf(':');
        String packageName = separator > 0 ? foreground.substring(0, separator) : foreground;
        return "com.xiaomi.subscreencenter".equals(packageName) || "com.xiaomi.mirror".equals(packageName);
    }

    static boolean isRearDisplayResumeEdge(boolean activeSession, boolean ending, int previousState, int nextState) {
        return activeSession && !ending && nextState == Display.STATE_ON && previousState != Display.STATE_ON;
    }

    static boolean isRearDisplaySuspendEdge(boolean activeSession, boolean ending, int previousState, int nextState) {
        return activeSession && !ending && previousState != nextState && VirtualCastOutputPowerGate.isSleeping(nextState);
    }

    static boolean isOutputAvailableResult(String result) {
        return "paused".equals(result) || "already_paused".equals(result)
                || "resumed".equals(result) || "already_available".equals(result);
    }

    static boolean allowRecoveryAction(boolean activeSession, boolean ending, boolean currentInstance,
                                       boolean sameSession, boolean currentEpoch, boolean waking,
                                       boolean screenOffCauseStillCurrent) {
        if (!activeSession || ending || !currentInstance || !sameSession || !currentEpoch) return false;
        return !waking || screenOffCauseStillCurrent;
    }

    static boolean expireViewportChange(boolean viewportChangePending, long viewportDeadline, long now) {
        return viewportChangePending && now >= viewportDeadline;
    }
}
