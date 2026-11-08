package top.zainzh.rearscreen.platform.actions

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.pm.LauncherApps

/** Converts provider output into a small, versioned descriptor instead of persisting an Intent/Parcelable. */
internal object ActionTargetPolicy {
    const val MAX_LABEL_CHARS = 80
    const val MAX_COMPONENT_CHARS = 240
    const val MAX_ACTION_CHARS = 160
    const val MAX_DATA_CHARS = 512
    const val MAX_EXTRA_KEYS = 16
    const val MAX_EXTRA_KEY_CHARS = 64
    const val MAX_TEXT_EXTRA_CHARS = 512
    const val MAX_TOTAL_EXTRA_CHARS = 2048
    const val MAX_CATEGORIES = 8
    const val MAX_CATEGORY_CHARS = 160

    private val allowedCategories = setOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_BROWSABLE)
    private val deniedSchemes = setOf(
        "android-app", "content", "file", "intent", "javascript", "market"
    )
    private val deniedActions = setOf(
        Intent.ACTION_CALL,
        Intent.ACTION_INSTALL_PACKAGE,
        Intent.ACTION_DELETE,
        Intent.ACTION_UNINSTALL_PACKAGE,
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_PACKAGE_ADDED,
        Intent.ACTION_PACKAGE_REMOVED
    )

    data class ParsedShortcut(val label: String, val component: ComponentName, val action: String,
                              val dataUri: String?, val categories: List<String>, val extras: Map<String, ActionExtra>)

    fun parseProviderResult(result: Intent): ParsedShortcut? = runCatching { parseProviderResultUnsafe(result) }.getOrNull()

    /** Sanitized shape for focused provider tests; deliberately omits URI values and extras. */
    internal fun providerResultShape(result: Intent): String {
        fun targetShape(intent: Intent?): String = if (intent == null) "none" else buildString {
            append("action=").append(intent.action)
            append(",component=").append(intent.component?.flattenToString())
            append(",scheme=").append(intent.data?.scheme)
            append(",categories=").append(intent.categories.orEmpty().sorted())
        }
        val request = getPinItemRequest(result)
        val info = request?.shortcutInfo
        val stack = info?.intents
        @Suppress("DEPRECATION")
        val legacy = runCatching { result.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) }.getOrNull()
        return "pin=${request != null},stackCount=${stack?.size},modern=${targetShape(stack?.singleOrNull() ?: info?.intent)},legacy=${targetShape(legacy)}"
    }

    private fun parseProviderResultUnsafe(result: Intent): ParsedShortcut? {
        // A modern provider may return a system PinItemRequest containing ShortcutInfo, while
        // legacy providers put the launch Intent in EXTRA_SHORTCUT_INTENT. Some system-mediated
        // ShortcutInfo results intentionally redact the executable Intent; those are rejected.
        // Extract only safe fields and never persist or accept the request/Parcelable itself.
        val modern = getPinItemRequest(result)?.shortcutInfo
        val modernIntents = modern?.intents
        if (modernIntents != null && modernIntents.size != 1) return null
        // ShortcutInfo may expose either its legacy one-Intent form (`getIntent`) or an ordered
        // back stack (`getIntents`). Only a one-entry form is safe for this action contract.
        val modernSingleIntent = modernIntents?.singleOrNull() ?: modern?.intent
        val target = modernSingleIntent
            ?: result.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
            ?: return null
        val component = target.component ?: return null
        if (component.packageName.isBlank() || component.className.isBlank() || component.flattenToString().length > MAX_COMPONENT_CHARS) return null
        if (target.`package` != null && target.`package` != component.packageName) return null
        if (target.selector != null || target.clipData != null) return null
        val action = target.action?.takeIf { it.isNotBlank() && it.length <= MAX_ACTION_CHARS } ?: return null
        if (action in deniedActions || action.startsWith("android.") && action != Intent.ACTION_MAIN && action != Intent.ACTION_VIEW) return null
        val categories = target.categories.orEmpty().sorted()
        if (categories.size > MAX_CATEGORIES || categories.any { !isBoundedCategory(it) }) return null
        val data = target.data?.toString()?.let { validateDataUri(it, component.packageName) } ?: if (target.data == null) null else return null
        val extras = parseExtras(target.extras ?: Bundle()) ?: return null
        val label = modern?.shortLabel?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL_CHARS }
            ?: result.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
            ?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL_CHARS }
            ?: component.className.substringAfterLast('.').take(MAX_LABEL_CHARS)
        return ParsedShortcut(label, component, action, data, categories, extras)
    }

    @Suppress("DEPRECATION")
    private fun getPinItemRequest(result: Intent): LauncherApps.PinItemRequest? {
        if (Build.VERSION.SDK_INT < 26) return null
        return runCatching {
        if (Build.VERSION.SDK_INT >= 33) result.getParcelableExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST, LauncherApps.PinItemRequest::class.java)
        else result.getParcelableExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST) as? LauncherApps.PinItemRequest
        }.getOrNull()
    }

    fun parseSelectedLauncher(selected: Intent): ParsedShortcut? = runCatching {
        val component = selected.component ?: return null
        if (component.packageName.isBlank() || component.className.isBlank() || component.flattenToString().length > MAX_COMPONENT_CHARS) return null
        if (selected.`package` != null && selected.`package` != component.packageName) return null
        if (selected.action != Intent.ACTION_MAIN || selected.data != null || selected.selector != null || selected.clipData != null) return null
        val categories = selected.categories.orEmpty().sorted()
        if (Intent.CATEGORY_LAUNCHER !in categories || categories.any { it !in allowedCategories } || categories.size > 2) return null
        val extras = parseExtras(selected.extras ?: Bundle()) ?: return null
        // The system picker should return no action arguments. Ignore its framework metadata only
        // when it is an empty Bundle; do not persist unexpected parameters from the picker.
        if (extras.isNotEmpty()) return null
        ParsedShortcut("", component, Intent.ACTION_MAIN, null, categories, emptyMap())
    }.getOrNull()

    fun validateDataUri(raw: String, targetPackage: String): String? {
        if (raw.isBlank() || raw.length > MAX_DATA_CHARS || raw.any { it.code < 0x20 }) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (!uri.isAbsolute || scheme in deniedSchemes) return null
        if (uri.userInfo != null) return null
        if (scheme == "http" || scheme == "https") {
            if (uri.host.isNullOrBlank()) return null
            // The fixed URL is delivered only to the selected explicit app component; Host never
            // fetches it and ZIP authors cannot rewrite it.
            return raw
        }
        val host = uri.host
        if (host != null && host.isNotBlank() && host.length > MAX_COMPONENT_CHARS) return null
        if (targetPackage.isBlank()) return null
        return raw
    }

    private fun isBoundedCategory(category: String): Boolean = category.isNotBlank() &&
        category.length <= MAX_CATEGORY_CHARS && category.none { it.code < 0x20 }

    private fun parseExtras(bundle: Bundle): Map<String, ActionExtra>? {
        val budget = ExtraBudget()
        return parseBundle(bundle, 0, budget)
    }

    private class ExtraBudget(var entries: Int = 0, var chars: Int = 0)

    private fun parseBundle(bundle: Bundle, depth: Int, budget: ExtraBudget): Map<String, ActionExtra>? {
        if (depth > 2 || bundle.size() > MAX_EXTRA_KEYS) return null
        // The outer Intent/ShortcutInfo containers are Android framework values. Inside a
        // provider-controlled extras Bundle, resolve only bootstrap/framework classes so an
        // arbitrary app Parcelable is never instantiated merely to reject it.
        bundle.classLoader = null
        val parsed = linkedMapOf<String, ActionExtra>()
        for (key in bundle.keySet().sorted()) {
            if (key.isBlank() || key.length > MAX_EXTRA_KEY_CHARS || key.any { it.code < 0x20 }) return null
            budget.entries++
            budget.chars += key.length
            if (budget.entries > MAX_EXTRA_KEYS || budget.chars > MAX_TOTAL_EXTRA_CHARS) return null
            val value = runCatching { bundle.get(key) }.getOrNull() ?: return null
            val safe = when (value) {
                is String -> value.takeIf { it.length <= MAX_TEXT_EXTRA_CHARS }?.also { budget.chars += it.length }?.let { ActionExtra.Text(it) }
                is Boolean -> ActionExtra.Bool(value)
                is Int -> ActionExtra.Int32(value)
                is Long -> ActionExtra.Int64(value)
                is Float -> value.takeIf { it.isFinite() }?.let { ActionExtra.Float64(it.toDouble()) }
                is Double -> value.takeIf { it.isFinite() }?.let { ActionExtra.Float64(it) }
                is Bundle -> parseBundle(value, depth + 1, budget)?.let { ActionExtra.Object(it) }
                else -> null // Only bounded primitive values and primitive-only Bundle maps persist.
            } ?: return null
            if (budget.chars > MAX_TOTAL_EXTRA_CHARS) return null
            parsed[key] = safe
        }
        return parsed
    }

    fun toIntent(action: RegisteredAction): Intent = Intent(action.action).apply {
        component = ComponentName(action.targetPackage, action.targetClass)
        `package` = action.targetPackage
        action.dataUri?.let { data = Uri.parse(it) }
        action.categories.forEach(::addCategory)
        action.extras.forEach { (key, value) -> when (value) {
            is ActionExtra.Text -> putExtra(key, value.value)
            is ActionExtra.Bool -> putExtra(key, value.value)
            is ActionExtra.Int32 -> putExtra(key, value.value)
            is ActionExtra.Int64 -> putExtra(key, value.value)
            is ActionExtra.Float64 -> putExtra(key, value.value)
            is ActionExtra.Object -> putExtra(key, Bundle().apply { value.values.forEach { (nestedKey, nestedValue) -> putSafeExtra(nestedKey, nestedValue) } })
        } }
        // Do not forward provider-supplied task flags, clip data, URI grants, selector, or extras
        // that did not pass the schema above. A user action starts only its exact activity.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun Bundle.putSafeExtra(key: String, value: ActionExtra) {
        when (value) {
            is ActionExtra.Text -> putString(key, value.value)
            is ActionExtra.Bool -> putBoolean(key, value.value)
            is ActionExtra.Int32 -> putInt(key, value.value)
            is ActionExtra.Int64 -> putLong(key, value.value)
            is ActionExtra.Float64 -> putDouble(key, value.value)
            is ActionExtra.Object -> putBundle(key, Bundle().apply { value.values.forEach { (nestedKey, nestedValue) -> putSafeExtra(nestedKey, nestedValue) } })
        }
    }
}
