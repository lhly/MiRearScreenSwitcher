package top.zainzh.rearscreen

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Composable internal fun RearCameraGapBatteryToggle(virtual: Boolean) {
    val context = LocalContext.current
    val preferences = remember(context) { RearCameraGapPreferences.preferences(context) }
    var enabled by remember(context) { mutableStateOf(RearCameraGapPreferences.enabled(context)) }
    val supported = remember { Build.VERSION.SDK_INT >= 33 && RearCameraGapGeometry.supported(Build.DEVICE) != null }
    DisposableEffect(preferences) {
        val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == RearCameraGapPreferences.ENABLED) enabled = RearCameraGapPreferences.enabled(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(changed)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(changed) }
    }
    AppToggle(stringResource(R.string.camera_gap_battery_title),
        stringResource(if (supported) R.string.camera_gap_battery_summary else R.string.camera_gap_battery_unavailable),
        checked = enabled, enabled = supported && virtual, onChange = { RearCameraGapPreferences.setEnabled(context, it) })
}

@Composable internal fun RearCameraStatusToggle(virtual: Boolean) {
    val context = LocalContext.current
    val preferences = remember(context) { RearCameraGapPreferences.preferences(context) }
    var enabled by remember(context) { mutableStateOf(RearCameraGapPreferences.statusEnabled(context)) }
    val supported = remember { Build.VERSION.SDK_INT >= 33 && RearCameraStatusGeometry.supported(RearCameraGapGeometry.supported(Build.DEVICE)) }
    DisposableEffect(preferences) {
        val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == RearCameraGapPreferences.STATUS_ENABLED) enabled = RearCameraGapPreferences.statusEnabled(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(changed)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(changed) }
    }
    AppToggle(stringResource(R.string.camera_status_title),
        stringResource(if (supported) R.string.camera_status_summary else R.string.camera_status_unavailable),
        checked = enabled, enabled = supported && virtual, onChange = { RearCameraGapPreferences.setStatusEnabled(context, it) })
}
