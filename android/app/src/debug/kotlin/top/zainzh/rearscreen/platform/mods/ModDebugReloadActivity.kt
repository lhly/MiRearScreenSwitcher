package top.zainzh.rearscreen.platform.mods

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.zainzh.rearscreen.AppCard
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.PageFrame
import top.zainzh.rearscreen.miuixContent
import java.io.InputStream

/** Explicit local file-picker route for same-author development reloads in Debug builds. */
class ModDebugReloadActivity : ComponentActivity() {
    private var candidate by androidx.compose.runtime.mutableStateOf<VerifiedModPackage?>(null)
    private var busy by androidx.compose.runtime.mutableStateOf(false)
    private var message by androidx.compose.runtime.mutableStateOf("")
    private var archive by androidx.compose.runtime.mutableStateOf<ByteArray?>(null)
    private var diagnosticId by androidx.compose.runtime.mutableStateOf("")
    private var diagnosticStatus by androidx.compose.runtime.mutableStateOf("")
    private var diagnosticRecords by androidx.compose.runtime.mutableStateOf<List<ModDebugDiagnostics.Record>>(emptyList())
    private var diagnosticResources by androidx.compose.runtime.mutableStateOf<ModDebugDiagnostics.Resources?>(null)
    private var diagnosticModId by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var diagnostics = ModDebugDiagnostics()
    private val diagnosticListener = object : ModBrokerListener {
        override fun onReady(modId: String, model: ModPersistedModel) { if (modId == diagnosticModId) recordDiagnostic("runtime.ready", model.revision) }
        override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) { if (modId == diagnosticModId) recordDiagnostic("model.committed", model.revision, eventId) }
        override fun onError(modId: String, code: String, message: String) { if (modId == diagnosticModId) recordDiagnostic("runtime.error", code = code) }
        override fun onEffectRequested(modId: String, eventId: String) { if (modId == diagnosticModId) recordDiagnostic("effect.requested", eventId = eventId) }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::readCandidate) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        miuixContent {
            PageFrame("Mod 开发重载", ::finish, "mod_debug_reload_back") { modifier, header ->
                androidx.compose.foundation.layout.Column(modifier.padding(horizontal = 20.dp, vertical = header + 12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    AppCard {
                        androidx.compose.foundation.layout.Column(Modifier.padding(18.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                            Text("用同一作者密钥替换已安装 Mod 的调试包。数据版本和 versionCode 必须保持不变；正常更新请回到 Mod 管理页。", color = AppPalette.secondary())
                            TextButton(if (busy) "正在验证…" else "选择已安装 Mod 的 ZIP", { if (!busy) picker.launch(arrayOf("application/zip", "application/octet-stream")) }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                            candidate?.let { pkg ->
                                Text("${pkg.name} · ${pkg.id} · ${pkg.version} (${pkg.versionCode})\n作者：${pkg.authorName}\n指纹：${pkg.authorFingerprint}\n数据版本：${pkg.dataVersion}")
                                TextButton(if (busy) "正在重载…" else "替换调试包", { if (!busy) reloadPackage(pkg) }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                            }
                        }
                    }
                    AppCard {
                        androidx.compose.foundation.layout.Column(Modifier.padding(18.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                            Text("单个 Mod 诊断", fontSize = 18.sp)
                            Text("只记录输入的 Mod：ready、提交 revision、Effect 请求和错误代码，最多 64 条；不保存 state/config 或错误正文。资源统计是该 Mod 签名载荷字节数，不代表进程 PSS/CPU。", color = AppPalette.secondary(), fontSize = 13.sp)
                            TextField(diagnosticId, { diagnosticId = it.filter { c -> c.isLetterOrDigit() || c in "._-" }.take(64) }, label = "Mod ID", singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextButton(if (diagnosticModId == null) "连接此 Mod" else "停止诊断", {
                                if (diagnosticModId == null) startDiagnostics() else stopDiagnostics()
                            }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                            diagnosticResources?.let { resources ->
                                Text("载荷 ${resources.payloadCount} 项 · ${resources.totalBytes} B\nUI ${resources.sceneBytes} B · JS ${resources.scriptBytes} B · 资源 ${resources.assetBytes} B · 其他 ${resources.otherBytes} B", color = AppPalette.secondary(), fontSize = 13.sp)
                            }
                            if (diagnosticStatus.isNotBlank()) Text(diagnosticStatus, color = AppPalette.secondary(), fontSize = 13.sp)
                            diagnosticRecords.takeLast(32).forEach { record ->
                                val detail = listOfNotNull(record.revision?.let { "r$it" }, record.eventId, record.code).joinToString(" · ")
                                Text("${record.sequence}. ${record.kind}${if (detail.isBlank()) "" else " · $detail"}", color = AppPalette.secondary(), fontSize = 13.sp)
                            }
                        }
                    }
                    if (message.isNotBlank()) AppCard { Text(message, Modifier.padding(18.dp), color = AppPalette.secondary()) }
                }
            }
        }
    }

    private fun readCandidate(uri: Uri) {
        busy = true; message = ""; candidate = null; archive = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "无法打开 ZIP" }; readBounded(input, ModPackageReader.MAX_ZIP_BYTES) }
                    bytes to ModPackageReader.read(bytes.inputStream())
                }
            }
            busy = false
            result.onSuccess { (bytes, pkg) -> archive = bytes; candidate = pkg; message = "已验证签名和载荷；重载前仍会核对安装身份、信任、授权及模型兼容。" }
                .onFailure { message = it.message ?: "Mod ZIP 无效，当前安装保持不变" }
        }
    }

    private fun reloadPackage(pkg: VerifiedModPackage) {
        val bytes = archive ?: return
        val installed = ModPackageStore.installed(this, pkg.id)
        if (installed == null) { message = "此 Mod 尚未安装；请使用正常导入流程。"; return }
        if (diagnosticModId == installed.id) stopDiagnostics()
        busy = true; message = "正在关闭旧运行会话…"
        ModBrokerProvider.get(this).beginPackageReplacement(installed.id, mainExecutor) { drained, reason ->
            if (!drained) {
                busy = false; message = reason ?: "旧运行会话未能安全关闭；当前安装保持不变。"
            } else lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ModPackageStore.debugReload(this@ModDebugReloadActivity, bytes) } }
                ModBrokerProvider.get(this@ModDebugReloadActivity).finishPackageReplacement(installed.id, result.isSuccess)
                busy = false
                result.onSuccess { candidate = null; archive = null; message = "调试包已重载；配置、状态、授权与正常更新版本序列均保留。" }
                    .onFailure { message = it.message ?: "重载失败，旧包和本地数据保持不变。" }
            }
        }
    }

    private fun startDiagnostics() {
        if (busy) return
        val id = diagnosticId.trim()
        val installed = runCatching { ModPackageStore.installed(this, id) }.getOrNull()
        if (installed == null || !installed.enabled || !ModPackageStore.isAuthorTrusted(this, installed.authorFingerprint)) {
            diagnosticStatus = "只允许连接已启用且作者仍受信任的目标 Mod。"
            return
        }
        val pkg = runCatching { ModPackageStore.find(this, id) }.getOrNull()
        if (pkg == null || pkg.authorFingerprint != installed.authorFingerprint) {
            diagnosticStatus = "目标 Mod 包验证失败。"
            return
        }
        diagnosticId = id
        diagnosticModId = id
        diagnostics = ModDebugDiagnostics()
        diagnosticResources = ModDebugDiagnostics.resources(pkg)
        diagnosticRecords = emptyList()
        diagnosticStatus = "正在读取此 Mod 的运行事件…"
        ModBrokerProvider.get(this).open(id, mainExecutor, diagnosticListener, visible = false)
    }

    private fun recordDiagnostic(kind: String, revision: Long? = null, eventId: String? = null, code: String? = null) {
        if (diagnosticModId == null) return
        diagnostics.record(kind, revision, eventId, code)
        runOnUiThread {
            if (!isFinishing && !isDestroyed && diagnosticModId != null) {
                diagnosticRecords = diagnostics.snapshot()
                diagnosticStatus = "正在诊断 ${diagnosticModId}；最多保留 64 条记录。"
            }
        }
    }

    private fun stopDiagnostics() {
        diagnosticModId?.let { ModBrokerProvider.get(this).closeObserver(it, diagnosticListener) }
        diagnosticModId = null
        diagnosticStatus = "诊断已停止。"
    }

    override fun onPause() {
        stopDiagnostics()
        super.onPause()
    }

    override fun onDestroy() {
        stopDiagnostics()
        super.onDestroy()
    }
}

private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 32 * 1024)); val buffer = ByteArray(8192); var total = 0
    while (true) {
        val count = input.read(buffer); if (count < 0) break
        total += count; require(total <= maxBytes) { "ZIP 超过 2 MiB" }; output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
