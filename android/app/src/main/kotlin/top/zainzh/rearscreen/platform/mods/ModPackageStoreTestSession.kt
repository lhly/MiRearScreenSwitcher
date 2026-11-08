package top.zainzh.rearscreen.platform.mods

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * Explicit instrumentation-only storage. It owns a unique DE database and package root;
 * it never uses ModDatabase.shared() or the production `platform-mods` directory.
 */
internal class ModPackageStoreTestSession private constructor(
    private val context: Context,
    internal val storage: Storage,
) : AutoCloseable {
    internal class Storage(
        val database: ModDatabase,
        val packageRoot: File,
        val testContext: Context,
        private val databaseName: String,
        private val isolationRoot: File,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            database.close()
            val rootPath = packageRoot.canonicalFile
            val isolatedPath = isolationRoot.canonicalFile
            require(rootPath != isolatedPath && rootPath.path.startsWith(isolatedPath.path + File.separator)) {
                "拒绝清理隔离目录之外的 Mod 数据"
            }
            require(packageRoot.name.startsWith("androidtest-") && databaseName.startsWith("remrss_mod_test_androidtest_")) {
                "拒绝清理非 AndroidTest 存储"
            }
            packageRoot.deleteRecursively()
            testContext.deleteDatabase(databaseName)
            closed = true
        }
    }

    companion object {
        fun create(targetContext: Context, label: String): ModPackageStoreTestSession {
            val app = targetContext.applicationContext
            require(!app.isDeviceProtectedStorage) { "AndroidTest scope factory requires the ordinary credential-protected target context" }
            val credentialContext = app
            val deviceContext = app.createDeviceProtectedStorageContext()
            require(deviceContext.isDeviceProtectedStorage) { "AndroidTest 存储未进入设备保护域" }

            val token = UUID.randomUUID().toString().replace('-', '_')
            val safeLabel = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(20).ifEmpty { "case" }
            val namespace = "androidtest-$safeLabel-$token"
            val databaseName = "remrss_mod_test_${namespace.replace('-', '_')}.db"
            val isolationRoot = File(deviceContext.noBackupFilesDir, "platform-mods-test").canonicalFile
            val packageRoot = File(isolationRoot, namespace).canonicalFile
            val databasePath = deviceContext.getDatabasePath(databaseName).canonicalFile

            val productionDatabasePath = credentialContext.getDatabasePath(ModDatabase.DATABASE_NAME).canonicalFile
            val productionPackageRoot = File(credentialContext.noBackupFilesDir, "platform-mods").canonicalFile
            require(databasePath != productionDatabasePath) { "AndroidTest 数据库与产品数据库重合" }
            require(packageRoot != productionPackageRoot && !packageRoot.path.startsWith(productionPackageRoot.path + File.separator)) {
                "AndroidTest ZIP 目录与产品 Mod 目录重合"
            }
            require(packageRoot.path.startsWith(isolationRoot.path + File.separator) && !packageRoot.exists()) {
                "AndroidTest 包目录不是新的隔离路径"
            }
            require(!databasePath.exists()) { "拒绝复用已有 AndroidTest 数据库" }

            val storage = Storage(ModDatabase(deviceContext, databaseName), packageRoot, deviceContext, databaseName, isolationRoot)
            return ModPackageStoreTestSession(deviceContext, storage)
        }
    }

    fun list(): List<InstalledMod> = ModPackageStore.list(context, storage)
    fun find(id: String): VerifiedModPackage? = ModPackageStore.find(context, id, storage)
    fun installed(id: String): InstalledMod? = ModPackageStore.installed(context, id, storage)
    fun model(id: String): ModPersistedModel? = ModPackageStore.model(context, id, storage)
    fun isAuthorTrusted(fingerprint: String): Boolean = ModPackageStore.isAuthorTrusted(context, fingerprint, storage)
    fun install(archive: ByteArray, decision: ModInstallDecision, migrator: ModMigrationRunner? = null): InstalledMod =
        ModPackageStore.install(context, archive, decision, migrator, storage)
    fun setEnabled(id: String, enabled: Boolean): InstalledMod = ModPackageStore.setEnabled(context, id, enabled, storage)
    fun setCapabilityGrant(id: String, capability: String, version: Int, granted: Boolean): InstalledMod =
        ModPackageStore.setCapabilityGrant(context, id, capability, version, granted, storage)
    fun commitModel(id: String, expectedRevision: Long, state: JSONObject?, config: JSONObject): ModPersistedModel =
        ModPackageStore.commitModel(context, id, expectedRevision, state, config, storage)
    fun commitInitialState(id: String, state: JSONObject): ModPersistedModel = ModPackageStore.commitInitialState(context, id, state, storage)
    fun uninstall(id: String) = ModPackageStore.uninstall(context, id, storage)

    override fun close() = storage.close()
}
