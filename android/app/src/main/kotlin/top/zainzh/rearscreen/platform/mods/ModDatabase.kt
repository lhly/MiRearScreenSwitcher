package top.zainzh.rearscreen.platform.mods

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class ModDatabase(context: Context, databaseName: String = DATABASE_NAME) : SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE author_trust (fingerprint TEXT PRIMARY KEY NOT NULL, author_name TEXT NOT NULL, trusted_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE mod_publishers (mod_id TEXT PRIMARY KEY NOT NULL, author_fingerprint TEXT NOT NULL)")
        db.execSQL("CREATE TABLE installed_mods (mod_id TEXT PRIMARY KEY NOT NULL, author_fingerprint TEXT NOT NULL, logical_session_id TEXT NOT NULL, name TEXT NOT NULL, version TEXT NOT NULL, version_code INTEGER NOT NULL, data_version INTEGER NOT NULL, package_path TEXT NOT NULL, enabled INTEGER NOT NULL, manifest_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE mod_capability_grants (mod_id TEXT NOT NULL, capability_name TEXT NOT NULL, version INTEGER NOT NULL, PRIMARY KEY(mod_id, capability_name), FOREIGN KEY(mod_id) REFERENCES installed_mods(mod_id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE mod_models (mod_id TEXT PRIMARY KEY NOT NULL, state_json TEXT, config_json TEXT NOT NULL, revision INTEGER NOT NULL, data_version INTEGER NOT NULL, FOREIGN KEY(mod_id) REFERENCES installed_mods(mod_id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX installed_mods_author ON installed_mods(author_fingerprint)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No migration exists for Mod database $oldVersion -> $newVersion")
    }

    companion object {
        const val DATABASE_NAME = "remrss_mod_platform.db"
        private val instances = mutableMapOf<String, ModDatabase>()

        /** Keep storage domains distinct even when a Context wrapper reports a different applicationContext. */
        fun shared(context: Context): ModDatabase = synchronized(this) {
            val app = context.applicationContext
            val storageContext = if (context.isDeviceProtectedStorage) {
                app.createDeviceProtectedStorageContext()
            } else {
                app
            }
            val path = storageContext.getDatabasePath(DATABASE_NAME).canonicalPath
            instances[path] ?: ModDatabase(storageContext, DATABASE_NAME).also { instances[path] = it }
        }
    }
}
