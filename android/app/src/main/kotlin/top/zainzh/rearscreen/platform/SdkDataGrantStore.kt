package top.zainzh.rearscreen.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import top.zainzh.rearscreen.platform.data.DataField
import java.security.MessageDigest

/** Persistent SDK data consent is tied to the installed signer, not a transient Binder epoch. */
internal class SdkDataGrantStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("platform_sdk_data_grants", Context.MODE_PRIVATE)

    fun grants(owner: SessionOwner): Set<String> {
        val principal = owner.principal as? SessionPrincipal.AndroidClient ?: return emptySet()
        if (!isCurrentIdentity(owner, principal.packageName)) return emptySet()
        return prefs.getStringSet(key(principal.packageName, owner.transportSigningCertificateDigest), emptySet())
            ?.toSet().orEmpty()
    }

    fun updateConsent(owner: SessionOwner, requested: Set<DataField>, allowed: Set<DataField>) {
        val principal = owner.principal as? SessionPrincipal.AndroidClient ?: return
        if (!isCurrentIdentity(owner, principal.packageName)) return
        val current = grants(owner).toMutableSet()
        if (requested.any(::isBattery)) {
            if (allowed.any(::isBattery)) current += DATA_BATTERY else current -= DATA_BATTERY
        }
        if (requested.any(::isSystemMetrics)) {
            if (allowed.any(::isSystemMetrics)) current += DATA_SYSTEM_METRICS else current -= DATA_SYSTEM_METRICS
        }
        prefs.edit().putStringSet(key(principal.packageName, owner.transportSigningCertificateDigest), current).apply()
    }

    private fun isCurrentIdentity(owner: SessionOwner, packageName: String): Boolean {
        if (owner.transportPackageName != packageName) return false
        val packages = app.packageManager.getPackagesForUid(owner.transportUid)?.toSet().orEmpty()
        if (packageName !in packages) return false
        val info = if (Build.VERSION.SDK_INT >= 28) app.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") app.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val cert = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        else @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
        val digest = cert?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02X".format(b) } }
            ?: return false
        return digest.equals(owner.transportSigningCertificateDigest, ignoreCase = true)
    }

    private fun key(packageName: String, digest: String) = "${packageName}_${digest.uppercase()}"
    private fun isBattery(field: DataField) = field == DataField.BATTERY_LEVEL_PERCENT || field == DataField.BATTERY_CHARGING
    private fun isSystemMetrics(field: DataField) = field.name.startsWith("SYSTEM_")

    companion object {
        const val DATA_BATTERY = "data.battery"
        const val DATA_SYSTEM_METRICS = "data.systemMetrics"
    }
}
