package top.zainzh.rearscreen

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.FileOutputStream

/** Uses the exact production page with a preview-only device catalog on emulators. */
class RearScreenshotComposeTest {
    @get:Rule val compose = createAndroidComposeRule<FixtureRearScreenshotActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var saved: Map<String, *> = emptyMap<String, Any?>()
    private var night = "auto"
    private var font = "1.0"
    private var rotation = "0"
    private var animation = "1.0"
    private var snapshotted = false
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8).trim() }
    @Before fun snapshot() {
        saved = RearScreenshotPreferences.preferences(compose.activity).all.toMap()
        val mode = shell("cmd uimode night")
        night = if (mode.contains("auto")) "auto" else if (mode.contains("yes")) "yes" else "no"
        font = shell("settings get system font_scale")
        rotation = shell("settings get system user_rotation")
        animation = shell("settings get global animator_duration_scale")
        snapshotted = true
    }
    @After fun restore() {
        if (!snapshotted) return
        val context = instrumentation.targetContext
        val editor = RearScreenshotPreferences.preferences(context).edit().clear()
        saved.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
        } }
        editor.commit()
        shell("wm user-rotation -d 0 lock ${rotation.toIntOrNull() ?: 0}")
        shell(if (font == "null") "settings delete system font_scale" else "settings put system font_scale $font")
        shell(if (animation == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $animation")
        shell("cmd uimode night $night")
    }
    private fun enable() {
        val label = compose.activity.getString(R.string.rear_screenshot_shell)
        if (!RearScreenshotPreferences.read(compose.activity).shell) compose.onNodeWithText(label).performClick()
        compose.onNode(hasText(label) and isToggleable()).assertIsOn()
    }
    private fun preview() {
        compose.onNodeWithTag("screenshot_scroll").performScrollToNode(hasText(compose.activity.getString(R.string.rear_screenshot_preview)))
        compose.waitUntil(45000) { compose.onAllNodesWithTag("screenshot_preview").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun shot(name: String) {
        compose.waitForIdle(); Thread.sleep(500)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        FileOutputStream(File(instrumentation.targetContext.filesDir, "screenshot-ui-$name.png")).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun settingsChoicesPreviewAndRecreationPersistTogether() {
        enable()
        compose.onNodeWithTag("screenshot_style").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.rear_screenshot_half)).performClick()
        compose.onNodeWithTag("screenshot_color").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.rear_screenshot_green)).performClick()
        val original = compose.activity.getString(R.string.rear_screenshot_keep_original)
        if (!RearScreenshotPreferences.read(compose.activity).keepOriginal) compose.onNodeWithText(original).performClick()
        preview()
        val selected = RearScreenshotPreferences.read(compose.activity)
        assertEquals("half", selected.style); assertEquals("LG", selected.color); assertTrue(selected.keepOriginal)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("screenshot_scroll").performScrollToNode(hasTestTag("screenshot_style"))
        compose.onNodeWithTag("screenshot_style").assertTextContains(compose.activity.getString(R.string.rear_screenshot_half), substring = true)
        compose.onNodeWithTag("screenshot_color").assertTextContains(compose.activity.getString(R.string.rear_screenshot_green), substring = true)
        preview()
        compose.onNodeWithTag("screenshot_scroll").performScrollToNode(hasTestTag("screenshot_preview"))
        shot("choices")
    }
    @Test fun lightDarkLargeTextLandscapeAndReducedMotionStayUsable() {
        enable(); preview()
        shell("cmd uimode night no")
        compose.waitUntil(10000) { compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO }
        shot("light")
        shell("settings put system font_scale 1.5")
        compose.waitUntil(10000) { compose.activity.resources.configuration.fontScale > 1.4f }
        preview(); shot("light-large")
        shell("cmd uimode night yes")
        compose.waitUntil(10000) { compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
        preview(); shot("dark-large")
        shell("settings put global animator_duration_scale 0")
        shell("wm user-rotation -d 0 lock 1")
        compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.onNodeWithTag("screenshot_scroll").performScrollToNode(hasTestTag("screenshot_style"))
        compose.onNodeWithTag("screenshot_style").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.rear_screenshot_half)).assertIsDisplayed()
        shot("landscape-dialog")
        compose.onNodeWithText(compose.activity.getString(R.string.rear_screenshot_half)).performClick()
        preview()
        compose.onNodeWithTag("screenshot_scroll").performScrollToNode(hasTestTag("screenshot_preview"))
        shot("landscape-preview")
    }
}
