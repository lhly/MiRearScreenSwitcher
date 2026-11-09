package top.zainzh.rearscreen.platform.notifications

import android.content.Context
import top.zainzh.rearscreen.NotificationService

internal data class NotificationRenderHandle(val token: Long, val capsule: Boolean)

internal interface NotificationRenderListener {
    fun onDisplayed()
    fun onEnded(reason: String)
}

/** Adapter to the existing physical notification Activity or virtual cast capsule. */
internal class AndroidOwnedNotificationPresentation(context: Context) : OwnedNotificationPresentation {
    private val app = context.applicationContext

    override fun present(
        item: OwnedNotificationItem,
        onDisplayed: () -> Unit,
        onEnded: (NotificationEndReason) -> Unit,
    ): PresentationResult {
        val listener = object : NotificationRenderListener {
            override fun onDisplayed() = onDisplayed.invoke()
            override fun onEnded(reason: String) {
                onEnded.invoke(runCatching { NotificationEndReason.valueOf(reason) }.getOrDefault(NotificationEndReason.HOST_ERROR))
            }
        }
        val token = NotificationService.offerOwnedNotification(
            item.owner.sessionOwner.transportPackageName, item.request.title, item.request.text,
            item.expiresAtElapsedMs, listener
        ) ?: return PresentationResult.Suppressed("display_unavailable", NotificationEndReason.DISPLAY_UNAVAILABLE)
        if (token == NotificationService.OWNED_RENDER_DEFERRED) return PresentationResult.Deferred
        return PresentationResult.Started(NotificationRenderHandle(token, token < 0))
    }

    override fun resume(
        handle: Any,
        item: OwnedNotificationItem,
        onDisplayed: () -> Unit,
        onEnded: (NotificationEndReason) -> Unit,
    ): PresentationResult {
        val render = handle as? NotificationRenderHandle
            ?: return present(item, onDisplayed, onEnded)
        if (!render.capsule) NotificationService.abandonOwnedNotification(render.token)
        val token = if (render.capsule) {
            NotificationService.resumeOwnedCapsule(render.token, item.expiresAtElapsedMs)
                ?: NotificationService.offerOwnedNotification(
                    item.owner.sessionOwner.transportPackageName, item.request.title, item.request.text,
                    item.expiresAtElapsedMs, listener(onDisplayed, onEnded)
                )
        } else {
            NotificationService.offerOwnedNotification(
                item.owner.sessionOwner.transportPackageName, item.request.title, item.request.text,
                item.expiresAtElapsedMs, listener(onDisplayed, onEnded)
            )
        } ?: return PresentationResult.Suppressed("display_unavailable", NotificationEndReason.DISPLAY_UNAVAILABLE)
        if (token == NotificationService.OWNED_RENDER_DEFERRED) return PresentationResult.Deferred
        return PresentationResult.Started(NotificationRenderHandle(token, token < 0))
    }

    override fun suspend(handle: Any) {
        val render = handle as? NotificationRenderHandle ?: return
        NotificationService.suspendOwnedNotification(render.token)
    }

    override fun cancel(handle: Any, reason: NotificationEndReason) {
        val render = handle as? NotificationRenderHandle ?: return
        NotificationService.cancelOwnedNotification(render.token, reason.name)
    }

    private fun listener(onDisplayed: () -> Unit, onEnded: (NotificationEndReason) -> Unit) = object : NotificationRenderListener {
        override fun onDisplayed() = onDisplayed.invoke()
        override fun onEnded(reason: String) {
            onEnded.invoke(runCatching { NotificationEndReason.valueOf(reason) }.getOrDefault(NotificationEndReason.HOST_ERROR))
        }
    }
}
