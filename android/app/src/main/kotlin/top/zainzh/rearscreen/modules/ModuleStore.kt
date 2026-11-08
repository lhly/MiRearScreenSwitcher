package top.zainzh.rearscreen.modules

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import top.zainzh.rearscreen.R
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipInputStream

object ModuleStore {
    const val ACTION_CHANGED = "top.zainzh.rearscreen.MODULE_CHANGED"
    private const val PREFS = "native_modules"
    private const val MAX_PACKAGE_BYTES = 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 600 * 1024
    private const val JSON_FILE = "module.json"
    private const val SIG_FILE = "module.sig"
    private val descriptorCache = mutableMapOf<String, CachedDescriptor>()
    private var recoveryDone = false

    private data class CachedDescriptor(
        val descriptor: ModuleDescriptor,
        val jsonLength: Long,
        val jsonModified: Long,
        val sigLength: Long,
        val sigModified: Long
    )

    @Synchronized fun list(context: Context): List<ModuleDescriptor> {
        recoverInterruptedOperations(context)
        val root = moduleRoot(context)
        return root.listFiles()?.asSequence()?.filter { it.isDirectory }?.mapNotNull { dir ->
            runCatching { cachedDescriptor(context, dir.name, dir) }.getOrNull()
        }?.sortedBy { it.id }?.toList() ?: emptyList()
    }

    @Synchronized fun get(context: Context, id: String): ModuleDescriptor? {
        if (!validId(id)) return null
        recoverInterruptedOperations(context)
        return runCatching { cachedDescriptor(context, id, File(moduleRoot(context), id)) }.getOrNull()
    }

    fun isEnabled(context: Context, id: String): Boolean =
        get(context, id) != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled.$id", false)

    @Synchronized fun setEnabled(context: Context, id: String, value: Boolean) {
        require(validId(id)) { "模块 id 格式无效" }
        require(get(context, id) != null) { "模块未安装或文件无效" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getBoolean("enabled.$id", false)
        if (old == value) return
        if (!prefs.edit().putBoolean("enabled.$id", value).commit()) throw IOException("无法保存模块启用状态")
        broadcast(context, id, if (value) "enable" else "disable")
    }

    /** The caller retains ownership of [input]; package reading is bounded and always closes only internal buffers. */
    @Synchronized fun install(context: Context, input: InputStream): ModuleDescriptor {
        recoverInterruptedOperations(context)
        val packageBytes = readBounded(input, MAX_PACKAGE_BYTES, "模块包超过 1 MiB")
        val entries = readPackage(packageBytes)
        verifySignature(context, entries.first, entries.second)
        val descriptor = ModuleValidator.parseAndValidate(entries.first)

        val root = moduleRoot(context)
        if (!root.exists() && !root.mkdirs()) throw IOException("无法创建模块目录")
        val target = File(root, descriptor.id)
        val existing = if (target.exists()) runCatching { readInstalled(context, target) }.getOrNull() else null
        if (target.exists() && existing == null) throw IOException("已安装模块目录损坏，拒绝覆盖")
        if (existing != null && descriptor.versionCode <= existing.versionCode) throw IllegalArgumentException("新版本号必须高于已安装版本")
        val wasEnabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled.${descriptor.id}", false)
        val temp = File(root, ".${descriptor.id}.${System.nanoTime()}.tmp")
        val backup = File(root, ".${descriptor.id}.${System.nanoTime()}.bak")
        try {
            if (!temp.mkdirs()) throw IOException("无法创建临时模块目录")
            writeSynced(File(temp, JSON_FILE), entries.first)
            writeSynced(File(temp, SIG_FILE), entries.second)
            if (target.exists() && !target.renameTo(backup)) throw IOException("无法暂存旧模块")
            if (!temp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("无法原子安装模块")
            }
            if (existing == null) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (!prefs.edit().putBoolean("enabled.${descriptor.id}", false).commit()) throw IOException("无法保存模块默认状态")
            }
            if (!cleanupRecoverably(backup)) Log.w("ModuleStore", "更新已完成，旧版备份留待恢复流程清理：${backup.name}")
        } catch (e: Exception) {
            if (temp.exists() && !temp.deleteRecursively()) recoveryDone = false
            if (backup.exists()) {
                if (target.exists() && !target.deleteRecursively()) recoveryDone = false
                if (!backup.renameTo(target)) recoveryDone = false
            } else if (existing == null) {
                if (target.exists() && !target.deleteRecursively()) recoveryDone = false
            }
            throw e
        }
        descriptorCache[descriptor.id] = cacheEntry(descriptor, target)
        broadcast(context, descriptor.id, if (existing == null) "install" else "update")
        return descriptor
    }

