package top.zainzh.rearscreen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.modules.ModuleDescriptor
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.window.WindowDialog

/** Shared interpreter for every protocol node on both host and rear display. */
@Composable
internal fun NativeModuleNodes(
    nodes: JSONArray,
    descriptor: ModuleDescriptor,
    config: JSONObject,
    state: JSONObject,
    rear: Boolean,
    onConfig: (JSONObject) -> Unit,
    onAction: (String) -> Unit,
    onValidity: (String,Boolean) -> Unit = { _,_ -> },
    actionsEnabled: Boolean = true
) {
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    for (index in 0 until nodes.length()) {
        val node = nodes.getJSONObject(index)
        key(node.optString("id", index.toString())) {
            NativeModuleNode(node, descriptor, config, state, locale, rear, onConfig, onAction, onValidity, actionsEnabled)
        }
    }
}

@Composable
private fun NativeModuleNode(
    node: JSONObject,
    descriptor: ModuleDescriptor,
    config: JSONObject,
    state: JSONObject,
    locale: String,
    rear: Boolean,
    onConfig: (JSONObject) -> Unit,
    onAction: (String) -> Unit,
    onValidity: (String,Boolean) -> Unit,
    actionsEnabled: Boolean
) {
    val id = node.optString("id")
    val type = node.optString("type")
    val bind = node.optString("bind")
    val modifier = Modifier.testTag("module_node_$id")
    val condition = node.optString("visibleWhen")
    if (condition.isNotEmpty() && !nativeCondition(condition, state, descriptor)) return
    val enabledWhen = node.optString("enabledWhen")
    val enabled = enabledWhen.isEmpty() || nativeCondition(enabledWhen, state, descriptor)
    val text = nativeText(node.opt("text"), locale, config, state)
    val gap = (node.optJSONObject("style")?.optInt("gap", 8) ?: 8).dp
    when (type) {
        "column" -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            NativeModuleNodes(node.optJSONArray("children") ?: JSONArray(), descriptor, config, state, rear, onConfig, onAction, onValidity, actionsEnabled)
        }
        "row" -> Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            val children=node.optJSONArray("children")?:JSONArray()
            for(index in 0 until children.length()) {
                val child = children.getJSONObject(index)
                key(child.optString("id", index.toString())) {
                    Box(Modifier.weight(1f)) {
                        NativeModuleNode(child,descriptor,config,state,locale,rear,onConfig,onAction,onValidity,actionsEnabled)
                    }
                }
            }
        }
        "card" -> AppCard(modifier) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(gap.coerceAtLeast(10.dp))) {
                NativeModuleNodes(node.optJSONArray("children") ?: JSONArray(), descriptor, config, state, rear, onConfig, onAction, onValidity, actionsEnabled)
            }
        }
        "text" -> {
            val value = if (bind.isEmpty()) text else nativeBinding(bind, config, state)
            val size = when (node.optJSONObject("style")?.optString("size")) {
                "title" -> if (rear) 24.sp else 24.sp
                "timer" -> if (rear) 48.sp else 36.sp
                "caption" -> 13.sp
                else -> 16.sp
            }
            Text(value, modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = size, color = AppPalette.text())
        }
        "textInput" -> {
            EditableValueNode(node, text, modifier, config, enabled, false, onConfig, onValidity)
        }
        "number" -> EditableValueNode(node, text, modifier, config, enabled, true, onConfig, onValidity)
        "toggle" -> {
            val key = bind.removePrefix("config.")
            AppToggle(text, null, config.optBoolean(key, false), enabled) {
                onConfig(JSONObject(config.toString()).put(key, it))
            }
        }
        "choice" -> ChoiceNode(node, text, modifier, descriptor, locale, config, enabled, onConfig)
        "stageList" -> StageListNode(id,text, modifier, config, enabled, onConfig,onValidity)
        "progress" -> LinearProgressIndicator(
            progress = state.optDouble("progress").toFloat().coerceIn(0f, 1f),
            modifier = modifier.fillMaxWidth().padding(14.dp)
        )
        "button" -> {
            val action=node.optString("action")
            val configAction=action=="start"||action=="save"
            TextButton(text, { onAction(action) },
                modifier.fillMaxWidth(), minHeight = 48.dp, enabled = enabled&&(!configAction||actionsEnabled),
                colors = if (action == "start" || action == "resume") ButtonDefaults.textButtonColorsPrimary()
                    else ButtonDefaults.textButtonColors())
        }
    }
}

