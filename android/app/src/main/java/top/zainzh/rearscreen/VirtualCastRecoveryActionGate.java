package top.zainzh.rearscreen;

import android.os.RemoteException;
import java.util.function.BooleanSupplier;

/** Binder gate for one recovery edge; stale power/session work is denied at the service action boundary. */
final class VirtualCastRecoveryActionGate extends ISceneTaskActionGate.Stub {
    private final VirtualCastRecoveryGuard guard;
    VirtualCastRecoveryActionGate(BooleanSupplier current) { this.guard = new VirtualCastRecoveryGuard(current); }
    @Override public boolean isCurrent() throws RemoteException {
        return guard.isCurrent();
    }
}

/** Plain production guard so the exact gate decision can be exercised in local JVM tests. */
final class VirtualCastRecoveryGuard {
    private final BooleanSupplier current;
    VirtualCastRecoveryGuard(BooleanSupplier current) { this.current = current; }
    boolean isCurrent() {
        try { return current.getAsBoolean(); }
        catch (RuntimeException e) { return false; }
    }
}
