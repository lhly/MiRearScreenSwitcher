package top.zainzh.rearscreen.platform.notifications

import android.os.SystemClock
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One bounded, authoritative state machine for SDK and Mod-owned notification presentations. */
internal class OwnedNotificationQueue(
    private val presentation: OwnedNotificationPresentation,
    private val policy: (NotificationOwner, NotificationRequest) -> NotificationPolicyResult,
    private val ownerIsCurrent: (NotificationOwner) -> Boolean = { it.isCurrent() },
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val maxPerOwner: Int = MAX_PER_OWNER,
    private val maxGlobal: Int = MAX_GLOBAL,
) : OwnedNotificationRouter, Closeable {
    private data class Key(val namespace: String, val generation: String, val id: String)
    private class Entry(val item: OwnedNotificationItem) {
        var handle: Any? = null
        var renderedRequest: NotificationRequest? = null
        var displayed = false
        var ended = false
        var suspended = false
        var deferred = false
        var rendering = false
        var expiry: ScheduledFuture<*>? = null
    }
    private class RateWindow {
        val calls = ArrayDeque<Long>()
        var lastCallElapsedMs = 0L
    }

    private val lock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "owned-notification-expiry").apply { isDaemon = true }
    }
    /** All renderer mutations share one FIFO, but never run while holding the queue lock. */
    private val presentationExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "owned-notification-renderer").apply { isDaemon = true }
    }
    /** Publisher callbacks are isolated from slow renderer work and also run outside the lock. */
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "owned-notification-callbacks").apply { isDaemon = true }
    }
    private val entries = LinkedHashMap<Key, Entry>()
    /** Stable namespace key: callback generations/reconnects do not reset the identity's limiter. */
    private val callTimes = LinkedHashMap<String, RateWindow>()
    private var currentKey: Key? = null
    private var closed = false

    override fun publish(
        owner: NotificationOwner,
        request: NotificationRequest,
        observer: NotificationObserver?,
    ): NotificationSubmitResult {
        val validation = validateRequest(request)
        if (validation != null) return NotificationSubmitResult(NotificationSubmitStatus.REJECTED, request.id, reason = validation)
        if (!ownerIsCurrent(owner)) return NotificationSubmitResult(NotificationSubmitStatus.REJECTED, request.id, reason = "STALE_OWNER")
        when (val decision = policy(owner, request)) {
            is NotificationPolicyResult.Allowed -> Unit
            is NotificationPolicyResult.Suppressed -> return NotificationSubmitResult(
                NotificationSubmitStatus.SUPPRESSED, request.id, reason = decision.reason
            )
            is NotificationPolicyResult.Rejected -> return NotificationSubmitResult(
                NotificationSubmitStatus.REJECTED, request.id, reason = decision.reason
            )
        }

        val at = now()
        val key = Key(owner.namespace, owner.generation, request.id)
        val requestId = UUID.randomUUID().toString()
        val item = OwnedNotificationItem(owner, request, requestId, at, at + request.durationMs, observer)
        val entry = Entry(item)
        var suspendedHandle: Any? = null
        var selectedToPresent: Pair<Key, Entry>? = null
        synchronized(lock) {
            if (closed) return NotificationSubmitResult(NotificationSubmitStatus.REJECTED, request.id, reason = "HOST_UNAVAILABLE")
            if (!allowRate(owner, at)) return NotificationSubmitResult(NotificationSubmitStatus.REJECTED, request.id, reason = "RATE_LIMITED")
            val replaced = entries[key]
            val ownerCount = entries.keys.count { it.namespace == owner.namespace && it.generation == owner.generation }
            if (replaced == null && ownerCount >= maxPerOwner) return NotificationSubmitResult(
                NotificationSubmitStatus.REJECTED, request.id, reason = "QUOTA_EXCEEDED"
            )
            if (replaced == null && entries.size >= maxGlobal) return NotificationSubmitResult(
                NotificationSubmitStatus.REJECTED, request.id, reason = "QUOTA_EXCEEDED"
            )
            if (replaced != null) finishLocked(key, replaced, NotificationEndReason.REPLACED)
            entries[key] = entry
            entry.expiry = scheduler.schedule({ expire(key, entry) }, request.durationMs, TimeUnit.MILLISECONDS)

            val activeKey = currentKey
            val active = activeKey?.let(entries::get)
            if (active != null && active !== entry) {
                if (active.item.request.priority.ordinal >= request.priority.ordinal) {
                    entry.suspended = true
                    return@synchronized
                }
                active.suspended = true
                active.deferred = false
                suspendedHandle = active.handle
                currentKey = key
                entry.suspended = false
                selectedToPresent = key to entry
            } else {
                val best = entries.entries.sortedWith(
                    compareByDescending<Map.Entry<Key, Entry>> { it.value.item.request.priority.ordinal }
                        .thenBy { it.value.item.submittedAtElapsedMs }
                ).first()
                entries.forEach { (candidateKey, candidate) -> candidate.suspended = candidateKey != best.key }
                currentKey = best.key
                selectedToPresent = best.key to best.value
            }
        }
        suspendedHandle?.let { handle -> enqueuePresentation { presentation.suspend(handle) } }
        selectedToPresent?.let { selected -> enqueuePresentation { present(selected.first, selected.second) } }
        return NotificationSubmitResult(NotificationSubmitStatus.ACCEPTED, request.id, requestId)
    }

    override fun cancel(owner: NotificationOwner, id: String, reason: NotificationEndReason): NotificationSubmitResult {
        if (!id.matches(ID_PATTERN)) return NotificationSubmitResult(NotificationSubmitStatus.REJECTED, id, reason = "INVALID_ARGUMENT")
        if (!ownerIsCurrent(owner)) return NotificationSubmitResult(NotificationSubmitStatus.REJECTED, id, reason = "STALE_OWNER")
        val key = Key(owner.namespace, owner.generation, id)
        synchronized(lock) {
            if (!allowRate(owner, now())) return NotificationSubmitResult(NotificationSubmitStatus.REJECTED, id, reason = "RATE_LIMITED")
            val entry = entries[key] ?: return NotificationSubmitResult(NotificationSubmitStatus.REJECTED, id, reason = "NOT_FOUND")
            finishLocked(key, entry, reason)
        }
        return NotificationSubmitResult(NotificationSubmitStatus.ACCEPTED, id, reason = reason.name.lowercase())
    }

    override fun closeOwner(owner: NotificationOwner, reason: NotificationEndReason) {
        val owned = synchronized(lock) { entries.filterKeys { it.namespace == owner.namespace && it.generation == owner.generation }.toList() }
        synchronized(lock) { owned.forEach { (key, entry) -> finishLocked(key, entry, reason) } }
    }

    fun closeOwnerSessionsForSdk(packageName: String, digest: String, reason: NotificationEndReason) {
        val suffix = ":$packageName:$digest"
        val owned = synchronized(lock) {
            entries.filterKeys { it.namespace.startsWith("sdk:") && it.namespace.endsWith(suffix) }.toList()
        }
        synchronized(lock) { owned.forEach { (key, entry) -> finishLocked(key, entry, reason) } }
    }

    override fun closeMod(modId: String, reason: NotificationEndReason) {
        val owned = synchronized(lock) { entries.filterKeys { it.namespace == "mod:$modId" }.toList() }
        synchronized(lock) { owned.forEach { (key, entry) -> finishLocked(key, entry, reason) } }
    }

    /** User-confirmed display ownership transfer ends exactly the old queue generation. */
    fun closeAll(reason: NotificationEndReason) {
        synchronized(lock) {
            // Serialize the transfer point with publish: every item accepted before this
            // critical section ends, while later publications belong to the new owner.
            entries.toList().forEach { (key, entry) -> finishLocked(key, entry, reason) }
        }
    }

    /** Recheck all accepted items; privacy changes terminate stale cleartext renderers immediately. */
    override fun policyChanged() {
        val snapshot = synchronized(lock) { entries.toList() }
        snapshot.forEach { (key, entry) ->
            when (val decision = policy(entry.item.owner, entry.item.request)) {
                is NotificationPolicyResult.Allowed -> {
                    val rendered = synchronized(lock) { if (entries[key] === entry) entry.renderedRequest else null }
                    if (rendered != null && rendered != decision.effectiveRequest) synchronized(lock) {
                        if (entries[key] === entry)
                            finishLocked(key, entry, NotificationEndReason.PRIVACY_POLICY, "privacy_policy_changed")
                    }
                }
                is NotificationPolicyResult.Suppressed -> synchronized(lock) {
                    if (entries[key] === entry) finishLocked(key, entry, decision.endedBy, decision.reason)
                }
                is NotificationPolicyResult.Rejected -> synchronized(lock) {
                    if (entries[key] === entry) finishLocked(key, entry, NotificationEndReason.AUTH_REVOKED, decision.reason)
                }
            }
        }
    }

    private fun reportDisplayed(key: Key, entry: Entry) {
        val decision = policy(entry.item.owner, entry.item.request)
        if (decision !is NotificationPolicyResult.Allowed) {
            val reason = (decision as? NotificationPolicyResult.Suppressed)?.endedBy ?: NotificationEndReason.AUTH_REVOKED
            val detail = when (decision) {
                is NotificationPolicyResult.Suppressed -> decision.reason
                is NotificationPolicyResult.Rejected -> decision.reason
                else -> "policy_changed"
            }
            synchronized(lock) { if (entries[key] === entry) finishLocked(key, entry, reason, detail) }
            return
        }
        val privacyChanged = synchronized(lock) {
            entries[key] === entry && entry.renderedRequest != null && entry.renderedRequest != decision.effectiveRequest
        }
        if (privacyChanged) {
            synchronized(lock) {
                if (entries[key] === entry) finishLocked(key, entry, NotificationEndReason.PRIVACY_POLICY, "privacy_policy_changed")
            }
            return
        }
        val observer = synchronized(lock) {
            if (closed || entries[key] !== entry || entry.ended || currentKey != key ||
                !ownerIsCurrent(entry.item.owner) || now() >= entry.item.expiresAtElapsedMs) {
                if (entries[key] === entry && now() >= entry.item.expiresAtElapsedMs)
                    finishLocked(key, entry, NotificationEndReason.TIMEOUT)
                return
            }
            if (entry.displayed) return
            entry.displayed = true
            entry.item.observer
        }
        observer?.onEvent(NotificationEvent(entry.item.requestId, entry.item.request.id, NotificationEventStatus.DISPLAYED))
    }

    private fun reportEnded(key: Key, entry: Entry, reason: NotificationEndReason) {
        synchronized(lock) {
            if (entries[key] !== entry || entry.ended) return
            if (reason == NotificationEndReason.COVERED && now() < entry.item.expiresAtElapsedMs) {
                entry.suspended = true
                entry.deferred = false
                if (currentKey == key) currentKey = null
                return
            }
            finishLocked(key, entry, if (reason == NotificationEndReason.COVERED) NotificationEndReason.TIMEOUT else reason)
        }
    }

    override fun presentationAvailable() {
        val selected = synchronized(lock) {
            if (closed) return
            val current = currentKey?.let(entries::get)
            if (current != null) {
                val key = currentKey ?: return
                if (current.deferred && !current.rendering) key to current else null
            } else {
                entries.entries
                    .filter { (_, entry) -> entry.suspended && !entry.ended && now() < entry.item.expiresAtElapsedMs }
                    .sortedWith(compareByDescending<Map.Entry<Key, Entry>> { it.value.item.request.priority.ordinal }
                        .thenBy { it.value.item.submittedAtElapsedMs })
                    .firstOrNull()?.let { (key, entry) -> entry.suspended = false; currentKey = key; key to entry }
            }
        } ?: return
        enqueuePresentation { present(selected.first, selected.second) }
    }

    private fun enqueuePresentation(action: () -> Unit) {
        runCatching { presentationExecutor.execute(action) }
    }

    private fun present(key: Key, entry: Entry) {
        val resumeHandle = synchronized(lock) {
            if (closed || entries[key] !== entry || entry.ended || currentKey != key || entry.rendering) return
            if (now() >= entry.item.expiresAtElapsedMs) {
                finishLocked(key, entry, NotificationEndReason.TIMEOUT)
                return
            }
            entry.rendering = true
            entry.suspended = false
            entry.handle
        }

        if (!ownerIsCurrent(entry.item.owner)) {
            synchronized(lock) {
                if (entries[key] === entry) finishLocked(key, entry, NotificationEndReason.OWNER_DIED)
            }
            return
        }
        val effective = when (val decision = policy(entry.item.owner, entry.item.request)) {
            is NotificationPolicyResult.Allowed -> decision.effectiveRequest
            is NotificationPolicyResult.Suppressed -> {
                synchronized(lock) {
                    if (entries[key] === entry) finishLocked(key, entry, decision.endedBy, decision.reason)
                }
                return
            }
            is NotificationPolicyResult.Rejected -> {
                synchronized(lock) {
                    if (entries[key] === entry) finishLocked(key, entry, NotificationEndReason.AUTH_REVOKED, decision.reason)
                }
                return
            }
        }
        val renderItem = entry.item.copy(request = effective)
        synchronized(lock) {
            if (entries[key] !== entry || currentKey != key || entry.ended) return
            entry.renderedRequest = effective
        }
        val started = if (resumeHandle == null) {
            presentation.present(renderItem, { reportDisplayed(key, entry) }, { reportEnded(key, entry, it) })
        } else {
            presentation.resume(resumeHandle, renderItem, { reportDisplayed(key, entry) }, { reportEnded(key, entry, it) })
        }
        var cancelHandle: Any? = null
        synchronized(lock) {
            if (entries[key] !== entry || currentKey != key || entry.ended || closed) {
                if (started is PresentationResult.Started) cancelHandle = started.handle
            } else {
                entry.rendering = false
                when (started) {
                    is PresentationResult.Started -> {
                        entry.handle = started.handle
                        entry.deferred = false
                        entry.suspended = false
                    }
                    PresentationResult.Deferred -> {
                        entry.deferred = true
                        entry.suspended = false
                    }
                    is PresentationResult.Suppressed -> finishLocked(key, entry, started.endedBy, started.reason)
                    is PresentationResult.Failed -> finishLocked(key, entry, NotificationEndReason.RENDER_FAILED, started.reason)
                }
            }
        }
        cancelHandle?.let { presentation.cancel(it, NotificationEndReason.REPLACED) }
    }

    private fun expire(key: Key, entry: Entry) {
        synchronized(lock) { if (entries[key] === entry) finishLocked(key, entry, NotificationEndReason.TIMEOUT) }
    }

    private fun finishLocked(key: Key, entry: Entry, reason: NotificationEndReason, detail: String? = null) {
        if (entries[key] !== entry || entry.ended) return
        entry.ended = true
        entries.remove(key)
        if (currentKey == key) currentKey = null
        entry.expiry?.cancel(false)
        val handle = entry.handle
        entry.handle = null
        val eventStatus = when {
            reason in setOf(NotificationEndReason.RENDER_FAILED, NotificationEndReason.HOST_ERROR) -> NotificationEventStatus.FAILED
            !entry.displayed && reason in setOf(NotificationEndReason.AUTH_REVOKED, NotificationEndReason.GLOBAL_DISABLED,
                NotificationEndReason.DND, NotificationEndReason.PRIVACY_POLICY, NotificationEndReason.DISPLAY_UNAVAILABLE) -> NotificationEventStatus.SUPPRESSED
            else -> NotificationEventStatus.ENDED
        }
        val event = NotificationEvent(
            entry.item.requestId, entry.item.request.id, eventStatus,
            reason = detail ?: reason.name.lowercase(), endedBy = reason,
        )
        val observer = entry.item.observer
        if (handle != null) enqueuePresentation { runCatching { presentation.cancel(handle, reason) } }
        runCatching { callbackExecutor.execute { runCatching { observer?.onEvent(event) } } }
        pruneRateWindowsLocked(now())
        if (!closed) runCatching { scheduler.execute { presentationAvailable() } }
    }

    private fun allowRate(owner: NotificationOwner, at: Long): Boolean {
        pruneRateWindowsLocked(at)
        val key = owner.namespace
        var window = callTimes[key]
        if (window == null) {
            if (callTimes.size >= MAX_RATE_IDENTITIES) return false
            window = RateWindow()
            callTimes[key] = window
        }
        while (window.calls.isNotEmpty() && at - window.calls.first() >= RATE_WINDOW_MS) window.calls.removeFirst()
        if (window.calls.count { at - it < BURST_WINDOW_MS } >= BURST_LIMIT || window.calls.size >= RATE_LIMIT) return false
        window.calls.addLast(at)
        window.lastCallElapsedMs = at
        return true
    }

    private fun pruneRateWindowsLocked(at: Long) {
        val activeNamespaces = entries.keys.mapTo(HashSet()) { it.namespace }
        val iterator = callTimes.entries.iterator()
        while (iterator.hasNext()) {
            val (identity, window) = iterator.next()
            while (window.calls.isNotEmpty() && at - window.calls.first() >= RATE_WINDOW_MS) window.calls.removeFirst()
            if (window.calls.isEmpty() && at - window.lastCallElapsedMs >= RATE_WINDOW_MS && identity !in activeNamespaces) iterator.remove()
        }
    }

    private fun validateRequest(request: NotificationRequest): String? {
        if (!request.id.matches(ID_PATTERN)) return "INVALID_ARGUMENT"
        if (request.title.codePointCount(0, request.title.length) !in 1..80 ||
            request.text.codePointCount(0, request.text.length) > 240 ||
            request.durationMs !in MIN_TTL_MS..MAX_TTL_MS ||
            request.title.any(::isForbiddenControl) || request.text.any(::isForbiddenControl)) return "INVALID_ARGUMENT"
        val bytes = request.title.toByteArray(Charsets.UTF_8).size + request.text.toByteArray(Charsets.UTF_8).size + request.id.length
        return if (bytes > MAX_PAYLOAD_BYTES) "INVALID_ARGUMENT" else null
    }

    private fun isForbiddenControl(char: Char): Boolean = char == '\u0000' ||
        (char.code < 0x20 && char != '\n' && char != '\r' && char != '\t') || char.code in 0x7f..0x9f

    override fun close() {
        val snapshot = synchronized(lock) { closed = true; entries.toList() }
        synchronized(lock) { snapshot.forEach { (key, entry) -> finishLocked(key, entry, NotificationEndReason.SERVICE_STOPPED) } }
        scheduler.shutdownNow()
        presentationExecutor.shutdown()
        callbackExecutor.shutdown()
    }

    companion object {
        const val MIN_TTL_MS = 1_000L
        const val MAX_TTL_MS = 15_000L
        const val MAX_PER_OWNER = 4
        const val MAX_GLOBAL = 16
        const val MAX_PAYLOAD_BYTES = 2_048
        const val RATE_LIMIT = 10
        const val RATE_WINDOW_MS = 10_000L
        const val BURST_LIMIT = 4
        const val BURST_WINDOW_MS = 1_000L
        const val MAX_RATE_IDENTITIES = 256
        val ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,64}")
    }
}