@Composable
private fun EditableValueNode(
    node: JSONObject,
    label: String,
    modifier: Modifier,
    config: JSONObject,
    enabled: Boolean,
    number: Boolean,
    onConfig: (JSONObject) -> Unit,
    onValidity: (String, Boolean) -> Unit
) {
    val id = node.optString("id")
    val bind = node.optString("bind")
    val key = bind.removePrefix("config.")
    val minimum = node.optInt("min", 1).coerceAtLeast(1)
    val maximum = node.optInt("max", if (number) 50 else Int.MAX_VALUE).coerceAtMost(if (number) 50 else 80).coerceAtLeast(minimum)
    val current = config.opt(key)?.toString().orEmpty()
    var showDialog by rememberSaveable(id) { mutableStateOf(false) }
    var draft by rememberSaveable(id) { mutableStateOf(current) }
    var dirty by rememberSaveable(id) { mutableStateOf(false) }
    var invalid by rememberSaveable(id) { mutableStateOf(false) }
    val valid = if (number) draft.toIntOrNull()?.let { it in minimum..maximum } == true
        else draft.isNotBlank() && draft.length <= maximum
    val help = if (number) stringResource(R.string.native_modules_number_range, minimum, maximum)
        else stringResource(R.string.native_modules_text_length_range)
    SideEffect { onValidity(id, !invalid && (!dirty || !showDialog)) }

    val editingDescription = stringResource(R.string.native_modules_editing)
    AppRow(label, current.ifBlank { stringResource(R.string.native_modules_not_set) },
        modifier.semantics { stateDescription = if (showDialog) editingDescription else "" },
        enabled = enabled, action = {
            draft = current
            dirty = false
            invalid = false
            showDialog = true
        })

    WindowDialog(show = showDialog, title = label, onDismissRequest = {
        showDialog = false
        dirty = false
        invalid = false
        onValidity(id, true)
    }) {
        val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .65f }
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).testTag("module_dialog_$id"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(draft, { value ->
            draft = value
                dirty = true
            val nextValid = if (number) value.toIntOrNull()?.let { it in minimum..maximum } == true
                else value.isNotBlank() && value.length <= maximum
            invalid = !nextValid
            onValidity(id, false)
            }, modifier = Modifier.fillMaxWidth().testTag("module_editor_$id"), label = label,
                singleLine = true, enabled = enabled,
                keyboardOptions = if (number) androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
                    else androidx.compose.foundation.text.KeyboardOptions())
            if (invalid) Text(help, color = AppPalette.accent(), fontSize = 13.sp)
            TextButton(stringResource(R.string.ui_apply), {
                if (!valid) {
                    invalid = true
                    onValidity(id, false)
                } else {
                    val value: Any = if (number) draft.toInt() else draft.trim()
                    onConfig(JSONObject(config.toString()).put(key, value))
                    dirty = false
                    invalid = false
                    showDialog = false
                    onValidity(id, true)
                }
            }, Modifier.fillMaxWidth(), minHeight = 48.dp, enabled = enabled && valid,
                colors = ButtonDefaults.textButtonColorsPrimary())
            TextButton(stringResource(R.string.ui_cancel), {
                showDialog = false
                dirty = false
                invalid = false
                onValidity(id, true)
            }, Modifier.fillMaxWidth().testTag("module_editor_cancel_$id"), minHeight = 48.dp)
        }
    }
}

