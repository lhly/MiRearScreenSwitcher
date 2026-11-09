package top.zainzh.rearscreen.platform.notifications

import top.zainzh.rearscreen.platform.SessionOwner

/** Issued only by RearCapabilityCore after resolving a live SDK or Mod principal. */
internal class NotificationOwner internal constructor(
    internal val sessionOwner: SessionOwner,
    internal val generation: String,
    internal val namespace: String,
    private val validity: () -> Boolean,
) {
    internal fun isCurrent(): Boolean = runCatching { validity() }.getOrDefault(false)
}

internal enum class NotificationPriority { LOW, NORMAL, HIGH }
internal enum class NotificationPrivacy { STANDARD, HIDE_TITLE, HIDE_TEXT }
internal enum class NotificationSubmitStatus { ACCEPTED, SUPPRESSED, REJECTED }
internal enum class NotificationEventStatus { DISPLAYED, SUPPRESSED, ENDED, FAILED }
internal enum class NotificationEndReason {
    TIMEOUT, REPLACED, CANCELLED, OWNER_CLOSED, OWNER_DIED, AUTH_REVOKED, GLOBAL_DISABLED,
    PRIVACY_POLICY, DND, COVERED, DISPLAY_UNAVAILABLE, SCENE_TAKEOVER, SERVICE_STOPPED,
    RENDER_FAILED, HOST_ERROR
}

internal data class NotificationRequest(
    val id: String,
    val title: String,
    val text: String = "",
    val durationMs: Long = 8_000,
    val priority: NotificationPriority = NotificationPriority.NORMAL,
    val privacy: NotificationPrivacy = NotificationPrivacy.STANDARD,
)

internal data class NotificationSubmitResult(
    val status: NotificationSubmitStatus,
    val id: String,
    val requestId: String? = null,
    val reason: String? = null,
)

internal data class NotificationEvent(
    val requestId: String,
    val id: String,
    val status: NotificationEventStatus,
    val reason: String? = null,
    val endedBy: NotificationEndReason? = null,
)

internal fun interface NotificationObserver { fun onEvent(event: NotificationEvent) }

internal sealed interface NotificationPolicyResult {
    data class Allowed(val effectiveRequest: NotificationRequest) : NotificationPolicyResult
    data class Suppressed(val reason: String, val endedBy: NotificationEndReason) : NotificationPolicyResult
    data class Rejected(val reason: String) : NotificationPolicyResult
}

internal data class OwnedNotificationItem(
    val owner: NotificationOwner,
    val request: NotificationRequest,
    val requestId: String,
    val submittedAtElapsedMs: Long,
    val expiresAtElapsedMs: Long,
    val observer: NotificationObserver?,
)

internal interface OwnedNotificationRouter {
    fun publish(owner: NotificationOwner, request: NotificationRequest,
                observer: NotificationObserver? = null): NotificationSubmitResult
    fun cancel(owner: NotificationOwner, id: String,
               reason: NotificationEndReason = NotificationEndReason.CANCELLED): NotificationSubmitResult
    fun closeOwner(owner: NotificationOwner, reason: NotificationEndReason)
    fun closeMod(modId: String, reason: NotificationEndReason)
    fun policyChanged()
    fun presentationAvailable()
}

internal interface OwnedNotificationPresentation {
    /** Returns a presentation handle only when the existing host accepted the request for rendering. */
    fun present(item: OwnedNotificationItem, onDisplayed: () -> Unit, onEnded: (NotificationEndReason) -> Unit): PresentationResult
    /** Resume a router-validated covered handle, or allocate a fresh physical render token. */
    fun resume(handle: Any, item: OwnedNotificationItem, onDisplayed: () -> Unit,
               onEnded: (NotificationEndReason) -> Unit): PresentationResult = present(item, onDisplayed, onEnded)
    /** Suspend without terminating an item or its deadline. */
    fun suspend(handle: Any)
    fun cancel(handle: Any, reason: NotificationEndReason)
}

internal sealed interface PresentationResult {
    data class Started(val handle: Any) : PresentationResult
    data object Deferred : PresentationResult
    data class Suppressed(val reason: String, val endedBy: NotificationEndReason) : PresentationResult
    data class Failed(val reason: String) : PresentationResult
}
