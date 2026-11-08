package top.zainzh.rearscreen.platform

import android.hardware.Sensor

/** Minimal platform metadata used to choose the real rear proximity source. */
internal data class RearProximitySensorCandidate(
    val type: Int,
    val name: String,
    val stringType: String,
    val wakeUp: Boolean,
    val maximumRange: Float = 0f,
    internal val platformHandle: Any? = null
)

/**
 * The rear proximity sensor may use an OEM-private sensor type rather than TYPE_PROXIMITY.
 * Select by the same rear-name contract used by the existing Keeper, while excluding factory
 * stream variants that are not the user-facing sensor source.
 */
internal object RearProximitySensorSelector {
    const val SENSOR_LIST_TYPE: Int = Sensor.TYPE_ALL

    fun selectIndex(candidates: List<RearProximitySensorCandidate>): Int? {
        val rearIndices = candidates.indices.filter { index ->
            val candidate = candidates[index]
            candidate.name.contains("Proximity", ignoreCase = true) &&
                candidate.name.contains("Back", ignoreCase = true) &&
                !candidate.stringType.contains(".factory", ignoreCase = true) &&
                candidate.type != OEM_REAR_PROXIMITY_FACTORY_TYPE
        }
        return rearIndices.firstOrNull { candidates[it].wakeUp } ?: rearIndices.firstOrNull()
    }

    private const val OEM_REAR_PROXIMITY_FACTORY_TYPE = 33171124
}
