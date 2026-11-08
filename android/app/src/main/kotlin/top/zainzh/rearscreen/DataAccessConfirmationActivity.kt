package top.zainzh.rearscreen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.zainzh.rearscreen.platform.data.DataField
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService

/** Foreground, explicit per-publisher consent for optional read-only platform data. */
class DataAccessConfirmationActivity : ComponentActivity() {
    private var nonce: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonce = intent.getStringExtra(RemoteRearPlatformService.EXTRA_DATA_CONSENT_NONCE)
        val details = nonce?.let(RemoteRearPlatformService::dataConsentDetails)
        if (details == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val fields = details.second.toSet()
        val existing = details.third
        val canBattery = fields.any { it == DataField.BATTERY_LEVEL_PERCENT || it == DataField.BATTERY_CHARGING }
        val canMetrics = fields.any { it.name.startsWith("SYSTEM_") }
        miuixContent {
            var battery by rememberSaveable { mutableStateOf(existing.any { it == DataField.BATTERY_LEVEL_PERCENT || it == DataField.BATTERY_CHARGING }) }
            var metrics by rememberSaveable { mutableStateOf(existing.any { it.name.startsWith("SYSTEM_") }) }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.Start
            ) {
                Text(stringResource(R.string.data_consent_title), fontSize = 22.sp, color = AppPalette.text())
                Text(stringResource(R.string.data_consent_intro, details.first), color = AppPalette.secondary())
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (canBattery) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.data_consent_battery))
                                Text(stringResource(R.string.data_consent_battery_detail), color = AppPalette.secondary())
                            }
                            Switch(battery, onCheckedChange = { battery = it })
                        }
                        if (canMetrics) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.data_consent_metrics))
                                Text(stringResource(R.string.data_consent_metrics_detail), color = AppPalette.secondary())
                            }
                            Switch(metrics, onCheckedChange = { metrics = it })
                        }
                    }
                }
                Text(stringResource(R.string.data_consent_footer), color = AppPalette.secondary())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(stringResource(R.string.scene_activation_deny), onClick = { complete(null) }, modifier = Modifier.weight(1f))
                    TextButton(stringResource(R.string.data_consent_allow), onClick = {
                        val accepted = buildSet {
                            if (battery) addAll(fields.filter { it == DataField.BATTERY_LEVEL_PERCENT || it == DataField.BATTERY_CHARGING })
                            if (metrics) addAll(fields.filter { it.name.startsWith("SYSTEM_") })
                        }
                        complete(accepted)
                    }, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    private fun complete(fields: Set<DataField>?) {
        nonce?.let { RemoteRearPlatformService.completeDataConsent(it, fields) }
        setResult(if (fields?.isNotEmpty() == true) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
