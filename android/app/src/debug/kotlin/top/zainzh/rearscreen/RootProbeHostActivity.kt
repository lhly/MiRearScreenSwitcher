package top.zainzh.rearscreen

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text

/** Keeps only an explicit instrumentation run visible; never included in release. */
class RootProbeHostActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        miuixContent {
            PageFrame("Root 后端验证", ::finish, "root_probe_back") { modifier, inset ->
                Column(modifier.padding(20.dp, inset + 12.dp, 20.dp, 24.dp)) {
                    AppCard { AppRow("正在进行设备验证", "验证结束后将自动退出。系统锁屏保持原状。") }
                }
            }
        }
    }
}
