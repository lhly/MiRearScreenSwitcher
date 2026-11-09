package dev.remrss.platform.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Optional registered-action capability probe; absence never changes the remrss.platform/1 handshake. */
internal data class RegisteredActionCapabilityState(val supported: Boolean, val granted: Boolean)
internal data class RegisteredActionCapabilityDeclaration(val name: String, val granted: Boolean)

internal fun registeredActionCapabilityState(rawCapabilitiesJson: String): RegisteredActionCapabilityState {
    val capabilities = JSONObject(rawCapabilitiesJson).optJSONArray("capabilities") ?: JSONArray()
    val declarations = (0 until capabilities.length()).mapNotNull { index ->
        capabilities.optJSONObject(index)?.let {
            RegisteredActionCapabilityDeclaration(it.optString("name"), it.optBoolean("granted", false))
        }
    }
    return registeredActionCapabilityState(declarations)
}

internal fun registeredActionCapabilityState(
    declarations: Iterable<RegisteredActionCapabilityDeclaration>,
): RegisteredActionCapabilityState {
    val action = declarations.firstOrNull { it.name == "action.invoke" }
        ?: return RegisteredActionCapabilityState(supported = false, granted = false)
    return RegisteredActionCapabilityState(supported = true, granted = action.granted)
}
