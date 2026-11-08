package top.zainzh.rearscreen;

import android.view.Display;

/** A single, lease-scoped rear-panel wake. This deliberately contains no polling loop that reissues input. */
final class SceneDisplayWakeup {
    interface DisplayStateReader { int state(int displayId) throws Exception; }
    interface WakeCommand { SceneTaskShellRunner.Result wake(int displayId); }
    interface Time { long elapsedRealtime(); void sleep(long millis) throws InterruptedException; }

    static final class Result {
        final boolean awake;
        final String status;
        final int commandExit;
        Result(boolean awake, String status, int commandExit) {
            this.awake = awake;
            this.status = status;
            this.commandExit = commandExit;
        }
    }

    private static final long CONFIRM_WINDOW_MS = 1_000L;
    private static final long CHECK_INTERVAL_MS = 50L;

    private SceneDisplayWakeup() { }

    static Result wakeIfNeeded(int displayId, DisplayStateReader displayState, WakeCommand wakeCommand, Time time) {
        if (displayId <= 0) return new Result(false, "invalid_rear_display", -1);
        final int initial;
        try { initial = displayState.state(displayId); }
        catch (Exception e) { return new Result(false, "display_state_unavailable_" + e.getClass().getSimpleName(), -1); }
        if (initial == Display.STATE_ON) return new Result(true, "already_on", 0);

        SceneTaskShellRunner.Result command = wakeCommand.wake(displayId);
        if (command == null) return new Result(false, "wake_result_unavailable", -1);
        if (command.failureCode != null) return new Result(false, "wake_runner_" + command.failureCode, command.exitCode);
        if (command.timedOut) return new Result(false, "wake_timeout", command.exitCode);
        if (command.truncated || command.exitCode != 0) return new Result(false, "wake_exit_" + command.exitCode, command.exitCode);

        long deadline = time.elapsedRealtime() + CONFIRM_WINDOW_MS;
        do {
            try {
                if (displayState.state(displayId) == Display.STATE_ON)
                    return new Result(true, "woken", command.exitCode);
            } catch (Exception e) {
                return new Result(false, "display_state_unavailable_" + e.getClass().getSimpleName(), command.exitCode);
            }
            if (time.elapsedRealtime() >= deadline) break;
            try { time.sleep(CHECK_INTERVAL_MS); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(false, "confirmation_interrupted", command.exitCode);
            }
        } while (true);
        return new Result(false, "display_did_not_reach_on", command.exitCode);
    }
}
