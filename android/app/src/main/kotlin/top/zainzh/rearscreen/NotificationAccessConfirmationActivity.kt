package top.zainzh.rearscreen

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService

/** Per-publisher user consent, independent from the notification-listener app selection. */
class NotificationAccessConfirmationActivity : ComponentActivity() {
    private var nonce: String? = null
    private var consentFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonce = intent.getStringExtra(RemoteRearPlatformService.EXTRA_NOTIFICATION_CONSENT_NONCE)
        val details = nonce?.let(RemoteRearPlatformService::notificationConsentDetails)
        if (details == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWithoutChange()
        })
        miuixContent {
            NotificationPublisherConsentScreen(details.first, details.second, ::finishWithoutChange, ::complete)
        }
    }

    private fun finishWithoutChange() {
        if (consentFinished) return
        consentFinished = true
        nonce?.let { RemoteRearPlatformService.completeNotificationConsent(it, null) }
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun complete(allowed: Boolean) {
        if (consentFinished) return
        consentFinished = true
        nonce?.let { RemoteRearPlatformService.completeNotificationConsent(it, allowed) }
        setResult(if (allowed) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}

/** Shared by the authenticated Activity and an isolated UI fixture. */
@Composable
internal fun NotificationPublisherConsentScreen(
    publisherLabel: String,
    initiallyAllowed: Boolean,
    onCancel: () -> Unit,
    onSave: (Boolean) -> Unit,
) {
    var allowed by rememberSaveable { mutableStateOf(initiallyAllowed) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)
            .testTag("notification_consent_scroll"),
        verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.Start
    ) {
        Text(stringResource(R.string.notification_publisher_consent_title), fontSize = 22.sp, color = AppPalette.text())
        Text(stringResource(R.string.notification_publisher_consent_intro, publisherLabel), color = AppPalette.secondary())
        AppCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.notification_publisher_consent_access))
                    Text(stringResource(R.string.notification_publisher_consent_detail), color = AppPalette.secondary())
                }
                Switch(allowed, onCheckedChange = { allowed = it })
            }
        }
        Text(stringResource(R.string.notification_publisher_consent_footer), color = AppPalette.secondary())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(stringResource(R.string.scene_activation_deny), onClick = onCancel, modifier = Modifier.weight(1f).testTag("notification_consent_cancel"))
            TextButton(stringResource(R.string.notification_publisher_consent_save), onClick = { onSave(allowed) }, modifier = Modifier.weight(1f).testTag("notification_consent_save"))
        }
    }
}
