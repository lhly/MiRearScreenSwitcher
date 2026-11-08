package top.zainzh.rearscreen.platform.mods

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.json.JSONArray
import top.zainzh.rearscreen.platform.RearCapabilityCore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** User review is explicit: author trust and each declared capability are recorded independently. */
data class ModInstallDecision(
    val confirmedAuthorFingerprint: String,
    val acceptedCapabilities: Set<String>
)

data class ModPersistedModel(
    val state: JSONObject?,
    val config: JSONObject,
    val revision: Long,
    val dataVersion: Int
)

data class ModMigrationResult(val state: JSONObject?, val config: JSONObject, val effects: List<JSONObject> = emptyList())

internal data class ModStoreTestEntrySnapshot(
    val id: String,
    val authorFingerprint: String,
    val version: String,
    val versionCode: Int,
    val dataVersion: Int,
    val enabled: Boolean,
    val grants: Set<String>,
    val packagePath: String,
    val packageExists: Boolean,
    val stateJson: String?,
    val configJson: String?,
    val revision: Long?,
    val modelDataVersion: Int?,
)

internal data class ModStoreTestSnapshot(
    val entries: List<ModStoreTestEntrySnapshot>,
    val trustedAuthors: Set<String>,
    val publishedModOwners: Map<String, String>,
)

/** Implemented only by the isolated runtime bridge; no generic fallback rewrites business data. */
fun interface ModMigrationRunner {
    fun migrate(oldPackage: VerifiedModPackage, newPackage: VerifiedModPackage, model: ModPersistedModel): ModMigrationResult
}

data class InstalledMod(
    val id: String,
    val name: String,
    val authorName: String,
    val authorFingerprint: String,
    val logicalSessionId: String,
    val version: String,
    val versionCode: Int,
    val dataVersion: Int,
    val enabled: Boolean,
    val requiredCapabilities: List<ModCapability>,
    val optionalCapabilities: List<ModCapability>,
    val grantedCapabilities: Set<String>
)

object ModPackageStore {
    private fun db(context: Context, storage: ModPackageStoreTestSession.Storage? = null) = storage?.database ?: ModDatabase.shared(context)
    private fun root(context: Context, storage: ModPackageStoreTestSession.Storage? = null) = storage?.packageRoot ?: File(context.noBackupFilesDir, "platform-mods")

