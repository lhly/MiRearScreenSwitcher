package top.zainzh.rearscreen

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class RearScreenshotNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun homeEntryAndBackPreserveHome() {
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("rear_screenshot_entry"))
        compose.onNodeWithTag("rear_screenshot_entry").performClick()
        compose.onNodeWithTag("screenshot_back").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("screenshot_back").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("rear_screenshot_entry").assertIsDisplayed()
        compose.waitForIdle(); Thread.sleep(700)
        val inst = InstrumentationRegistry.getInstrumentation()
        val bitmap = inst.uiAutomation.takeScreenshot()
        FileOutputStream(File(inst.targetContext.filesDir, "screenshot-ui-home.png")).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
