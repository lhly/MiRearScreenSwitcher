package top.zainzh.rearscreen

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Preview-only model selection exercises the exact production page and preference reader. */
class RearShellSettingsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<FixtureRearScreenshotActivity>()
    private fun label(color: String) = compose.activity.getString(when (color) {
        "BK" -> R.string.rear_screenshot_black; "WH" -> R.string.rear_screenshot_white
        "PU" -> R.string.rear_screenshot_purple; "LG" -> R.string.rear_screenshot_green
        "PK" -> R.string.rear_screenshot_pink; "DB" -> R.string.rear_screenshot_deep_blue
        "RD" -> R.string.rear_screenshot_red; else -> R.string.rear_screenshot_transparent_red
    })
    private fun preview() {
        compose.onNodeWithTag("screenshot_scroll").performScrollToNode(hasText(compose.activity.getString(R.string.rear_screenshot_preview)))
        compose.waitUntil(45_000) { compose.onAllNodesWithTag("screenshot_preview").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun perModelPaletteRestoredColorPreviewAndRecreationStayConsistent() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val orientation = InstrumentationRegistry.getArguments().getString("orientation", "")
        if (orientation.isNotEmpty()) {
            inst.uiAutomation.setRotation(if (orientation == "landscape") android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0)
            compose.waitUntil(5_000) {
                (compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) == (orientation == "landscape")
            }
        }
        val prefs = RearScreenshotPreferences.preferences(compose.activity)
        val saved = prefs.all.toMap()
        try {
            for (device in listOf("pandora", "popsicle", "hongkong", "madrid", "unknown")) {
                compose.runOnIdle {
                    prefs.edit().putBoolean("shell", false).putBoolean("keep_original", true)
                        .putString("style", "full").putString("color", "LG").commit()
                    compose.activity.intent.putExtra("template_device", device)
                }
                compose.activityRule.scenario.recreate()
                val switchTitle = compose.activity.getString(R.string.rear_screenshot_shell)
                compose.onNodeWithTag("screenshot_scroll").performScrollToNode(hasText(switchTitle))
                val toggle = compose.onNode(hasText(switchTitle) and isToggleable())
                if (device == "unknown") {
                    toggle.assertIsNotEnabled()
                    compose.onNodeWithTag("screenshot_color").assertDoesNotExist()
                    assertEquals("LG", prefs.getString("color", null))
                    continue
                }
                toggle.assertIsEnabled().performClick()
                val palette = RearShellCatalog.colors(device)
                val restored = if (device in listOf("hongkong", "madrid")) "BK" else "LG"
                compose.onNodeWithTag("screenshot_scroll").performScrollToNode(hasTestTag("screenshot_color"))
                compose.onNodeWithTag("screenshot_color").assertTextContains(label(restored), substring = true).performClick()
                for (color in listOf("BK", "WH", "PU", "LG", "PK", "DB", "RD", "CY")) {
                    val nodes = compose.onAllNodesWithText(label(color))
                    if (color in palette) assertTrue("Missing $device/$color", nodes.fetchSemanticsNodes().isNotEmpty())
                    else nodes.assertCountEquals(0)
                }
                val selected = if (device in listOf("hongkong", "madrid")) "CY" else "WH"
                compose.onNodeWithText(label(selected)).performScrollTo().performClick()
                compose.onNodeWithTag("screenshot_scroll").performScrollToNode(hasTestTag("screenshot_style"))
                compose.onNodeWithTag("screenshot_style").performClick()
                compose.onNodeWithText(compose.activity.getString(R.string.rear_screenshot_half)).performClick()
                preview()
                compose.activityRule.scenario.recreate()
                preview()
                val options = RearScreenshotPreferences.readForDevice(compose.activity, device)
                assertEquals(selected, options.color); assertEquals("half", options.style); assertTrue(options.keepOriginal)
                compose.onNodeWithTag("screenshot_scroll").performScrollToNode(hasTestTag("screenshot_preview"))
                val bitmap = inst.uiAutomation.takeScreenshot()
                if (orientation.isNotEmpty()) assertEquals(orientation == "landscape", bitmap.width > bitmap.height)
                val suffix = InstrumentationRegistry.getArguments().getString("snapshot", "default")!!.replace(Regex("[^A-Za-z0-9_-]"), "")
                compose.activity.openFileOutput("shell-ui-$device-$suffix.png", 0).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            compose.runOnIdle {
                prefs.edit().clear().apply {
                    saved.forEach { (key, value) -> when (value) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                    } }
                }.commit()
            }
            if (orientation.isNotEmpty()) inst.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }
}
