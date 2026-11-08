package top.zainzh.rearscreen.timer;

/**
 * Elapsed-realtime timer with no scheduler, Android dependency or per-second mutation.
 * Callers render only while visible and schedule at most the next stage boundary.
 * All public times come from the same monotonic clock; bootCount identifies that clock's boot.
 */
public final class StageTimerEngine {
    public enum Status { IDLE, RUNNING, PAUSED, FINISHED, STOPPED, INTERRUPTED }

    public final StageTimerPlan plan;
    private Status status = Status.IDLE;
    private long elapsedBeforeAnchorMs;
    private long anchorElapsedMs;
    private int bootCount = -1;
    private long lastObservedElapsedMs;

    public StageTimerEngine(StageTimerPlan plan) {
        if (plan == null) throw new IllegalArgumentException("Plan cannot be null");
        this.plan = plan;
    }

    /** Explicitly starts or restarts the plan, including a previously interrupted timer. */
    public synchronized Snapshot start(long nowElapsedMs, int currentBootCount) {
        checkClock(nowElapsedMs, currentBootCount);
        status = Status.RUNNING;
        elapsedBeforeAnchorMs = 0;
        anchorElapsedMs = nowElapsedMs;
        lastObservedElapsedMs = nowElapsedMs;
        bootCount = currentBootCount;
        return snapshot(nowElapsedMs, currentBootCount);
    }

    public synchronized Snapshot pause(long nowElapsedMs, int currentBootCount) {
        Snapshot current = snapshot(nowElapsedMs, currentBootCount);
        if (status == Status.RUNNING) {
            elapsedBeforeAnchorMs = plan.totalDurationMs - current.totalRemainingMs;
            anchorElapsedMs = nowElapsedMs;
            status = Status.PAUSED;
        }
        return snapshot(nowElapsedMs, currentBootCount);
    }

    public synchronized Snapshot resume(long nowElapsedMs, int currentBootCount) {
        snapshot(nowElapsedMs, currentBootCount);
        if (status == Status.PAUSED) {
            anchorElapsedMs = nowElapsedMs;
            status = Status.RUNNING;
        }
        return snapshot(nowElapsedMs, currentBootCount);
    }

    /** Ends an active session. Repeated stop, or stop after completion, has no effect. */
    public synchronized Snapshot stop(long nowElapsedMs, int currentBootCount) {
        Snapshot current = snapshot(nowElapsedMs, currentBootCount);
        if (status == Status.RUNNING || status == Status.PAUSED) {
            elapsedBeforeAnchorMs = plan.totalDurationMs - current.totalRemainingMs;
            anchorElapsedMs = nowElapsedMs;
            status = Status.STOPPED;
        }
        return snapshot(nowElapsedMs, currentBootCount);
    }

    /**
     * Resolves the present stage directly, even after many stages elapsed while asleep.
     * No missed-stage events are generated, so a caller never needs to replay old reminders.
     */
    public synchronized Snapshot snapshot(long nowElapsedMs, int currentBootCount) {
        checkClock(nowElapsedMs, currentBootCount);
        boolean active = status == Status.RUNNING || status == Status.PAUSED;
        if (active && (bootCount != currentBootCount || nowElapsedMs < lastObservedElapsedMs
                || nowElapsedMs < anchorElapsedMs)) {
            // A reboot invalidates elapsedRealtime. Do not guess from the wall clock or restart.
            if (status == Status.RUNNING) elapsedBeforeAnchorMs += Math.min(
                    plan.totalDurationMs - elapsedBeforeAnchorMs, lastObservedElapsedMs - anchorElapsedMs);
            interrupt(nowElapsedMs, currentBootCount);
        }
        if (active) lastObservedElapsedMs = nowElapsedMs;

        long consumed = elapsedBeforeAnchorMs;
        if (status == Status.RUNNING) {
            long sinceAnchor = nowElapsedMs - anchorElapsedMs;
            long remainingAtAnchor = plan.totalDurationMs - elapsedBeforeAnchorMs;
            if (sinceAnchor >= remainingAtAnchor) {
                consumed = plan.totalDurationMs;
                elapsedBeforeAnchorMs = consumed;
                anchorElapsedMs = nowElapsedMs;
                status = Status.FINISHED;
            } else consumed += sinceAnchor;
        }
        int count = plan.stages.size();
        if (consumed >= plan.totalDurationMs) {
            return new Snapshot(status, count - 1, plan.rounds - 1,
                    count * plan.rounds - 1, count * plan.rounds, 0, 0, -1);
        }
        int round = (int) (consumed / plan.cycleDurationMs);
        long withinCycle = consumed % plan.cycleDurationMs;
        int index = 0;
        while (withinCycle >= plan.stages.get(index).durationMs) {
            withinCycle -= plan.stages.get(index).durationMs;
            index++;
        }
        long stageRemaining = plan.stages.get(index).durationMs - withinCycle;
        long boundary = -1;
        if (status == Status.RUNNING) {
            if (nowElapsedMs > Long.MAX_VALUE - stageRemaining) {
                elapsedBeforeAnchorMs = consumed;
                interrupt(nowElapsedMs, currentBootCount);
            }
            else boundary = nowElapsedMs + stageRemaining;
        }
        int ordinal = round * count + index;
        return new Snapshot(status, index, round, ordinal, ordinal,
                stageRemaining, plan.totalDurationMs - consumed, boundary);
    }

