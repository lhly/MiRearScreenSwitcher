package top.zainzh.rearscreen;

/** Returns one exact committed physical task without taking over any newer rear owner. */
final class PhysicalCastReturnFlow {
    enum Result { STOPPED, STALE, MOVE_FAILED, RESTORE_FAILED, STOP_FAILED }

    interface Port {
        boolean isExactTaskOnRear(int taskId, String packageName) throws Exception;
        boolean moveExactTaskToMain(int taskId) throws Exception;
        boolean mayRestoreLauncher() throws Exception;
        boolean restoreLauncher() throws Exception;
        boolean stopKeeperForExactTask(String packageTask, long ownerGeneration);
        void clearExactOwnership(int taskId, long ownerGeneration);
    }

    private PhysicalCastReturnFlow() {}

    static Result stop(String packageTask, boolean keeperOwnsTask, boolean launcherDisabled,
                       long ownerGeneration, Port port)
            throws Exception {
        int separator = packageTask == null ? -1 : packageTask.lastIndexOf(':');
        if (separator <= 0 || separator == packageTask.length() - 1) return Result.STALE;
        String packageName = packageTask.substring(0, separator);
        int taskId;
        try { taskId = Integer.parseInt(packageTask.substring(separator + 1)); }
        catch (NumberFormatException invalid) { return Result.STALE; }
        if (taskId <= 0 || packageName.isBlank()) return Result.STALE;

        boolean onRear = port.isExactTaskOnRear(taskId, packageName);
        if (onRear && !port.moveExactTaskToMain(taskId)) return Result.MOVE_FAILED;
        if (!onRear && !keeperOwnsTask && !launcherDisabled) {
            port.clearExactOwnership(taskId, ownerGeneration);
            return Result.STALE;
        }

        boolean restorationSafe = false;
        boolean restorationCheckFailed = false;
        if (launcherDisabled) {
            try { restorationSafe = port.mayRestoreLauncher(); }
            catch (Exception failure) { restorationCheckFailed = true; }
        }
        boolean restoreAttempted = launcherDisabled && restorationSafe;
        boolean restored = !restoreAttempted;
        if (restoreAttempted) {
            try { restored = port.restoreLauncher(); }
            catch (Exception failure) { restored = false; }
        }
        boolean clearOwnerRecord = !launcherDisabled || (restorationSafe && restored);
        if (keeperOwnsTask && !port.stopKeeperForExactTask(packageTask, ownerGeneration))
            return Result.STOP_FAILED;
        if (!restored || restorationCheckFailed) return Result.RESTORE_FAILED;
        if (clearOwnerRecord) port.clearExactOwnership(taskId, ownerGeneration);
        return Result.STOPPED;
    }
}
