package top.zainzh.rearscreen.platform.notifications

import android.content.Context
import android.content.SharedPreferences
import top.zainzh.rearscreen.platform.SessionOwner
import top.zainzh.rearscreen.platform.SessionPrincipal

/** Publisher grants are separate from NotificationListenerService's source-app selection. */
internal class NotificationAuthorizationStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isGranted(owner: SessionOwner): Boolean {
        val principal = owner.principal as? SessionPrincipal.AndroidClient ?: return false
        if (principal.uid != owner.transportUid || principal.packageName != owner.transportPackageName ||
            principal.signingCertificateDigest != owner.transportSigningCertificateDigest) return false
        return prefs.getBoolean(grantKey(principal.packageName, principal.signingCertificateDigest), false)
    }

    fun setGranted(packageName: String, signingDigest: String, granted: Boolean) {
        val key = grantKey(packageName, signingDigest)
        if (granted) prefs.edit().putBoolean(key, true).apply() else prefs.edit().remove(key).apply()
    }

    companion object {
        private const val PREFS = "rear_notification_publisher_grants"
        private fun grantKey(packageName: String, digest: String) = "$packageName:$digest"
    }
}

internal fun notificationOwnerForSdk(owner: SessionOwner, isCurrent: () -> Boolean): NotificationOwner {
    val identity = owner.principal as? SessionPrincipal.AndroidClient
        ?: throw SecurityException("E_CALLER_IDENTITY_MISMATCH")
    require(identity.uid == owner.transportUid && identity.packageName == owner.transportPackageName &&
        identity.signingCertificateDigest == owner.transportSigningCertificateDigest) { "E_CALLER_IDENTITY_MISMATCH" }
    return NotificationOwner(owner, identity.connectionEpoch.toString(),
        "sdk:${identity.uid}:${identity.packageName}:${identity.signingCertificateDigest}", isCurrent)
}

internal fun notificationOwnerForMod(owner: SessionOwner, isCurrent: () -> Boolean): NotificationOwner {
    val principal = owner.principal as? SessionPrincipal.SignedMod ?: throw SecurityException("E_OWNER_MISMATCH")
    return NotificationOwner(owner, principal.logicalSessionId, "mod:${principal.modId}", isCurrent)
}
