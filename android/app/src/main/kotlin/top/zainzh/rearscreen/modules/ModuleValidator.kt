package top.zainzh.rearscreen.modules

import org.json.JSONArray
import org.json.JSONObject

internal object ModuleValidator {
    const val MAX_JSON_BYTES = 512 * 1024
    private val idPattern = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    private val nodeTypes = setOf("text", "column", "row", "card", "textInput", "number", "toggle", "button", "progress", "choice", "stageList")
    private val binds = setOf("config.title", "config.rounds", "config.keepVisible", "config.stages", "config.preset", "state.stageName", "state.remainingText", "state.position", "state.progress")
    private val actions = setOf("start", "pause", "resume", "stop", "show", "close", "save")
    private val states = setOf("enabled", "active", "running", "paused", "finished", "idle")

    fun parseAndValidate(bytes: ByteArray): ModuleDescriptor = try {
        parse(bytes)
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("module.json 字段结构无效", e)
    }

    private fun parse(bytes: ByteArray): ModuleDescriptor {
        require(bytes.isNotEmpty() && bytes.size <= MAX_JSON_BYTES) { "module.json 为空或超过 512 KiB" }
        val root = try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch (e: Exception) {
            throw IllegalArgumentException("module.json 不是有效 JSON", e)
        }
        require(root.keys().asSequence().all { it in setOf("schemaVersion", "id", "versionCode", "versionName", "minHostApi", "maxHostApi", "runtime", "name", "description", "presets", "defaultConfig", "settingsUi", "rearUi") }) { "module.json 包含未知字段" }
        require(intValue(root, "schemaVersion") == 1) { "不支持的 schemaVersion" }
        val id = requiredString(root, "id", "id", 64)
        require(idPattern.matches(id)) { "模块 id 格式无效" }
        val versionCode = intValue(root, "versionCode") ?: -1
        require(versionCode > 0) { "versionCode 必须为正整数" }
        val versionName = requiredString(root, "versionName", "versionName", 64)
        require(versionName.isNotBlank()) { "versionName 不能为空" }
        val minApi = intValue(root, "minHostApi") ?: -1
        val maxApi = intValue(root, "maxHostApi") ?: -1
        require(minApi >= 1 && maxApi >= minApi && minApi <= 2 && maxApi >= 1) { "模块与当前宿主 API 不兼容" }
        val runtime = requiredString(root, "runtime", "runtime", 32)
        require(runtime == "stage_timer") { "不支持的 runtime：$runtime" }
        validateLocalized(root.optJSONObject("name") ?: fail("缺少 name"), "name", true)
        if (root.has("description")) validateLocalized(root.optJSONObject("description") ?: fail("description 必须是本地化对象"), "description", false)
        val presets = root.optJSONArray("presets") ?: fail("缺少 presets")
        require(presets.length() in 1..32) { "presets 数量必须为 1–32" }
        val presetIds = mutableSetOf<String>()
        for (i in 0 until presets.length()) {
            val p = presets.getJSONObject(i)
            val pid = requiredString(p, "id", "preset.id", 64)
            require(idPattern.matches(pid) && presetIds.add(pid)) { "预设 id 无效或重复" }
            validateLocalized(p.optJSONObject("name") ?: fail("预设缺少 name"), "preset.name", true)
            val rounds = intValue(p, "rounds") ?: -1
            require(rounds in 1..50) { "预设 rounds 必须为 1–50" }
            validateStages(p.optJSONArray("stages") ?: fail("预设缺少 stages"), true, rounds)
        }
        val defaultConfig = root.optJSONObject("defaultConfig") ?: fail("缺少 defaultConfig")
        validateConfig(defaultConfig)
        if (defaultConfig.has("preset")) require(requiredString(defaultConfig, "preset", "defaultConfig.preset", 64) in presetIds) { "defaultConfig.preset 未指向有效预设" }
        validateNodes(root.optJSONArray("settingsUi") ?: fail("缺少 settingsUi"), presets.length())
        validateNodes(root.optJSONArray("rearUi") ?: fail("缺少 rearUi"), presets.length())
        return ModuleDescriptor(id, versionCode, versionName, runtime, root)
    }

