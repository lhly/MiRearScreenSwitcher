package top.zainzh.rearscreen

import android.app.ActivityManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Verifies the real singleTask Activity can await a replacement Intent before finishing. */
@RunWith(AndroidJUnit4::class)
class ModuleRearPendingIntentLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val component = "${context.packageName}/${ModuleRearActivity::class.java.name}"

    @Test fun missingBaseIntentStaysPendingThenRejectsMalformedNewIntentWithoutKeepOrFgs() {
        ActivityScenario.launch(NativeModuleRendererTestHostActivity::class.java).use {
            cleanupPendingRearTask()

            val startOutput = shell("am start -W --display 0 -n $component")
            assertStartAccepted(startOutput)
            assertTrue("missing base Intent must remain pending, not finish in onCreate",
                awaitRearTaskPresent(expected = true, timeoutMs = 800L))
            assertFalse("pending create must not request screen-on", moduleRearHasKeepScreenOnFlag())
            assertFalse("pending create must not start recovery FGS", recoveryServiceRunning())

            val invalidOutput = shell(
                "am start -W --display 0 -n $component --es module_id '__invalid_module__' --ei display_id 0"
            )
            assertStartAccepted(invalidOutput)
            assertTrue("malformed replacement Intent must reject and finish the pending task",
                awaitRearTaskPresent(expected = false, timeoutMs = 2_000L))
            assertFalse("rejected pending create must not leave recovery FGS", recoveryServiceRunning())
        }
    }

    @Test fun missingBaseIntentFinishesAtItsBoundedTimeout() {
        ActivityScenario.launch(NativeModuleRendererTestHostActivity::class.java).use {
            cleanupPendingRearTask()

            val startOutput = shell("am start -W --display 0 -n $component")
            assertStartAccepted(startOutput)
            assertTrue("Activity should enter the pending window before timeout",
                awaitRearTaskPresent(expected = true, timeoutMs = 800L))
            assertTrue("uninitialized pending Activity should finish within its 1.5s bound",
                awaitRearTaskPresent(expected = false, timeoutMs = 2_500L))
            assertFalse("timeout must not start recovery FGS", recoveryServiceRunning())
        }
    }

    private fun assertStartAccepted(output: String) {
        assertTrue("Activity launch must reach Android's start path: $output",
            output.contains("Status: ok") || output.contains("Warning: Activity not started"))
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun rearTaskPresent(): Boolean = shell("dumpsys activity activities")
        .lineSequence()
        .any { line -> line.contains("Hist") && line.contains(context.packageName) && line.contains("ModuleRearActivity") }

    private fun awaitRearTaskPresent(expected: Boolean, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (rearTaskPresent() == expected) return true
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return rearTaskPresent() == expected
    }

    private fun recoveryServiceRunning(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(1_000).any {
            it.service.className == ModuleDisplayRecoveryService::class.java.name
        }
    }

    private fun moduleRearHasKeepScreenOnFlag(): Boolean {
        val dump = shell("dumpsys window windows")
        val windowStart = dump.indexOf(component)
        if (windowStart < 0) return false
        val previousWindow = dump.lastIndexOf("Window{", windowStart)
        val nextWindow = dump.indexOf("Window{", windowStart + 1)
        val end = if (nextWindow >= 0) nextWindow else dump.length
        val block = dump.substring(previousWindow.coerceAtLeast(0), end)
        return Regex("\\bfl=0x([0-9a-fA-F]+)").findAll(block).any { match ->
            val flags = match.groupValues[1].toLongOrNull(16) ?: return@any false
            flags and 0x80L != 0L
        }
    }

    private fun cleanupPendingRearTask() {
        if (!rearTaskPresent()) return
        shell("am start -W --display 0 -n $component --es module_id '__invalid_module__' --ei display_id 0")
        awaitRearTaskPresent(expected = false, timeoutMs = 2_000L)
    }
}
