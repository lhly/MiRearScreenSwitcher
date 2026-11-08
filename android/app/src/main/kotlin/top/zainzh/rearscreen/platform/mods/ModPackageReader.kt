package top.zainzh.rearscreen.platform.mods

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.zip.ZipInputStream

/** Validates a complete signed Mod before any file is installed or reducer is started. */
object ModPackageReader {
    const val MAX_ZIP_BYTES = 2 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 8 * 1024 * 1024
    const val MAX_FILES = 64
    const val MAX_UI_BYTES = 64 * 1024
    const val MAX_JS_BYTES = 512 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_SIGNATURE_BYTES = 1024
    private const val MAX_PUBLIC_KEY_BYTES = 4096
    private val idPattern = Regex("^[a-z0-9][a-z0-9_.-]{2,63}$")
    private val tokenPattern = Regex("^[a-z][A-Za-z0-9._-]{0,63}$")
    private val configFieldPattern = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
    private val opaqueActionId = Regex("^[a-f0-9]{48}$")
    private const val opaqueActionIdPattern = "^[a-f0-9]{48}$"
    private val versionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$")
    private val capabilities = mapOf(
        "rear.scene" to 1, "rear.scene.keep_visible" to 1, "data.battery" to 1, "data.systemMetrics" to 1,
        "model.local" to 1, "ui.native" to 1, "ui.dialog" to 1, "action.invoke" to 1, "schedule.visible" to 1, "clock.snapshot" to 1
    )
    private val nodeFields = mapOf(
        "layout.column" to setOf("type", "children", "gap", "horizontalAlignment", "verticalAlignment"),
        "layout.row" to setOf("type", "children", "gap", "horizontalAlignment", "verticalAlignment"),
        "layout.box" to setOf("type", "children", "gap", "horizontalAlignment", "verticalAlignment"),
        "ui.text" to setOf("type", "value", "style", "format", "tone", "visibleWhen"),
        "ui.number" to setOf("type", "value", "style", "format", "tone", "visibleWhen"),
        "ui.list" to setOf("type", "items", "maxItems", "style", "emptyText", "format", "visibleWhen"),
        "ui.icon" to setOf("type", "name", "label", "tone", "visibleWhen"),
        "ui.deadlineText" to setOf("type", "value", "format", "visibleWhen"),
        "ui.button" to setOf("type", "label", "onPress", "enabled", "hint", "visibleWhen"),
        "ui.image" to setOf("type", "asset", "alt", "visibleWhen"),
        "ui.divider" to setOf("type", "visibleWhen"),
        "ui.progress" to setOf("type", "value", "min", "max", "visibleWhen"),
        "settings.row" to setOf("type", "label", "value", "hint", "onPress", "visibleWhen"),
        "settings.input" to setOf("type", "label", "value", "hint", "placeholder", "visibleWhen"),
        "settings.action" to setOf("type", "label", "value", "hint", "visibleWhen"),
        "settings.number" to setOf("type", "label", "value", "hint", "min", "max", "visibleWhen"),
        "settings.toggle" to setOf("type", "label", "value", "hint", "visibleWhen"),
        "settings.select" to setOf("type", "label", "value", "hint", "options", "visibleWhen"),
        "settings.save" to setOf("type", "visibleWhen"), "settings.discard" to setOf("type", "visibleWhen"),
        "ui.confirm" to setOf("type", "label", "title", "message", "onConfirm", "visibleWhen")
    )

