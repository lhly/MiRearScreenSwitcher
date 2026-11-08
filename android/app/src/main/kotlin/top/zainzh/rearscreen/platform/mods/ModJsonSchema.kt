package top.zainzh.rearscreen.platform.mods

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Bounded, deliberately small JSON Schema subset shared by model transactions and manifest checks. */
internal object ModJsonSchema {
    private val schemaKey = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")

    fun validateDefinition(schema: JSONObject, field: String) {
        validateDefinitionNode(schema, field, 0)
        require(schema.optString("type") == "object") { "$field 根类型必须是 object" }
    }

    fun validateValue(schema: JSONObject, value: Any?, field: String = "model", depth: Int = 0) {
        require(depth <= 8) { "$field 超过最大深度" }
        val type = schema.optString("type")
        when (type) {
            "object" -> {
                require(value is JSONObject) { "$field 必须为 object" }
                val props = schema.optJSONObject("properties") ?: JSONObject()
                val keys = value.keys().asSequence().toSet()
                val required = schema.optJSONArray("required")?.toStringSet().orEmpty()
                val missing = required - keys
                require(missing.isEmpty()) { "$field 缺少必需字段：${missing.sorted().joinToString("、")}" }
                if (!schema.optBoolean("additionalProperties", false)) {
                    require(keys.all { props.has(it) }) { "$field 含 schema 未声明字段" }
                }
                keys.forEach { key ->
                    if (props.has(key)) validateValue(props.getJSONObject(key), value.get(key), "$field.$key", depth + 1)
                }
            }
            "array" -> {
                require(value is JSONArray) { "$field 必须为 array" }
                val max = schema.optInt("maxItems", -1)
                val min = schema.optInt("minItems", 0)
                require(min >= 0 && max in min..128 && value.length() in min..max) { "$field 数组长度超出 schema" }
                val itemSchema = schema.optJSONObject("items") ?: error("$field items 缺失")
                for (i in 0 until value.length()) validateValue(itemSchema, value.get(i), "$field[$i]", depth + 1)
            }
            "integer" -> {
                require(value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0) { "$field 必须为有限整数" }
                boundedNumber(schema, value.toDouble(), field)
            }
            "number" -> {
                require(value is Number && value.toDouble().isFinite()) { "$field 必须为有限数字" }
                boundedNumber(schema, value.toDouble(), field)
            }
            "string" -> {
                require(value is String && value.length <= schema.optInt("maxLength", -1).takeIf { it >= 0 } ?: 4096) { "$field 字符串超长或类型错误" }
            }
            "boolean" -> require(value is Boolean) { "$field 必须为 boolean" }
            else -> error("$field schema 类型无效")
        }
        if (schema.has("enum")) {
            val choices = schema.getJSONArray("enum")
            require((0 until choices.length()).any { choices.get(it) == value }) { "$field 不在 enum 范围" }
        }
        if (schema.has("const")) require(schema.get("const") == value) { "$field 与 const 不符" }
    }

    fun hasPath(schema: JSONObject, path: String): Boolean {
        return atPath(schema, path) != null
    }

    fun atPath(schema: JSONObject, path: String): JSONObject? {
        var node = schema
        for (part in path.split('.')) {
            node = node.optJSONObject("properties")?.optJSONObject(part) ?: return null
        }
        return node
    }

    private fun validateDefinitionNode(schema: JSONObject, field: String, depth: Int) {
        require(depth <= 8) { "$field schema 嵌套超过 8 层" }
        val allowed = setOf("type", "properties", "required", "additionalProperties", "items", "minimum", "maximum", "minItems", "maxItems", "maxLength", "enum", "const", "default")
        val it = schema.keys()
        while (it.hasNext()) require(it.next() in allowed) { "$field schema 含未知字段" }
        when (schema.optString("type")) {
            "object" -> {
                val props = schema.optJSONObject("properties") ?: JSONObject()
                require(props.length() <= 64) { "$field 最多声明 64 个字段" }
                val keys = props.keys().asSequence().toSet()
                require(keys.all { schemaKey.matches(it) }) { "$field 字段名无效" }
                val required = schema.optJSONArray("required")?.toStringSet().orEmpty()
                require(required.size == (schema.optJSONArray("required")?.length() ?: 0) && required.all { it in keys }) { "$field.required 无效" }
                if (field.startsWith("configSchema")) require(required.all { props.getJSONObject(it).has("default") }) { "$field.required 配置字段必须声明 default" }
                require(!schema.optBoolean("additionalProperties", false)) { "$field.additionalProperties 首版必须为 false" }
                keys.forEach { validateDefinitionNode(props.getJSONObject(it), "$field.$it", depth + 1) }
            }
            "array" -> {
                val max = schema.optInt("maxItems", -1)
                val min = schema.optInt("minItems", 0)
                require(min >= 0 && max in min..128) { "$field.maxItems 必须是 0..128 的有界值" }
                validateDefinitionNode(schema.optJSONObject("items") ?: error("$field.items 缺失"), "$field[]", depth + 1)
            }
            "integer", "number" -> {
                require(schema.has("minimum") && schema.has("maximum")) { "$field 必须声明最小/最大值" }
                val min = schema.getDouble("minimum"); val max = schema.getDouble("maximum")
                require(min.isFinite() && max.isFinite() && min <= max && abs(min) <= 1e12 && abs(max) <= 1e12) { "$field 数值范围无效" }
            }
            "string" -> require(schema.optInt("maxLength", -1) in 0..4096) { "$field.maxLength 必须为 0..4096" }
            "boolean" -> Unit
            else -> error("$field schema type 无效")
        }
        if (schema.has("enum")) require(schema.getJSONArray("enum").length() in 1..64) { "$field.enum 长度无效" }
        if (schema.has("const")) require(schema.get("const") !is JSONObject && schema.get("const") !is JSONArray) { "$field.const 必须是标量" }
        if (schema.has("default")) validateValue(schema, schema.get("default"), "$field.default")
    }

    private fun boundedNumber(schema: JSONObject, value: Double, field: String) {
        require(value >= schema.optDouble("minimum", Double.NaN) && value <= schema.optDouble("maximum", Double.NaN)) { "$field 超出 schema 范围" }
    }

    private fun JSONArray.toStringSet(): Set<String> = (0 until length()).map { getString(it) }.toSet()
}
