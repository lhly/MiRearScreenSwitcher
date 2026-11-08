package top.zainzh.rearscreen.platform.mods

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import android.os.SystemClock
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.zainzh.rearscreen.AppCard
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.AppRow
import top.zainzh.rearscreen.AppSection
import top.zainzh.rearscreen.AppToggle
import top.zainzh.rearscreen.PageFrame
import top.zainzh.rearscreen.platform.actions.RegisteredActionRef

private val LocalModSceneVisible = staticCompositionLocalOf { true }
private val LocalModRegisteredAction = staticCompositionLocalOf<(RegisteredActionRef) -> Unit> { {} }
private val LocalSelectRegisteredAction = staticCompositionLocalOf<(String, RegisteredActionRef?) -> Unit> { { _, _ -> } }
private val LocalRegisteredActionDrafts = staticCompositionLocalOf<Map<String, RegisteredActionRef>> { emptyMap() }
private val LocalClearRegisteredActionDrafts = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun ModNativeScene(
    mod: VerifiedModPackage,
    scene: JSONObject,
    state: JSONObject?,
    config: JSONObject,
    data: JSONObject,
    rear: Boolean,
    onBack: () -> Unit = {},
    onEvent: (String) -> Unit,
    onSaveConfig: (JSONObject, (Boolean, String?) -> Unit) -> Unit,
    notice: String? = null,
    visible: Boolean = true,
    onRegisteredAction: (RegisteredActionRef) -> Unit = {},
    registeredActionDrafts: Map<String, RegisteredActionRef> = emptyMap(),
    onSelectRegisteredAction: (String, RegisteredActionRef?) -> Unit = { _, _ -> },
    onClearRegisteredActionDrafts: () -> Unit = {},
) {
    val root = scene.getJSONObject("root")
    var drafts by rememberSaveable(mod.id, scene.optString("scene")) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val setDraft: (String, String) -> Unit = { key, value -> drafts = drafts + (key to value) }
    val clearDrafts: () -> Unit = { drafts = emptyMap() }
    androidx.compose.runtime.CompositionLocalProvider(LocalModSceneVisible provides visible, LocalModRegisteredAction provides onRegisteredAction,
        LocalSelectRegisteredAction provides onSelectRegisteredAction, LocalRegisteredActionDrafts provides registeredActionDrafts,
        LocalClearRegisteredActionDrafts provides onClearRegisteredActionDrafts) { if (rear) {
        Box(Modifier.fillMaxSize().background(AppPalette.background()).padding(16.dp), contentAlignment = Alignment.Center) {
            Node(mod, root, state, config, data, drafts, setDraft, clearDrafts, "root", rear, onEvent, onSaveConfig)
        }
    } else {
        PageFrame(title = mod.name, back = onBack, backTag = "mod_back_${mod.id}") { body, header ->
            Column(body.padding(start = 20.dp, end = 20.dp, top = header + 12.dp, bottom = 24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!notice.isNullOrBlank()) Text(notice, Modifier.fillMaxWidth(), fontSize = 13.sp, color = AppPalette.secondary())
                AppCard { Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Node(mod, root, state, config, data, drafts, setDraft, clearDrafts, "root", false, onEvent, onSaveConfig) } }
            }
        }
    } }
}