@Composable
private fun ChoiceNode(node: JSONObject, label: String, modifier: Modifier, descriptor: ModuleDescriptor, locale: String,
    config: JSONObject, enabled:Boolean, onConfig: (JSONObject) -> Unit) {
    val presets = descriptor.document.optJSONArray("presets") ?: JSONArray()
    val activeId = config.optString("preset")
    val active = (0 until presets.length()).map { presets.getJSONObject(it) }
    val selectedName = active.firstOrNull { it.optString("id") == activeId }?.let { nativeText(it.opt("name"), locale, config, JSONObject()) }.orEmpty()
    var showDialog by rememberSaveable(node.optString("id")) { mutableStateOf(false) }
    AppRow(label, selectedName, modifier = modifier, enabled=enabled, action = { showDialog = true })
    WindowDialog(show = showDialog, title = label, onDismissRequest = { showDialog = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .65f })
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            active.forEach { preset ->
                val presetName = nativeText(preset.opt("name"), locale, config, JSONObject())
                val selected = preset.optString("id") == activeId
                AppRow(presetName, if (selected) stringResource(R.string.native_modules_selected) else null,
                    enabled = enabled, action = {
                        val stages = preset.getJSONArray("stages")
                        val next = JSONObject(config.toString()).put("preset", preset.optString("id"))
                            .put("rounds", preset.optInt("rounds"))
                            .put("stages", JSONArray().apply {
                                for (i in 0 until stages.length()) {
                                    val stage = stages.getJSONObject(i)
                                    put(JSONObject().put("name", nativeText(stage.opt("name"), locale, config, JSONObject()))
                                        .put("durationSeconds", stage.optInt("durationSeconds")))
                                }
                            })
                        onConfig(next)
                        showDialog = false
                    })
            }
        }
    }
}

