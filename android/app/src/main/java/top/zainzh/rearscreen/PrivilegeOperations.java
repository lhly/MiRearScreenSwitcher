package top.zainzh.rearscreen;

import java.util.concurrent.atomic.AtomicInteger;

/** Prevent backend changes during finite commands; long sessions have their own guards. */
final class PrivilegeOperations {
    private static final AtomicInteger active = new AtomicInteger();
    private static boolean switching;
    static synchronized void begin() {
        if (switching) throw new IllegalStateException("Control service is switching");
        active.incrementAndGet();
    }
    static synchronized void end() { active.decrementAndGet(); }
    static synchronized boolean beginSwitch() {
        if (switching || active.get() != 0) return false;
        switching = true; return true;
    }
    static synchronized void endSwitch() { switching = false; }
}
