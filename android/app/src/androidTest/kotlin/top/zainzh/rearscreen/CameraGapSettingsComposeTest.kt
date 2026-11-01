package top.zainzh.rearscreen

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class CameraGapSettingsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun modeVisibilityPreferenceRecreationAndUnsupportedDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val orientation = InstrumentationRegistry.getArguments().getString("orientation", "")
        if (orientation == "portrait") instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
        if (orientation == "landscape") instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
        compose.waitForIdle()
        val activity = compose.activity
        val mode = RearCastMode.preferences(activity)
        val prefs = RearCameraGapPreferences.preferences(activity)
        val hadMode = mode.contains(RearCastMode.VIRTUAL)
        val oldMode = RearCastMode.useVirtual(activity)
        val hadBattery = prefs.contains(RearCameraGapPreferences.ENABLED)
        val oldBattery = RearCameraGapPreferences.enabled(activity)
        val hadStatus = prefs.contains(RearCameraGapPreferences.STATUS_ENABLED)
        val oldStatus = RearCameraGapPreferences.statusEnabled(activity)
        val titles = listOf(activity.getString(R.string.camera_gap_battery_title), activity.getString(R.string.camera_status_title))
        var title = titles.first()
        fun entry() = compose.onNode(hasText(title) and isToggleable())
        fun reveal() {
            compose.onNodeWithTag("home_list").performScrollToNode(hasText(title))
            compose.waitForIdle()
            val navigationTop = compose.onNodeWithContentDescription(NativeStrings.get(compose.activity, "screen_title"))
                .fetchSemanticsNode().boundsInRoot.top
            val bottom = entry().fetchSemanticsNode().boundsInRoot.bottom
            if (bottom > navigationTop - 16f) {
                compose.onNodeWithTag("home_list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
                    it(0f, bottom - navigationTop + 16f)
                }
                compose.waitForIdle()
            }
            assertTrue("Battery setting must be fully reachable above navigation",
                entry().fetchSemanticsNode().boundsInRoot.bottom <= navigationTop - 8f)
        }
        try {
            compose.runOnIdle {
                RearCastMode.setVirtual(activity, true)
                RearCameraGapPreferences.setEnabled(activity, false)
                RearCameraGapPreferences.setStatusEnabled(activity, false)
            }
            compose.waitForIdle(); reveal()
            entry().assertIsDisplayed().assertIsOff()
            if (RearCameraGapGeometry.supported(android.os.Build.DEVICE) == null) {
                entry().assertIsNotEnabled()
                compose.onNodeWithText(activity.getString(R.string.camera_gap_battery_unavailable), substring = true).assertExists()
            }
            title = titles.last(); reveal()
            entry().assertIsDisplayed().assertIsOff()
            if (!RearCameraStatusGeometry.supported(RearCameraGapGeometry.supported(android.os.Build.DEVICE))) entry().assertIsNotEnabled()
            // A saved choice must survive hiding the group and recreating the host, including on an unsupported device.
            compose.runOnIdle {
                RearCameraGapPreferences.setEnabled(activity, true)
                RearCameraGapPreferences.setStatusEnabled(activity, true)
                RearCastMode.setVirtual(activity, false)
            }
            compose.waitForIdle(); entry().assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            compose.runOnIdle { RearCastMode.setVirtual(compose.activity, true) }
            compose.waitForIdle(); reveal(); entry().assertIsOn()
            compose.runOnIdle { assertTrue(RearCameraGapPreferences.enabled(compose.activity)) }
            compose.runOnIdle { assertTrue(RearCameraGapPreferences.statusEnabled(compose.activity)) }
            val inst = InstrumentationRegistry.getInstrumentation()
            val suffix = InstrumentationRegistry.getArguments().getString("snapshot", "default")!!.replace(Regex("[^A-Za-z0-9_-]"), "")
            Thread.sleep(350)
            val bitmap = inst.uiAutomation.takeScreenshot()
            inst.targetContext.openFileOutput("camera-gap-settings-$suffix.png", 0).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        } finally {
            compose.runOnIdle {
                mode.edit().apply { if (hadMode) putBoolean(RearCastMode.VIRTUAL, oldMode) else remove(RearCastMode.VIRTUAL) }.commit()
                prefs.edit().apply { if (hadBattery) putBoolean(RearCameraGapPreferences.ENABLED, oldBattery) else remove(RearCameraGapPreferences.ENABLED) }.commit()
                prefs.edit().apply { if (hadStatus) putBoolean(RearCameraGapPreferences.STATUS_ENABLED, oldStatus) else remove(RearCameraGapPreferences.STATUS_ENABLED) }.commit()
            }
            if (orientation.isNotEmpty()) instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }
}
