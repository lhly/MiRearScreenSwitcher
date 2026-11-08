package top.zainzh.rearscreen.platform.clock

import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

const val MAX_VISIBLE_SCHEDULE_KEYS = 4
const val MIN_VISIBLE_INTERVAL_MS = 1_000L

/** Timer is one-shot; provider owns at most one pending timer for every Mod combined. */
interface OneShotScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Closeable
}

/** Single shared Android-process timer. It creates no thread/task until first scheduled work. */
class ExecutorOneShotScheduler : OneShotScheduler, Closeable {
    private val lock = Any()
    private var executor: java.util.concurrent.ScheduledThreadPoolExecutor? = null

    override fun schedule(delayMs: Long, task: () -> Unit): Closeable {
        val future: ScheduledFuture<*> = synchronized(lock) {
            val pool = executor ?: java.util.concurrent.ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "remrss-visible-clock").apply { isDaemon = true }
            }.also {
                it.removeOnCancelPolicy = true
                it.setKeepAliveTime(1, TimeUnit.SECONDS)
                it.allowCoreThreadTimeOut(true)
                executor = it
            }
            pool.schedule(task, max(0L, delayMs), TimeUnit.MILLISECONDS)
        }
        return Closeable { future.cancel(false) }
    }

    override fun close() {
        synchronized(lock) { executor?.shutdownNow(); executor = null }
    }
}

enum class VisibleTickCause { INTERVAL, DEADLINE }

data class VisibleTick(
    val ownerId: String,
    val epoch: Long,
    val key: String,
    /** Changes on every same-key replacement; lets the caller reject an in-flight stale tick. */
    val registrationGeneration: Long,
    /** Changes on every visibility edge, invalidating work from an earlier visible interval. */
    val visibilityGeneration: Long,
    val observedElapsedRealtimeMs: Long,
    val causes: Set<VisibleTickCause>,
    /** The first due instant represented by this merged tick; always elapsedRealtime. */
    val scheduledElapsedRealtimeMs: Long = observedElapsedRealtimeMs,
    /** Number of elapsed periodic opportunities skipped; never replayed as separate ticks. */
    val missedCount: Int = 0,
)

/** A visible lease belongs to one stable Mod ID and one worker epoch. */
interface VisibleScheduleLease : Closeable {
    val ownerId: String
    val epoch: Long
    fun setVisible(visible: Boolean)
    /** Null disables that leg. Deadline is absolute elapsedRealtime; it fires at most once. */
    fun set(key: String, intervalMs: Long? = null, deadlineElapsedRealtimeMs: Long? = null)
    fun remove(key: String)
    /** Accept this exact currently-delivering tick once, before turning it into a Mod event. */
    fun tryAccept(tick: VisibleTick): Boolean
}

/**
 * Shared, in-process visible scheduler. Visibility is supplied by the actual host owner; this
 * class never infers it from business state. Callbacks run only through the supplied executor.
 */
