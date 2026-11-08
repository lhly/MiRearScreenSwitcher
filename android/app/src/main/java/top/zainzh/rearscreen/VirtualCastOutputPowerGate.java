package top.zainzh.rearscreen;

import android.view.Display;
import java.util.function.BooleanSupplier;

/** Production commit-edge checks shared by the cast Session and its power restore caller. */
final class VirtualCastOutputPowerGate {
    enum SurfaceChoice { RETAINED, REPLACEMENT, NONE, INVALID_REPLACEMENT }
    interface CleanupEffect { void run() throws Exception; }

    private VirtualCastOutputPowerGate() {}

    static boolean mayCommit(boolean sessionCurrent, boolean ownerAlive, boolean hostStillOnRear,
                             int actualRearState, boolean outputAvailable, BooleanSupplier actionCurrent) {
        if (!sessionCurrent || !ownerAlive || !hostStillOnRear || actionCurrent == null) return false;
        if (outputAvailable) {
            if (actualRearState != Display.STATE_ON) return false;
        } else if (!isSleeping(actualRearState)) {
            return false;
        }
        try { return actionCurrent.getAsBoolean(); }
        catch (RuntimeException denied) { return false; }
    }

    static SurfaceChoice chooseRestoreSurface(boolean retainedValid, boolean replacementProvided,
                                               boolean replacementValid) {
        if (replacementProvided) return replacementValid ? SurfaceChoice.REPLACEMENT : SurfaceChoice.INVALID_REPLACEMENT;
        return retainedValid ? SurfaceChoice.RETAINED : SurfaceChoice.NONE;
    }

    static boolean deferSurfaceChangeWhilePowerSuspended(boolean powerSuspended) {
        return powerSuspended;
    }

    /** A cancelled ON commit must pause input/gesture clock before detaching its output Surface. */
    static boolean rollbackCancelledRestore(CleanupEffect pauseInput, CleanupEffect detachOutput) {
        boolean clean = true;
        try { pauseInput.run(); } catch (Exception failure) { clean = false; }
        try { detachOutput.run(); } catch (Exception failure) { clean = false; }
        return clean;
    }

    static boolean isSleeping(int state) {
        return state == Display.STATE_OFF || state == Display.STATE_DOZE
                || state == Display.STATE_DOZE_SUSPEND || state == Display.STATE_ON_SUSPEND;
    }
}
