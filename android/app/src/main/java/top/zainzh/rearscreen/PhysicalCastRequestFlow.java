package top.zainzh.rearscreen;

/** Owns the reversible preparation and the exact physical-cast commit boundary. */
final class PhysicalCastRequestFlow {
    static final class Target {
        final String packageName;
        final int taskId;

        Target(String packageName, int taskId) {
            this.packageName = packageName;
            this.taskId = taskId;
        }

        String legacyValue() { return packageName + ":" + taskId; }
    }

    interface Port {
        /** Apply only reversible request configuration; never stop an app, disable launcher, or start Keeper here. */
        void prepare() throws Exception;
        /** Launch the requested target and return the observed foreground package:task, or null if unresolved. */
        String launchAndObserve(int attempt) throws Exception;
        boolean moveToRear(int taskId) throws Exception;
        void projectionCommitted(int taskId, String packageName) throws Exception;
        void disableLauncher(Target target) throws Exception;
        boolean startKeeper(String packageTask) throws Exception;
        void rollbackPreparation();
        void postCommitFailure(Exception failure);
    }

    static final class Result {
        final Target target;
        final boolean committed;
        final boolean keeperStarted;
        final String failure;
        private Result(Target target, boolean committed, boolean keeperStarted, String failure) {
            this.target = target;
            this.committed = committed;
            this.keeperStarted = keeperStarted;
            this.failure = failure;
        }
        static Result failed(String reason) { return new Result(null, false, false, reason); }
        static Result committed(Target target, boolean keeperStarted) {
            return new Result(target, true, keeperStarted, null);
        }
    }

    private interface TargetResolver { Target resolve() throws Exception; }
    private PhysicalCastRequestFlow() {}

    static Result launchAndHandoff(String expectedPackage, int maxAttempts, Port port) {
        return execute(port, () -> {
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                Target target = parseExact(expectedPackage, port.launchAndObserve(attempt));
                if (target != null) return target;
            }
            return null;
        });
    }

    static Result prepareAndHandoff(Target target, Port port) {
        return execute(port, () -> target);
    }

    private static Result execute(Port port, TargetResolver resolver) {
        boolean preparationAttempted = false;
        boolean committed = false;
        try {
            preparationAttempted = true;
            port.prepare();
            Target target = resolver.resolve();
            if (target == null) return Result.failed("target_unresolved");
            if (!port.moveToRear(target.taskId)) return Result.failed("move_rejected");

            // A verified move is the irreversible ownership boundary. A later maintenance error
            // cannot undo the display move or restore the previous Scene/notification owner.
            committed = true;
            runPostCommit(port, () -> port.projectionCommitted(target.taskId, target.packageName));
            boolean keeperStarted = false;
            try {
                keeperStarted = port.startKeeper(target.legacyValue());
                if (!keeperStarted) port.postCommitFailure(new IllegalStateException("Keeper start was not accepted"));
            } catch (Exception failure) { port.postCommitFailure(failure); }
            // Keep the system launcher available when there is no Keeper to own teardown.
            // The committed task remains returnable through the persistent exact-task record.
            if (keeperStarted) runPostCommit(port, () -> port.disableLauncher(target));
            return Result.committed(target, keeperStarted);
        } catch (Exception failure) {
            return Result.failed(failure.getClass().getSimpleName());
        } finally {
            if (preparationAttempted && !committed) {
                try { port.rollbackPreparation(); }
                catch (RuntimeException rollbackFailure) { port.postCommitFailure(rollbackFailure); }
            }
        }
    }

    private interface PostCommitAction { void run() throws Exception; }
    private static void runPostCommit(Port port, PostCommitAction action) {
        try { action.run(); }
        catch (Exception failure) { port.postCommitFailure(failure); }
    }

    private static Target parseExact(String expectedPackage, String observed) {
        if (observed == null) return null;
        int separator = observed.lastIndexOf(':');
        if (separator <= 0 || separator == observed.length() - 1
                || !expectedPackage.equals(observed.substring(0, separator))) return null;
        try {
            int task = Integer.parseInt(observed.substring(separator + 1));
            return task > 0 ? new Target(expectedPackage, task) : null;
        } catch (NumberFormatException invalidTask) { return null; }
    }
}
