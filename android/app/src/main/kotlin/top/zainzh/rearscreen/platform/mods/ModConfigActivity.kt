package top.zainzh.rearscreen.platform.mods

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
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
import top.zainzh.rearscreen.platform.actions.RegisteredActionRef
import top.zainzh.rearscreen.platform.actions.RegisteredActionsActivity

internal data class RestoredModConfigPickerState(
    val pendingBinding: String?, val pendingModId: String?, val drafts: Map<String, RegisteredActionRef>
)

/** Bundle-only picker continuation: safe across Activity recreation without retaining lambdas. */
internal object ModConfigPickerState {
    fun write(bundle: Bundle, pendingBinding: String?, pendingModId: String?, drafts: Map<String, RegisteredActionRef>) {
        bundle.putString("registeredAction.pendingBinding", pendingBinding)
        bundle.putString("registeredAction.pendingModId", pendingModId)
        bundle.putString("registeredAction.drafts", JSONObject().apply {
            drafts.forEach { (binding, ref) -> put(binding, JSONObject().put("id", ref.id).put("version", ref.version)) }
        }.toString())
    }
    fun read(bundle: Bundle?): RestoredModConfigPickerState {
        val drafts = runCatching {
            val json = JSONObject(bundle?.getString("registeredAction.drafts") ?: "{}")
            buildMap { json.keys().forEach { key -> json.optJSONObject(key)?.let { put(key, RegisteredActionRef(it.getString("id"), it.getInt("version"))) } } }
        }.getOrDefault(emptyMap())
        return RestoredModConfigPickerState(bundle?.getString("registeredAction.pendingBinding"),
            bundle?.getString("registeredAction.pendingModId"), drafts)
    }
}

/** Settings scene Activity for a single installed, trusted Mod. */
class ModConfigActivity : ComponentActivity() {
    private var modId: String = ""
    private var packageInfo by mutableStateOf<VerifiedModPackage?>(null)
    private var model by mutableStateOf<ModPersistedModel?>(null)
    private var error by mutableStateOf("")
    private var sceneVisible by mutableStateOf(false)
    private var hostData by mutableStateOf(JSONObject())
    private var dataSubscription: Closeable? = null
    private val hostDataEpoch = ModDataSubscriptionEpoch()
    private val core by lazy { top.zainzh.rearscreen.platform.RearCapabilityCore.get(this) }
    private val broker by lazy { ModBrokerProvider.get(this) }
    private var pendingActionBinding: String? = null
    private var pendingActionModId: String? = null
    private var registeredActionDrafts by mutableStateOf<Map<String, RegisteredActionRef>>(emptyMap())
    private val actionPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val id = data?.getStringExtra(RegisteredActionsActivity.EXTRA_SELECTED_REF_ID)
        val version = data?.getIntExtra(RegisteredActionsActivity.EXTRA_SELECTED_REF_VERSION, 0) ?: 0
        val ref = if (result.resultCode == RESULT_OK && !id.isNullOrBlank() && version == 1) RegisteredActionRef(id, version) else null
        val binding = pendingActionBinding
        val expectedModId = pendingActionModId
        pendingActionBinding = null
        pendingActionModId = null
        if (ref != null && binding != null && expectedModId == modId && packageInfo?.id == modId) {
            registeredActionDrafts = registeredActionDrafts + (binding to ref)
            error = "动作已选择；点击保存设置后生效"
        } else if (result.resultCode == RESULT_OK) error = "动作选择已取消或设置场景已变化"
    }
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
        val restoredPickerState = ModConfigPickerState.read(savedInstanceState)
        pendingActionBinding = restoredPickerState.pendingBinding
        pendingActionModId = restoredPickerState.pendingModId
        registeredActionDrafts = restoredPickerState.drafts
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
                    onEvent = { if (!broker.submitUserEvent(active.id, it)) error = "操作暂未提交：输入队列已满或设置页正在切换，请稍后重试" },
                    visible = sceneVisible,
                    onRegisteredAction = ::invokeRegisteredAction,
                    registeredActionDrafts = registeredActionDrafts,
                    onSelectRegisteredAction = ::selectRegisteredAction,
                    onClearRegisteredActionDrafts = { registeredActionDrafts = emptyMap() },
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
        updateSceneVisibility()
        broker.setObserverVisible(modId, brokerListener, sceneVisible)
        startHostData()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        updateSceneVisibility()
    }

    override fun onPause() {
        sceneVisible = false
        if (modId.isNotEmpty()) broker.setObserverVisible(modId, brokerListener, false)
        stopHostData()
        super.onPause()
    }

    private fun updateSceneVisibility() {
        val decor = window.decorView
        sceneVisible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            decor.isAttachedToWindow && decor.isShown && decor.windowVisibility == View.VISIBLE && window.decorView.hasWindowFocus()
        if (modId.isNotEmpty()) broker.setObserverVisible(modId, brokerListener, sceneVisible)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        ModConfigPickerState.write(outState, pendingActionBinding, pendingActionModId, registeredActionDrafts)
        super.onSaveInstanceState(outState)
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

    private fun selectRegisteredAction(binding: String, currentRef: RegisteredActionRef?) {
        val active = packageInfo ?: return
        val declared = active.requiredCapabilities.any { it.name == "action.invoke" } || active.optionalCapabilities.any { it.name == "action.invoke" }
        if (!declared) { error = "此 Mod 未声明已登记动作能力"; return }
        val installed = ModPackageStore.installed(this, modId)
        if (installed == null || !installed.enabled || installed.authorFingerprint != active.authorFingerprint) {
            error = "此 Mod 已停用或作者信任已撤销"; return
        }
        if ("action.invoke" !in installed.grantedCapabilities) { error = "此 Mod 未获已登记动作授权"; return }
        val owner = core.modDataOwner(modId) ?: run { error = "此 Mod 当前不可用"; return }
        pendingActionBinding = binding
        pendingActionModId = modId
        runCatching { actionPicker.launch(RegisteredActionsActivity.createIntent(this, owner, selectAction = true)) }
            .onFailure { pendingActionBinding = null; pendingActionModId = null; error = it.message ?: "无法打开已登记动作选择器" }
    }

    /** Settings-scene action buttons are real native clicks, dispatched locally through Core. */
    private fun invokeRegisteredAction(ref: RegisteredActionRef) {
        val active = packageInfo ?: return
        if (!sceneVisible || isFinishing || isDestroyed) return
        val installed = ModPackageStore.installed(this, active.id)
        val declared = active.requiredCapabilities.any { it.name == "action.invoke" } || active.optionalCapabilities.any { it.name == "action.invoke" }
        val owner = core.modDataOwner(active.id)
        if (!declared || installed == null || !installed.enabled || installed.authorFingerprint != active.authorFingerprint ||
            "action.invoke" !in installed.grantedCapabilities || owner == null) {
            error = "此 Mod 当前未获已登记动作授权"
            return
        }
        val activation = runCatching { core.issueRegisteredActionActivationAfterHostClick(owner, ref) }
            .getOrElse { error = "无法确认此快捷操作，请重新点击"; return }
        Thread {
            val result = core.invokeRegisteredAction(owner, ref, activation)
            runOnUiThread {
                if (!sceneVisible || isFinishing || isDestroyed) return@runOnUiThread
                error = when (result) {
                    top.zainzh.rearscreen.platform.actions.ActionDispatchResult.Accepted -> ""
                    is top.zainzh.rearscreen.platform.actions.ActionDispatchResult.Rejected -> "无法执行快捷操作：${result.error}"
                }
            }
        }.start()
    }

}
