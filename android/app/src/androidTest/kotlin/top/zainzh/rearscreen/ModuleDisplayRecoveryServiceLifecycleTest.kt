package top.zainzh.rearscreen

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies stale explicit service actions do not leave a started FGS/service behind. */
@RunWith(AndroidJUnit4::class)
class ModuleDisplayRecoveryServiceLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun staleStopAndCancelIntentsDoNotLeaveServiceRunningWithoutALease() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val staleLease = ModuleDisplayLease("stale-fixture", 99, "old-generation", 876, 7, 123L)
        val actions = listOf(
            ModuleDisplayRecoveryService.ACTION_STOP,
            ModuleDisplayRecoveryService.ACTION_CANCEL
        )

        ActivityScenario.launch(NativeModuleRendererTestHostActivity::class.java).use { scenario ->
            for (action in actions) {
                assertFalse("test must start without its recovery service", isRecoveryRunning(manager))
                val identity = RecoveryLeaseIntents.create(action, staleLease, context.packageName)
                val start = Intent(context, ModuleDisplayRecoveryService::class.java)
                    .setAction(action)
                    .putExtras(identity)
                var startedByTest = false
                try {
                    scenario.onActivity { activity -> activity.startService(start) }
                    startedByTest = true
                    assertTrue("stale $action must stopSelf without a foreground-service timeout",
                        awaitStopped(manager, 3_000L))
                } finally {
                    if (startedByTest && isRecoveryRunning(manager)) context.stopService(start)
                }
            }
        }
        assertFalse("no stale recovery service may remain", isRecoveryRunning(manager))
    }

    private fun isRecoveryRunning(manager: ActivityManager): Boolean =
        manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == ModuleDisplayRecoveryService::class.java.name
        }

    private fun awaitStopped(manager: ActivityManager, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (!isRecoveryRunning(manager)) return true
            SystemClock.sleep(50)
        }
        return false
    }
}
