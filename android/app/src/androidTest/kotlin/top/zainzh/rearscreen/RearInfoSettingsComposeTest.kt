package top.zainzh.rearscreen

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class RearInfoSettingsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun accessibleEntryHelpAndReturnKeepHomePosition() {
        compose.waitForIdle(); Thread.sleep(700); compose.waitForIdle()
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("rear_info_entry"))
        Thread.sleep(300)
        compose.onNodeWithTag("rear_info_entry").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("rear_info_settings_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rear_info_settings_list").performScrollToNode(hasTestTag("rear_info_open"))
        compose.onNodeWithTag("rear_info_open").assertIsDisplayed()
        compose.onNodeWithTag("rear_info_back").assertIsDisplayed()
        val inst = InstrumentationRegistry.getInstrumentation()
        val suffix = InstrumentationRegistry.getArguments().getString("snapshot", "default")!!.replace(Regex("[^A-Za-z0-9_-]"), "")
        compose.waitForIdle()
        Thread.sleep(700) // Semantics can be ready before the window transition reaches SurfaceFlinger.
        compose.waitForIdle()
        val image = inst.uiAutomation.takeScreenshot()
        inst.targetContext.openFileOutput("rear-info-settings-$suffix.png", 0).use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        compose.onNodeWithTag("rear_info_back").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("rear_info_back").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("rear_info_entry").assertIsDisplayed()
    }
}
