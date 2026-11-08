package top.zainzh.rearscreen.platform.mods

/** Bounded, single-Mod debug evidence. Never stores business state/config or error messages. */
internal class ModDebugDiagnostics(private val capacity: Int = DEFAULT_CAPACITY) {
    init { require(capacity in 1..MAX_CAPACITY) }

    data class Record(
        val sequence: Long,
        val kind: String,
        val revision: Long? = null,
        val eventId: String? = null,
        val code: String? = null,
    )

    data class Resources(
        val payloadCount: Int,
        val totalBytes: Long,
        val sceneBytes: Long,
        val scriptBytes: Long,
        val assetBytes: Long,
        val otherBytes: Long,
    )

    private val records = mutableListOf<Record>()
    private var nextSequence = 1L

    @Synchronized fun record(kind: String, revision: Long? = null, eventId: String? = null, code: String? = null) {
        require(kind in ALLOWED_KINDS) { "未知诊断记录类型" }
        val safeCode = code?.takeIf { it.matches(Regex("^[A-Z0-9_]{1,48}$")) }
        val safeEventId = eventId?.takeIf { it.matches(Regex("^[A-Za-z0-9_.:-]{1,96}$")) }
        records += Record(nextSequence++, kind, revision?.takeIf { it >= 0 }, safeEventId, safeCode)
        while (records.size > capacity) records.removeAt(0)
    }

    @Synchronized fun snapshot(): List<Record> = records.toList()

    companion object {
        const val DEFAULT_CAPACITY = 64
        const val MAX_CAPACITY = 64
        private val ALLOWED_KINDS = setOf("runtime.ready", "model.committed", "effect.requested", "runtime.error")

        fun resources(pkg: VerifiedModPackage): Resources {
            var scene = 0L; var script = 0L; var asset = 0L; var other = 0L
            pkg.payloads.forEach { (path, bytes) -> when {
                path.endsWith(".ui.json") -> scene += bytes.size
                path == "main.js" -> script += bytes.size
                path.startsWith("assets/") -> asset += bytes.size
                else -> other += bytes.size
            } }
            return Resources(pkg.payloads.size, scene + script + asset + other, scene, script, asset, other)
        }
    }
}