@Composable
private fun Node(
    mod: VerifiedModPackage,
    node: JSONObject,
    state: JSONObject?,
    config: JSONObject,
    data: JSONObject,
    drafts: Map<String, String>,
    setDraft: (String, String) -> Unit,
    clearDrafts: () -> Unit,
    path: String,
    rear: Boolean,
    onEvent: (String) -> Unit,
    onSaveConfig: (JSONObject, (Boolean, String?) -> Unit) -> Unit,
) {
    if (!nodeVisible(node, state, config, data)) return
    val type = node.getString("type")
    val children = node.optJSONArray("children")
    val gap = node.optInt("gap", 8).coerceIn(0, 64).dp
    var dialogNode by rememberSaveable(mod.id, path) { mutableStateOf<String?>(null) }
    var localError by rememberSaveable(mod.id, path) { mutableStateOf("") }
    val onRegisteredAction = LocalModRegisteredAction.current
    val onSelectRegisteredAction = LocalSelectRegisteredAction.current
    val registeredActionDrafts = LocalRegisteredActionDrafts.current
    val clearRegisteredActionDrafts = LocalClearRegisteredActionDrafts.current
    val resolve: (String) -> String = { key -> resolveBinding(key, state, config, data) }
    val draftValue: (String) -> String = { key -> drafts[key] ?: resolve(key) }
    val action: (JSONObject) -> Unit = { descriptor ->
        when (descriptor.optString("type")) {
            "registeredAction" -> registeredActionRef(descriptor, config)?.let(onRegisteredAction)
            else -> onEvent(descriptor.getString("name"))
        }
    }

    when (type) {
        "layout.column" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = horizontalAlignment(node)) {
            (0 until children!!.length()).forEach { index -> Node(mod, children.getJSONObject(index), state, config, data, drafts, setDraft, clearDrafts, "$path.$index", rear, onEvent, onSaveConfig) }
        }
        "layout.row" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = rowArrangement(node, gap), verticalAlignment = verticalAlignment(node)) {
            (0 until children!!.length()).forEach { index ->
                // Row children get equal, bounded width after spacing is reserved.
                // This keeps nested fillMaxWidth layouts (columns, buttons, fields)
                // inside their own cell instead of measuring each at screen width.
                Box(Modifier.weight(1f), contentAlignment = boxAlignment(node)) {
                    Node(mod, children.getJSONObject(index), state, config, data, drafts, setDraft, clearDrafts, "$path.$index", rear, onEvent, onSaveConfig)
                }
            }
        }
        "layout.box" -> Box(Modifier.fillMaxWidth(), contentAlignment = boxAlignment(node)) {
            (0 until children!!.length()).forEach { index -> Node(mod, children.getJSONObject(index), state, config, data, drafts, setDraft, clearDrafts, "$path.$index", rear, onEvent, onSaveConfig) }
        }
        "ui.text", "ui.number" -> {
            val value = node.opt("value")
            val text = formatModValue(if (value is JSONObject) resolve(value.getString("bind")) else value?.toString().orEmpty(), node.optString("format", "text"))
            val style = node.optString("style")
            val size = when (style) { "title", "displayNumber" -> 32.sp; "caption" -> 13.sp; else -> 16.sp }
            Text(text, fontSize = size, fontWeight = if (style in setOf("title", "displayNumber")) FontWeight.SemiBold else FontWeight.Normal,
                color = modTone(node.optString("tone", if (style == "caption") "secondary" else "primary")),
                modifier = if (style == "title" || style == "displayNumber") Modifier.semantics { heading() } else Modifier)
        }
        "ui.list" -> {
            val items = resolveArray(node.getJSONObject("items").getString("bind"), state, config, data).take(node.getInt("maxItems"))
            if (items.isEmpty()) { if (node.has("emptyText")) Text(node.getString("emptyText"), fontSize = 13.sp, color = AppPalette.secondary()) }
            else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { item -> Text(formatModValue(item.toString(), node.optString("format", "text")), fontSize = if (node.optString("style") == "caption") 13.sp else 16.sp,
                    color = if (node.optString("style") == "caption") AppPalette.secondary() else AppPalette.text()) }
            }
        }
        "ui.icon" -> {
            val glyph = mapOf("add" to "＋", "remove" to "−", "check" to "✓", "close" to "×", "timer" to "◷", "trophy" to "★", "info" to "ⓘ", "warning" to "⚠")[node.getString("name")].orEmpty()
            Text(glyph, Modifier.semantics { contentDescription = node.getString("label") }, fontSize = 24.sp, color = modTone(node.optString("tone", "primary")))
        }
        "ui.deadlineText" -> ModDeadlineText(resolve(node.getJSONObject("value").getString("bind")))
        "ui.button" -> Column(Modifier.fillMaxWidth()) {
            val descriptor = node.getJSONObject("onPress")
            val unavailableAction = descriptor.optString("type") == "registeredAction" && registeredActionRef(descriptor, config) == null
            TextButton(node.getString("label"), { action(descriptor) }, Modifier.fillMaxWidth(), enabled = node.optBoolean("enabled", true) && !unavailableAction, minHeight = 48.dp)
            val hint = node.optString("hint").ifEmpty { if (unavailableAction) "请先在设置中选择已登记动作" else "" }
            if (hint.isNotEmpty()) Text(hint, Modifier.padding(horizontal = 12.dp), fontSize = 13.sp, color = AppPalette.secondary())
        }
        "ui.divider" -> Spacer(Modifier.fillMaxWidth().height(1.dp).background(AppPalette.secondary().copy(alpha = .16f)))
        "ui.progress" -> {
            val raw = node.opt("value")
            val value = (if (raw is JSONObject) resolve(raw.getString("bind")).toFloatOrNull() else (raw as? Number)?.toFloat()) ?: 0f
            val min = node.getDouble("min").toFloat(); val max = node.getDouble("max").toFloat()
            LinearProgressIndicator(Modifier.fillMaxWidth(), ((value - min) / (max - min)).coerceIn(0f, 1f))
        }
        "ui.image" -> {
            val bytes = mod.payloads[node.getString("asset")]
            val image = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            if (image != null) Image(image, contentDescription = node.optString("alt").ifEmpty { null },
                modifier = Modifier.widthIn(max = 360.dp).heightIn(max = 256.dp), contentScale = ContentScale.Fit)
        }
        "settings.row" -> AppRow(node.getString("label"), node.optJSONObject("value")?.let { resolve(it.getString("bind")) }
            ?.let { value -> if (node.has("hint")) "$value\n${node.getString("hint")}" else value } ?: node.optString("hint").ifEmpty { null },
            action = if (node.has("onPress")) ({ action(node.getJSONObject("onPress")) }) else null)
        "settings.input" -> {
            val binding = node.getJSONObject("value").getString("bind")
            val value = drafts[binding] ?: resolve(binding)
            TextField(value, { setDraft(binding, it) }, label = node.optString("label"), singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
            val helper = node.optString("hint").ifEmpty { node.optString("placeholder") }
            if (helper.isNotEmpty()) Text(helper, fontSize = 13.sp, color = AppPalette.secondary())
        }
        "settings.action" -> {
            val binding = node.getJSONObject("value").getString("bind")
            val raw = drafts[binding] ?: resolve(binding)
            val ref = registeredActionDrafts[binding] ?: runCatching { JSONObject(raw).let { RegisteredActionRef(it.getString("id"), it.getInt("version")) } }.getOrNull()
            AppRow(node.getString("label"), ref?.let { "已选择：${it.id}" } ?: node.optString("hint").ifEmpty { "选择已登记动作" }, action = {
                onSelectRegisteredAction(binding, ref)
            })
        }
        "settings.number" -> {
            val binding = node.getJSONObject("value").getString("bind")
            val value = drafts[binding] ?: resolve(binding)
            TextField(value, { setDraft(binding, it.filter { ch -> ch.isDigit() || ch == '-' }) }, label = node.getString("label"), singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (node.has("hint")) Text(node.getString("hint"), fontSize = 13.sp, color = AppPalette.secondary())
        }
        "settings.toggle" -> {
            val binding = node.getJSONObject("value").getString("bind")
            val value = (drafts[binding] ?: resolve(binding)).toBooleanStrictOrNull() ?: false
            AppToggle(node.getString("label"), node.optString("hint").ifEmpty { null }, value) { setDraft(binding, it.toString()) }
        }
        "settings.select" -> {
            val binding = node.getJSONObject("value").getString("bind")
            val value = drafts[binding] ?: resolve(binding)
            val selected = node.getJSONArray("options").asList().firstOrNull { it.opt("value")?.toString() == value }?.optString("label")
                ?: value
            AppRow(node.getString("label"), if (node.has("hint")) "$selected\n${node.getString("hint")}" else selected, action = { dialogNode = path })
            val choices = node.getJSONArray("options")
            WindowDialog(show = dialogNode == path, title = node.getString("label"), onDismissRequest = { dialogNode = null }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until choices.length()) {
                        val option = choices.getJSONObject(i)
                        AppRow(option.getString("label"), null, action = { setDraft(binding, option.get("value").toString()); dialogNode = null })
                    }
                }
            }
        }
        "ui.confirm" -> {
            AppRow(node.getString("label"), null, action = { dialogNode = path })
            WindowDialog(show = dialogNode == path, title = node.getString("title"), onDismissRequest = { dialogNode = null }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(node.getString("message"), color = AppPalette.secondary())
                    TextButton("取消", { dialogNode = null }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                    TextButton("确认", { dialogNode = null; action(node.getJSONObject("onConfirm")) }, Modifier.fillMaxWidth(), minHeight = 48.dp)
                }
            }
        }
        "settings.save" -> TextButton("保存设置", {
            try {
                val updated = JSONObject(config.toString())
                drafts.forEach { (binding, text) -> setJsonPath(updated, binding.removePrefix("config."), parseDraft(binding, text, config, mod.configSchema)) }
                registeredActionDrafts.forEach { (binding, ref) ->
                    require(binding.matches(Regex("^config\\.[A-Za-z][A-Za-z0-9_]{0,63}$"))) { "登记动作草稿只支持config顶层字段" }
                    setJsonPath(updated, binding.removePrefix("config."), JSONObject().put("id", ref.id).put("version", ref.version))
                }
                ModJsonSchema.validateValue(mod.configSchema, updated, "config")
                onSaveConfig(updated) { saved, error ->
                    if (saved) { clearDrafts(); clearRegisteredActionDrafts(); localError = "" }
                    else localError = error ?: "设置保存失败"
                }
            } catch (e: Exception) { localError = e.message ?: "设置值无效" }
        }, Modifier.fillMaxWidth(), minHeight = 48.dp)
        "settings.discard" -> TextButton("取消更改", { clearDrafts(); clearRegisteredActionDrafts(); localError = "" }, Modifier.fillMaxWidth(), minHeight = 48.dp)
        else -> Unit
    }
    if (localError.isNotEmpty() && (type == "settings.save" || type.startsWith("settings."))) {
        Text(localError, fontSize = 13.sp, color = AppPalette.destructive())
    }
}

