package top.zainzh.rearscreen;

/** Generation fence shared by a cast Session and its out-of-lock health probe. */
final class VirtualCastHealthGeneration {
    private long structure;
    private long lease;
    synchronized long capture() { return structure; }
    synchronized long captureLease() { return lease; }
    synchronized long advance() { return ++structure; }
    synchronized long renewLease() { return ++lease; }
    synchronized boolean isCurrent(long captured) { return structure == captured; }
    synchronized boolean isLeaseCurrent(long captured) { return lease == captured; }
    synchronized boolean runIfCurrent(long captured, Runnable action) {
        if (structure != captured) return false;
        action.run();
        return true;
    }
    synchronized boolean runIfLeaseCurrent(long captured, Runnable action) {
        if (lease != captured) return false;
        action.run();
        return true;
    }
}
