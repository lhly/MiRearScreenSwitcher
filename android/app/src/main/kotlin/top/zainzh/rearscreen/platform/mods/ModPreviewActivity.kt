package top.zainzh.rearscreen.platform.mods

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import top.zainzh.rearscreen.platform.RearCapabilityCore
import top.yukonga.miuix.kmp.basic.Text
import top.zainzh.rearscreen.AppCard
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.AppRow
import top.zainzh.rearscreen.AppSection
import top.zainzh.rearscreen.AppToggle
import top.zainzh.rearscreen.PageFrame
import top.zainzh.rearscreen.miuixContent

/** Same Compose/Miuix node renderer as the live Mod scene, without a display lease or reducer. */
class ModPreviewActivity : ComponentActivity() {
    private var pkg by mutableStateOf<VerifiedModPackage?>(null)
    private var initialModel by mutableStateOf<ModPersistedModel?>(null)
    private var failure by mutableStateOf("")
    private var hostData by mutableStateOf(JSONObject())
    private var dataSubscription: Closeable? = null
    private val hostDataEpoch = ModDataSubscriptionEpoch()
    private val core by lazy { RearCapabilityCore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modId = intent.getStringExtra("mod_id").orEmpty()
        lifecycleScope.launch {
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    val installed = ModPackageStore.installed(this@ModPreviewActivity, modId)
                        ?: error("找不到已导入的 Mod")
                    require(ModPackageStore.isAuthorTrusted(this@ModPreviewActivity, installed.authorFingerprint)) { "此作者尚未受信任" }
                    val verified = ModPackageStore.find(this@ModPreviewActivity, modId) ?: error("Mod 包无法读取")
                    val stored = ModPackageStore.model(this@ModPreviewActivity, modId)
                    verified to stored?.copy(
                        state = stored.state?.let { JSONObject(it.toString()) },
                        config = JSONObject(stored.config.toString())
                    )
                }
            }
            loaded.onSuccess { (verified, model) ->
                pkg = verified
                initialModel = model ?: ModPersistedModel(null, defaultConfig(verified.configSchema), 0, verified.dataVersion)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startHostData()
            }.onFailure { failure = it.message ?: "无法载入预览" }
        }
        miuixContent {
            val active = pkg
            val saved = initialModel
            PageFrame("本机预览", ::finish, "mod_preview_back") { body, header ->
                Column(body.padding(start = 20.dp, end = 20.dp, top = header + 8.dp, bottom = 24.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("同源原生渲染，不申请背屏服务或显示租约。控件只影响本次预览。", color = AppPalette.secondary())
                    if (failure.isNotEmpty()) AppCard { Text(failure, Modifier.padding(18.dp), color = AppPalette.destructive()) }
                    if (active != null && saved != null) PreviewBody(active, saved, hostData)
                }
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

    private fun startHostData() {
        dataSubscription?.close()
        dataSubscription = null
        val generation = hostDataEpoch.start()
        hostData = JSONObject()
        val active = pkg ?: return
        val fields = ModHostDataSnapshot.fieldsFor(active)
        if (fields.isEmpty()) return
        val owner = core.modDataOwner(active.id) ?: return
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

@androidx.compose.runtime.Composable
private fun PreviewBody(pkg: VerifiedModPackage, initial: ModPersistedModel, hostData: JSONObject) {
    var rear by rememberSaveable(pkg.id) { mutableStateOf(pkg.rearScene!=null) }
    var preset by rememberSaveable(pkg.id) { mutableStateOf(0) }
    var dark by rememberSaveable(pkg.id) { mutableStateOf(0) }
    var scale by rememberSaveable(pkg.id) { mutableStateOf(0) }
    var rotation by rememberSaveable(pkg.id) { mutableStateOf(0) }
    var safeArea by rememberSaveable(pkg.id) { mutableStateOf(true) }
    var blackCover by rememberSaveable(pkg.id) { mutableStateOf(false) }
    var config by remember(pkg.id) { mutableStateOf(JSONObject(initial.config.toString())) }
    var previewMessage by rememberSaveable(pkg.id) { mutableStateOf("") }
    val presets = listOf(PreviewSize(400, 400), PreviewSize(360, 640), PreviewSize(640, 360), PreviewSize(347, 212, dp = true))
    val scales = listOf(1f, 1.3f, 1.6f, 2f)
    val rotations = listOf(0, 90, 180, 270)
    val themeNames = listOf("跟随系统", "浅色", "深色")

    AppSection("预览选项")
    AppCard {
        Column {
            AppRow("场景", if (rear) "背屏" else "设置", enabled=pkg.rearScene!=null, action = { rear = !rear })
            AppRow("屏幕尺寸", "${presets[preset].width} × ${presets[preset].height} ${if (presets[preset].dp) "dp" else "px"}", action = { preset = (preset + 1) % presets.size })
            AppToggle("显示安全区", "以边框标出内容边界", safeArea) { safeArea = it }
            AppRow("主题", themeNames[dark], action = { dark = (dark + 1) % themeNames.size })
            AppRow("字号", "${(scales[scale] * 100).toInt()}%", action = { scale = (scale + 1) % scales.size })
            AppRow("旋转", "${rotations[rotation]}°", action = { rotation = (rotation + 1) % rotations.size })
            AppToggle("模拟纯黑遮挡", "检查黑态下内容是否被完全覆盖", blackCover) { blackCover = it }
        }
    }
    AppSection("${pkg.name} · ${if (rear) "背屏场景" else "设置场景"}")
    BoxWithConstraints(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
        val chosen = presets[preset]
        val quarterTurn = rotations[rotation] % 180 != 0
        val contentWidth = if (quarterTurn) chosen.height else chosen.width
        val contentHeight = if (quarterTurn) chosen.width else chosen.height
        val factor = if (chosen.dp) 1f else minOf(maxWidth.value / contentWidth, 360f / contentHeight, 1f)
        Box(Modifier.width((contentWidth * factor).dp).height((contentHeight * factor).dp), contentAlignment = Alignment.Center) {
          Box(Modifier.requiredSize((chosen.width * factor).dp, (chosen.height * factor).dp)
            .graphicsLayer(rotationZ = rotations[rotation].toFloat())
            .clip(RoundedCornerShape(22.dp)).background(if (dark == 2) Color(0xFF101013) else if (dark == 1) Color(0xFFF3F3F5) else AppPalette.background())
            .border(1.dp, AppPalette.secondary().copy(alpha = .38f), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
            val baseConfiguration = LocalConfiguration.current
            val simulatedConfiguration = remember(dark, scale, baseConfiguration) {
                Configuration(baseConfiguration).apply {
                    if (dark != 0) {
                        val mode = if (dark == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
                    }
                    fontScale = scales[scale]
                }
            }
            val context = LocalContext.current
            val simulatedContext = remember(simulatedConfiguration, context) { context.createConfigurationContext(simulatedConfiguration) }
            val baseDensity = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalConfiguration provides simulatedConfiguration,
                LocalContext provides simulatedContext,
                LocalDensity provides Density(baseDensity.density, scales[scale]),
            ) {
                top.zainzh.rearscreen.AppTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (safeArea) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 20.dp)
                                .border(1.dp, AppPalette.accent().copy(alpha = .7f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                renderPreview(pkg, initial, rear, config, hostData,
                                    onMessage = { previewMessage = it }, onConfig = { config = it })
                            }
                        } else renderPreview(pkg, initial, rear, config, hostData,
                            onMessage = { previewMessage = it }, onConfig = { config = it })
                        if (rotation != 0) Text("${rotations[rotation]}°", Modifier.align(Alignment.TopEnd).padding(8.dp), color = AppPalette.secondary())
                        if (blackCover) Box(Modifier.fillMaxSize().background(Color.Black))
          }
                    }
                }
            }
        }
    }
    if (previewMessage.isNotEmpty()) Text(previewMessage, color = AppPalette.secondary())
    Text("预览中的按钮不会调用业务脚本；配置保存只改本次预览。", color = AppPalette.secondary())
}

@androidx.compose.runtime.Composable
private fun renderPreview(
    pkg: VerifiedModPackage,
    initial: ModPersistedModel,
    rear: Boolean,
    config: JSONObject,
    data: JSONObject,
    onMessage: (String) -> Unit,
    onConfig: (JSONObject) -> Unit,
) {
    ModNativeScene(pkg, if (rear) requireNotNull(pkg.rearScene) else pkg.settingsScene, initial.state, config, data, rear,
        onEvent = { onMessage("本机预览不会执行事件") },
        onSaveConfig = { next, done -> onConfig(JSONObject(next.toString())); done(true, null); onMessage("已更新本次预览配置") })
}

private data class PreviewSize(val width: Int, val height: Int, val dp: Boolean = false)

private fun defaultConfig(schema: JSONObject): JSONObject {
    val output = JSONObject()
    val properties = schema.optJSONObject("properties") ?: return output
    val keys = properties.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val field = properties.getJSONObject(key)
        if (field.has("default")) output.put(key, field.get("default"))
        else if (field.optString("type") == "object") output.put(key, defaultConfig(field))
    }
    return output
}
