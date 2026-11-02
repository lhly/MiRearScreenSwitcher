package top.zainzh.rearscreen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
class CameraTemplateSettingsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun knownDevicesAreEnabledWithAccurateValidationLabelsAndUnknownDeviceStaysDisabled() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val orientation = InstrumentationRegistry.getArguments().getString("orientation", "")
        if (orientation.isNotEmpty()) {
            inst.uiAutomation.setRotation(if (orientation == "landscape") android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0)
            compose.waitUntil(5_000) {
                val landscape = compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                landscape == (orientation == "landscape")
            }
        }
        val context = compose.activity
        val preferences = RearCameraGapPreferences.preferences(context)
        val keys = listOf(RearCameraGapPreferences.ENABLED, RearCameraGapPreferences.STATUS_ENABLED)
        val saved = keys.associateWith { preferences.all[it] }
        val device = mutableStateOf("pandora")
        val titles = listOf(context.getString(R.string.camera_gap_battery_title), context.getString(R.string.camera_status_title))
        val note = context.getString(R.string.camera_area_template_unverified)
        try {
            compose.runOnIdle {
                preferences.edit().putBoolean(keys[0], false).putBoolean(keys[1], false).commit()
                context.setContentView(ComposeView(context).apply {
                    setContent { AppTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            AppCard {
                                RearCameraGapBatteryToggle(true, device.value)
                                AppDivider()
                                RearCameraStatusToggle(true, device.value)
                            }
                        }
                    } }
                })
            }
            for (model in listOf("pandora", "popsicle", "hongkong", "madrid", "unknown")) {
                compose.runOnIdle { device.value = model }
                compose.waitForIdle()
                for (title in titles) {
                    val row = compose.onNode(hasText(title) and isToggleable()).performScrollTo()
                    if (model == "unknown") row.assertIsNotEnabled() else row.assertIsEnabled()
                }
                compose.onAllNodesWithText(note, substring = true).assertCountEquals(if (model in listOf("popsicle", "unknown")) 0 else 2)
                if (model == "pandora") {
                    titles.forEach { compose.onNode(hasText(it) and isToggleable()).performScrollTo().performClick().assertIsOn() }
                    compose.runOnIdle { assertTrue(RearCameraGapPreferences.enabled(context)); assertTrue(RearCameraGapPreferences.statusEnabled(context)) }
                }
                val suffix = InstrumentationRegistry.getArguments().getString("snapshot", "default")!!.replace(Regex("[^A-Za-z0-9_-]"), "")
                val bitmap = inst.uiAutomation.takeScreenshot()
                if (orientation.isNotEmpty()) assertEquals("Screenshot must reflect the requested orientation", orientation == "landscape", bitmap.width > bitmap.height)
                context.openFileOutput("camera-template-settings-$model-$suffix.png", 0).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            // Changing the selected layout must not clear either existing user preference.
            compose.runOnIdle { assertTrue(RearCameraGapPreferences.enabled(context)); assertTrue(RearCameraGapPreferences.statusEnabled(context)) }
        } finally {
            compose.runOnIdle {
                preferences.edit().apply {
                    saved.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) else remove(key) }
                }.commit()
            }
            if (orientation.isNotEmpty()) inst.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }
}
