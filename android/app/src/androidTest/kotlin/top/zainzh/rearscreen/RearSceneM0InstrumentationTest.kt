package top.zainzh.rearscreen

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only M0 proof; requires an explicitly created secondary emulator display. */
@RunWith(AndroidJUnit4::class)
class RearSceneM0InstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun independentSceneKeepsPrimaryTaskAndReportsActualWindowLifecycle() {
        assumeTrue("launching Activities on another display needs API 26+", Build.VERSION.SDK_INT >= 26)
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val texture = android.graphics.SurfaceTexture(0).apply { setDefaultBufferSize(1200, 800) }
        val surface = android.view.Surface(texture)
        val virtualDisplay = displayManager.createVirtualDisplay(
            "ReMRSS-M0-secondary", 1200, 800, 240, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        assumeTrue("emulator must permit a public secondary display fixture", virtualDisplay != null)
        val secondaryDisplay = requireNotNull(virtualDisplay).display
        RearSceneM0Activity.primaryDestroyCount = 0
        RearSceneM0Activity.rearDestroyCount = 0
        RearSceneM0Activity.primary = null
        RearSceneM0Activity.rear = null

        context.startActivity(Intent(context, RearSceneM0PrimaryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val primary = await { RearSceneM0Activity.primary }
        assertNotNull("primary fixture should start", primary)
        val primaryActivity = requireNotNull(primary)
        val primaryTask = primaryActivity.taskId

        val launchOutput = shell("am start --display ${secondaryDisplay.displayId} -n ${context.packageName}/${RearSceneM0RearActivity::class.java.name}")
        assertTrue("host-authorized Activity launch should be accepted by the emulator task manager: $launchOutput",
            launchOutput.contains("Starting") || launchOutput.contains("Warning: Activity not started"))
        val rear = await { RearSceneM0Activity.rear?.takeIf { it.window.decorView.width > 0 && it.window.decorView.height > 0 } }
        assertNotNull("independent rear Activity should reach a measured window", rear)
        val rearActivity = requireNotNull(rear)
        assertEquals(secondaryDisplay.displayId, rearActivity.display?.displayId)
        assertNotEquals("scene must be a distinct task from the primary task", primaryTask, rearActivity.taskId)
        assertTrue("primary task must remain alive", !primaryActivity.isFinishing && !primaryActivity.isDestroyed)
        assertEquals(android.view.Display.DEFAULT_DISPLAY, primaryActivity.display?.displayId)

        val before = RearSceneM0Activity.snapshot
        val actualViewport = requireNotNull(before)
        assertTrue("actual window size must be reported in pixels", actualViewport.windowWidthPx > 0 && actualViewport.windowHeightPx > 0)
        assertTrue("density must be reported separately from pixels", actualViewport.densityDpi > 0)
        assertTrue("configured display pixels must be reported separately from window pixels", actualViewport.displayWidthPx > 0 && actualViewport.displayHeightPx > 0)

        instrumentation.runOnMainSync { rearActivity.recreate() }
        val recreated = await { RearSceneM0Activity.rear?.takeIf { it !== rearActivity && it.window.decorView.width > 0 } }
        assertNotNull("recreation should attach a new Activity in the existing scene task", recreated)
        val recreatedActivity = requireNotNull(recreated)
        assertEquals(secondaryDisplay.displayId, recreatedActivity.display?.displayId)
        assertEquals(primaryTask, primaryActivity.taskId)
        assertTrue("primary task must still be alive after recreation", !primaryActivity.isFinishing && !primaryActivity.isDestroyed)

        instrumentation.runOnMainSync { recreatedActivity.finish() }
        assertTrue("closing the scene should destroy only its rear Activity", await { RearSceneM0Activity.rear == null && RearSceneM0Activity.rearDestroyCount >= 1 } == true)
        assertTrue("closing the scene must not finish the primary Activity", !primaryActivity.isFinishing && !primaryActivity.isDestroyed)
        instrumentation.runOnMainSync { primaryActivity.finish() }
        virtualDisplay.release()
        surface.release()
        texture.release()
    }


    @Test fun emulatorSecondaryInputMustBeTouchCapableBeforeRoutingIsClaimed() {
        assumeTrue("launching Activities on another display needs API 26+", Build.VERSION.SDK_INT >= 26)
        val texture = android.graphics.SurfaceTexture(0).apply { setDefaultBufferSize(1200, 800) }
        val surface = android.view.Surface(texture)
        val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "ReMRSS-M0-input", 1200, 800, 240, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        assumeTrue("emulator must permit a public secondary display fixture", display != null)
        val info = shell("dumpsys display").lineSequence().firstOrNull { it.contains("ReMRSS-M0-input") }.orEmpty()
        android.util.Log.i("RearSceneM0", "secondary_display_input=$info")
        assumeTrue("the emulator virtual display reports no touch-capable input path: $info",
            info.contains("touch EXTERNAL") || info.contains("touch VIRTUAL") || info.contains("touch INTERNAL"))
        context.startActivity(Intent(context, RearSceneM0PrimaryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotNull("primary fixture should start", await { RearSceneM0Activity.primary })
        val launchOutput = shell("am start --display ${display!!.display.displayId} -n ${context.packageName}/${RearSceneM0RearActivity::class.java.name}")
        assertTrue("rear Activity should launch on touch-capable emulator display: $launchOutput", launchOutput.contains("Starting"))
        assertNotNull("rear Activity should measure its actual window", await { RearSceneM0Activity.rear?.takeIf { it.window.decorView.width > 0 } })
        val size = requireNotNull(RearSceneM0Activity.snapshot)
        val touchOutput = tapOnDisplay(display.display.displayId, size.windowWidthPx / 2, size.windowHeightPx / 2)
        assertTrue("secondary display input should arrive at Activity (input=$touchOutput)",
            await { RearSceneM0Activity.snapshot?.inputUps?.let { it > 0 } == true } == true)
        instrumentation.runOnMainSync { RearSceneM0Activity.rear?.finish(); RearSceneM0Activity.primary?.finish() }
        display.release()
        surface.release()
        texture.release()
    }

    private fun tapOnDisplay(displayId: Int, x: Int, y: Int): String =
        shell("input -d $displayId tap $x $y")

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }

    private fun <T> await(timeoutMs: Long = 5_000, read: () -> T?): T? {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) {
            instrumentation.waitForIdleSync()
            read()?.let { return it }
            SystemClock.sleep(50)
        }
        return null
    }
}
