package top.zainzh.rearscreen.platform.actions

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

internal interface RegisteredActionStore {
    fun read(): List<RegisteredAction>
    fun write(actions: List<RegisteredAction>): Boolean
}

/** Host-private, non-backed-up durable registry; only the bounded target descriptor is persisted. */
internal class PrivateFileRegisteredActionStore(context: Context) : RegisteredActionStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "registered_actions_v1.json"))

    override fun read(): List<RegisteredAction> {
        val encoded = try {
            file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        val root = try { JSONArray(encoded) } catch (error: Exception) {
            throw IllegalStateException("registered action store is unreadable", error)
        }
        if (root.length() > PlatformRegisteredActionRegistry.MAX_RECORDS) {
            throw IllegalStateException("registered action store exceeds the supported record limit")
        }
        return buildList(root.length()) {
            for (index in 0 until root.length()) {
                try {
                    add(decode(root.getJSONObject(index)))
                } catch (error: Exception) {
                    // Fail closed. Silently dropping a damaged record could let a later write
                    // replace or erase an existing user's binding.
                    throw IllegalStateException("registered action store contains an invalid record", error)
                }
            }
        }
    }

    override fun write(actions: List<RegisteredAction>): Boolean {
        val output = runCatching { JSONArray().apply { actions.forEach { put(encode(it)) } }.toString().toByteArray(Charsets.UTF_8) }.getOrNull()
            ?: return false
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(output)
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let(file::failWrite)
            false
        }
    }

    private fun encode(record: RegisteredAction) = JSONObject().apply {
        put("id", record.ref.id)
        put("version", record.ref.version)
        put("ownerKind", record.owner.kind)
        put("ownerId", record.owner.publisherId)
        put("label", record.label)
        put("targetPackage", record.targetPackage)
        put("targetClass", record.targetClass)
        put("targetSigner", record.targetSignerSha256)
        put("action", record.action)
        if (record.dataUri != null) put("dataUri", record.dataUri)
        put("categories", JSONArray(record.categories))
        put("extras", encodeExtras(record.extras))
        put("revoked", record.revoked)
    }

    private fun decode(obj: JSONObject): RegisteredAction {
        val categories = obj.getJSONArray("categories").let { array ->
            buildList { for (i in 0 until array.length()) add(array.getString(i)) }
        }
        return RegisteredAction(
            RegisteredActionRef(obj.getString("id"), obj.getInt("version")),
            ActionPublisherKey.restore(obj.getString("ownerKind"), obj.getString("ownerId")),
            obj.getString("label"), obj.getString("targetPackage"), obj.getString("targetClass"),
            obj.getString("targetSigner"), obj.getString("action"), obj.optString("dataUri").takeIf { obj.has("dataUri") },
            categories, decodeExtras(obj.getJSONObject("extras")), obj.optBoolean("revoked")
        )
    }

    private fun encodeExtras(values: Map<String, ActionExtra>) = JSONObject().apply {
        values.forEach { (key, value) -> put(key, encodeExtra(value)) }
    }

    private fun encodeExtra(value: ActionExtra): JSONObject = JSONObject().apply {
        when (value) {
            is ActionExtra.Text -> put("type", "text").put("value", value.value)
            is ActionExtra.Bool -> put("type", "bool").put("value", value.value)
            is ActionExtra.Int32 -> put("type", "int32").put("value", value.value)
            is ActionExtra.Int64 -> put("type", "int64").put("value", value.value)
            is ActionExtra.Float64 -> put("type", "float64").put("value", value.value)
            is ActionExtra.Object -> put("type", "object").put("value", encodeExtras(value.values))
        }
    }

    private fun decodeExtras(obj: JSONObject): Map<String, ActionExtra> = buildMap {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.getJSONObject(key)
            put(key, when (value.getString("type")) {
                "text" -> ActionExtra.Text(value.getString("value"))
                "bool" -> ActionExtra.Bool(value.getBoolean("value"))
                "int32" -> ActionExtra.Int32(value.getInt("value"))
                "int64" -> ActionExtra.Int64(value.getLong("value"))
                "float64" -> ActionExtra.Float64(value.getDouble("value"))
                "object" -> ActionExtra.Object(decodeExtras(value.getJSONObject("value")))
                else -> error("unknown action extra type")
            })
        }
    }
}
