package top.zainzh.rearscreen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService

/** Foreground, user-visible approval for one independently launched rear Activity. */
class SceneActivationConfirmationActivity : ComponentActivity() {
    private var nonce: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonce = intent.getStringExtra(RemoteRearPlatformService.EXTRA_ACTIVATION_NONCE)
        val details = nonce?.let(RemoteRearPlatformService::activationDetails)
        if (details == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        miuixContent {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(stringResource(R.string.scene_activation_title), fontSize = 22.sp, color = AppPalette.text())
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.scene_activation_request, details.first, details.second))
                        Text(stringResource(R.string.scene_activation_effect), color = AppPalette.secondary())
                        if (details.third) Text(stringResource(R.string.scene_activation_keep_visible), color = AppPalette.secondary())
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(stringResource(R.string.scene_activation_deny), onClick = { complete(false) }, modifier = Modifier.weight(1f))
                    TextButton(stringResource(R.string.scene_activation_allow), onClick = { complete(true) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    private fun complete(allow: Boolean) {
        nonce?.let { RemoteRearPlatformService.completeActivation(it, allow) }
        setResult(if (allow) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
