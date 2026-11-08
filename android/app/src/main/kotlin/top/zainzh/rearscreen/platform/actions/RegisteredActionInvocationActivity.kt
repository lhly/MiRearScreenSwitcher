package top.zainzh.rearscreen.platform.actions

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.zainzh.rearscreen.AppCard
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.AppPageHeader
import top.zainzh.rearscreen.miuixContent
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService

/** User-confirmed invocation route for SDK clients; the PendingIntent contains only a host nonce. */
class RegisteredActionInvocationActivity : ComponentActivity() {
    private var nonce: String? = null
    private var completed = false
    private val prompt by lazy { nonce?.let(RemoteRearPlatformService::actionInvocationPrompt) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonce = intent.getStringExtra(RemoteRearPlatformService.EXTRA_ACTION_INVOCATION_NONCE)
        val value = prompt
        if (value == null) { finish(); return }
        miuixContent {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
                AppPageHeader("调用快捷操作", ::cancel, "registered_action_invocation_back", Modifier.fillMaxWidth())
                AppCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${value.appLabel} 请求调用你为它登记的操作：${value.actionLabel}", color = AppPalette.text())
                        Text("目标：${value.targetLabel}。每次调用都需要你在此确认。", color = AppPalette.secondary())
                        TextButton("取消", ::cancel, Modifier.fillMaxWidth(), minHeight = 48.dp)
                        TextButton("确认调用", ::confirm, Modifier.fillMaxWidth(), minHeight = 48.dp)
                    }
                }
            }
        }
    }

    private fun confirm() {
        if (completed) return
        completed = true
        nonce?.let { RemoteRearPlatformService.completeActionInvocation(it, true) }
        finish()
    }

    private fun cancel() {
        if (completed) { finish(); return }
        completed = true
        nonce?.let { RemoteRearPlatformService.completeActionInvocation(it, false) }
        finish()
    }

    override fun onDestroy() {
        if (!completed) nonce?.let { RemoteRearPlatformService.completeActionInvocation(it, false) }
        super.onDestroy()
    }
}
