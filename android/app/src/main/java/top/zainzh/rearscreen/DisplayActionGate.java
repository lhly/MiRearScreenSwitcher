package top.zainzh.rearscreen;

import java.util.concurrent.Callable;

/** Shared serialization boundary for rear-display ownership and user setting writes. */
final class DisplayActionGate {
    private final Object lock = new Object();

    void run(Runnable action) {
        synchronized (lock) { action.run(); }
    }

    <T> T call(Callable<T> action) throws Exception {
        synchronized (lock) { return action.call(); }
    }
}