    @Throws(ModPackageException::class)
    fun read(input: InputStream): VerifiedModPackage {
        val zipped = readBounded(input, MAX_ZIP_BYTES, ModPackageError.TOO_LARGE, "ZIP 超过 2 MiB")
        val entries = readZip(zipped)
        val manifestBytes = entries["manifest.json"] ?: fail(ModPackageError.INVALID_MANIFEST, "缺少 manifest.json")
        val signatureBytes = entries["manifest.sig"] ?: fail(ModPackageError.INVALID_SIGNATURE, "缺少 manifest.sig")
        val publicKeyBytes = entries["author.pub"] ?: fail(ModPackageError.INVALID_AUTHOR_KEY, "缺少 author.pub")
        if (manifestBytes.size > MAX_MANIFEST_BYTES || signatureBytes.size !in 1..MAX_SIGNATURE_BYTES || publicKeyBytes.size !in 1..MAX_PUBLIC_KEY_BYTES) {
            fail(ModPackageError.LIMIT_EXCEEDED, "签名元数据超过上限")
        }
        val publicKey = parsePublicKey(publicKeyBytes)
        val rsa = publicKey as? RSAPublicKey ?: fail(ModPackageError.INVALID_AUTHOR_KEY, "作者公钥必须是 RSA")
        val keyBits = rsa.modulus.bitLength()
        if (keyBits !in 2048..8192 || signatureBytes.size != (keyBits + 7) / 8) {
            fail(ModPackageError.INVALID_AUTHOR_KEY, "RSA 公钥须为 2048..8192 位且签名长度匹配")
        }
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        if (!verifier.verify(signatureBytes)) fail(ModPackageError.INVALID_SIGNATURE, "manifest RSA-SHA256 签名无效")

        val manifestText = decodeUtf8(manifestBytes, "manifest.json")
        requireStrictJson(manifestBytes, "manifest.json")
        val json = try { JSONObject(manifestText) } catch (e: Exception) { fail(ModPackageError.INVALID_MANIFEST, "manifest JSON 无效") }
        val parsed = try { parseManifest(json) }
        catch (e: ModPackageException) { throw e }
        catch (e: Exception) { fail(ModPackageError.INVALID_MANIFEST, e.message ?: "manifest 无效") }
        val fingerprint = sha256(rsa.encoded)
        if (parsed.authorFingerprint != fingerprint) fail(ModPackageError.INVALID_AUTHOR_KEY, "author.pub 指纹与已签 manifest 不一致")
        verifyPayloads(parsed, entries)

        val settings = parseUi(entries.getValue(parsed.settingsPath), parsed.settingsPath, parsed, entries)
        val rear = parsed.rearPath?.let { parseUi(entries.getValue(it), it, parsed, entries) }
        validateAssets(entries)
        val reducer = if (parsed.runtime == ModRuntimeKind.QUICKJS) entries["main.js"] else null
        return VerifiedModPackage(
            id = parsed.id, name = parsed.name, version = parsed.version, versionCode = parsed.versionCode,
            authorName = parsed.authorName, authorFingerprint = parsed.authorFingerprint,
            platformApi = parsed.platformApi, minimumAndroidApi = parsed.minimumAndroidApi,
            requiredCapabilities = parsed.requiredCapabilities, optionalCapabilities = parsed.optionalCapabilities,
            requiredPermissions = parsed.requiredPermissions, optionalPermissions = parsed.optionalPermissions,
            settingsScene = settings, rearScene = rear, stateSchema = parsed.stateSchema,
            configSchema = parsed.configSchema, dataVersion = parsed.dataVersion,
            runtime = parsed.runtime, reducerSource = reducer, publicKeyPem = publicKeyBytes,
            signedManifest = manifestBytes, payloads = entries.filterKeys { it !in setOf("manifest.json", "manifest.sig", "author.pub") }
        )
    }

