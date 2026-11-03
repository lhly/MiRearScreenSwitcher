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
        // LazyColumn sees the viewport edge; the floating navigation bar covers part of it.
        val navigationTop = compose.onNodeWithContentDescription(NativeStrings.get(compose.activity, "screen_title"))
            .fetchSemanticsNode().boundsInRoot.top
        val entryBottom = compose.onNodeWithTag("rear_screenshot_entry").fetchSemanticsNode().boundsInRoot.bottom
        if (entryBottom > navigationTop - 16f) {
            compose.onNodeWithTag("home_list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
                it(0f, entryBottom - navigationTop + 32f)
            }
        }
        org.junit.Assert.assertTrue(compose.onNodeWithTag("rear_screenshot_entry").fetchSemanticsNode().boundsInRoot.bottom < navigationTop - 8f)
        compose.onNodeWithTag("rear_screenshot_entry").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("screenshot_back").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("screenshot_back").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("screenshot_back").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("rear_screenshot_entry").assertIsDisplayed() }.isSuccess }
        compose.waitForIdle(); Thread.sleep(700)
        val inst = InstrumentationRegistry.getInstrumentation()
        val bitmap = inst.uiAutomation.takeScreenshot()
        FileOutputStream(File(inst.targetContext.filesDir, "screenshot-ui-home.png")).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
