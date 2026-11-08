package top.zainzh.rearscreen.timer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, bounded input for a local multi-stage timer. Durations are milliseconds. */
public final class StageTimerPlan {
    public static final int MAX_STAGES = 20;
    public static final int MAX_ROUNDS = 50;
    public static final int MAX_TITLE_LENGTH = 80;
    public static final int MAX_STAGE_NAME_LENGTH = 60;
    public static final long MIN_STAGE_DURATION_MS = 1_000L;
    public static final long MAX_TOTAL_DURATION_MS = 24 * 60 * 60_000L;

    public final String title;
    public final List<Stage> stages;
    public final int rounds;
    public final long cycleDurationMs;
    public final long totalDurationMs;

    public StageTimerPlan(String title, List<Stage> stages, int rounds) {
        this.title = checkedName(title, MAX_TITLE_LENGTH);
        if (stages == null || stages.isEmpty() || stages.size() > MAX_STAGES)
            throw new IllegalArgumentException("A timer requires 1 to " + MAX_STAGES + " stages");
        if (rounds < 1 || rounds > MAX_ROUNDS)
            throw new IllegalArgumentException("Rounds must be between 1 and " + MAX_ROUNDS);
        List<Stage> copy = new ArrayList<>(stages.size());
        long cycle = 0;
        for (Stage stage : stages) {
            if (stage == null) throw new IllegalArgumentException("Stage cannot be null");
            cycle += stage.durationMs;
            if (cycle > MAX_TOTAL_DURATION_MS)
                throw new IllegalArgumentException("Timer duration exceeds 24 hours");
            copy.add(stage);
        }
        if (cycle > MAX_TOTAL_DURATION_MS / rounds)
            throw new IllegalArgumentException("Timer duration including rounds exceeds 24 hours");
        this.stages = Collections.unmodifiableList(copy);
        this.rounds = rounds;
        this.cycleDurationMs = cycle;
        this.totalDurationMs = cycle * rounds;
    }

    public static final class Stage {
        public final String name;
        public final long durationMs;

        public Stage(String name, long durationMs) {
            this.name = checkedName(name, MAX_STAGE_NAME_LENGTH);
            if (durationMs < MIN_STAGE_DURATION_MS || durationMs > MAX_TOTAL_DURATION_MS)
                throw new IllegalArgumentException("Stage duration must be 1 second to 24 hours");
            this.durationMs = durationMs;
        }
    }

    private static String checkedName(String value, int maximumLength) {
        if (value == null) throw new IllegalArgumentException("Name cannot be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maximumLength)
            throw new IllegalArgumentException("Name must contain 1 to " + maximumLength + " characters");
        return trimmed;
    }
}