    internal fun parseRuntimeObject(json: String, label: String): JSONObject {
        val bytes=json.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size>256*1024) fail(ModPackageError.LIMIT_EXCEEDED,"$label 超过 256 KiB")
        requireStrictJson(bytes,label)
        return try { JSONObject(json) } catch (e: Exception) { fail(ModPackageError.INVALID_MANIFEST,"$label 必须为 JSON object") }
    }

    private data class ParsedManifest(
        val id: String, val name: String, val version: String, val versionCode: Int,
        val authorName: String, val authorFingerprint: String, val platformApi: Int,
        val minimumAndroidApi: Int, val requiredCapabilities: List<ModCapability>,
        val optionalCapabilities: List<ModCapability>, val requiredPermissions: List<String>,
        val optionalPermissions: List<String>, val settingsPath: String, val rearPath: String?,
        val runtime: ModRuntimeKind, val stateSchema: JSONObject, val configSchema: JSONObject,
        val dataVersion: Int, val payloads: List<ModPayload>
    )

    private fun parseManifest(root: JSONObject): ParsedManifest {
        requireKeys(root, setOf("format", "formatVersion", "id", "name", "version", "versionCode", "author", "platform", "minimumAndroidApi", "capabilities", "permissions", "scenes", "runtime", "data", "payloads"), "manifest")
        if (root.optString("format") != "remrss.mod" || root.opt("formatVersion") !is Number || root.opt("formatVersion") is Boolean || root.int("formatVersion", 1, 1) != 1) {
            fail(ModPackageError.UNSUPPORTED_FORMAT, "只支持 remrss.mod formatVersion 1")
        }
        val id = root.string("id", 64)
        if (!idPattern.matches(id)) fail(ModPackageError.INVALID_MANIFEST, "Mod id 格式无效")
        val name = root.string("name", 80, minLength = 1)
        val version = root.string("version", 64)
        if (!versionPattern.matches(version)) fail(ModPackageError.INVALID_MANIFEST, "语义版本格式无效")
        val versionCode = root.int("versionCode", min = 1)
        val author = root.getJSONObject("author")
        requireKeys(author, setOf("name", "keyFingerprint"), "author")
        val authorName = author.string("name", 80, minLength = 1)
        val fingerprint = author.string("keyFingerprint", 64)
        if (!Regex("^[0-9a-f]{64}$").matches(fingerprint)) fail(ModPackageError.INVALID_MANIFEST, "作者 keyFingerprint 格式无效")
        val platform = root.getJSONObject("platform")
        requireKeys(platform, setOf("api"), "platform")
        val platformApi = platform.int("api", min = 1)
        if (platformApi != 1) fail(ModPackageError.UNSUPPORTED_FORMAT, "当前宿主只支持 Mod platform.api 1")
        val minimumAndroid = root.int("minimumAndroidApi", min = 24, max = 1000)
        val caps = parseCapabilities(root.getJSONObject("capabilities"))
        val permissions = parsePermissionSet(root.getJSONObject("permissions"))
        val scenes = root.getJSONObject("scenes")
        if (root.has("contributions")) fail(ModPackageError.UNSUPPORTED_FORMAT, "官方主题贡献不属于 RC1 Mod 能力")
        requireKeys(scenes, setOf("settings", "rear"), "scenes")
        val settingsPath = safePath(scenes.string("settings", 240))
        val rearPath = if(scenes.has("rear")) safePath(scenes.string("rear", 240)) else null
        if(rearPath==null && (caps.first+caps.second).any { it.name in setOf("rear.scene","rear.scene.keep_visible") }) fail(ModPackageError.INVALID_MANIFEST,"rear.scene requires a rear scene declaration")
        if (!settingsPath.endsWith(".ui.json") || rearPath?.endsWith(".ui.json")==false || settingsPath == rearPath) {
            fail(ModPackageError.INVALID_MANIFEST, "settings/rear scene 路径必须分别指向 .ui.json")
        }
        val runtimeObj = root.getJSONObject("runtime")
        val runtimeKind = runtimeObj.string("kind", 16)
        val runtime = when (runtimeKind) {
            "none" -> { requireKeys(runtimeObj, setOf("kind"), "runtime"); ModRuntimeKind.NONE }
            "quickjs" -> {
                requireKeys(runtimeObj, setOf("kind", "entry"), "runtime")
                if (runtimeObj.string("entry", 32) != "main.js") fail(ModPackageError.INVALID_MANIFEST, "quickjs entry 必须为 main.js")
                if (minimumAndroid < 29) fail(ModPackageError.INVALID_MANIFEST, "quickjs Mod 的 minimumAndroidApi 至少为 29")
                ModRuntimeKind.QUICKJS
            }
            else -> fail(ModPackageError.UNSUPPORTED_FORMAT, "不支持的 runtime.kind：$runtimeKind")
        }
        val data = root.getJSONObject("data")
        requireKeys(data, setOf("stateSchema", "configSchema", "dataVersion"), "data")
        val stateSchema = data.getJSONObject("stateSchema")
        val configSchema = data.getJSONObject("configSchema")
        try {
            ModJsonSchema.validateDefinition(stateSchema, "stateSchema")
            ModJsonSchema.validateDefinition(configSchema, "configSchema")
        } catch (e: Exception) { fail(ModPackageError.INVALID_SCHEMA, e.message ?: "state/config schema 无效") }
        val dataVersion = data.int("dataVersion", min = 1)
        val payloads = parsePayloads(root.getJSONArray("payloads"))
        if (settingsPath !in payloads.map { it.path } || rearPath!=null && rearPath !in payloads.map { it.path }) {
            fail(ModPackageError.INVALID_MANIFEST, "两个 scene 文件必须列在 payloads")
        }
        if (runtime == ModRuntimeKind.QUICKJS && "main.js" !in payloads.map { it.path }) fail(ModPackageError.INVALID_MANIFEST, "quickjs 包必须列出 main.js")
        if (runtime == ModRuntimeKind.NONE && payloads.any { it.path == "main.js" }) fail(ModPackageError.INVALID_MANIFEST, "无脚本 Mod 不得包含 main.js")
        return ParsedManifest(id, name, version, versionCode, authorName, fingerprint, platformApi, minimumAndroid,
            caps.first, caps.second, permissions.first, permissions.second, settingsPath, rearPath, runtime,
            stateSchema, configSchema, dataVersion, payloads)
    }

    private fun parseCapabilities(obj: JSONObject): Pair<List<ModCapability>, List<ModCapability>> {
        requireKeys(obj, setOf("required", "optional"), "capabilities")
        val required = parseCapabilityArray(obj.getJSONArray("required"))
        val optional = parseCapabilityArray(obj.getJSONArray("optional"))
        if ((required.map { it.name }.toSet() intersect optional.map { it.name }.toSet()).isNotEmpty()) {
            fail(ModPackageError.INVALID_MANIFEST, "能力不能同时声明 required/optional")
        }
        return required to optional
    }

    private fun parseCapabilityArray(array: JSONArray): List<ModCapability> {
        if (array.length() > 32) fail(ModPackageError.LIMIT_EXCEEDED, "能力条目超过 32")
        val result = ArrayList<ModCapability>(array.length())
        val names = HashSet<String>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            requireKeys(item, setOf("name", "minimumVersion"), "capability[$i]")
            val name = item.string("name", 64)
            val version = item.int("minimumVersion", min = 1)
            if (!tokenPattern.matches(name) || capabilities[name]?.let { version <= it } != true) {
                fail(ModPackageError.UNKNOWN_CAPABILITY, "不认识或不支持能力 $name@$version")
            }
            if (!names.add(name)) fail(ModPackageError.INVALID_MANIFEST, "能力重复：$name")
            result += ModCapability(name, version)
        }
        return result
    }

    private fun parsePermissionSet(obj: JSONObject): Pair<List<String>, List<String>> {
        requireKeys(obj, setOf("required", "optional"), "permissions")
        fun strings(array: JSONArray): List<String> {
            if (array.length() > 32) fail(ModPackageError.LIMIT_EXCEEDED, "权限声明超过 32")
            val values = (0 until array.length()).map { array.getString(it) }
            if (values.any { !tokenPattern.matches(it) } || values.toSet().size != values.size) fail(ModPackageError.INVALID_MANIFEST, "权限声明格式无效")
            return values
        }
        val required = strings(obj.getJSONArray("required")); val optional = strings(obj.getJSONArray("optional"))
        if ((required.toSet() intersect optional.toSet()).isNotEmpty()) fail(ModPackageError.INVALID_MANIFEST, "权限不能同时声明 required/optional")
        return required to optional
    }

    private fun parsePayloads(array: JSONArray): List<ModPayload> {
        if (array.length() !in 2..62) fail(ModPackageError.LIMIT_EXCEEDED, "payloads 数量必须在 2..62")
        val seen = HashSet<String>()
        val result = ArrayList<ModPayload>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            requireKeys(item, setOf("path", "bytes", "sha256"), "payload[$i]")
            val path = safePath(item.string("path", 240))
            if (!seen.add(path)) fail(ModPackageError.DUPLICATE_ENTRY, "payload 重复：$path")
            val bytes = item.int("bytes", min = 0, max = MAX_EXPANDED_BYTES)
            val digest = item.string("sha256", 64)
            if (!Regex("^[0-9a-f]{64}$").matches(digest)) fail(ModPackageError.INVALID_MANIFEST, "payload SHA-256 格式无效：$path")
            result += ModPayload(path, bytes, digest)
        }
        return result
    }

    private fun verifyPayloads(manifest: ParsedManifest, entries: Map<String, ByteArray>) {
        val declared = manifest.payloads.map { it.path }.toSet()
        val expected = declared + setOf("manifest.json", "manifest.sig", "author.pub")
        if (entries.keys != expected) fail(ModPackageError.PAYLOAD_MISMATCH, "ZIP 存在未声明或缺失条目")
        var expanded = 0L
        manifest.payloads.forEach { item ->
            val value = entries[item.path] ?: fail(ModPackageError.PAYLOAD_MISMATCH, "缺少载荷 ${item.path}")
            if (value.size != item.bytes || sha256(value) != item.sha256) fail(ModPackageError.PAYLOAD_MISMATCH, "载荷完整性校验失败：${item.path}")
            if (item.path.endsWith(".ui.json") && value.size > MAX_UI_BYTES) fail(ModPackageError.LIMIT_EXCEEDED, "UI 文件超过 64 KiB：${item.path}")
            if (item.path == "main.js" && value.size > MAX_JS_BYTES) fail(ModPackageError.LIMIT_EXCEEDED, "main.js 超过 512 KiB")
        }
        entries.values.forEach { expanded += it.size }
        if (expanded > MAX_EXPANDED_BYTES) fail(ModPackageError.LIMIT_EXCEEDED, "ZIP 展开内容超过 8 MiB")
    }

    private fun parseUi(bytes: ByteArray, path: String, manifest: ParsedManifest, entries: Map<String, ByteArray>): JSONObject {
        requireStrictJson(bytes, path)
        val root = try { JSONObject(decodeUtf8(bytes, path)) } catch (e: Exception) { fail(ModPackageError.INVALID_UI, "$path JSON 无效") }
        try {
            requireKeys(root, setOf("scene", "root"), path)
            val expectedScene = if (path == manifest.settingsPath) "settings" else "rear"
            require(root.getString("scene") == expectedScene) { "$path scene 字段不匹配" }
            val caps = (manifest.requiredCapabilities + manifest.optionalCapabilities).map { it.name }.toSet()
            var count = 0
            var hasConfigInputs = false
            var hasSave = false
            fun walk(node: JSONObject, depth: Int) {
                count++
                require(count <= 128 && depth <= 8) { "UI 节点数或深度超过限制" }
                val type = node.optString("type")
                val allowed = nodeFields[type] ?: error("未知 UI 组件：$type")
                if (type in setOf("settings.input", "settings.number", "settings.toggle", "settings.select", "settings.action")) hasConfigInputs = true
                if (type == "settings.save") hasSave = true
                require(node.keys().asSequence().all { it in allowed }) { "$type 含未知字段" }
                val required = when (type) {
                    "layout.column", "layout.row", "layout.box" -> setOf("children")
                    "ui.text", "ui.number" -> setOf("value")
                    "ui.button" -> setOf("label", "onPress")
                    "ui.image" -> setOf("asset")
                    "ui.list" -> setOf("items", "maxItems")
                    "ui.icon" -> setOf("name", "label")
                    "ui.deadlineText" -> setOf("value")
                    "ui.progress" -> setOf("value", "min", "max")
                    "settings.row" -> setOf("label")
                    "settings.input", "settings.action" -> setOf("label", "value")
                    "settings.number" -> setOf("label", "value", "min", "max")
                    "settings.toggle" -> setOf("label", "value")
                    "settings.select" -> setOf("label", "value", "options")
                    "ui.confirm" -> setOf("label", "title", "message", "onConfirm")
                    else -> emptySet()
                }
                require(node.keys().asSequence().toSet().containsAll(required)) { "$type 缺少必需字段" }
                if (node.has("gap")) require(node.getInt("gap") in 0..64) { "gap 超出 0..64" }
                for (key in listOf("value")) if (node.has(key)) validateBinding(node.get(key), manifest, caps, type)
                if (node.has("items")) {
                    validateBinding(node.get("items"), manifest, caps, type)
                    val path = node.getJSONObject("items").getString("bind")
                    val itemSchema = ModJsonSchema.atPath(if (path.startsWith("state.")) manifest.stateSchema else manifest.configSchema, path.substringAfter('.'))
                    require(itemSchema?.optString("type") == "array") { "ui.list.items 必须绑定 schema 中声明的数组" }
                    require(itemSchema.optJSONObject("items")?.optString("type") in setOf("string", "integer", "number", "boolean")) { "ui.list 只接受标量数组" }
                    require(node.getInt("maxItems") in 1..32) { "ui.list.maxItems 必须为 1..32" }
                }
                if (node.has("visibleWhen")) {
                    val condition = node.getJSONObject("visibleWhen")
                    requireKeys(condition, setOf("bind", "equals"), "visibleWhen")
                    require(condition.has("bind") && condition.has("equals")) { "visibleWhen 需要 bind 和 equals" }
                    validateBinding(JSONObject().put("bind", condition.getString("bind")), manifest, caps, type)
                    require(condition.get("equals") !is JSONObject && condition.get("equals") !is JSONArray && condition.get("equals") != JSONObject.NULL) { "visibleWhen.equals 必须为标量" }
                    val boundPath = condition.getString("bind")
                    val boundSchema = when {
                        boundPath.startsWith("state.") -> ModJsonSchema.atPath(manifest.stateSchema, boundPath.removePrefix("state."))
                        boundPath.startsWith("config.") -> ModJsonSchema.atPath(manifest.configSchema, boundPath.removePrefix("config."))
                        else -> null
                    }
                    if (boundSchema != null) {
                        val boundType = boundSchema.optString("type")
                        val expectedType = when (condition.get("equals")) { is Boolean -> "boolean"; is Number -> "number"; else -> "string" }
                        require(boundType == expectedType || expectedType == "number" && boundType == "integer") { "visibleWhen.equals 类型与绑定不匹配" }
                    }
                }
                if (type.startsWith("layout.")) {
                    require(node.optString("horizontalAlignment", "start") in if (type == "layout.row") setOf("start", "center", "end", "spaceBetween") else setOf("start", "center", "end")) { "horizontalAlignment 无效" }
                    require(node.optString("verticalAlignment", "center") in setOf("top", "center", "bottom")) { "verticalAlignment 无效" }
                }
                if (node.has("format")) require(node.getString("format") in setOf("text", "integer", "decimal", "percent", "duration")) { "未知 format" }
                if (node.has("tone")) require(node.getString("tone") in setOf("primary", "secondary", "success", "warning", "error")) { "未知语义 tone" }
                if (type == "ui.icon") require(node.getString("name") in setOf("add", "remove", "check", "close", "timer", "trophy", "info", "warning")) { "未知宿主图标" }
                if (type == "ui.deadlineText") {
                    require(node.optString("format", "duration") == "duration") { "ui.deadlineText 仅支持 duration" }
                    val path = node.optJSONObject("value")?.optString("bind") ?: error("ui.deadlineText.value 必须绑定毫秒截止时间")
                    require(path.startsWith("state.") || path.startsWith("config.")) { "ui.deadlineText 只能绑定 state/config 的 elapsedRealtime 毫秒截止值" }
                    val schema = ModJsonSchema.atPath(if (path.startsWith("state.")) manifest.stateSchema else manifest.configSchema, path.substringAfter('.'))
                    require(schema?.optString("type") == "integer") { "ui.deadlineText 必须绑定整数毫秒截止时间" }
                    require("clock.snapshot" in caps) { "ui.deadlineText 需要声明 clock.snapshot 能力" }
                }
                for (key in listOf("label", "hint", "placeholder", "title", "message", "emptyText")) if (node.has(key)) {
                    val value = node.get(key); require(value is String && value.length <= if (key == "label") 120 else 240) { "$type.$key 类型或长度无效" }
                }
                if (type in setOf("settings.input", "settings.number", "settings.toggle", "settings.select", "settings.action")) {
                    val binding=node.optJSONObject("value")?.optString("bind") ?: ""
                    require(binding.startsWith("config.")) { "$type.value 必须绑定 config.*" }
                    val schema=ModJsonSchema.atPath(manifest.configSchema,binding.removePrefix("config.")) ?: error("配置字段未在 configSchema 声明")
                    val expected=when(type) {
                        "settings.input" -> setOf("string")
                        "settings.number" -> setOf("integer","number")
                        "settings.toggle" -> setOf("boolean")
                        "settings.action" -> setOf("object")
                        else -> setOf("string","integer","number","boolean")
                    }
                    require(schema.optString("type") in expected) { "$type 与 config schema 类型不匹配" }
                    if (type == "settings.action") {
                        require("action.invoke" in caps) { "settings.action 需要声明 action.invoke 能力" }
                        validateRegisteredActionConfig(manifest.configSchema, binding.removePrefix("config."))
                    }
                }
                if (type == "ui.button" || type == "settings.row" && node.has("onPress") || type == "ui.confirm") {
                    val action = node.optJSONObject(if (type == "ui.confirm") "onConfirm" else "onPress") ?: error("action 无效")
                    validateAction(action, caps, manifest.runtime, manifest)
                }
                if (type == "ui.image") {
                    val asset = safePath(node.getString("asset"))
                    require(asset.startsWith("assets/") && asset in manifest.payloads.map { it.path }) { "image 必须引用已签名 assets/ 载荷" }
                    if (node.has("alt")) require(node.getString("alt").length <= 120) { "alt 超长" }
                }
                if (type == "ui.progress" || type == "settings.number") {
                    val min = node.getDouble("min"); val max = node.getDouble("max")
                    require(min.isFinite() && max.isFinite() && min < max) { "$type 范围无效" }
                    if (type == "settings.number") require(node.get("min") is Number && node.get("max") is Number && node.getDouble("min") % 1.0 == 0.0 && node.getDouble("max") % 1.0 == 0.0) { "settings.number min/max 必须为整数" }
                }
                if (type == "settings.select") {
                    val options = node.getJSONArray("options")
                    require(options.length() in 1..32) { "select options 必须在 1..32" }
                    val values = mutableSetOf<String>()
                    for (i in 0 until options.length()) {
                        val option = options.getJSONObject(i); requireKeys(option, setOf("label", "value"), "select option")
                        require(option.getString("label").length <= 80) { "select label 超长" }
                        require(option.get("value") !is JSONObject && option.get("value") !is JSONArray && option.get("value") != JSONObject.NULL) { "select value 必须是非空标量" }
                        require(values.add(option.get("value").toString())) { "select value 重复" }
                    }
                }
                if (node.has("style")) {
                    val style = node.getString("style")
                    require(if (type == "ui.list") style in setOf("body", "caption") else style in setOf("title", "body", "caption", "number", "displayNumber")) { "未知语义样式" }
                }
                if (type in setOf("layout.column", "layout.row", "layout.box")) {
                    val children = node.getJSONArray("children"); require(children.length() <= 128) { "容器子项过多" }
                    for (i in 0 until children.length()) walk(children.getJSONObject(i), depth + 1)
                }
            }
            walk(root.getJSONObject("root"), 1)
            if (expectedScene == "settings" && hasConfigInputs) require(hasSave) { "设置表单含草稿字段时必须有 settings.save" }
            if (expectedScene == "rear") require(!hasSave) { "settings.save 只能出现在设置场景" }
        } catch (e: ModPackageException) { throw e }
        catch (e: Exception) {
            fail(ModPackageError.INVALID_UI, e.message ?: "$path UI 无效")
        }
        return root
    }

    private fun validateAction(action: JSONObject, caps: Set<String>, runtime: ModRuntimeKind, manifest: ParsedManifest) {
        when (action.optString("type")) {
            "event" -> {
                requireKeys(action, setOf("type", "name"), "event action")
                require(runtime == ModRuntimeKind.QUICKJS && tokenPattern.matches(action.getString("name"))) { "event action 需要 QuickJS reducer 和合法事件名" }
            }
            "registeredAction" -> {
                requireKeys(action, setOf("type", "ref"), "registered action")
                require("action.invoke" in caps) { "registered action 需要声明 action.invoke 能力" }
                val ref = action.getJSONObject("ref")
                if (ref.keys().asSequence().toSet() == setOf("id", "version")) {
                    val version = ref.opt("version")
                    require(opaqueActionId.matches(ref.getString("id")) && version is Number && version.toDouble() == 1.0) { "registered action ref 无效或版本不支持" }
                } else {
                    requireKeys(ref, setOf("bind"), "registered action binding")
                    val binding = ref.getString("bind")
                    require(binding.startsWith("config.") && binding.substringAfter("config.").matches(configFieldPattern)) { "registered action 只支持 config.* 顶层字段绑定" }
                    validateRegisteredActionConfig(manifest.configSchema, binding.removePrefix("config."))
                }
            }
            else -> error("未知 action 类型")
        }
    }

    private fun validateRegisteredActionConfig(configSchema: JSONObject, field: String) {
        require(field.matches(configFieldPattern)) { "registered action config 字段必须是顶层字段" }
        val required = configSchema.optJSONArray("required")?.toStringSet().orEmpty()
        require(field !in required) { "registered action config 字段必须可选，以支持尚未选择动作" }
        val schema = ModJsonSchema.atPath(configSchema, field) ?: error("registered action config 字段未声明")
        val props = schema.optJSONObject("properties") ?: error("registered action configSchema 必须声明 id/version")
        val schemaRequired = schema.optJSONArray("required")?.toStringSet().orEmpty()
        require(schema.optString("type") == "object" && props.length() == 2 && props.has("id") && props.has("version") &&
            schemaRequired == setOf("id", "version") && !schema.optBoolean("additionalProperties", false)) { "registered action configSchema 只允许必需的 id/version" }
        val id = props.getJSONObject("id"); val version = props.getJSONObject("version")
        require(id.optString("type") == "string" && id.optInt("maxLength", 0) == 48 && id.optString("pattern") == opaqueActionIdPattern &&
            version.optString("type") == "integer" && version.optInt("const", -1) == 1) { "registered action configSchema 必须使用登记 opaque id 与 const 1 version" }
    }

    private fun validateBinding(value: Any, manifest: ParsedManifest, caps: Set<String>, nodeType: String) {
        if (value !is JSONObject) {
            require(value is String || value is Number || value is Boolean) { "$nodeType.value 类型无效" }
            return
        }
        requireKeys(value, setOf("bind"), "$nodeType.value")
        val path = value.string("bind", 160)
        val match = Regex("^(state|config|data)\\.([A-Za-z][A-Za-z0-9_.]*)$").matchEntire(path) ?: error("binding path 格式无效")
        val namespace = match.groupValues[1]; val field = match.groupValues[2]
        when (namespace) {
            "state" -> require(ModJsonSchema.hasPath(manifest.stateSchema, field)) { "state binding 未在 schema 声明：$path" }
            "config" -> require(ModJsonSchema.hasPath(manifest.configSchema, field)) { "config binding 未在 schema 声明：$path" }
            "data" -> {
                val source = field.substringBefore('.')
                val requiredCapability = when (source) { "battery" -> "data.battery"; "systemMetrics" -> "data.systemMetrics"; else -> null }
                require(requiredCapability != null && requiredCapability in caps) { "data binding 未声明数据能力：$path" }
                val supportedPath = when (field) {
                    "battery.level", "battery.scale", "battery.charging",
                    "systemMetrics.cpu.utilizationPercent",
                    "systemMetrics.memory.availableBytes", "systemMetrics.memory.totalBytes" -> true
                    else -> false
                }
                require(supportedPath) { "宿主未提供此数据字段：$path" }
            }
        }
    }

    private fun validateAssets(entries: Map<String, ByteArray>) {
        var cacheBytes = 0L
        entries.filterKeys { it.startsWith("assets/") }.forEach { (path, bytes) ->
            if (!path.endsWith(".png", true) && !path.endsWith(".webp", true)) fail(ModPackageError.INVALID_RESOURCE, "资源只允许 PNG/WebP：$path")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth !in 1..1024 || options.outHeight !in 1..1024) fail(ModPackageError.INVALID_RESOURCE, "图片尺寸须为 1..1024px：$path")
            cacheBytes += options.outWidth.toLong() * options.outHeight * 4
            if (cacheBytes > 8 * 1024 * 1024) fail(ModPackageError.LIMIT_EXCEEDED, "图片解码缓存超过 8 MiB")
            if (bytes.size >= 21 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP" && bytes.copyOfRange(12, 16).decodeToString() == "VP8X" && bytes[20].toInt() and 0x02 != 0) {
                fail(ModPackageError.INVALID_RESOURCE, "不支持动画 WebP：$path")
            }
        }
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var expanded = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes), StandardCharsets.UTF_8).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entries.size >= MAX_FILES) fail(ModPackageError.LIMIT_EXCEEDED, "ZIP 文件数超过 64")
                    if (entry.isDirectory) fail(ModPackageError.INVALID_ZIP, "ZIP 不允许显式目录条目")
                    val path = safePath(entry.name, allowMetadata = true)
                    if (entries.containsKey(path)) fail(ModPackageError.DUPLICATE_ENTRY, "ZIP 重复条目：$path")
                    val itemLimit = when (path) {
                        "manifest.json" -> MAX_MANIFEST_BYTES
                        "manifest.sig" -> MAX_SIGNATURE_BYTES
                        "author.pub" -> MAX_PUBLIC_KEY_BYTES
                        "main.js" -> MAX_JS_BYTES
                        else -> MAX_EXPANDED_BYTES
                    }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        expanded += count
                        if (expanded > MAX_EXPANDED_BYTES || output.size().toLong() + count > itemLimit) fail(ModPackageError.LIMIT_EXCEEDED, "ZIP 展开内容或条目超过上限：$path")
                        output.write(buffer, 0, count)
                    }
                    entries[path] = output.toByteArray()
                    zip.closeEntry()
                }
            }
        } catch (e: ModPackageException) { throw e }
        catch (e: Exception) { fail(ModPackageError.INVALID_ZIP, "ZIP 结构无效：${e.message}") }
        return entries
    }

    private fun parsePublicKey(pem: ByteArray) = try {
        val text = decodeUtf8(pem, "author.pub")
        val base64 = Regex("-----BEGIN PUBLIC KEY-----\\s*(.*?)\\s*-----END PUBLIC KEY-----", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: fail(ModPackageError.INVALID_AUTHOR_KEY, "author.pub 必须是 SubjectPublicKeyInfo PEM")
        val der = Base64.decode(base64.filterNot { it.isWhitespace() }, Base64.DEFAULT)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    } catch (e: ModPackageException) { throw e }
    catch (e: Exception) { fail(ModPackageError.INVALID_AUTHOR_KEY, "author.pub 无效") }

    private fun requireStrictJson(bytes: ByteArray, label: String) {
        try {
            val reader = JsonReader(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).apply { isLenient = false }
            fun value(depth: Int) {
                require(depth <= 16) { "$label JSON 深度超过 16" }
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject(); val keys = HashSet<String>()
                        while (reader.hasNext()) { val name = reader.nextName(); require(keys.add(name)) { "$label JSON 重复字段：$name" }; value(depth + 1) }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> { reader.beginArray(); while (reader.hasNext()) value(depth + 1); reader.endArray() }
                    JsonToken.STRING -> reader.nextString()
                    JsonToken.NUMBER -> require(reader.nextDouble().isFinite()) { "$label JSON 数字必须有限" }
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> error("$label JSON token 无效")
                }
            }
            value(0); require(reader.peek() == JsonToken.END_DOCUMENT) { "$label 有尾随 JSON 内容" }; reader.close()
        } catch (e: Exception) { fail(ModPackageError.INVALID_MANIFEST, e.message ?: "$label JSON 无效") }
    }

    private fun readBounded(input: InputStream, limit: Int, code: ModPackageError, message: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 32 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) fail(code, message)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun safePath(value: String, allowMetadata: Boolean = false): String {
        if (value.isBlank() || value.startsWith('/') || value.contains('\\') || value.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(value)) {
            fail(ModPackageError.UNSAFE_PATH, "ZIP 路径无效")
        }
        val parts = value.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) fail(ModPackageError.UNSAFE_PATH, "拒绝 ZIP 路径穿越")
        if (!allowMetadata && value in setOf("manifest.json", "manifest.sig", "author.pub")) fail(ModPackageError.UNSAFE_PATH, "载荷路径保留")
        return value
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun decodeUtf8(bytes: ByteArray, label: String): String = try { StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (e: Exception) { fail(ModPackageError.INVALID_MANIFEST, "$label 必须为 UTF-8") }

    private fun requireKeys(obj: JSONObject, expected: Set<String>, field: String) {
        val keys = obj.keys().asSequence().toSet()
        require(keys == expected) { "$field 字段缺失或包含未知字段" }
    }
    private fun JSONArray.toStringSet(): Set<String> = (0 until length()).map { getString(it) }.toSet()
    private fun JSONObject.string(key: String, max: Int, minLength: Int = 0): String {
        val value = opt(key) as? String ?: fail(ModPackageError.INVALID_MANIFEST, "$key 必须为字符串")
        if (value.length !in minLength..max) fail(ModPackageError.INVALID_MANIFEST, "$key 长度无效")
        return value
    }
    private fun JSONObject.int(key: String, min: Int, max: Int = Int.MAX_VALUE): Int {
        val value = opt(key)
        if (value !is Number || value is Double && !value.isFinite() || value.toDouble() % 1.0 != 0.0 || value.toLong() !in min.toLong()..max.toLong()) {
            fail(ModPackageError.INVALID_MANIFEST, "$key 必须是 $min..$max 范围整数")
        }
        return value.toInt()
    }
    private fun fail(code: ModPackageError, message: String): Nothing = throw ModPackageException(code, message)
}
