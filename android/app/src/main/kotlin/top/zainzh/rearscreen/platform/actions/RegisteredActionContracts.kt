package top.zainzh.rearscreen.platform.actions

import top.zainzh.rearscreen.platform.SessionOwner
import top.zainzh.rearscreen.platform.SessionPrincipal

/** Opaque handle exposed to a Mod or SDK consumer. It never contains an executable target. */
data class RegisteredActionRef(val id: String, val version: Int = 1)

/** Stable publisher identity; deliberately excludes a logical-session id or Binder connection epoch. */
class ActionPublisherKey private constructor(val kind: String, val publisherId: String) {
    companion object {
        internal fun restore(kind: String, publisherId: String) = ActionPublisherKey(kind, publisherId)
        fun from(owner: SessionOwner): ActionPublisherKey? = when (val principal = owner.principal) {
            is SessionPrincipal.SignedMod -> ActionPublisherKey(
                "mod", "${principal.authorFingerprint}\u0000${principal.modId}"
            )
            is SessionPrincipal.AndroidClient -> ActionPublisherKey(
                "sdk", "${principal.packageName}\u0000${principal.signingCertificateDigest}"
            )
            is SessionPrincipal.HostFeature -> ActionPublisherKey("host", principal.featureId)
        }
    }

    override fun equals(other: Any?): Boolean = other is ActionPublisherKey &&
        kind == other.kind && publisherId == other.publisherId

    override fun hashCode(): Int = 31 * kind.hashCode() + publisherId.hashCode()
}

data class RegisteredAction(
    val ref: RegisteredActionRef,
    val owner: ActionPublisherKey,
    val label: String,
    val targetPackage: String,
    val targetClass: String,
    val targetSignerSha256: String,
    val action: String,
    val dataUri: String?,
    val categories: List<String>,
    val extras: Map<String, ActionExtra>,
    val revoked: Boolean = false
)

sealed interface ActionExtra {
    data class Text(val value: String) : ActionExtra
    data class Bool(val value: Boolean) : ActionExtra
    data class Int32(val value: Int) : ActionExtra
    data class Int64(val value: Long) : ActionExtra
    data class Float64(val value: Double) : ActionExtra
    data class Object(val values: Map<String, ActionExtra>) : ActionExtra
}

enum class ActionError {
    NOT_FOUND,
    REVOKED,
    OWNER_MISMATCH,
    TARGET_UNAVAILABLE,
    INVALID_TARGET,
    LIMIT_EXCEEDED,
    STORAGE_FAILED,
    LAUNCH_REJECTED,
    BACKGROUND_RESTRICTED
}

sealed interface ActionRegistrationResult {
    data class Registered(val ref: RegisteredActionRef) : ActionRegistrationResult
    data object UserCancelled : ActionRegistrationResult
    data class Rejected(val error: ActionError) : ActionRegistrationResult
}

sealed interface ActionDispatchResult {
    /** OS accepted the explicit activity launch request; no claim about downstream business completion. */
    data object Accepted : ActionDispatchResult
    data class Rejected(val error: ActionError) : ActionDispatchResult
}

/** Called only by Core after it has authenticated owner, grant, activation, visibility, and lock policy. */
interface RegisteredActionInvoker {
    fun resolve(owner: SessionOwner, ref: RegisteredActionRef): ActionResolveResult
    fun launch(resolved: ResolvedRegisteredAction): ActionDispatchResult
}

interface RegisteredActionRegistry : RegisteredActionInvoker {
    fun listForOwner(owner: SessionOwner): List<RegisteredActionSummary>
    fun registerLauncherResult(owner: SessionOwner, selectedActivity: android.content.Intent): ActionRegistrationResult
    fun registerFromProviderResult(owner: SessionOwner, providerResult: android.content.Intent): ActionRegistrationResult
    fun revoke(owner: SessionOwner, ref: RegisteredActionRef): Boolean
    fun revokeAllForPublisher(owner: SessionOwner): Int
}

sealed interface ActionResolveResult {
    data class Resolved(val action: ResolvedRegisteredAction) : ActionResolveResult
    data class Rejected(val error: ActionError) : ActionResolveResult
}

/** Created only by the registry after owner/revocation/installed-target checks. */
class ResolvedRegisteredAction internal constructor(internal val record: RegisteredAction)

data class RegisteredActionSummary(
    val ref: RegisteredActionRef,
    val label: String,
    val targetLabel: String,
    val available: Boolean,
    val revoked: Boolean
)