@Composable private fun ModDeadlineText(rawDeadline: String) {
    val visible = LocalModSceneVisible.current
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(visible) { if (visible) while (true) { now = SystemClock.elapsedRealtime(); delay(1_000) } }
    val deadline = rawDeadline.toLongOrNull() ?: 0L
    val remaining = ((deadline - now).coerceAtLeast(0L) + 999L) / 1_000L
    Text("%02d:%02d".format(remaining / 60, remaining % 60), fontSize = 16.sp, color = AppPalette.text())
}

private fun nodeVisible(node: JSONObject, state: JSONObject?, config: JSONObject, data: JSONObject): Boolean {
    val condition = node.optJSONObject("visibleWhen") ?: return true
    val current = resolveBinding(condition.getString("bind"), state, config, data)
    val expected = condition.get("equals")
    return if (expected is Number) current.toDoubleOrNull()?.let { it == expected.toDouble() } == true else current == expected.toString()
}

private fun resolveArray(path: String, state: JSONObject?, config: JSONObject, data: JSONObject): List<Any> {
    val pieces = path.split('.'); var value: Any? = when (pieces.firstOrNull()) { "state" -> state; "config" -> config; "data" -> data; else -> null }
    pieces.drop(1).forEach { part -> value = when (val current = value) {
        is JSONObject -> current.opt(part)
        is JSONArray -> part.toIntOrNull()?.let { if (it in 0 until current.length()) current.opt(it) else null }
        else -> null
    } }
    val array = value as? JSONArray ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.opt(it)?.takeUnless { item -> item == JSONObject.NULL || item is JSONObject || item is JSONArray } }
}