    /** Persist after a command/snapshot; no periodic saving is necessary while running. */
    public synchronized SavedState exportState() {
        return new SavedState(status, elapsedBeforeAnchorMs, anchorElapsedMs, bootCount, lastObservedElapsedMs);
    }

    /** Restores a process-death checkpoint; corrupt/old-boot active state becomes INTERRUPTED. */
    public static StageTimerEngine restore(StageTimerPlan plan, SavedState saved,
            long nowElapsedMs, int currentBootCount) {
        checkClock(nowElapsedMs, currentBootCount);
        StageTimerEngine engine = new StageTimerEngine(plan);
        if (!validState(plan, saved)) {
            engine.status = Status.INTERRUPTED;
            engine.bootCount = currentBootCount;
            engine.anchorElapsedMs = nowElapsedMs;
            engine.lastObservedElapsedMs = nowElapsedMs;
        } else {
            engine.status = saved.status;
            engine.elapsedBeforeAnchorMs = saved.elapsedBeforeAnchorMs;
            engine.anchorElapsedMs = saved.anchorElapsedMs;
            engine.bootCount = saved.bootCount;
            engine.lastObservedElapsedMs = saved.lastObservedElapsedMs;
        }
        engine.snapshot(nowElapsedMs, currentBootCount);
        return engine;
    }

    private static boolean validState(StageTimerPlan plan, SavedState saved) {
        if (saved == null || saved.status == null || saved.elapsedBeforeAnchorMs < 0
                || saved.elapsedBeforeAnchorMs > plan.totalDurationMs || saved.anchorElapsedMs < 0
                || saved.lastObservedElapsedMs < saved.anchorElapsedMs) return false;
        if (saved.status == Status.IDLE) return saved.elapsedBeforeAnchorMs == 0
                && saved.anchorElapsedMs == 0 && saved.lastObservedElapsedMs == 0 && saved.bootCount == -1;
        if (saved.bootCount < 0) return false;
        if (saved.status == Status.FINISHED) return saved.elapsedBeforeAnchorMs == plan.totalDurationMs;
        if (saved.status == Status.RUNNING || saved.status == Status.PAUSED)
            return saved.elapsedBeforeAnchorMs < plan.totalDurationMs;
        return true;
    }

    private static void checkClock(long nowElapsedMs, int currentBootCount) {
        if (nowElapsedMs < 0 || currentBootCount < 0)
            throw new IllegalArgumentException("Clock and boot count must be nonnegative");
    }

    private void interrupt(long nowElapsedMs, int currentBootCount) {
        status = Status.INTERRUPTED;
        anchorElapsedMs = nowElapsedMs;
        lastObservedElapsedMs = nowElapsedMs;
        bootCount = currentBootCount;
    }

    public static final class Snapshot {
        public final Status status;
        public final int stageIndex;
        public final int roundIndex;
        public final int stageOrdinal;
        public final int completedStages;
        public final long stageRemainingMs;
        public final long totalRemainingMs;
        /** The next stage/end deadline in elapsedRealtime milliseconds, or -1 when not running. */
        public final long boundaryElapsedMs;

        private Snapshot(Status status, int stageIndex, int roundIndex, int stageOrdinal,
                int completedStages, long stageRemainingMs, long totalRemainingMs, long boundaryElapsedMs) {
            this.status = status;
            this.stageIndex = stageIndex;
            this.roundIndex = roundIndex;
            this.stageOrdinal = stageOrdinal;
            this.completedStages = completedStages;
            this.stageRemainingMs = stageRemainingMs;
            this.totalRemainingMs = totalRemainingMs;
            this.boundaryElapsedMs = boundaryElapsedMs;
        }
    }

    /** Plain fields for an Android persistence adapter; the plan is stored separately. */
    public static final class SavedState {
        public final Status status;
        public final long elapsedBeforeAnchorMs;
        public final long anchorElapsedMs;
        public final int bootCount;
        public final long lastObservedElapsedMs;

        public SavedState(Status status, long elapsedBeforeAnchorMs, long anchorElapsedMs,
                int bootCount, long lastObservedElapsedMs) {
            this.status = status;
            this.elapsedBeforeAnchorMs = elapsedBeforeAnchorMs;
            this.anchorElapsedMs = anchorElapsedMs;
            this.bootCount = bootCount;
            this.lastObservedElapsedMs = lastObservedElapsedMs;
        }
    }
}
