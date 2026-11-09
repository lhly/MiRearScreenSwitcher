package top.zainzh.rearscreen.platform.notifications

import android.app.NotificationManager
import android.content.Context
import android.os.Process
import top.zainzh.rearscreen.NotificationService
import top.zainzh.rearscreen.R
import top.zainzh.rearscreen.platform.SessionPrincipal
import top.zainzh.rearscreen.platform.mods.ModPackageStore

/** Pure projection of the legacy notification preferences onto a publisher-owned item. */
internal fun legacyNotificationPreferenceBlock(
    followDndMode: Boolean,
    dndActive: Boolean,
    onlyWhenCovered: Boolean,
    mainScreenCovered: Boolean,
): String? = when {
    followDndMode && dndActive -> "dnd"
    onlyWhenCovered && !mainScreenCovered -> "only_when_covered"
    else -> null
}

/** Re-evaluates publisher identity, capability grant and user policy at every transition. */
internal class OwnedNotificationPolicy(private val context: Context) {
    private val app = context.applicationContext
    private val grants = NotificationAuthorizationStore(app)

    fun ownerIsCurrent(owner: NotificationOwner): Boolean {
        if (!owner.isCurrent()) return false
        return when (val principal = owner.sessionOwner.principal) {
            is SessionPrincipal.AndroidClient -> {
                principal.uid == owner.sessionOwner.transportUid &&
                    principal.packageName == owner.sessionOwner.transportPackageName &&
                    principal.signingCertificateDigest == owner.sessionOwner.transportSigningCertificateDigest
            }
            is SessionPrincipal.SignedMod -> {
                val current = top.zainzh.rearscreen.platform.RearCapabilityCore.get(app).modDataOwner(principal.modId)
                current == owner.sessionOwner && owner.sessionOwner.transportUid == Process.myUid()
            }
            is SessionPrincipal.HostFeature -> false
        }
    }

    fun decide(owner: NotificationOwner, request: NotificationRequest): NotificationPolicyResult {
        if (!ownerIsCurrent(owner)) return NotificationPolicyResult.Rejected("STALE_OWNER")
        when (val principal = owner.sessionOwner.principal) {
            is SessionPrincipal.AndroidClient -> if (!grants.isGranted(owner.sessionOwner))
                return NotificationPolicyResult.Suppressed("not_authorized", NotificationEndReason.AUTH_REVOKED)
            is SessionPrincipal.SignedMod -> {
                val installed = ModPackageStore.installed(app, principal.modId)
                    ?: return NotificationPolicyResult.Rejected("OWNER_UNAVAILABLE")
                if (!installed.enabled || installed.authorFingerprint != principal.authorFingerprint ||
                    installed.logicalSessionId != principal.logicalSessionId ||
                    "rear.notification" !in installed.grantedCapabilities ||
                    !ModPackageStore.isAuthorTrusted(app, installed.authorFingerprint))
                    return NotificationPolicyResult.Suppressed("not_authorized", NotificationEndReason.AUTH_REVOKED)
            }
            is SessionPrincipal.HostFeature -> return NotificationPolicyResult.Rejected("OWNER_UNAVAILABLE")
        }

        val prefs = app.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("flutter.notification_service_enabled", false))
            return NotificationPolicyResult.Suppressed("global_disabled", NotificationEndReason.GLOBAL_DISABLED)
        val privatePrefs = app.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val dndActive = manager != null && manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val legacyBlock = legacyNotificationPreferenceBlock(
            followDndMode = privatePrefs.getBoolean("notification_follow_dnd_mode", true),
            dndActive = dndActive,
            onlyWhenCovered = privatePrefs.getBoolean("notification_only_when_locked", false),
            mainScreenCovered = NotificationService.isMainScreenCoveredForNotifications(),
        )
        if (legacyBlock == "dnd") return NotificationPolicyResult.Suppressed("dnd", NotificationEndReason.DND)
        if (legacyBlock == "only_when_covered") return NotificationPolicyResult.Suppressed(
            "only_when_covered", NotificationEndReason.DISPLAY_UNAVAILABLE
        )

        var title = request.title
        var text = request.text
        if (request.privacy == NotificationPrivacy.HIDE_TITLE || privatePrefs.getBoolean("notification_privacy_hide_title", false))
            title = app.getString(R.string.privacy_mode_enabled)
        if (request.privacy == NotificationPrivacy.HIDE_TEXT || privatePrefs.getBoolean("notification_privacy_hide_content", false))
            text = app.getString(R.string.new_message_placeholder)
        return NotificationPolicyResult.Allowed(request.copy(title = title, text = text))
    }
}