class VisibleScheduleProvider(
    private val elapsedRealtime: () -> Long,
    private val scheduler: OneShotScheduler,
    private val callbackExecutor: Executor,
    private val onCallbackFailure: (ownerId: String, epoch: Long, error: Throwable) -> Unit,
) : Closeable {
    internal fun ownerCountForTesting(): Int = synchronized(lock) { owners.size }
    private data class Entry(
        val key: String,
        var intervalMs: Long?,
        var nextIntervalAt: Long?,
        var deadlineAt: Long?,
        val generation: Long,
        var deadlineConsumed: Boolean = false,
    )
    private data class Owner(
        val id: String,
        val epoch: Long,
        val onTick: (VisibleTick) -> Unit,
        val entries: LinkedHashMap<String, Entry> = LinkedHashMap(),
        /** One mutable queue slot per key; replacements update it rather than enqueueing more. */
        val pending: LinkedHashMap<String, PendingSlot> = LinkedHashMap(),
        var nextEntryGeneration: Long = 0,
        var visibilityGeneration: Long = 0,
        var visible: Boolean = false,
        var closed: Boolean = false,
    )
    private data class PendingSlot(
        var entry: Entry,
        var tick: VisibleTick?,
        var draining: Boolean = false,
        var delivering: VisibleTick? = null,
        var deliveryAccepted: Boolean = false,
    )
    private data class DispatchJob(val owner: Owner, val key: String, val slot: PendingSlot)
    private data class ArmedTimer(val id: Long, var handle: Closeable?)

    private val lock = Any()
    private val owners = LinkedHashMap<String, Owner>()
    private var armed: ArmedTimer? = null
    private var nextTimerId = 0L
    private var closed = false

    /** Opening a new epoch atomically invalidates the previous lease for this owner. */
    fun open(ownerId: String, epoch: Long, onTick: (VisibleTick) -> Unit): VisibleScheduleLease {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        require(epoch >= 0) { "epoch must be non-negative" }
        val owner = Owner(ownerId, epoch, onTick)
        synchronized(lock) {
            check(!closed) { "provider is closed" }
            owners.put(ownerId, owner)?.closed = true
            rearmLocked()
        }
        return Lease(owner)
    }

    private inner class Lease(private val owner: Owner) : VisibleScheduleLease {
        override val ownerId get() = owner.id
        override val epoch get() = owner.epoch

        override fun setVisible(visible: Boolean) = synchronized(lock) {
            if (!isCurrentLocked(owner)) return@synchronized
            if (owner.visible == visible) return@synchronized
            owner.visible = visible
            owner.visibilityGeneration++
            val now = elapsedRealtime()
            if (visible) {
                owner.entries.values.forEach { entry ->
                    if (entry.intervalMs != null) entry.nextIntervalAt = now + entry.intervalMs!!
                }
            } else {
                owner.entries.values.forEach { it.nextIntervalAt = null }
                // Drop every not-yet-dispatched tick from the prior visible generation. If a
                // deadline was consumed into a pending slot, make that one-shot due again so it
                // can be delivered once on the next visible edge; interval causes never revive.
                owner.pending.values.forEach { slot ->
                    val pendingTick = slot.tick
                    if (pendingTick != null && VisibleTickCause.DEADLINE in pendingTick.causes &&
                        slot.entry.deadlineConsumed) {
                        slot.entry.deadlineConsumed = false
                        slot.entry.deadlineAt = now
                    }
                    val delivering = slot.delivering
                    if (delivering != null && !slot.deliveryAccepted &&
                        VisibleTickCause.DEADLINE in delivering.causes &&
                        owner.entries[delivering.key] === slot.entry &&
                        slot.entry.generation == delivering.registrationGeneration && slot.entry.deadlineConsumed) {
                        slot.entry.deadlineConsumed = false
                        slot.entry.deadlineAt = now
                    }
                    slot.tick = null
                }
            }
            rearmLocked()
        }

        override fun set(key: String, intervalMs: Long?, deadlineElapsedRealtimeMs: Long?) {
            require(key.isNotBlank()) { "key must not be blank" }
            require(intervalMs == null || intervalMs >= MIN_VISIBLE_INTERVAL_MS) {
                "intervalMs must be at least $MIN_VISIBLE_INTERVAL_MS"
            }
            require(intervalMs != null || deadlineElapsedRealtimeMs != null) {
                "at least one schedule leg is required; use remove to cancel"
            }
            synchronized(lock) {
                if (!isCurrentLocked(owner)) return
                val existing = owner.entries[key]
                if (existing == null && owner.entries.size >= MAX_VISIBLE_SCHEDULE_KEYS)
                    throw IllegalStateException("at most $MAX_VISIBLE_SCHEDULE_KEYS visible schedule keys per owner")
                val now = elapsedRealtime()
                // Replacing the same key replaces its interval/deadline; it never accumulates.
                val replacement = Entry(
                    key = key,
                    intervalMs = intervalMs,
                    nextIntervalAt = if (owner.visible && intervalMs != null) now + intervalMs else null,
                    deadlineAt = deadlineElapsedRealtimeMs,
                    generation = ++owner.nextEntryGeneration,
                )
                owner.entries[key] = replacement
                owner.pending[key]?.let { slot -> slot.entry = replacement; slot.tick = null }
                rearmLocked()
            }
        }

        override fun remove(key: String) = synchronized(lock) {
            if (isCurrentLocked(owner)) { owner.entries.remove(key); rearmLocked() }
        }

        override fun tryAccept(tick: VisibleTick): Boolean = synchronized(lock) {
            if (tick.ownerId != owner.id || tick.epoch != owner.epoch || !isCurrentLocked(owner) || !owner.visible ||
                owner.visibilityGeneration != tick.visibilityGeneration) return@synchronized false
            val entry = owner.entries[tick.key] ?: return@synchronized false
            if (entry.generation != tick.registrationGeneration) return@synchronized false
            val slot = owner.pending[tick.key] ?: return@synchronized false
            if (slot.entry !== entry || slot.delivering !== tick || slot.deliveryAccepted) return@synchronized false
            slot.deliveryAccepted = true
            true
        }

        override fun close() = synchronized(lock) {
            if (!owner.closed) {
                owner.closed = true
                if (owners[owner.id] === owner) owners.remove(owner.id)
                rearmLocked()
            }
        }
    }

    private fun isCurrentLocked(owner: Owner) = !closed && !owner.closed && owners[owner.id] === owner

    internal fun registeredKeyCount(ownerId: String): Int = synchronized(lock) { owners[ownerId]?.entries?.size ?: 0 }

    private fun rearmLocked() {
        armed?.handle?.close()
        armed = null
        if (closed) return
        var earliest: Long? = null
        owners.values.filter { it.visible && !it.closed }.forEach { owner ->
            owner.entries.values.forEach { entry ->
                val interval = entry.nextIntervalAt
                if (interval != null && (earliest == null || interval < earliest!!)) earliest = interval
                val deadline = entry.deadlineAt.takeUnless { entry.deadlineConsumed }
                if (deadline != null && (earliest == null || deadline < earliest!!)) earliest = deadline
            }
        }
        if (earliest != null) {
            val dueAt = earliest!!
            val arm = ArmedTimer(++nextTimerId, null)
            armed = arm
            val handle = scheduler.schedule(max(0L, dueAt - elapsedRealtime())) { fire(arm.id) }
            if (armed === arm) arm.handle = handle else handle.close()
        }
    }

    private fun fire(timerId: Long) {
        val jobs = ArrayList<DispatchJob>()
        synchronized(lock) {
            if (armed?.id != timerId) return
            armed = null
            if (closed) return
            val now = elapsedRealtime()
            owners.values.filter { it.visible && !it.closed }.forEach { owner ->
                owner.entries.values.forEach { entry ->
                    val causes = linkedSetOf<VisibleTickCause>()
                    var scheduledAt: Long? = null
                    var missedCount = 0
                    if (entry.nextIntervalAt?.let { it <= now } == true) {
                        causes += VisibleTickCause.INTERVAL
                        val dueAt = entry.nextIntervalAt!!
                        scheduledAt = dueAt
                        missedCount = ((now - dueAt) / entry.intervalMs!!).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        // Do not replay missed intervals; next cadence begins from current time.
                        entry.nextIntervalAt = now + entry.intervalMs!!
                    }
                    if (!entry.deadlineConsumed && entry.deadlineAt?.let { it <= now } == true) {
                        causes += VisibleTickCause.DEADLINE
                        val deadlineAt = entry.deadlineAt!!
                        scheduledAt = minOf(scheduledAt ?: deadlineAt, deadlineAt)
                        // Consume before leaving the lock/calling consumer: reentry cannot loop.
                        entry.deadlineConsumed = true
                        entry.deadlineAt = null
                    }
                    if (causes.isNotEmpty()) queueTickLocked(owner, entry, VisibleTick(
                        owner.id, owner.epoch, entry.key, entry.generation,
                        owner.visibilityGeneration, now, causes, scheduledAt ?: now, missedCount
                    ))?.let(jobs::add)
                }
            }
            rearmLocked()
        }
        // Every due key is visible in its owner.pending slot before an external callback can
        // re-enter visibility/close/update paths during dispatch of the first key.
        jobs.forEach(::submit)
    }

    /** Must be called under [lock], so an entire due batch is claimed before callbacks begin. */
    private fun queueTickLocked(owner: Owner, entry: Entry, tick: VisibleTick): DispatchJob? {
        val slot = owner.pending[tick.key]
        if (slot == null) {
            val created = PendingSlot(entry, tick, draining = true)
            owner.pending[tick.key] = created
            return DispatchJob(owner, tick.key, created)
        }
        if (slot.entry !== entry) {
            slot.entry = entry
            slot.tick = tick
        } else {
            val existing = slot.tick
            slot.tick = if (existing == null) tick else existing.copy(
                registrationGeneration = tick.registrationGeneration,
                visibilityGeneration = tick.visibilityGeneration,
                observedElapsedRealtimeMs = tick.observedElapsedRealtimeMs,
                causes = existing.causes + tick.causes,
                scheduledElapsedRealtimeMs = minOf(existing.scheduledElapsedRealtimeMs, tick.scheduledElapsedRealtimeMs),
                missedCount = (existing.missedCount.toLong() + tick.missedCount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        if (slot.draining) return null
        slot.draining = true
        return DispatchJob(owner, tick.key, slot)
    }

    private fun submit(job: DispatchJob) {
        try { callbackExecutor.execute { drain(job.owner, job.key, job.slot) } }
        catch (error: RejectedExecutionException) {
            synchronized(lock) {
                if (job.owner.pending[job.key] === job.slot) job.owner.pending.remove(job.key)
            }
            onCallbackFailure(job.owner.id, job.owner.epoch, error)
        }
    }

    private fun drain(owner: Owner, key: String, claimedSlot: PendingSlot) {
        while (true) {
            val next = synchronized(lock) {
                val slot = owner.pending[key]
                val tick = slot?.tick
                if (slot !== claimedSlot) {
                    null
                } else if (tick == null) {
                    slot.draining = false
                    owner.pending.remove(key)
                    null
                } else if (!isCurrentLocked(owner) || !owner.visible || owner.entries[key] !== slot.entry ||
                    slot.entry.generation != tick.registrationGeneration ||
                    owner.visibilityGeneration != tick.visibilityGeneration) {
                    if (VisibleTickCause.DEADLINE in tick.causes && isCurrentLocked(owner) &&
                        owner.entries[key] === slot.entry && !owner.visible) {
                        slot.entry.deadlineConsumed = false
                        slot.entry.deadlineAt = elapsedRealtime()
                        rearmLocked()
                    }
                    owner.pending.remove(key)
                    null
                } else {
                    slot.tick = null
                    slot.delivering = tick
                    slot.deliveryAccepted = false
                    slot.entry to tick
                }
            } ?: return

            try { owner.onTick(next.second) }
            catch (error: Throwable) { onCallbackFailure(owner.id, owner.epoch, error) }

            val continueDrain = synchronized(lock) {
                val slot = owner.pending[key]
                if (slot !== claimedSlot) {
                    false
                } else if (slot.tick == null) {
                    slot.delivering = null
                    slot.draining = false
                    if (slot.entry === next.first && next.first.intervalMs == null && next.first.deadlineConsumed)
                        owner.entries.remove(key)
                    owner.pending.remove(key)
                    rearmLocked()
                    false
                } else true
            }
            if (!continueDrain) return
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            owners.values.forEach { it.closed = true }
            owners.clear()
            armed?.handle?.close()
            armed = null
        }
    }
}
