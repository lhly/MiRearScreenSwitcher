package top.zainzh.rearscreen.platform.mods

import org.json.JSONObject
import top.zainzh.rearscreen.platform.RearCapabilityCore
import top.zainzh.rearscreen.platform.SessionOwner
import top.zainzh.rearscreen.platform.data.DataField
import top.zainzh.rearscreen.platform.data.DataSnapshot
import top.zainzh.rearscreen.platform.data.DataValue
import java.io.Closeable

/** Converts the Core's live-grant-filtered data facade into the public UI binding shape. */
internal object ModHostDataSnapshot {
    fun fieldsFor(mod: VerifiedModPackage): Set<DataField> = buildSet {
        val capabilities = (mod.requiredCapabilities + mod.optionalCapabilities).map { it.name }.toSet()
        if ("data.battery" in capabilities) {
            add(DataField.BATTERY_LEVEL_PERCENT)
            add(DataField.BATTERY_CHARGING)
        }
        if ("data.systemMetrics" in capabilities) {
            add(DataField.SYSTEM_CPU_UTILIZATION_PERCENT)
            add(DataField.SYSTEM_MEMORY_AVAILABLE_BYTES)
            add(DataField.SYSTEM_MEMORY_TOTAL_BYTES)
        }
    }

    fun observe(
        core: RearCapabilityCore,
        owner: SessionOwner,
        fields: Set<DataField>,
        callback: (JSONObject) -> Unit
    ): Closeable? {
        if (fields.isEmpty()) return null
        return when (val result = core.observeModData(owner, fields, callback = { callback(toJson(it)) })) {
            is top.zainzh.rearscreen.platform.ModDataObserveResult.Opened -> result.subscription
            is top.zainzh.rearscreen.platform.ModDataObserveResult.Rejected -> null
        }
    }

    fun toJson(snapshot: DataSnapshot): JSONObject {
        val root = JSONObject()
        val battery = JSONObject()
        putValue(snapshot, DataField.BATTERY_LEVEL_PERCENT, battery, "level")
        putValue(snapshot, DataField.BATTERY_CHARGING, battery, "charging")
        if (snapshot.fields.keys.any { it == DataField.BATTERY_LEVEL_PERCENT || it == DataField.BATTERY_CHARGING }) {
            // Retain the v1 binding contract while level is now expressed as percent.
            battery.put("scale", 100)
            root.put("battery", battery)
        }

        val cpu = JSONObject()
        putValue(snapshot, DataField.SYSTEM_CPU_UTILIZATION_PERCENT, cpu, "utilizationPercent")
        val memory = JSONObject()
        putValue(snapshot, DataField.SYSTEM_MEMORY_AVAILABLE_BYTES, memory, "availableBytes")
        putValue(snapshot, DataField.SYSTEM_MEMORY_TOTAL_BYTES, memory, "totalBytes")
        if (snapshot.fields.keys.any { it in setOf(
                DataField.SYSTEM_CPU_UTILIZATION_PERCENT,
                DataField.SYSTEM_MEMORY_AVAILABLE_BYTES,
                DataField.SYSTEM_MEMORY_TOTAL_BYTES
            ) }) {
            root.put("systemMetrics", JSONObject().put("cpu", cpu).put("memory", memory))
        }
        return root
    }

    private fun putValue(snapshot: DataSnapshot, field: DataField, target: JSONObject, key: String) {
        val value = snapshot.fields[field] ?: return
        target.put(key, when (value) {
            is DataValue.Number -> if (value.value.isFinite()) value.value else JSONObject.NULL
            is DataValue.Flag -> value.value
            is DataValue.Unavailable -> JSONObject.NULL
        })
    }
}