private fun formatModValue(value: String, format: String): String = when (format) {
    "integer" -> value.toDoubleOrNull()?.toLong()?.toString() ?: value
    "decimal" -> value.toDoubleOrNull()?.let { "%.1f".format(it) } ?: value
    "percent" -> value.toDoubleOrNull()?.let { "%.0f%%".format(it) } ?: value
    "duration" -> value.toLongOrNull()?.let { "%02d:%02d".format(it.coerceAtLeast(0) / 60, it.coerceAtLeast(0) % 60) } ?: value
    else -> value
}

@Composable private fun modTone(tone: String): Color = when (tone) {
    "secondary" -> AppPalette.secondary()
    "success" -> colorResource(top.zainzh.rearscreen.R.color.remrss_charging_liquid)
    "warning" -> colorResource(top.zainzh.rearscreen.R.color.remrss_primary)
    "error" -> AppPalette.destructive()
    else -> AppPalette.text()
}

private fun horizontalAlignment(node: JSONObject) = when (node.optString("horizontalAlignment", "start")) {
    "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start
}
private fun verticalAlignment(node: JSONObject) = when (node.optString("verticalAlignment", "center")) {
    "top" -> Alignment.Top; "bottom" -> Alignment.Bottom; else -> Alignment.CenterVertically
}
private fun rowArrangement(node: JSONObject, gap: androidx.compose.ui.unit.Dp) = when (node.optString("horizontalAlignment", "start")) {
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    "end" -> Arrangement.spacedBy(gap, Alignment.End)
    "spaceBetween" -> Arrangement.SpaceBetween
    else -> Arrangement.spacedBy(gap, Alignment.Start)
}
private fun boxAlignment(node: JSONObject) = when (node.optString("horizontalAlignment", "center")) {
    "start" -> when (node.optString("verticalAlignment", "center")) { "top" -> Alignment.TopStart; "bottom" -> Alignment.BottomStart; else -> Alignment.CenterStart }
    "end" -> when (node.optString("verticalAlignment", "center")) { "top" -> Alignment.TopEnd; "bottom" -> Alignment.BottomEnd; else -> Alignment.CenterEnd }
    else -> when (node.optString("verticalAlignment", "center")) { "top" -> Alignment.TopCenter; "bottom" -> Alignment.BottomCenter; else -> Alignment.Center }
}

