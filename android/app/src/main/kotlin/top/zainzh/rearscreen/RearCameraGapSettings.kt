package top.zainzh.rearscreen

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Composable private fun cameraAreaSummary(resource: Int, profile: RearCameraGapGeometry.Profile?): String {
    val summary = stringResource(resource)
    return if (profile != null && !profile.verified)
        summary + "\n" + stringResource(R.string.camera_area_template_unverified)
    else summary
}

@Composable internal fun RearCameraGapBatteryToggle(virtual: Boolean, device: String = Build.DEVICE) {
    val context = LocalContext.current
    val preferences = remember(context) { RearCameraGapPreferences.preferences(context) }
    var enabled by remember(context) { mutableStateOf(RearCameraGapPreferences.enabled(context)) }
    val profile = remember(device) { RearCameraGapGeometry.supported(device) }
    val supported = Build.VERSION.SDK_INT >= 33 && profile != null
    DisposableEffect(preferences) {
        val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == RearCameraGapPreferences.ENABLED) enabled = RearCameraGapPreferences.enabled(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(changed)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(changed) }
    }
    AppToggle(stringResource(R.string.camera_gap_battery_title),
        cameraAreaSummary(if (supported) R.string.camera_gap_battery_summary else R.string.camera_gap_battery_unavailable,
            if (supported) profile else null),
        checked = enabled, enabled = supported && virtual, onChange = { RearCameraGapPreferences.setEnabled(context, it) })
}

@Composable internal fun RearCameraStatusToggle(virtual: Boolean, device: String = Build.DEVICE) {
    val context = LocalContext.current
    val preferences = remember(context) { RearCameraGapPreferences.preferences(context) }
    var enabled by remember(context) { mutableStateOf(RearCameraGapPreferences.statusEnabled(context)) }
    val profile = remember(device) { RearCameraGapGeometry.supported(device) }
    val supported = Build.VERSION.SDK_INT >= 33 && RearCameraStatusGeometry.supported(profile)
    DisposableEffect(preferences) {
        val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == RearCameraGapPreferences.STATUS_ENABLED) enabled = RearCameraGapPreferences.statusEnabled(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(changed)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(changed) }
    }
    AppToggle(stringResource(R.string.camera_status_title),
        cameraAreaSummary(if (supported) R.string.camera_status_summary else R.string.camera_status_unavailable,
            if (supported) profile else null),
        checked = enabled, enabled = supported && virtual, onChange = { RearCameraGapPreferences.setStatusEnabled(context, it) })
}
