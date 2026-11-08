package top.zainzh.rearscreen;

import android.view.Display;
import org.junit.Test;
import static org.junit.Assert.*;

/** The Scene wake is one target-display input, only when the measured rear display is not ON. */
public class SceneDisplayWakeupTest {
    @Test public void alreadyOnDoesNotInjectInput() {
        int[] commands = {0};
        SceneDisplayWakeup.Result result = SceneDisplayWakeup.wakeIfNeeded(1, id -> Display.STATE_ON,
                id -> { commands[0]++; return command(0, false); }, time());
        assertTrue(result.awake);
        assertEquals("already_on", result.status);
        assertEquals(0, commands[0]);
    }

    @Test public void sleepingDisplayGetsExactlyOneWakeAndMustBecomeOn() {
        int[] state = {Display.STATE_OFF};
        int[] commands = {0};
        long[] now = {0};
        SceneDisplayWakeup.Time time = new SceneDisplayWakeup.Time() {
            @Override public long elapsedRealtime() { return now[0]; }
            @Override public void sleep(long millis) { now[0] += millis; if (now[0] >= 100) state[0] = Display.STATE_ON; }
        };
        SceneDisplayWakeup.Result result = SceneDisplayWakeup.wakeIfNeeded(7, id -> state[0], id -> {
            assertEquals("the physical rear display is the only wake target", 7, id);
            commands[0]++;
            return command(0, false);
        }, time);
        assertTrue(result.awake);
        assertEquals("woken", result.status);
        assertEquals("wake command is not retried while checking the power state", 1, commands[0]);
    }

    @Test public void commandSuccessAloneDoesNotClaimDisplayVisible() {
        long[] now = {0};
        SceneDisplayWakeup.Result result = SceneDisplayWakeup.wakeIfNeeded(1, id -> Display.STATE_OFF,
                id -> command(0, false), new SceneDisplayWakeup.Time() {
                    @Override public long elapsedRealtime() { return now[0]; }
                    @Override public void sleep(long millis) { now[0] += millis; }
                });
        assertFalse(result.awake);
        assertEquals("display_did_not_reach_on", result.status);
    }

    @Test public void unknownDisplayStateDoesNotInjectInput() {
        int[] commands = {0};
        SceneDisplayWakeup.Result result = SceneDisplayWakeup.wakeIfNeeded(1, id -> { throw new SecurityException(); },
                id -> { commands[0]++; return command(0, false); }, time());
        assertFalse(result.awake);
        assertEquals("display_state_unavailable_SecurityException", result.status);
        assertEquals(0, commands[0]);
    }

    @Test public void invalidDisplayDoesNotInjectInput() {
        int[] commands = {0};
        SceneDisplayWakeup.Result result = SceneDisplayWakeup.wakeIfNeeded(0, id -> Display.STATE_OFF,
                id -> { commands[0]++; return command(0, false); }, time());
        assertFalse(result.awake);
        assertEquals("invalid_rear_display", result.status);
        assertEquals(0, commands[0]);
    }

    private static SceneTaskShellRunner.Result command(int exit, boolean timeout) {
        return new SceneTaskShellRunner.Result("", exit, timeout, false, null);
    }
    private static SceneDisplayWakeup.Time time() {
        return new SceneDisplayWakeup.Time() {
            @Override public long elapsedRealtime() { return 0; }
            @Override public void sleep(long millis) { }
        };
    }
}