private fun resolveBinding(path: String, state: JSONObject?, config: JSONObject, data: JSONObject): String {
    val pieces = path.split('.')
    var value: Any? = when (pieces.firstOrNull()) { "state" -> state; "config" -> config; "data" -> data; else -> null }
    for (piece in pieces.drop(1)) {
        value = when (value) {
            is JSONObject -> value.opt(piece)
            is JSONArray -> piece.toIntOrNull()?.let { if (it in 0 until value.length()) value.opt(it) else null }
            else -> null
        }
    }
    return when (value) { null, JSONObject.NULL -> ""; is JSONObject, is JSONArray -> value.toString(); else -> value.toString() }
}

private fun registeredActionRef(action: JSONObject, config: JSONObject): RegisteredActionRef? {
    val ref = action.optJSONObject("ref") ?: return null
    val resolved = if (ref.has("bind")) {
        val binding = ref.optString("bind")
        if (!binding.matches(Regex("^config\\.[A-Za-z][A-Za-z0-9_]{0,63}$"))) return null
        val raw = resolveBinding(binding, null, config, JSONObject())
        if (raw.isBlank()) return null
        runCatching { JSONObject(raw) }.getOrNull() ?: return null
    } else ref
    val id = resolved.optString("id")
    val version = resolved.opt("version")
    if (!id.matches(Regex("^[a-f0-9]{48}$")) || version !is Number || version.toInt() != 1 || version.toDouble() != 1.0) return null
    return RegisteredActionRef(id, 1)
}

private fun setJsonPath(target: JSONObject, path: String, value: Any) {
    val parts = path.split('.')
    require(parts.isNotEmpty() && parts.all { it.matches(Regex("^[A-Za-z][A-Za-z0-9_]*$")) }) { "配置路径无效" }
    var node = target
    parts.dropLast(1).forEach { part ->
        if (!node.has(part) || node.optJSONObject(part) == null) node.put(part, JSONObject())
        node = node.getJSONObject(part)
    }
    node.put(parts.last(), value)
}

private fun parseDraft(binding: String, value: String, config: JSONObject, schema: JSONObject): Any {
    val path = binding.removePrefix("config.").split('.')
    var typeSchema = schema
    path.forEach { key -> typeSchema = typeSchema.optJSONObject("properties")?.optJSONObject(key) ?: error("配置 schema 字段不存在") }
    return when (typeSchema.getString("type")) {
        "boolean" -> value.toBooleanStrict()
        "integer" -> value.toLong().also { require(it.toDouble() in typeSchema.getDouble("minimum")..typeSchema.getDouble("maximum")) { "整数超出范围" } }
        "number" -> value.toDouble().also { require(it.isFinite() && it in typeSchema.getDouble("minimum")..typeSchema.getDouble("maximum")) { "数值超出范围" } }
        "string" -> value.also { require(it.length <= typeSchema.getInt("maxLength")) { "文字超出长度" } }
        "object" -> JSONObject(value)
        else -> error("此输入控件不支持该配置类型")
    }
}

private fun JSONArray.asList(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
