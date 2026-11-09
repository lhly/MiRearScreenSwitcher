package top.zainzh.rearscreen.platform

import android.view.Display
import org.json.JSONObject

/** Physical state reported by privileged transaction 40, distinct from the app's cached listener. */
internal enum class SceneRecoveryRearPower { ON, OFF, UNKNOWN, REPLACED }

/** Fails closed unless the currently selected rear and its per-display physical state are present. */
internal fun sceneRecoveryRearPower(stateJson: String?, expectedDisplayId: Int): SceneRecoveryRearPower {
    if (stateJson.isNullOrBlank() || expectedDisplayId < 1) return SceneRecoveryRearPower.UNKNOWN
    return runCatching {
        val root = JSONObject(stateJson)
        val selectedId = root.optInt("selectedId", -1)
        if (root.has("error") || selectedId < 1) return@runCatching SceneRecoveryRearPower.UNKNOWN
        if (selectedId != expectedDisplayId) return@runCatching SceneRecoveryRearPower.REPLACED
        val unique = root.optString("selectedUnique", "")
        if (unique.isBlank()) return@runCatching SceneRecoveryRearPower.UNKNOWN
        val displays = root.optJSONArray("displays") ?: return@runCatching SceneRecoveryRearPower.UNKNOWN
        val display = (0 until displays.length()).asSequence().mapNotNull(displays::optJSONObject)
            .firstOrNull { it.optInt("id", -1) == selectedId }
            ?: return@runCatching SceneRecoveryRearPower.UNKNOWN
        if (display.optString("unique", "") != unique || !display.has("state"))
            return@runCatching SceneRecoveryRearPower.UNKNOWN
        val state = display.optInt("state", -1)
        when {
            state == Display.STATE_ON -> SceneRecoveryRearPower.ON
            state in Display.STATE_OFF..Display.STATE_ON_SUSPEND -> SceneRecoveryRearPower.OFF
            else -> SceneRecoveryRearPower.UNKNOWN
        }
    }.getOrDefault(SceneRecoveryRearPower.UNKNOWN)
}
