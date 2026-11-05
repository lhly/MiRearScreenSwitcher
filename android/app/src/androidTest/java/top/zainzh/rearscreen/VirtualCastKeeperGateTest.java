package top.zainzh.rearscreen;

import android.content.Context;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

public class VirtualCastKeeperGateTest {
    @Test public void preferenceOnlyKeeperDoesNotCountAsPhysicalCast() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeFalse("Do not disturb a live physical cast", RearScreenKeeperService.isPhysicalCastRunning());
        boolean keeperAlreadyRunning = RearScreenKeeperService.isRunning();
        boolean startedForTest = false;
        Intent updatePreference = new Intent(context, RearScreenKeeperService.class)
                .setAction("ACTION_SET_PROXIMITY_ENABLED")
                .putExtra("enabled", false);
        try {
            if (!keeperAlreadyRunning) {
                context.startService(updatePreference);
                startedForTest = true;
            }
            long deadline = android.os.SystemClock.uptimeMillis() + 2_000;
            while (!RearScreenKeeperService.isRunning()
                    && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(25);
            }

            assertTrue("the preference update may leave its service instance running", RearScreenKeeperService.isRunning());
            assertFalse("a settings-only service has no physical cast task", RearScreenKeeperService.isPhysicalCastRunning());
            assertFalse("the virtual-cast gate must only reject a real physical cast", VirtualCastUriFlow.isPhysicalCastActive());
        } finally {
            if (startedForTest) context.stopService(new Intent(context, RearScreenKeeperService.class));
        }
    }
}