    private fun validateLocalized(obj: JSONObject, field: String, required: Boolean) {
        require(obj.length() in if (required) 1..3 else 0..3) { "$field 语言数量无效" }
        for (key in obj.keys()) {
            require(key in setOf("zh-CN", "zh-TW", "en")) { "$field 含不支持的语言 $key" }
            requiredString(obj, key, "$field.$key", 240).also { require(it.isNotBlank()) { "$field.$key 不能为空" } }
        }
    }

    private fun validateConfig(obj: JSONObject) {
        require(requiredString(obj, "title", "defaultConfig.title", 80).trim().isNotEmpty()) { "defaultConfig.title 不能为空" }
        require(obj.has("title")) { "defaultConfig 缺少 title" }
        val rounds = intValue(obj, "rounds") ?: -1
        require(rounds in 1..50) { "defaultConfig.rounds 必须为 1–50" }
        require(obj.has("keepVisible") && obj.opt("keepVisible") is Boolean) { "defaultConfig.keepVisible 必须为布尔值" }
        validateStages(obj.optJSONArray("stages") ?: fail("defaultConfig 缺少 stages"), false, rounds)
    }

    private fun validateStages(stages: JSONArray, localized: Boolean, rounds: Int) {
        require(stages.length() in 1..20) { "阶段数量必须为 1–20" }
        var cycleSeconds = 0L
        for (i in 0 until stages.length()) {
            val s = stages.getJSONObject(i)
            if (localized) {
                val names = s.optJSONObject("name") ?: fail("阶段缺少 name")
                validateLocalized(names, "stage.name", true)
                for (locale in names.keys()) bounded(names.optString(locale), "stage.name.$locale", 60)
            } else requiredString(s, "name", "stage.name", 60).also { require(it.isNotBlank()) { "阶段名称不能为空" } }
            val duration = intValue(s, "durationSeconds") ?: -1
            require(duration in 1..86400) { "阶段时长必须为 1–86400 秒" }
            cycleSeconds += duration.toLong()
        }
        require(cycleSeconds * rounds <= 86400L) { "计时计划总时长（含轮数）不能超过 24 小时" }
    }

