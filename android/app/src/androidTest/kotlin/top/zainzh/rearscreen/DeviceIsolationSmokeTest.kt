package top.zainzh.rearscreen

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Read-only proof that the guarded test APK ran on the dedicated emulator. */
class DeviceIsolationSmokeTest {
    @Test
    fun runsOnlyOnRootDedicatedEmulatorAndDebugVariant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val testContext = instrumentation.context

        assertEquals("emu64a", Build.DEVICE)
        assertEquals("1", shellGetprop("ro.kernel.qemu"))
        assertEquals("remrss-root-rc3-isolated", shellGetprop("ro.boot.qemu.avd_name"))
        assertEquals("top.zainzh.rearscreen.debug", appContext.packageName)
        assertEquals("top.zainzh.rearscreen.debug.test", testContext.packageName)
    }

    private fun shellGetprop(name: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor = instrumentation.uiAutomation.executeShellCommand("getprop $name")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
    }
}
