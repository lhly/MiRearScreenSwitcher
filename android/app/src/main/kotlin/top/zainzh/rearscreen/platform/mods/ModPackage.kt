package top.zainzh.rearscreen.platform.mods

import org.json.JSONObject

enum class ModRuntimeKind { NONE, QUICKJS }

data class ModCapability(val name: String, val minimumVersion: Int)

data class ModPayload(val path: String, val bytes: Int, val sha256: String)

/** A fully verified v1 package. Source bytes and UI JSON retain the signed package meaning. */
data class VerifiedModPackage internal constructor(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val authorName: String,
    val authorFingerprint: String,
    val platformApi: Int,
    val minimumAndroidApi: Int,
    val requiredCapabilities: List<ModCapability>,
    val optionalCapabilities: List<ModCapability>,
    val requiredPermissions: List<String>,
    val optionalPermissions: List<String>,
    val settingsScene: JSONObject,
    val rearScene: JSONObject?,
    val stateSchema: JSONObject,
    val configSchema: JSONObject,
    val dataVersion: Int,
    val runtime: ModRuntimeKind,
    val reducerSource: ByteArray?,
    val publicKeyPem: ByteArray,
    val signedManifest: ByteArray,
    val payloads: Map<String, ByteArray>
) {
    val publisherKey: String get() = authorFingerprint
    val installationIdentity: String get() = "$authorFingerprint:$id"
}

sealed interface ModPackageReadResult {
    data class Valid(val packageInfo: VerifiedModPackage) : ModPackageReadResult
    data class Invalid(val reason: ModPackageError) : ModPackageReadResult
}

enum class ModPackageError {
    TOO_LARGE, INVALID_ZIP, DUPLICATE_ENTRY, UNSAFE_PATH, UNSUPPORTED_FORMAT,
    INVALID_MANIFEST, INVALID_SIGNATURE, INVALID_AUTHOR_KEY, PAYLOAD_MISMATCH,
    LIMIT_EXCEEDED, UNKNOWN_CAPABILITY, INVALID_UI, INVALID_SCHEMA, INVALID_RESOURCE
}

class ModPackageException(val code: ModPackageError, message: String) : IllegalArgumentException(message)