    @Synchronized internal fun list(context: Context, storage: ModPackageStoreTestSession.Storage? = null): List<InstalledMod> {
        val database = db(context, storage).readableDatabase
        return database.rawQuery("SELECT mod_id,name,author_fingerprint,logical_session_id,version,version_code,data_version,enabled,manifest_json FROM installed_mods ORDER BY name COLLATE NOCASE", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val manifest = JSONObject(cursor.getString(8))
                    val caps = parseCapabilities(manifest.getJSONObject("capabilities"))
                    add(InstalledMod(cursor.getString(0), cursor.getString(1), manifest.getJSONObject("author").getString("name"), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                        cursor.getInt(5), cursor.getInt(6), cursor.getInt(7) != 0, caps.first, caps.second, grants(database,cursor.getString(0))))
                }
            }
        }
    }

    @Synchronized internal fun find(context: Context, id: String, storage: ModPackageStoreTestSession.Storage? = null): VerifiedModPackage? {
        val row = db(context, storage).readableDatabase.rawQuery("SELECT package_path FROM installed_mods WHERE mod_id=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return null
        return File(row).inputStream().use(ModPackageReader::read)
    }

    @Synchronized internal fun installed(context: Context, id: String, storage: ModPackageStoreTestSession.Storage? = null): InstalledMod? = list(context, storage).firstOrNull { it.id==id }

    @Synchronized internal fun model(context: Context, id: String, storage: ModPackageStoreTestSession.Storage? = null): ModPersistedModel? =
        db(context, storage).readableDatabase.rawQuery("SELECT state_json,config_json,revision,data_version FROM mod_models WHERE mod_id=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.model() else null
        }

    @Synchronized internal fun isAuthorTrusted(context: Context, fingerprint: String, storage: ModPackageStoreTestSession.Storage? = null): Boolean =
        db(context, storage).readableDatabase.rawQuery("SELECT 1 FROM author_trust WHERE fingerprint=?", arrayOf(fingerprint)).use { it.moveToFirst() }

    /** Read-only baseline used to prove isolated AndroidTest work leaves the production store unchanged. */
    @Synchronized internal fun productionSnapshotForTest(context: Context): ModStoreTestSnapshot {
        val database = ModDatabase.shared(context).readableDatabase
        val entries = database.rawQuery(
            "SELECT mod_id,author_fingerprint,version,version_code,data_version,enabled,package_path FROM installed_mods ORDER BY mod_id", null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val path = File(cursor.getString(6)).canonicalPath
                    val model = readModel(database, id)
                    add(ModStoreTestEntrySnapshot(
                        id = id,
                        authorFingerprint = cursor.getString(1),
                        version = cursor.getString(2),
                        versionCode = cursor.getInt(3),
                        dataVersion = cursor.getInt(4),
                        enabled = cursor.getInt(5) != 0,
                        grants = grants(database, id),
                        packagePath = path,
                        packageExists = File(path).isFile,
                        stateJson = model?.state?.let(::canonicalJson),
                        configJson = model?.config?.let(::canonicalJson),
                        revision = model?.revision,
                        modelDataVersion = model?.dataVersion
                    ))
                }
            }
        }
        val trusted = database.rawQuery("SELECT fingerprint FROM author_trust ORDER BY fingerprint", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val publishers = database.rawQuery("SELECT mod_id,author_fingerprint FROM mod_publishers ORDER BY mod_id", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        return ModStoreTestSnapshot(entries, trusted, publishers)
    }

    @Synchronized internal fun install(
        context: Context,
        archive: ByteArray,
        decision: ModInstallDecision,
        migrator: ModMigrationRunner? = null,
        storage: ModPackageStoreTestSession.Storage? = null
    ): InstalledMod {
        val verified = ModPackageReader.read(archive.inputStream())
        require(decision.confirmedAuthorFingerprint == verified.authorFingerprint) { "用户确认的作者指纹与包不一致" }
        require(verified.requiredCapabilities.all { it.name in decision.acceptedCapabilities }) { "缺少用户确认的必需能力授权" }
        require((verified.requiredCapabilities + verified.optionalCapabilities).map { it.name }.containsAll(decision.acceptedCapabilities)) { "授权列表包含 manifest 未声明能力" }

        val database = db(context, storage).writableDatabase
        val old = readInstallRow(database, verified.id)
        if (old != null) {
            require(old.authorFingerprint == verified.authorFingerprint) { "同一 Mod ID 不能被其他作者接管" }
            require(verified.versionCode > old.versionCode) { "更新 versionCode 必须高于已安装版本" }
        }
        val boundAuthor = database.rawQuery("SELECT author_fingerprint FROM mod_publishers WHERE mod_id=?", arrayOf(verified.id)).use { if (it.moveToFirst()) it.getString(0) else null }
        require(boundAuthor == null || boundAuthor == verified.authorFingerprint) { "Mod ID 已绑定其他作者指纹" }

        val previousModel = readModel(database, verified.id)
        val logicalSessionId=old?.logicalSessionId ?: UUID.randomUUID().toString()
        val nextModel = when {
            previousModel == null -> ModPersistedModel(null, defaults(verified.configSchema), 0, verified.dataVersion)
            previousModel.dataVersion != verified.dataVersion -> {
                val oldPackage = File(old?.packagePath ?: error("缺少旧活动包")).inputStream().use(ModPackageReader::read)
                val runner = migrator ?: throw IllegalStateException("dataVersion 更新需要隔离迁移函数；旧包和模型保持不变")
                val migrated = runner.migrate(oldPackage, verified, previousModel)
                validateMigrated(verified, previousModel, migrated)
                ModPersistedModel(migrated.state, migrated.config, previousModel.revision + 1, verified.dataVersion)
            }
            else -> {
                validateModel(verified, previousModel.state, previousModel.config)
                previousModel
            }
        }
        validateModel(verified, nextModel.state, nextModel.config)

        val packageRoot = File(root(context, storage), verified.id)
        if (!packageRoot.exists() && !packageRoot.mkdirs()) throw IOException("无法创建 Mod 目录")
        val candidate = File(packageRoot, ".${UUID.randomUUID()}.candidate")
        writeSynced(candidate, archive)
        val active = File(packageRoot, "${verified.versionCode}-${UUID.randomUUID()}.zip")
        try {
            if (!candidate.renameTo(active)) throw IOException("无法原子落盘候选 Mod")
            database.beginTransaction()
            try {
                val current = readInstallRow(database, verified.id)
                require(current?.versionCode == old?.versionCode && current?.authorFingerprint == old?.authorFingerprint) { "并发更新已改变活动版本" }
                database.execSQL("INSERT OR IGNORE INTO mod_publishers(mod_id,author_fingerprint) VALUES(?,?)", arrayOf(verified.id, verified.authorFingerprint))
                database.execSQL("INSERT OR IGNORE INTO author_trust(fingerprint,author_name,trusted_at) VALUES(?,?,?)", arrayOf<Any?>(verified.authorFingerprint, verified.authorName, System.currentTimeMillis()))
                database.execSQL("INSERT OR REPLACE INTO installed_mods(mod_id,author_fingerprint,logical_session_id,name,version,version_code,data_version,package_path,enabled,manifest_json) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(verified.id, verified.authorFingerprint, logicalSessionId, verified.name, verified.version, verified.versionCode, verified.dataVersion, active.absolutePath, old?.enabled ?: 0, verified.signedManifest.toString(Charsets.UTF_8)))
                database.delete("mod_capability_grants", "mod_id=?", arrayOf(verified.id))
                val grants = verified.requiredCapabilities + verified.optionalCapabilities.filter { it.name in decision.acceptedCapabilities }
                grants.forEach { cap -> database.execSQL("INSERT INTO mod_capability_grants(mod_id,capability_name,version) VALUES(?,?,?)", arrayOf<Any?>(verified.id, cap.name, cap.minimumVersion)) }
                val values = ContentValues().apply {
                    put("mod_id", verified.id)
                    put("state_json", nextModel.state?.let(::canonicalJson))
                    put("config_json", canonicalJson(nextModel.config))
                    put("revision", nextModel.revision)
                    put("data_version", nextModel.dataVersion)
                }
                database.insertWithOnConflict("mod_models", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        } catch (e: Exception) {
            active.delete()
            candidate.delete()
            throw e
        }
        if (old != null && storage == null) closeDataSessions(context, verified.id)
        old?.packagePath?.let { File(it).takeIf { file -> file.absolutePath != active.absolutePath }?.delete() }
        return InstalledMod(verified.id, verified.name, verified.authorName, verified.authorFingerprint, logicalSessionId, verified.version, verified.versionCode,
            verified.dataVersion, old?.enabled ?: false, verified.requiredCapabilities,
            verified.optionalCapabilities.filter { it.name in decision.acceptedCapabilities },
            (verified.requiredCapabilities+verified.optionalCapabilities.filter { it.name in decision.acceptedCapabilities }).map { it.name }.toSet())
    }

    @Synchronized internal fun setEnabled(context: Context, id: String, enabled: Boolean, storage: ModPackageStoreTestSession.Storage? = null): InstalledMod {
        val database = db(context, storage).writableDatabase
        val wasEnabled = readInstallRow(database, id)?.enabled ?: false
        val pkg = find(context, id, storage) ?: throw IllegalArgumentException("Mod 未安装或包无效")
        if (enabled) {
            require(isAuthorTrusted(context, pkg.authorFingerprint, storage)) { "作者信任已撤销" }
            val granted = database.rawQuery("SELECT capability_name FROM mod_capability_grants WHERE mod_id=?", arrayOf(id)).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            require(pkg.requiredCapabilities.all { it.name in granted }) { "必需能力未授权" }
        }
        database.execSQL("UPDATE installed_mods SET enabled=? WHERE mod_id=?", arrayOf<Any?>(if (enabled) 1 else 0, id))
        if (wasEnabled && !enabled && storage == null) closeDataSessions(context, id)
        return list(context, storage).first { it.id == id }
    }

    @Synchronized internal fun setCapabilityGrant(context: Context, id: String, capability: String, version: Int, granted: Boolean, storage: ModPackageStoreTestSession.Storage? = null): InstalledMod {
        val database=db(context, storage).writableDatabase
        val pkg=find(context,id,storage)?:throw IllegalArgumentException("Mod 未安装")
        val requested=(pkg.requiredCapabilities+pkg.optionalCapabilities).firstOrNull { it.name==capability }
            ?:throw IllegalArgumentException("能力未由当前 manifest 声明")
        require(version>=requested.minimumVersion) { "授权版本低于 Mod 声明的最低版本" }
        if (granted) database.execSQL("INSERT OR REPLACE INTO mod_capability_grants(mod_id,capability_name,version) VALUES(?,?,?)",arrayOf<Any?>(id,capability,version))
        else {
            database.delete("mod_capability_grants","mod_id=? AND capability_name=?",arrayOf(id,capability))
            if (pkg.requiredCapabilities.any { it.name==capability }) database.execSQL("UPDATE installed_mods SET enabled=0 WHERE mod_id=?",arrayOf(id))
            if (storage == null) closeDataSessions(context, id)
        }
        return list(context, storage).first { it.id == id }
    }

    /** Revocation disables only packages from that author and keeps their data for an explicit re-trust. */
    @Synchronized internal fun revokeAuthorTrust(context: Context, fingerprint: String, storage: ModPackageStoreTestSession.Storage? = null): Set<String> {
        val database = db(context, storage).writableDatabase
        val ids = database.rawQuery("SELECT mod_id FROM installed_mods WHERE author_fingerprint=?", arrayOf(fingerprint)).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        database.beginTransaction()
        try {
            database.delete("author_trust", "fingerprint=?", arrayOf(fingerprint))
            ids.forEach { id -> database.execSQL("UPDATE installed_mods SET enabled=0 WHERE mod_id=?", arrayOf(id)) }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        if (storage == null) ids.forEach { closeDataSessions(context, it) }
        return ids
    }

    @Synchronized internal fun uninstall(context: Context, id: String, storage: ModPackageStoreTestSession.Storage? = null) {
        val database = db(context, storage).writableDatabase
        val install = readInstallRow(database, id) ?: return
        database.beginTransaction()
        try {
            database.delete("installed_mods", "mod_id=?", arrayOf(id))
            database.delete("mod_models", "mod_id=?", arrayOf(id))
            database.delete("mod_capability_grants", "mod_id=?", arrayOf(id))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        if (storage == null) closeDataSessions(context, id)
        File(install.packagePath).delete()
    }

    /** Optimistic state/config commit; unchanged JSON does not write or advance the revision. */
    @Synchronized internal fun commitModel(context: Context, id: String, expectedRevision: Long, state: JSONObject?, config: JSONObject, storage: ModPackageStoreTestSession.Storage? = null): ModPersistedModel {
        val database = db(context, storage).writableDatabase
        val pkg = find(context, id, storage) ?: throw IllegalStateException("活动包已移除")
        validateModel(pkg, state, config)
        val current = readModel(database, id) ?: throw IllegalStateException("模型尚未初始化")
        require(current.revision == expectedRevision) { "模型 revision 已变化" }
        if (canonicalJson(current.state) == canonicalJson(state) && canonicalJson(current.config) == canonicalJson(config)) return current
        val updated = current.copy(state = state, config = config, revision = current.revision + 1)
        database.beginTransaction()
        try {
            val values = ContentValues().apply { put("state_json", state?.let(::canonicalJson)); put("config_json", canonicalJson(config)); put("revision", updated.revision) }
            val changed = database.update("mod_models", values, "mod_id=? AND revision=?", arrayOf(id, expectedRevision.toString()))
            require(changed == 1) { "模型 revision 冲突" }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        return updated
    }

    @Synchronized internal fun commitInitialState(context: Context, id: String, state: JSONObject, storage: ModPackageStoreTestSession.Storage? = null): ModPersistedModel {
        val database = db(context, storage).writableDatabase
        val pkg = find(context, id, storage) ?: throw IllegalStateException("活动包已移除")
        require(pkg.runtime == ModRuntimeKind.QUICKJS) { "无脚本 Mod 不创建 reducer state" }
        val current = readModel(database, id) ?: throw IllegalStateException("模型行缺失")
        if (current.state != null) return current
        validateModel(pkg, state, current.config)
        val values = ContentValues().apply { put("state_json", canonicalJson(state)) }
        val changed = database.update("mod_models", values, "mod_id=? AND state_json IS NULL AND revision=?", arrayOf(id, current.revision.toString()))
        require(changed == 1) { "模型初始化冲突" }
        return current.copy(state = state)
    }

    private data class InstallRow(val authorFingerprint: String, val logicalSessionId: String, val versionCode: Int, val packagePath: String, val enabled: Boolean)
    private fun readInstallRow(database: SQLiteDatabase, id: String): InstallRow? = database.rawQuery("SELECT author_fingerprint,logical_session_id,version_code,package_path,enabled FROM installed_mods WHERE mod_id=?", arrayOf(id)).use {
        if (it.moveToFirst()) InstallRow(it.getString(0), it.getString(1), it.getInt(2), it.getString(3), it.getInt(4) != 0) else null
    }
    private fun readModel(database: SQLiteDatabase, id: String): ModPersistedModel? = database.rawQuery("SELECT state_json,config_json,revision,data_version FROM mod_models WHERE mod_id=?", arrayOf(id)).use { if (it.moveToFirst()) it.model() else null }
    private fun Cursor.model() = ModPersistedModel(if (isNull(0)) null else JSONObject(getString(0)), JSONObject(getString(1)), getLong(2), getInt(3))

    private fun validateModel(pkg: VerifiedModPackage, state: JSONObject?, config: JSONObject) {
        state?.let { ModJsonSchema.validateValue(pkg.stateSchema, it, "state") }
        ModJsonSchema.validateValue(pkg.configSchema, config, "config")
        require((state?.toString()?.length ?: 0) <= 64 * 1024 && config.toString().length <= 16 * 1024) { "state/config 超出持久化上限" }
    }
    private fun validateMigrated(pkg: VerifiedModPackage, previous: ModPersistedModel, model: ModMigrationResult) {
        require(model.effects.isEmpty()) { "迁移不得返回 Effect" }
        // null is valid only while the model has never been initialized. Once a
        // state object exists, migration may not erase it and defer its loss to init.
        require(previous.state == null || model.state != null) {
            "迁移不得丢弃已有 state；旧包和模型保持不变"
        }
        validateModel(pkg, model.state, model.config)
    }

    private fun defaults(schema: JSONObject): JSONObject {
        val result = JSONObject()
        val props = schema.optJSONObject("properties") ?: return result
        val keys = props.keys()
        while (keys.hasNext()) {
            val key = keys.next(); val field = props.getJSONObject(key)
            if (field.has("default")) result.put(key, field.get("default"))
            else if (field.optString("type") == "object") result.put(key, defaults(field))
        }
        return result
    }
    private fun canonicalJson(value: JSONObject?): String? {
        if (value == null) return null
        fun normalize(item: Any): Any = when (item) {
            is JSONObject -> JSONObject().also { out -> item.keys().asSequence().sorted().forEach { key -> out.put(key,normalize(item.get(key))) } }
            is JSONArray -> JSONArray().also { out -> for (i in 0 until item.length()) out.put(normalize(item.get(i))) }
            else -> item
        }
        return normalize(value).toString()
    }
    private fun parseCapabilities(obj: JSONObject): Pair<List<ModCapability>, List<ModCapability>> {
        fun list(array: org.json.JSONArray): List<ModCapability> = (0 until array.length()).map { i ->
            val item=array.getJSONObject(i); ModCapability(item.getString("name"),item.getInt("minimumVersion"))
        }
        return list(obj.getJSONArray("required")) to list(obj.getJSONArray("optional"))
    }
    private fun grants(database:SQLiteDatabase,id:String):Set<String> = database.rawQuery("SELECT capability_name FROM mod_capability_grants WHERE mod_id=?",arrayOf(id)).use { cursor -> buildSet { while(cursor.moveToNext()) add(cursor.getString(0)) } }
    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun closeDataSessions(context: Context, id: String) {
        runCatching { RearCapabilityCore.get(context).closeModDataSessionsForMod(id) }
    }
}