    @Synchronized fun uninstall(context: Context, id: String) {
        recoverInterruptedOperations(context)
        require(validId(id)) { "模块 id 格式无效" }
        val target = File(moduleRoot(context), id)
        require(target.exists() && get(context, id) != null) { "模块未安装或文件无效" }
        val tombstone = File(moduleRoot(context), ".$id.${System.nanoTime()}.remove")
        val wasEnabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled.$id", false)
        if (!target.renameTo(tombstone)) throw IOException("无法安全移除模块")
        try {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("enabled.$id").commit()) throw IOException("无法清理模块状态")
            if (!cleanupRecoverably(tombstone)) Log.w("ModuleStore", "卸载已完成，残留目录留待恢复流程清理：${tombstone.name}")
        } catch (e: Exception) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled.$id", wasEnabled).commit()
            if (!tombstone.renameTo(target)) recoveryDone = false
            throw e
        }
        descriptorCache.remove(id)
        broadcast(context, id, "remove")
    }

    /** Finish or roll back a transaction interrupted between directory renames. */
    private fun recoverInterruptedOperations(context: Context) {
        if (recoveryDone) return
        val root = moduleRoot(context)
        val entries = root.listFiles()?.filter { it.name.startsWith(".") } ?: run { recoveryDone = true; return }
        val transactionPattern = Regex("^\\.([a-z0-9][a-z0-9_.-]{0,63})\\.(\\d+)\\.(tmp|bak|remove)$")
        val grouped = entries.mapNotNull { file -> transactionPattern.matchEntire(file.name)?.let { it.groupValues[1] to (file to it.groupValues[3]) } }.groupBy({ it.first }, { it.second })
        for ((id, artifacts) in grouped) {
            val target = File(root, id)
            val removals = artifacts.filter { it.second == "remove" }.map { it.first }
            val backups = artifacts.filter { it.second == "bak" }.map { it.first }.sortedByDescending { it.name }
            val temporaries = artifacts.filter { it.second == "tmp" }.map { it.first }
            val targetDescriptor = if (target.exists()) runCatching { readInstalled(context, target) }.getOrNull() else null

            if (removals.isNotEmpty() && targetDescriptor == null && !target.exists()) {
                descriptorCache.remove(id)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("enabled.$id").commit()
                for (file in removals + backups + temporaries) cleanupBestEffort(file)
                continue
            }

            if (targetDescriptor != null) {
                descriptorCache[id] = cacheEntry(targetDescriptor, target)
                for (file in removals + backups + temporaries) cleanupBestEffort(file)
                continue
            }

            val recoverable = backups.firstOrNull { backup -> runCatching { readInstalled(context, backup, id) }.isSuccess }
                ?: removals.firstOrNull { tombstone -> runCatching { readInstalled(context, tombstone, id) }.isSuccess }
            if (recoverable != null) {
                if (target.exists() && !target.deleteRecursively()) throw IOException("无法清理损坏的模块目录以恢复备份")
                if (!recoverable.renameTo(target)) throw IOException("无法从中断事务恢复模块 $id")
                val restored = readInstalled(context, target)
                descriptorCache[id] = cacheEntry(restored, target)
                for (file in (removals + backups + temporaries).filter { it != recoverable }) cleanupBestEffort(file)
            } else {
                if (target.exists() && !target.deleteRecursively()) throw IOException("无法清理损坏的模块目录")
                descriptorCache.remove(id)
                for (file in removals + backups + temporaries) cleanupBestEffort(file)
            }
        }
        recoveryDone = true
    }

    private fun cachedDescriptor(context: Context, id: String, dir: File): ModuleDescriptor {
        val json = File(dir, JSON_FILE)
        val sig = File(dir, SIG_FILE)
        val cached = descriptorCache[id]
        if (cached != null && json.isFile && sig.isFile &&
            json.length() == cached.jsonLength && json.lastModified() == cached.jsonModified &&
            sig.length() == cached.sigLength && sig.lastModified() == cached.sigModified) {
            return copyDescriptor(cached.descriptor)
        }
        val descriptor = readInstalled(context, dir)
        descriptorCache[id] = cacheEntry(descriptor, dir)
        return copyDescriptor(descriptor)
    }

    private fun cacheEntry(descriptor: ModuleDescriptor, dir: File): CachedDescriptor {
        val json = File(dir, JSON_FILE)
        val sig = File(dir, SIG_FILE)
        return CachedDescriptor(copyDescriptor(descriptor), json.length(), json.lastModified(), sig.length(), sig.lastModified())
    }

    private fun copyDescriptor(value: ModuleDescriptor) = ModuleDescriptor(
        value.id, value.versionCode, value.versionName, value.runtime, JSONObject(value.document.toString())
    )

    private fun cleanupRecoverably(file: File): Boolean = !file.exists() || file.deleteRecursively()

    private fun cleanupBestEffort(file: File) {
        if (!cleanupRecoverably(file)) Log.w("ModuleStore", "事务已恢复，残留文件待下次进程启动重试：${file.name}")
    }

    /** Clears only process-local caches so instrumentation can simulate a process restart after a crash. */
    @Synchronized internal fun resetProcessCacheForTests() {
        descriptorCache.clear()
        recoveryDone = false
    }

    private fun readInstalled(context: Context, dir: File, expectedId: String = dir.name): ModuleDescriptor {
        val json = File(dir, JSON_FILE).readBytesBounded(ModuleValidator.MAX_JSON_BYTES)
        val sig = File(dir, SIG_FILE).readBytesBounded(4096)
        verifySignature(context, json, sig)
        val descriptor = ModuleValidator.parseAndValidate(json)
        if (descriptor.id != expectedId) throw IOException("模块目录与 id 不一致")
        return descriptor
    }

    private fun readPackage(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        var json: ByteArray? = null
        var sig: ByteArray? = null
        var total = 0
        val seen = mutableSetOf<String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) throw IllegalArgumentException("模块包不允许目录条目")
                    if (!seen.add(entry.name)) throw IllegalArgumentException("模块包含重复条目")
                    if (entry.name !in setOf(JSON_FILE, SIG_FILE)) throw IllegalArgumentException("模块包含不允许的条目：${entry.name}")
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_EXPANDED_BYTES) throw IllegalArgumentException("模块展开内容超过 600 KiB")
                        out.write(buffer, 0, n)
                    }
                    if (entry.name == JSON_FILE) json = out.toByteArray() else sig = out.toByteArray()
                    zip.closeEntry()
                }
            }
        } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) { throw IOException("模块 ZIP 损坏", e) }
        return (json ?: throw IllegalArgumentException("缺少 module.json")) to (sig ?: throw IllegalArgumentException("缺少 module.sig"))
    }

    private fun verifySignature(context: Context, json: ByteArray, encoded: ByteArray) {
        val signatureText = encoded.toString(Charsets.US_ASCII).trim()
        val sigBytes = try { Base64.decode(signatureText, Base64.DEFAULT) } catch (e: Exception) { throw IllegalArgumentException("module.sig 不是有效 Base64", e) }
        val keyBytes = context.resources.openRawResource(R.raw.module_public_key).use { it.readBytes() }
        try {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update(json)
            require(verifier.verify(sigBytes)) { "模块签名校验失败" }
        } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) { throw IllegalArgumentException("模块签名无法验证", e) }
    }

    private fun readBounded(input: InputStream, limit: Int, error: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw IllegalArgumentException(error)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun File.readBytesBounded(limit: Int): ByteArray {
        if (!isFile || length() > limit) throw IOException("模块文件缺失或超限")
        return inputStream().use { readBounded(it, limit, "模块文件超限") }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        file.outputStream().use { stream -> stream.write(bytes); stream.flush(); stream.fd.sync() }
    }

    private fun moduleRoot(context: Context) = File(context.filesDir, "modules")
    private fun validId(id: String) = Regex("[a-z0-9][a-z0-9_.-]{0,63}").matches(id)
    private fun broadcast(context: Context, id: String, operation: String) {
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName).putExtra("id", id).putExtra("operation", operation))
    }
}
