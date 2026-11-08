package top.zainzh.rearscreen.modules

import org.json.JSONObject
import java.util.Locale

data class ModuleDescriptor(
    val id: String,
    val versionCode: Int,
    val versionName: String,
    val runtime: String,
    val document: JSONObject
) {
    fun label(localeTag: String): String = localized(document.optJSONObject("name"), localeTag)
    fun description(localeTag: String): String = localized(document.optJSONObject("description"), localeTag)

    private fun localized(values: JSONObject?, localeTag: String): String {
        if (values == null) return ""
        val locale = Locale.forLanguageTag(localeTag.replace('_', '-'))
        val exact = locale.toLanguageTag()
        val mapped = when {
            locale.language == "zh" && (locale.script.equals("Hans", true) || locale.country in setOf("CN", "SG")) -> "zh-CN"
            locale.language == "zh" && (locale.script.equals("Hant", true) || locale.country in setOf("TW", "HK", "MO")) -> "zh-TW"
            locale.language == "en" -> "en"
            else -> ""
        }
        return values.optString(exact).takeIf { it.isNotBlank() }
            ?: values.optString(mapped).takeIf { it.isNotBlank() }
            ?: values.optString(locale.language).takeIf { it.isNotBlank() }
            ?: values.optString("en").takeIf { it.isNotBlank() }
            ?: values.keys().asSequence().map { values.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
    }
}