@Composable
private fun StageListNode(
    nodeId: String,
    label: String,
    modifier: Modifier,
    config: JSONObject,
    enabled: Boolean,
    onConfig: (JSONObject) -> Unit,
    onValidity: (String, Boolean) -> Unit
) {
    val stages = config.optJSONArray("stages") ?: JSONArray()
    val newStageName = stringResource(R.string.native_modules_new_stage)
    val totalSeconds = (0 until stages.length()).sumOf { stages.getJSONObject(it).optLong("durationSeconds") }
    val rounds = config.optInt("rounds", 1).coerceAtLeast(1).toLong()
    val totalValid = totalSeconds <= 86400L && rounds <= 86400L / totalSeconds.coerceAtLeast(1L)
    var editingIndex by rememberSaveable(nodeId) { mutableIntStateOf(-1) }
    var nameDraft by rememberSaveable(nodeId, "name") { mutableStateOf("") }
    var secondsDraft by rememberSaveable(nodeId, "seconds") { mutableStateOf("") }
    var editDirty by rememberSaveable(nodeId, "dirty") { mutableStateOf(false) }
    var nameInvalid by rememberSaveable(nodeId, "name-invalid") { mutableStateOf(false) }
    var secondsInvalid by rememberSaveable(nodeId, "seconds-invalid") { mutableStateOf(false) }
    SideEffect { onValidity(nodeId, totalValid) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 16.sp, color = AppPalette.text(), modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        for (index in 0 until stages.length()) {
            val stage = stages.getJSONObject(index)
            val name = stage.optString("name")
            key("$nodeId.stage.$index") {
                AppRow(name, durationSummary(stage.optInt("durationSeconds")),
                    modifier = Modifier.testTag("module_node_${nodeId}_stage_$index"), enabled = enabled,
                    action = {
                        editingIndex = index
                        nameDraft = name
                        secondsDraft = stage.optInt("durationSeconds").toString()
                    editDirty = false
                        nameInvalid = false
                        secondsInvalid = false
                    })
            }
            if (index < stages.length() - 1) AppDivider()
        }
        if (!totalValid) Text(stringResource(R.string.native_modules_total_duration_range),
            Modifier.padding(horizontal = 18.dp), fontSize = 13.sp, color = AppPalette.accent())
        AppRow(stringResource(R.string.native_modules_add_stage), null,
            modifier = Modifier.testTag("module_node_${nodeId}_add"), enabled = enabled && stages.length() < 20,
            action = {
                if (enabled && stages.length() < 20) {
                    val copy = mutableStages(config).put(JSONObject().put("name", newStageName).put("durationSeconds", 60))
                    onConfig(JSONObject(config.toString()).put("stages", copy))
                }
            })
    }

    val selected = stages.optJSONObject(editingIndex)
    val nameId = "$nodeId.name.$editingIndex"
    val secondsId = "$nodeId.duration.$editingIndex"
    val nameValid = nameDraft.isNotBlank() && nameDraft.length <= 60
    val secondsValue = secondsDraft.toIntOrNull()
    val secondsValid = secondsValue != null && secondsValue in 1..86400
    val candidateTotalSeconds = if (selected != null && secondsValid) {
        totalSeconds - selected.optLong("durationSeconds") + secondsValue!!.toLong()
    } else {
        totalSeconds
    }
    val candidateTotalValid = candidateTotalSeconds <= 86400L &&
        rounds <= 86400L / candidateTotalSeconds.coerceAtLeast(1L)
    SideEffect {
        if (selected != null) {
            onValidity(nameId, nameValid && !editDirty)
            onValidity(secondsId, secondsValid && !editDirty)
        }
    }

    WindowDialog(show = selected != null, title = selected?.optString("name"), onDismissRequest = {
        if (editingIndex >= 0) {
            onValidity(nameId, true)
            onValidity(secondsId, true)
        }
        editingIndex = -1
        editDirty = false
        nameInvalid = false
        secondsInvalid = false
    }) {
        val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .7f }
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).testTag("module_dialog_$nodeId"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(nameDraft, { value ->
                nameDraft = value
                editDirty = true
                nameInvalid = value.isBlank() || value.length > 60
                onValidity(nameId, !nameInvalid)
            }, Modifier.fillMaxWidth().testTag("module_editor_${nodeId}_name"),
                label = stringResource(R.string.native_modules_stage_name), singleLine = true, enabled = enabled)
            if (nameInvalid) Text(stringResource(R.string.native_modules_stage_name_range), color = AppPalette.accent())
            TextField(secondsDraft, { value ->
                secondsDraft = value
                editDirty = true
                secondsInvalid = value.toIntOrNull()?.let { it !in 1..86400 } ?: true
                onValidity(secondsId, !secondsInvalid)
            }, Modifier.fillMaxWidth().testTag("module_editor_${nodeId}_seconds"),
                label = stringResource(R.string.native_modules_seconds), singleLine = true, enabled = enabled,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
            if (secondsInvalid) Text(stringResource(R.string.native_modules_seconds_range), color = AppPalette.accent())
            if (nameValid && secondsValid && !candidateTotalValid) {
                Text(stringResource(R.string.native_modules_total_duration_range), color = AppPalette.accent())
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(stringResource(R.string.native_modules_move_up), {
                    if (editingIndex > 0 && nameValid && secondsValid) {
                        val values = (0 until stages.length()).map { JSONObject(stages.getJSONObject(it).toString()) }.toMutableList()
                        values[editingIndex].put("name", nameDraft.trim()).put("durationSeconds", secondsValue)
                        java.util.Collections.swap(values, editingIndex, editingIndex - 1)
                        onConfig(JSONObject(config.toString()).put("stages", JSONArray(values)))
                        onValidity(nameId, true); onValidity(secondsId, true); editingIndex = -1; editDirty = false
                    }
                }, Modifier.weight(1f), minHeight = 48.dp,
                    enabled = enabled && editingIndex > 0 && nameValid && secondsValid && candidateTotalValid)
                TextButton(stringResource(R.string.native_modules_move_down), {
                    if (editingIndex < stages.length() - 1 && nameValid && secondsValid) {
                        val values = (0 until stages.length()).map { JSONObject(stages.getJSONObject(it).toString()) }.toMutableList()
                        values[editingIndex].put("name", nameDraft.trim()).put("durationSeconds", secondsValue)
                        java.util.Collections.swap(values, editingIndex, editingIndex + 1)
                        onConfig(JSONObject(config.toString()).put("stages", JSONArray(values)))
                        onValidity(nameId, true); onValidity(secondsId, true); editingIndex = -1; editDirty = false
                    }
                }, Modifier.weight(1f), minHeight = 48.dp,
                    enabled = enabled && editingIndex < stages.length() - 1 && nameValid && secondsValid && candidateTotalValid)
            }
            if (stages.length() > 1) TextButton(stringResource(R.string.native_modules_remove_stage), {
                val values = (0 until stages.length()).filter { it != editingIndex }
                    .map { JSONObject(stages.getJSONObject(it).toString()) }
                onConfig(JSONObject(config.toString()).put("stages", JSONArray(values)))
                onValidity(nameId, true); onValidity(secondsId, true); editingIndex = -1; editDirty = false
            }, Modifier.fillMaxWidth(), minHeight = 48.dp, enabled = enabled)

            TextButton(stringResource(R.string.ui_apply), {
                if (!nameValid || !secondsValid) {
                    nameInvalid = !nameValid
                    secondsInvalid = !secondsValid
                    onValidity(nameId, nameValid)
                    onValidity(secondsId, secondsValid)
                } else {
                    val copy = mutableStages(config).apply {
                        getJSONObject(editingIndex).put("name", nameDraft.trim()).put("durationSeconds", secondsValue)
                    }
                    onConfig(JSONObject(config.toString()).put("stages", copy))
                    onValidity(nameId, true); onValidity(secondsId, true); editingIndex = -1; editDirty = false
                }
            }, Modifier.fillMaxWidth(), minHeight = 48.dp,
                enabled = enabled && nameValid && secondsValid && candidateTotalValid,
                colors = ButtonDefaults.textButtonColorsPrimary())
            TextButton(stringResource(R.string.ui_cancel), {
                onValidity(nameId, true); onValidity(secondsId, true); editingIndex = -1; editDirty = false
                nameInvalid = false; secondsInvalid = false
            }, Modifier.fillMaxWidth().testTag("module_editor_cancel_$nodeId"), minHeight = 48.dp)
        }
    }
}

