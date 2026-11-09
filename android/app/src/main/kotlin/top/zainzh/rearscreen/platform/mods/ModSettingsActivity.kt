package top.zainzh.rearscreen.platform.mods

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.zainzh.rearscreen.AppCard
import top.zainzh.rearscreen.AppRow
import top.zainzh.rearscreen.AppToggle
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.PageFrame
import top.zainzh.rearscreen.R
import top.zainzh.rearscreen.miuixContent
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executor

internal fun modInstallSuccessNotice(name: String, replacing: Boolean, enabled: Boolean): String =
    if (replacing) "已更新 $name；原有配置与数据已保留，当前仍为${if (enabled) "启用" else "停用"}状态。"
    else "已安装 $name；默认保持停用，请先审核设置与能力。"

private sealed interface ModDestructiveAction {
    data class RevokeAuthor(val mod: InstalledMod, val affectedMods: Int) : ModDestructiveAction
    data class Uninstall(val mod: InstalledMod) : ModDestructiveAction
}

/** Native trust/install and lifecycle surface. Package verification happens before prompting for trust. */
class ModSettingsActivity : ComponentActivity() {
    private var mods by mutableStateOf<List<InstalledMod>>(emptyList())
    private var pending by mutableStateOf<VerifiedModPackage?>(null)
    private var working by mutableStateOf(false)
    private var error by mutableStateOf("")
    private var notice by mutableStateOf("")
    private var destructiveAction by mutableStateOf<ModDestructiveAction?>(null)
    private val executor: Executor by lazy { mainExecutor }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) inspectArchive(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reload()
        miuixContent {
            val candidate = pending
            PageFrame("Mod 管理", ::finish, "mod_settings_back") { modifier, header ->
                LazyColumn(modifier.padding(horizontal = 20.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = header + 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        AppCard {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("导入标准 .zip Mod", fontSize = 18.sp)
                                Text("RC 预览：Mod 支持能力与接口仍在完善。", color = AppPalette.secondary(), fontSize = 13.sp)
                                TextButton(if (working) "正在验证…" else "选择 ZIP 文件", { if (!working) picker.launch(arrayOf("application/zip", "application/octet-stream")) }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                            }
                        }
                    }
                    if (error.isNotEmpty()) item { AppCard { Column(Modifier.padding(18.dp)) { Text(error, color = AppPalette.destructive()) } } }
                    if (notice.isNotEmpty()) item { AppCard { Column(Modifier.padding(18.dp)) { Text(notice, color = AppPalette.secondary()) } } }
                    if (mods.isEmpty()) item { AppCard { AppRow("尚未安装 Mod", null) } }
                    items(mods, key = { it.id }) { mod ->
                        AppCard {
                            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(mod.name, Modifier.padding(horizontal = 18.dp, vertical = 8.dp), fontSize = 18.sp)
                                Text("${mod.authorName} · ${mod.version}", Modifier.padding(horizontal = 18.dp), fontSize = 13.sp)
                                Text("已授权 ${mod.grantedCapabilities.sorted().joinToString { capabilityLabel(it) }.ifEmpty { "无" }}", Modifier.padding(horizontal = 18.dp, vertical = 8.dp), fontSize = 13.sp)
                                AppToggle("启用", null, mod.enabled) { enabled -> changeEnabled(mod, enabled) }
                                AppRow("本机预览", "同源原生渲染；不连接背屏", action = {
                                    startActivity(android.content.Intent(this@ModSettingsActivity, ModPreviewActivity::class.java).putExtra("mod_id", mod.id))
                                })
                                mod.optionalCapabilities.forEach { capability ->
                                    AppToggle("${capabilityLabel(capability.name)} v${capability.minimumVersion}", "可单独授权或撤销", capability.name in mod.grantedCapabilities) { enabled -> changeGrant(mod, capability, enabled) }
                                }
                                AppRow("设置", null, action = { startActivity(android.content.Intent(this@ModSettingsActivity, ModConfigActivity::class.java).putExtra("mod_id", mod.id)) })
                                val sceneAllowed = "rear.scene" in mod.grantedCapabilities
                                AppRow("显示到背屏", if (sceneAllowed) null else "此 Mod 未请求背屏显示能力", enabled = sceneAllowed,
                                    action = if (sceneAllowed) ({ openRear(mod.id) }) else null)
                                if (sceneAllowed) {
                                    Text(getString(R.string.mod_scene_cover_behavior_note), Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                                        color = AppPalette.secondary(), fontSize = 13.sp)
                                }
                                TextButton("撤销此作者信任", { destructiveAction = ModDestructiveAction.RevokeAuthor(mod, mods.count { it.authorFingerprint == mod.authorFingerprint }) }, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), minHeight = 48.dp)
                                TextButton("卸载并删除本地数据", { destructiveAction = ModDestructiveAction.Uninstall(mod) }, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), minHeight = 48.dp)
                            }
                        }
                    }
                }
            }
            WindowDialog(show = candidate != null, title = "审核 Mod 作者与能力", onDismissRequest = { pending = null }) {
                candidate?.let { pkg ->
                    val optional = pkg.optionalCapabilities.map { it.name }
                    var selected by remember(pkg.id, pkg.versionCode) { mutableStateOf(emptySet<String>()) }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${pkg.name} · ${pkg.version} (${pkg.versionCode})")
                        Text("作者：${pkg.authorName}\nRSA 指纹：${pkg.authorFingerprint}", fontSize = 12.sp)
                        Text("必需能力：${pkg.requiredCapabilities.joinToString { "${capabilityLabel(it.name)}（${it.name}）v${it.minimumVersion}" }.ifEmpty { "无" }}")
                        Text("权限声明：必需 ${pkg.requiredPermissions.joinToString().ifEmpty { "无" }}；可选 ${pkg.optionalPermissions.joinToString().ifEmpty { "无" }}。声明不是系统权限授权。", fontSize = 12.sp)
                        if (optional.isNotEmpty()) {
                            Text("可选能力（默认关闭）：", fontSize = 14.sp)
                            optional.forEach { name -> AppToggle("${capabilityLabel(name)}（$name）", null, name in selected) { enabled -> selected = if (enabled) selected + name else selected - name } }
                        }
                        Text("签名证明包由该公钥持有者发布；信任作者不等于授予全部能力。", fontSize = 12.sp)
                        TextButton("拒绝", { pending = null }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                val verb = if (mods.any { it.id == pkg.id }) "确认更新并保留数据" else "信任此作者并安装"
                        TextButton(verb, { install(pkg, selected) }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                    }
                }
            }
            val action = destructiveAction
            WindowDialog(show = action != null, title = when (action) {
                is ModDestructiveAction.RevokeAuthor -> "撤销作者信任？"
                is ModDestructiveAction.Uninstall -> "卸载 Mod？"
                null -> "确认操作"
            }, onDismissRequest = { destructiveAction = null }) {
                when (action) {
                    is ModDestructiveAction.RevokeAuthor -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("将停用该作者的 ${action.affectedMods} 个 Mod，已保存的数据会保留。作者再次被信任前，这些 Mod 都不能运行。")
                        TextButton("取消", { destructiveAction = null }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                        TextButton("确认撤销信任", { destructiveAction = null; revokeAuthor(action.mod) }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                    }
                    is ModDestructiveAction.Uninstall -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("将移除 ${action.mod.name} 的安装包、配置和本地数据；此操作不能撤销。")
                        TextButton("取消", { destructiveAction = null }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                        TextButton("确认卸载并删除数据", { destructiveAction = null; uninstall(action.mod) }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                    }
                    null -> Unit
                }
            }
        }
    }

    override fun onResume() { super.onResume(); reload() }

    private fun reload() { runCatching { ModPackageStore.list(this) }.onSuccess { mods = it }.onFailure { error = it.message ?: "无法读取 Mod 列表" } }

    private fun inspectArchive(uri: Uri) {
        working = true; error = ""; notice = ""
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "无法打开 ZIP" }; readBounded(input, ModPackageReader.MAX_ZIP_BYTES) }
                    .let { bytes -> bytes to ModPackageReader.read(bytes.inputStream()) }
                }
            }
            working = false
            result.onSuccess { (bytes, pkg) -> stagedArchive = bytes; pending = pkg }
                .onFailure { error = it.message ?: "Mod 包无效" }
        }
    }

    private var stagedArchive by mutableStateOf<ByteArray?>(null)

    private fun install(pkg: VerifiedModPackage, optional: Set<String>) {
        val archive = stagedArchive ?: run { pending = null; return }
        val selected = optional + pkg.requiredCapabilities.map { it.name }
        working = true
        val old = ModPackageStore.installed(this, pkg.id)
        if (old == null) commitInstall(pkg, archive, selected, null)
        else ModBrokerProvider.get(this).beginPackageReplacement(old.id, mainExecutor) { drained, reason ->
            if (!drained) {
                working = false
                error = reason ?: "无法安全更新 Mod"
            } else commitInstall(pkg, archive, selected, old)
        }
    }

    private fun commitInstall(pkg: VerifiedModPackage, archive: ByteArray, selected: Set<String>, old: InstalledMod?) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ModPackageStore.install(this@ModSettingsActivity, archive, ModInstallDecision(pkg.authorFingerprint, selected), IsolatedModMigrationRunner(this@ModSettingsActivity))
                }
            }
            if (old != null) {
                if (result.isSuccess) withContext(Dispatchers.IO) {
                    runCatching { top.zainzh.rearscreen.platform.RearCapabilityCore.get(this@ModSettingsActivity).displaySessions.closeOwnerSessions(sessionOwner(old), top.zainzh.rearscreen.platform.SceneCloseReason.OWNER_REVOKED) }
                }
                val closeUnconfirmed = isRuntimeCloseUnconfirmed(result.exceptionOrNull())
                // Never rebind the old worker merely because migration teardown
                // could not confirm slot release. Keep the Mod paused until an
                // explicit close completion path can safely resume it.
                if (!closeUnconfirmed) ModBrokerProvider.get(this@ModSettingsActivity).finishPackageReplacement(old.id, result.isSuccess)
            }
            working = false; pending = null; stagedArchive = null
            result.onSuccess { reload(); notice = modInstallSuccessNotice(it.name, old != null, it.enabled) }
                .onFailure { error = it.message ?: "安装失败，旧版本和本地数据保持不变" }
        }
    }

    private fun changeEnabled(mod: InstalledMod, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ModPackageStore.setEnabled(this@ModSettingsActivity, mod.id, enabled) }
                .onSuccess { if (!enabled) closeModSessions(it); withContext(Dispatchers.Main) { reload(); error = ""; notice = if (enabled) "Mod 已启用" else "Mod 已停用" } }
                .onFailure { withContext(Dispatchers.Main) { reload(); error = it.message ?: "状态更新失败" } }
        }
    }

    private fun changeGrant(mod: InstalledMod, capability: ModCapability, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ModPackageStore.setCapabilityGrant(this@ModSettingsActivity, mod.id, capability.name, capability.minimumVersion, enabled) }
                .onSuccess { if (!enabled) closeModSessions(mod); withContext(Dispatchers.Main) { reload(); error = ""; notice = if (enabled) "能力已授权" else "能力已撤销" } }
                .onFailure { withContext(Dispatchers.Main) { reload(); error = it.message ?: "能力授权更新失败" } }
        }
    }

    private fun revokeAuthor(mod: InstalledMod) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ids = runCatching { ModPackageStore.revokeAuthorTrust(this@ModSettingsActivity, mod.authorFingerprint) }.getOrDefault(emptySet())
            ids.forEach { id -> ModPackageStore.installed(this@ModSettingsActivity, id)?.let(::closeModSessions) }
            withContext(Dispatchers.Main) { reload(); error = ""; notice = "已撤销作者信任，并停用其 ${ids.size} 个 Mod；数据保留" }
        }
    }

    private fun closeModSessions(mod: InstalledMod) {
        runCatching { ModBrokerProvider.get(this).invalidateAndWait(mod.id) }
        runCatching { top.zainzh.rearscreen.platform.RearCapabilityCore.get(this).displaySessions.closeOwnerSessions(sessionOwner(mod), top.zainzh.rearscreen.platform.SceneCloseReason.OWNER_REVOKED) }
    }

    private fun uninstall(mod: InstalledMod) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { top.zainzh.rearscreen.platform.RearCapabilityCore.get(this@ModSettingsActivity).displaySessions.closeOwnerSessions(sessionOwner(mod), top.zainzh.rearscreen.platform.SceneCloseReason.OWNER_REVOKED) }
            runCatching { ModBrokerProvider.get(this@ModSettingsActivity).invalidateAndWait(mod.id, "E_MOD_UNINSTALLED", "Mod 已卸载") }
            runCatching { ModPackageStore.uninstall(this@ModSettingsActivity, mod.id) }
            withContext(Dispatchers.Main) { reload(); error = ""; notice = "已卸载 ${mod.name} 并删除其本地数据" }
        }
    }

    private fun openRear(id: String) {
        val mod = ModPackageStore.installed(this, id) ?: return
        if ("rear.scene" !in mod.grantedCapabilities) { error = "作者未声明或用户未授权 rear.scene"; return }
        ModPackageStore.find(this, id) ?: return
        val request = top.zainzh.rearscreen.platform.SceneRequest(
            component = ComponentName(this, ModRearSceneActivity::class.java),
            region = top.zainzh.rearscreen.platform.RearRegion.CAMERA_SAFE,
            keepVisible = "rear.scene.keep_visible" in mod.grantedCapabilities,
            requiredCapabilities = buildSet {
                add(top.zainzh.rearscreen.platform.CapabilityRequirement("rear.scene", 1))
                if (mod.grantedCapabilities.contains("rear.scene.keep_visible")) add(top.zainzh.rearscreen.platform.CapabilityRequirement("rear.scene.keep_visible", 1))
            }
        )
        val owner = sessionOwner(mod)
        // The token is minted only in this positive action, after the visible Miuix confirmation.
        error = ""; notice = "正在连接背屏…"
        val token = runCatching {
            top.zainzh.rearscreen.platform.RearCapabilityCore.get(this).issueSceneActivationAfterUserConfirmation(owner, request)
        }.getOrElse { exception ->
            notice = ""; error = "无法打开背屏：${exception.message ?: "授权暂不可用"}；Mod 设置仍可在本机使用。"
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { top.zainzh.rearscreen.platform.RearCapabilityCore.get(this@ModSettingsActivity).displaySessions.openScene(owner, request, token, object : top.zainzh.rearscreen.platform.SceneObserver {
                override fun onSceneEvent(sessionId: String, generation: Long, event: top.zainzh.rearscreen.platform.SceneEvent) {
                    when (event) {
                        top.zainzh.rearscreen.platform.SceneEvent.BackendLost -> lifecycleScope.launch(Dispatchers.Main) { notice = ""; error = "背屏服务已断开；Mod 设置仍可在本机使用。" }
                        is top.zainzh.rearscreen.platform.SceneEvent.Closed -> lifecycleScope.launch(Dispatchers.Main) { notice = ""; error = "背屏场景已关闭：${event.reason}" }
                        else -> Unit
                    }
                }
            }) }
            withContext(Dispatchers.Main) {
                result.onSuccess { opened ->
                    when (opened) {
                        is top.zainzh.rearscreen.platform.SceneOpenResult.Rejected -> { notice = ""; error = sceneErrorMessage(opened.code) }
                        is top.zainzh.rearscreen.platform.SceneOpenResult.Accepted -> notice = "已请求显示到背屏。"
                    }
                }.onFailure { exception ->
                    notice = ""
                    error = "无法打开背屏：${exception.message ?: "服务暂不可用"}；Mod 设置仍可在本机使用。"
                }
            }
        }
    }

    private fun sceneErrorMessage(code: top.zainzh.rearscreen.platform.SceneError): String = when (code) {
        top.zainzh.rearscreen.platform.SceneError.BACKEND_UNAVAILABLE -> "背屏服务不可用；Mod 设置仍可在本机使用。"
        top.zainzh.rearscreen.platform.SceneError.UNSUPPORTED -> "当前设备不支持背屏显示；Mod 设置仍可在本机使用。"
        top.zainzh.rearscreen.platform.SceneError.DISPLAY_UNAVAILABLE -> "背屏显示区域暂不可用；Mod 设置仍可在本机使用。"
        top.zainzh.rearscreen.platform.SceneError.DISPLAY_BUSY -> "背屏正在使用中；请稍后再试。"
        top.zainzh.rearscreen.platform.SceneError.CAPABILITY_DENIED -> "此 Mod 未获背屏显示授权。"
        top.zainzh.rearscreen.platform.SceneError.STALE_SESSION -> "背屏请求已过期，请重新打开。"
        top.zainzh.rearscreen.platform.SceneError.INVALID_COMPONENT,
        top.zainzh.rearscreen.platform.SceneError.INVALID_STATE,
        top.zainzh.rearscreen.platform.SceneError.LIMIT_EXCEEDED,
        top.zainzh.rearscreen.platform.SceneError.STORAGE_FAILED -> "背屏请求未完成：$code"
    }

    private fun sessionOwner(mod: InstalledMod): top.zainzh.rearscreen.platform.SessionOwner {
        val info = if (android.os.Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        val cert = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            else @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
        val digest = cert?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) } } ?: error("无法确定宿主签名")
        return top.zainzh.rearscreen.platform.SessionOwner(android.os.Process.myUid(), packageName, digest,
            top.zainzh.rearscreen.platform.SessionPrincipal.SignedMod(mod.authorFingerprint, mod.id, mod.logicalSessionId))
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= limit) { "ZIP 超过 2 MiB" }; output.write(buffer, 0, count) }
        return output.toByteArray()
    }

    private fun capabilityLabel(name: String): String = when (name) {
        "data.battery" -> "电池信息"
        "data.systemMetrics" -> "设备运行指标"
        "rear.scene" -> "背屏显示"
        "rear.scene.keep_visible" -> "保持显示"
        "rear.notification" -> "发布自有背屏通知"
        "rear.info" -> "背屏信息"
        else -> name
    }

    private fun brokerListener(done: (Boolean, String?) -> Unit) = object : ModBrokerListener {
        override fun onReady(modId: String, model: ModPersistedModel) = Unit
        override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) = Unit
        override fun onError(modId: String, code: String, message: String) { done(false, message) }
        override fun onEffectRequested(modId: String, eventId: String) = Unit
    }
}
