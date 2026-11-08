package top.zainzh.rearscreen;

import java.util.concurrent.*;

/** A session-owned gesture tick source. Output loss cancels its scheduled work; it never polls while paused. */
final class PausableGestureClock {
    private final ScheduledExecutorService executor;
    private final Runnable tick;
    private final long periodMillis;
    private ScheduledFuture<?> future;
    private long generation;
    private boolean closed;

    PausableGestureClock(ScheduledExecutorService executor, Runnable tick, long periodMillis) {
        this.executor = executor; this.tick = tick; this.periodMillis = periodMillis;
    }
    synchronized void resume() {
        if (closed || future != null && !future.isDone()) return;
        long expected = ++generation;
        future = executor.scheduleAtFixedRate(() -> {
            synchronized (PausableGestureClock.this) {
                if (closed || expected != generation || future == null) return;
            }
            tick.run();
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }
    synchronized void pause() {
        generation++;
        if (future != null) { future.cancel(false); future = null; }
    }
    synchronized void close() {
        if (closed) return;
        closed = true; generation++;
        if (future != null) { future.cancel(false); future = null; }
        executor.shutdownNow();
    }
}