@Composable
private fun durationSummary(seconds: Int): String {
    return if (seconds >= 60 && seconds % 60 == 0) {
        stringResource(R.string.native_modules_minutes, seconds / 60)
    } else stringResource(R.string.native_modules_seconds_compact, seconds)
}

private fun mutableStages(config: JSONObject) = JSONArray().apply {
    val source = config.optJSONArray("stages") ?: JSONArray()
    for (index in 0 until source.length()) put(JSONObject(source.getJSONObject(index).toString()))
}
private fun reorder(config: JSONObject, from: Int, to: Int, onConfig: (JSONObject) -> Unit) {
    val stages = mutableStages(config); val values = (0 until stages.length()).map { stages.getJSONObject(it) }.toMutableList()
    java.util.Collections.swap(values, from, to)
    onConfig(JSONObject(config.toString()).put("stages", JSONArray(values)))
}
private fun removeStage(config: JSONObject, index: Int, onConfig: (JSONObject) -> Unit) {
    val stages = mutableStages(config); val values = (0 until stages.length()).filter { it != index }.map { stages.getJSONObject(it) }
    onConfig(JSONObject(config.toString()).put("stages", JSONArray(values)))
}

private fun nativeCondition(value: String, state: JSONObject, descriptor: ModuleDescriptor): Boolean {
    val negate = value.startsWith("!")
    val key = value.removePrefix("!")
    val enabled = when (key) {
        "enabled" -> state.optBoolean("enabled")
        "active", "running", "paused", "finished", "idle" -> state.optBoolean(key)
        else -> false
    }
    return if (negate) !enabled else enabled
}

private fun nativeText(value: Any?, locale: String, config: JSONObject, state: JSONObject): String {
    val normalized = locale.replace('_', '-').let { tag ->
        when {
            tag.equals("zh-CN", true) || tag.equals("zh-Hans", true) || tag.startsWith("zh-Hans-") || tag.equals("zh-SG", true) -> "zh-CN"
            tag.equals("zh-TW", true) || tag.equals("zh-Hant", true) || tag.startsWith("zh-Hant-") || tag.equals("zh-HK", true) -> "zh-TW"
            tag.startsWith("en-", true) || tag.equals("en", true) -> "en"
            else -> tag
        }
    }
    val raw = if (value is JSONObject) value.optString(normalized).ifBlank {
        value.optString(normalized.substringBefore('-')).ifBlank { value.optString("en") }
    } else value?.toString().orEmpty()
    return Regex("\\{(state|config)\\.([A-Za-z][A-Za-z0-9]*)\\}").replace(raw) { match ->
        val source = if (match.groupValues[1] == "state") state else config
        source.optString(match.groupValues[2], match.value)
    }
}

private fun nativeBinding(bind: String, config: JSONObject, state: JSONObject): String =
    if (bind.startsWith("state.")) state.optString(bind.removePrefix("state."))
    else config.optString(bind.removePrefix("config."))
