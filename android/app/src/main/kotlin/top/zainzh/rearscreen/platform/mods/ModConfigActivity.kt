package top.zainzh.rearscreen.platform.mods

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.Closeable
import top.yukonga.miuix.kmp.basic.Text
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.miuixContent

/** Settings scene Activity for a single installed, trusted Mod. */
class ModConfigActivity : ComponentActivity() {
    private var modId: String = ""
    private var packageInfo by mutableStateOf<VerifiedModPackage?>(null)
    private var model by mutableStateOf<ModPersistedModel?>(null)
    private var error by mutableStateOf("")
    private var hostData by mutableStateOf(JSONObject())
    private var dataSubscription: Closeable? = null
    private val hostDataEpoch = ModDataSubscriptionEpoch()
    private val core by lazy { top.zainzh.rearscreen.platform.RearCapabilityCore.get(this) }
    private val broker by lazy { ModBrokerProvider.get(this) }
    private val brokerListener = object : ModBrokerListener {
        override fun onReady(modId: String, model: ModPersistedModel) { this@ModConfigActivity.model = model }
        override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { this@ModConfigActivity.model = model }
        override fun onError(modId: String, code: String, message: String) {
            if (code in setOf("E_PACKAGE_REPLACED", "E_MOD_REVOKED", "E_MOD_UNINSTALLED")) finish()
            else error = "$code：$message"
        }
        override fun onEffectRequested(modId: String, eventId: String) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modId = intent.getStringExtra("mod_id").orEmpty()
        val installed = ModPackageStore.installed(this, modId)
        if (installed == null || !installed.enabled || !ModPackageStore.isAuthorTrusted(this, installed.authorFingerprint)) { finish(); return }
        packageInfo = runCatching { ModPackageStore.find(this, modId) }.getOrNull()
        model = ModPackageStore.model(this, modId)
        val verified = packageInfo ?: run { finish(); return }
        broker.open(modId, mainExecutor, brokerListener)
        miuixContent {
            val active = packageInfo
            val current = model
            if (active == null || current == null) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text(if (error.isEmpty()) "正在加载设置…" else error, color = AppPalette.secondary()) }
            } else {
                ModNativeScene(active, active.settingsScene, current.state, current.config, hostData, false,
                    onBack = ::finish,
                    onEvent = { broker.submitEvent(active.id, it) },
                    onSaveConfig = { config, done -> broker.saveConfig(active.id, config, mainExecutor) { saved, reason ->
                        if (saved) model = ModPackageStore.model(this@ModConfigActivity, active.id)
                        done(saved, reason)
                    } },
                    notice = error.ifBlank { null })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startHostData()
    }

    override fun onPause() {
        stopHostData()
        super.onPause()
    }

    override fun onDestroy() {
        if (modId.isNotEmpty()) broker.closeObserver(modId, brokerListener)
        super.onDestroy()
    }

    private fun startHostData() {
        dataSubscription?.close()
        dataSubscription = null
        val generation = hostDataEpoch.start()
        hostData = JSONObject()
        val active = packageInfo ?: return
        val fields = ModHostDataSnapshot.fieldsFor(active)
        if (fields.isEmpty()) return
        val owner = core.modDataOwner(modId) ?: return
        dataSubscription = ModHostDataSnapshot.observe(core, owner, fields) { snapshot ->
            runOnUiThread { if (hostDataEpoch.isCurrent(generation) && !isFinishing && !isDestroyed) hostData = snapshot }
        }
    }

    private fun stopHostData() {
        hostDataEpoch.stop()
        dataSubscription?.close()
        dataSubscription = null
        hostData = JSONObject()
    }
}