    private fun validateNodes(nodes: JSONArray, presetCount: Int) {
        require(nodes.length() <= 128) { "页面节点不能超过 128 个" }
        val ids = mutableSetOf<String>()
        var count = 0
        fun visit(array: JSONArray, depth: Int) {
            require(depth <= 8) { "节点树深度不能超过 8 层" }
            for (i in 0 until array.length()) {
                require(++count <= 128) { "页面节点不能超过 128 个" }
                val node = array.getJSONObject(i)
                val id = requiredString(node, "id", "node.id", 64)
                require(id.isNotBlank() && id.length <= 64 && ids.add(id)) { "节点 id 为空、过长或重复" }
                val type = requiredString(node, "type", "node.type", 32)
                require(type in nodeTypes) { "未知节点类型：$type" }
                node.opt("text")?.let { text ->
                    require(text is String || text is JSONObject) { "节点 text 必须为字符串或本地化对象" }
                    if (text is String) validateText(text) else {
                        val localized = text as JSONObject
                        validateLocalized(localized, "node.text", true)
                        for (locale in localized.keys()) validateText(localized.optString(locale))
                    }
                }
                optionalString(node, "bind").takeIf { it.isNotEmpty() }?.let { bind ->
                    require(bind in binds) { "未知 bind：$bind" }
                    val compatible = when (type) {
                        "textInput" -> bind == "config.title"
                        "number" -> bind == "config.rounds"
                        "toggle" -> bind == "config.keepVisible"
                        "stageList" -> bind == "config.stages"
                        "choice" -> bind == "config.preset"
                        "progress" -> bind == "state.progress"
                        "text" -> bind in setOf("config.title", "config.rounds", "state.stageName", "state.remainingText", "state.position")
                        else -> false
                    }
                    require(compatible) { "节点类型 $type 不支持 bind：$bind" }
                }
                optionalString(node, "action").takeIf { it.isNotEmpty() }?.let { action ->
                    require(action in actions) { "未知 action：$action" }
                    require(type == "button") { "只有 button 节点可以声明 action" }
                }
                optionalString(node, "visibleWhen").takeIf { it.isNotEmpty() }?.let(::validateCondition)
                optionalString(node, "enabledWhen").takeIf { it.isNotEmpty() }?.let(::validateCondition)
                if (node.has("style")) (node.optJSONObject("style") ?: fail("node.style 必须是对象")).let { style ->
                    require(style.keys().asSequence().all { it in setOf("size", "gap") }) { "style 仅允许 size 和 gap" }
                    optionalString(style, "size").takeIf { it.isNotEmpty() }?.let { require(it in setOf("title", "body", "caption", "timer")) { "未知语义字号" } }
                    if (style.has("gap")) require((intValue(style, "gap") ?: -1) in 0..64) { "布局 gap 必须为 0–64" }
                }
                if (type == "number") {
                    val min = if (node.has("min")) intValue(node, "min") ?: -1 else 1
                    val max = if (node.has("max")) intValue(node, "max") ?: -1 else 50
                    require(min in 1..50 && max in 1..50 && min <= max) { "number min/max 必须在 1–50 且 min 不大于 max" }
                } else require(!node.has("min") && !node.has("max")) { "仅 number 节点可声明 min/max" }
                node.opt("children")?.let { children ->
                    require(type in setOf("column", "row", "card")) { "仅容器节点可包含 children" }
                    require(children is JSONArray) { "children 必须为数组" }
                    visit(children as JSONArray, depth + 1)
                }
                if (node.has("presetIndex")) require((intValue(node, "presetIndex") ?: -1) in 0 until presetCount) { "presetIndex 超出预设范围" }
                require(node.keys().asSequence().all { it in setOf("id", "type", "text", "bind", "action", "visibleWhen", "enabledWhen", "style", "children", "presetIndex", "hint", "placeholder", "min", "max") }) { "节点包含未知字段" }
                listOf("hint", "placeholder").forEach { k -> if (node.has(k)) requiredString(node, k, "node.$k", 120) }
            }
        }
        visit(nodes, 1)
    }

    private fun validateText(value: String) {
        bounded(value, "node.text", 500)
        val stripped = Regex("\\{(state|config)\\.[A-Za-z][A-Za-z0-9]*\\}").replace(value, "")
        require(!stripped.contains('{') && !stripped.contains('}')) { "文本占位符仅支持 {state.xxx} 或 {config.xxx}" }
        Regex("\\{(state|config)\\.([A-Za-z][A-Za-z0-9]*)\\}").findAll(value).forEach { m ->
            val full = "${m.groupValues[1]}.${m.groupValues[2]}"
            require(full in binds) { "文本占位符不在白名单：$full" }
        }
    }

    private fun validateCondition(value: String) {
        val key = value.removePrefix("!")
        require(key in states) { "visibleWhen/enabledWhen 条件无效：$value" }
    }

    private fun bounded(value: String, field: String, max: Int): String {
        require(value.length <= max) { "$field 超过 $max 个字符" }
        return value
    }

    private fun requiredString(obj: JSONObject, key: String, field: String, max: Int): String {
        val value = obj.opt(key) as? String ?: fail("$field 必须为字符串")
        return bounded(value, field, max)
    }

    private fun optionalString(obj: JSONObject, key: String): String =
        if (!obj.has(key)) "" else obj.opt(key) as? String ?: fail("$key 必须为字符串")

    private fun intValue(obj: JSONObject, key: String): Int? {
        val value = obj.opt(key) as? Number ?: return null
        val number = value.toDouble()
        if (!number.isFinite() || number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
        return number.toInt()
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}
