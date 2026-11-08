package top.zainzh.rearscreen.platform.runtime;

import java.util.concurrent.TimeUnit;

/** Package-local bridge for Mod AndroidTests to coordinate Runtime's debug close barrier. */
public final class ModRuntimeCloseBarrierBridge {
    public interface Barrier {
        boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException;
        void release();
    }

    private ModRuntimeCloseBarrierBridge() { }

    public static Barrier blockNextClose() {
        IsolatedModRuntimeClient.CloseBarrierForTesting delegate = IsolatedModRuntimeClient.blockNextCloseForTesting();
        return new Barrier() {
            @Override public boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
                return delegate.awaitEntered(timeout, unit);
            }
            @Override public void release() { delegate.release(); }
        };
    }
}
