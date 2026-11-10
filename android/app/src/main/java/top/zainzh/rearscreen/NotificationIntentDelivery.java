package top.zainzh.rearscreen;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/** Atomic handoff arbitration between the notification worker and its queued UI delivery. */
final class NotificationIntentDelivery {
    enum State { PENDING, CLAIMED, COMPLETED, CANCELLED, TERMINATED }
    enum Outcome { NO_ACTIVE, ACCEPTED, REJECTED, CANCELLED_BEFORE_START, TERMINATED }

    static final class Result {
        final Outcome outcome;
        final int taskId;
        Result(Outcome outcome, int taskId) { this.outcome = outcome; this.taskId = taskId; }
        boolean mayStartFreshPlaceholder() {
            return outcome == Outcome.NO_ACTIVE || outcome == Outcome.CANCELLED_BEFORE_START;
        }
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile int taskId = -1;

    boolean claim() { return state.compareAndSet(State.PENDING, State.CLAIMED); }

    boolean runClaimed(IntSupplier request, Runnable terminalCleanup) {
        if (!claim()) return false;
        int acceptedTaskId = -1;
        try {
            acceptedTaskId = request.getAsInt();
            return true;
        } finally {
            if (!complete(acceptedTaskId) && state.get() == State.TERMINATED) terminalCleanup.run();
        }
    }

    boolean complete(int acceptedTaskId) {
        if (state.get() != State.CLAIMED) return false;
        taskId = acceptedTaskId;
        if (!state.compareAndSet(State.CLAIMED, State.COMPLETED)) return false;
        completed.countDown();
        return true;
    }

    boolean cancelPending() {
        if (!state.compareAndSet(State.PENDING, State.CANCELLED)) return false;
        completed.countDown();
        return true;
    }

    boolean terminateClaimed() {
        if (!state.compareAndSet(State.CLAIMED, State.TERMINATED)) return false;
        completed.countDown();
        return true;
    }

    boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return completed.await(timeout, unit);
    }

    State state() { return state.get(); }

    Result result() {
        State current = state.get();
        switch (current) {
            case COMPLETED: return new Result(taskId > 0 ? Outcome.ACCEPTED : Outcome.REJECTED, taskId);
            case CANCELLED: return new Result(Outcome.CANCELLED_BEFORE_START, -1);
            case TERMINATED: return new Result(Outcome.TERMINATED, -1);
            default: throw new IllegalStateException("delivery has no terminal result: " + current);
        }
    }
}
