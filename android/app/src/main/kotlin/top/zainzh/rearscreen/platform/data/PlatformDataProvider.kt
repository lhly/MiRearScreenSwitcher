package top.zainzh.rearscreen.platform.data

import android.content.Context
import android.os.SystemClock
import top.zainzh.rearscreen.platform.SessionOwner
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Read-only, permission-filtered facts shared by platform clients. */
interface PlatformDataProvider {
    fun snapshot(owner: SessionOwner, requestedFields: Set<DataField>): DataSnapshot

    /** Callbacks are serialized on a worker thread, never the UI or Binder thread. */
    fun observe(
        owner: SessionOwner,
        fields: Set<DataField>,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        callback: (DataSnapshot) -> Unit
    ): Closeable

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val MIN_INTERVAL_MS = 2_000L
        /** Caps callback and observer state; excess registrations fail synchronously. */
        const val MAX_OBSERVERS = 64
    }
}

enum class DataField {
    BATTERY_LEVEL_PERCENT,
    BATTERY_CHARGING,
    SYSTEM_CPU_UTILIZATION_PERCENT,
    SYSTEM_MEMORY_AVAILABLE_BYTES,
    SYSTEM_MEMORY_TOTAL_BYTES,
    SYSTEM_GPU_UTILIZATION_PERCENT
}

data class DataSnapshot(val fields: Map<DataField, DataValue>, val sampledAtElapsedRealtimeMs: Long) {
    /** Consumers receive only the fields they explicitly requested and the core authorized. */
    fun filteredTo(requested: Set<DataField>) = copy(fields = fields.filterKeys { it in requested })
}

sealed interface DataValue {
    val sampledAtElapsedRealtimeMs: Long

    data class Number(
        val value: Double,
        val unit: String,
        override val sampledAtElapsedRealtimeMs: Long
    ) : DataValue

    data class Flag(val value: Boolean, override val sampledAtElapsedRealtimeMs: Long) : DataValue

    data class Unavailable(
        val reason: Reason,
        override val sampledAtElapsedRealtimeMs: Long
    ) : DataValue

    enum class Reason { SOURCE_UNAVAILABLE, SAMPLE_PENDING, INVALID_SAMPLE, UNSUPPORTED }
}

/** Injectable boundary keeps timing and data-source behavior deterministic in tests. */
internal interface PlatformDataSource {
    fun readBattery(): BatterySample?
    fun readCpuCounters(): CpuCounterSample?
    fun readMemory(): MemorySample?
    fun startBatteryEvents(onChanged: () -> Unit): Closeable
    fun startSystemMetrics()
    fun stopSystemMetrics()
}

internal data class BatterySample(
    val level: Int,
    val scale: Int,
    val charging: Boolean?,
    val sampledAtElapsedRealtimeMs: Long
)

internal data class MemorySample(val availableBytes: Long, val totalBytes: Long, val sampledAtElapsedRealtimeMs: Long)
internal data class CpuCounterSample(val rawLine: String, val sampledAtElapsedRealtimeMs: Long)

internal fun interface PlatformDataClock { fun nowMs(): Long }
internal fun interface PlatformDataSchedule {
    fun every(intervalMs: Long, task: () -> Unit): Closeable
}

/** One instance belongs to the process core; all owners share its source and sampling loop. */
internal class SharedPlatformDataProvider(
    private val source: PlatformDataSource,
    private val clock: PlatformDataClock = PlatformDataClock { SystemClock.elapsedRealtime() },
    private val schedule: PlatformDataSchedule,
    private val callbackExecutor: ThreadPoolExecutor = CallbackDispatch.executor()
) : PlatformDataProvider, Closeable {
    private data class Subscription(
        val owner: SessionOwner,
        val fields: Set<DataField>,
        val intervalMs: Long,
        val callback: (DataSnapshot) -> Unit,
        val latest: AtomicReference<DataSnapshot?> = AtomicReference(null),
        val dispatching: AtomicBoolean = AtomicBoolean(false),
        val pendingTask: AtomicReference<Runnable?> = AtomicReference(null),
        val closed: AtomicBoolean = AtomicBoolean(false)
    )

    private val lock = Any()
    private val subscriptions = LinkedHashSet<Subscription>()
    private val cpuBaseline = HashMap<SessionOwner, CpuReading>()
    private val cpuLastValues = HashMap<SessionOwner, DataValue>()
    private var loop: Closeable? = null
    private var activeIntervalMs: Long? = null
    private var batteryEvents: Closeable? = null
    private var metricsStarted = false
    private var closed = false

    override fun snapshot(owner: SessionOwner, requestedFields: Set<DataField>): DataSnapshot =
        read(owner, requestedFields, allowCpuRead = false).filteredTo(requestedFields)

    override fun observe(
        owner: SessionOwner,
        fields: Set<DataField>,
        intervalMs: Long,
        callback: (DataSnapshot) -> Unit
    ): Closeable {
        require(fields.isNotEmpty()) { "At least one data field is required" }
        val subscription = Subscription(owner, fields.toSet(), intervalMs.coerceAtLeast(PlatformDataProvider.MIN_INTERVAL_MS), callback)
        synchronized(lock) {
            check(!closed) { "Provider is closed" }
            if (subscriptions.size >= PlatformDataProvider.MAX_OBSERVERS) {
                throw java.util.concurrent.RejectedExecutionException("Maximum ${PlatformDataProvider.MAX_OBSERVERS} data observers reached")
            }
            subscriptions += subscription
            reconcileLocked()
        }
        // Initial data is delivered asynchronously; no registration call invokes user code inline.
        if (!dispatch(subscription, read(owner, fields, allowCpuRead = false))) {
            remove(subscription)
            throw java.util.concurrent.RejectedExecutionException("Data callback capacity is unavailable")
        }
        return Closeable { remove(subscription) }
    }

    private fun remove(subscription: Subscription) {
        if (!subscription.closed.compareAndSet(false, true)) return
        synchronized(lock) {
            subscriptions.remove(subscription)
            if (subscriptions.none { it.owner == subscription.owner }) {
                cpuBaseline.remove(subscription.owner)
                cpuLastValues.remove(subscription.owner)
            }
            if (subscriptions.none { DataField.BATTERY_LEVEL_PERCENT in it.fields || DataField.BATTERY_CHARGING in it.fields }) {
                batteryEvents?.close(); batteryEvents = null
            }
            if (subscriptions.none { it.fields.any(::needsPolling) }) {
                loop?.close(); loop = null
                activeIntervalMs = null
                cpuBaseline.clear()
                cpuLastValues.clear()
            } else reconcileLocked()
            if (subscriptions.none { DataField.SYSTEM_CPU_UTILIZATION_PERCENT in it.fields }) {
                if (metricsStarted) source.stopSystemMetrics()
                metricsStarted = false
            }
        }
        subscription.pendingTask.getAndSet(null)?.let(callbackExecutor::remove)
    }

    private fun reconcileLocked() {
        if (closed) return
        val wantsBattery = subscriptions.any { it.fields.any(::isBattery) }
        if (wantsBattery && batteryEvents == null) {
            batteryEvents = source.startBatteryEvents { refreshBatteryObservers() }
        }
        val wantsMetrics = subscriptions.any { it.fields.any(::needsPolling) }
        if (wantsMetrics) {
            if (!metricsStarted && subscriptions.any { DataField.SYSTEM_CPU_UTILIZATION_PERCENT in it.fields }) {
                source.startSystemMetrics()
                metricsStarted = true
            }
            val interval = subscriptions.filter { it.fields.any(::needsPolling) }
                .minOfOrNull { it.intervalMs }
                ?: PlatformDataProvider.DEFAULT_INTERVAL_MS
            val boundedInterval = interval.coerceAtLeast(PlatformDataProvider.MIN_INTERVAL_MS)
            if (activeIntervalMs != boundedInterval) {
                loop?.close()
                loop = schedule.every(boundedInterval) { sampleObservers() }
                activeIntervalMs = boundedInterval
            }
        }
    }

    private fun sampleObservers() {
        val current = synchronized(lock) { subscriptions.filter { it.fields.any(::needsPolling) } }
        if (current.isEmpty()) return
        val union = current.flatMapTo(LinkedHashSet()) { it.fields }
        val shared = readSources(union, allowCpuRead = true)
        val now = clock.nowMs()
        val cpuValues = current.asSequence()
            .filter { DataField.SYSTEM_CPU_UTILIZATION_PERCENT in it.fields }
            .map { it.owner }
            .distinct()
            .associateWith { owner ->
                cpuValue(owner, shared.cpu?.let { parseCpu(it.rawLine, it.sampledAtElapsedRealtimeMs) }, now)
            }
        for (subscription in current) {
            if (!subscription.closed.get()) dispatch(subscription, read(
                subscription.owner, subscription.fields, allowCpuRead = true, sharedSources = shared, precomputedCpuValues = cpuValues
            ))
        }
    }

    private fun refreshBatteryObservers() {
        val current = synchronized(lock) { subscriptions.filter { it.fields.any(::isBattery) } }
        if (current.isEmpty()) return
        val union = current.flatMapTo(LinkedHashSet()) { it.fields }
        val shared = readSources(union, allowCpuRead = false)
        current.forEach { if (!it.closed.get()) dispatch(it, read(it.owner, it.fields, allowCpuRead = false, sharedSources = shared)) }
    }

    private data class SharedSources(val battery: BatterySample?, val memory: MemorySample?, val cpu: CpuCounterSample?)

    private fun readSources(requested: Set<DataField>, allowCpuRead: Boolean) = SharedSources(
        battery = if (requested.any(::isBattery)) runCatching { source.readBattery() }.getOrNull() else null,
        memory = if (requested.any(::isMemory)) runCatching { source.readMemory() }.getOrNull() else null,
        cpu = if (allowCpuRead && DataField.SYSTEM_CPU_UTILIZATION_PERCENT in requested)
            runCatching { source.readCpuCounters() }.getOrNull() else null
    )

    private fun read(
        owner: SessionOwner,
        requested: Set<DataField>,
        allowCpuRead: Boolean,
        sharedSources: SharedSources? = null,
        precomputedCpuValues: Map<SessionOwner, DataValue>? = null
    ): DataSnapshot {
        val now = clock.nowMs()
        val values = LinkedHashMap<DataField, DataValue>()
        if (requested.any(::isBattery)) {
            val battery = if (sharedSources != null) sharedSources.battery else runCatching { source.readBattery() }.getOrNull()
            if (DataField.BATTERY_LEVEL_PERCENT in requested) {
                val percent = battery?.takeIf { it.scale > 0 && it.level in 0..it.scale }
                    ?.let { it.level * 100.0 / it.scale }
                values[DataField.BATTERY_LEVEL_PERCENT] = percent?.let {
                    DataValue.Number(it, "percent", battery.sampledAtElapsedRealtimeMs)
                } ?: unavailable(battery?.sampledAtElapsedRealtimeMs ?: now, if (battery == null) DataValue.Reason.SOURCE_UNAVAILABLE else DataValue.Reason.INVALID_SAMPLE)
            }
            if (DataField.BATTERY_CHARGING in requested) {
                values[DataField.BATTERY_CHARGING] = battery?.charging?.let {
                    DataValue.Flag(it, battery.sampledAtElapsedRealtimeMs)
                } ?: unavailable(battery?.sampledAtElapsedRealtimeMs ?: now,
                    if (battery == null) DataValue.Reason.SOURCE_UNAVAILABLE else DataValue.Reason.INVALID_SAMPLE)
            }
        }
        if (requested.any(::isMemory) || DataField.SYSTEM_CPU_UTILIZATION_PERCENT in requested || DataField.SYSTEM_GPU_UTILIZATION_PERCENT in requested) {
            val memory = if (sharedSources != null) sharedSources.memory else if (requested.any(::isMemory)) runCatching { source.readMemory() }.getOrNull() else null
            if (DataField.SYSTEM_MEMORY_AVAILABLE_BYTES in requested) values[DataField.SYSTEM_MEMORY_AVAILABLE_BYTES] = memory
                ?.takeIf { it.availableBytes >= 0 }?.let { DataValue.Number(it.availableBytes.toDouble(), "byte", it.sampledAtElapsedRealtimeMs) }
                ?: unavailable(memory?.sampledAtElapsedRealtimeMs ?: now, DataValue.Reason.SOURCE_UNAVAILABLE)
            if (DataField.SYSTEM_MEMORY_TOTAL_BYTES in requested) values[DataField.SYSTEM_MEMORY_TOTAL_BYTES] = memory
                ?.takeIf { it.totalBytes > 0 }?.let { DataValue.Number(it.totalBytes.toDouble(), "byte", it.sampledAtElapsedRealtimeMs) }
                ?: unavailable(memory?.sampledAtElapsedRealtimeMs ?: now, DataValue.Reason.SOURCE_UNAVAILABLE)
            if (DataField.SYSTEM_CPU_UTILIZATION_PERCENT in requested) {
                val raw = if (sharedSources != null) sharedSources.cpu else if (allowCpuRead) runCatching { source.readCpuCounters() }.getOrNull() else null
                val reading = raw?.let { parseCpu(it.rawLine, it.sampledAtElapsedRealtimeMs) }
                values[DataField.SYSTEM_CPU_UTILIZATION_PERCENT] = if (!allowCpuRead)
                    synchronized(lock) { cpuLastValues[owner] } ?: unavailable(now, DataValue.Reason.SAMPLE_PENDING)
                else precomputedCpuValues?.get(owner) ?: cpuValue(owner, reading, now)
            }
            if (DataField.SYSTEM_GPU_UTILIZATION_PERCENT in requested) {
                values[DataField.SYSTEM_GPU_UTILIZATION_PERCENT] = unavailable(now, DataValue.Reason.UNSUPPORTED)
            }
        }
        return DataSnapshot(values, now).filteredTo(requested)
    }

    private data class CpuReading(val counters: LongArray, val sampledAt: Long)
    private fun cpuValue(owner: SessionOwner, current: CpuReading?, now: Long): DataValue {
        if (current == null) return rememberCpu(owner, unavailable(now, DataValue.Reason.SOURCE_UNAVAILABLE))
        val previous = synchronized(lock) { cpuBaseline.put(owner, current) }
            ?: return rememberCpu(owner, unavailable(current.sampledAt, DataValue.Reason.SAMPLE_PENDING))
        if (current.sampledAt <= previous.sampledAt) return rememberCpu(owner, unavailable(current.sampledAt, DataValue.Reason.SAMPLE_PENDING))
        val delta = LongArray(8)
        for (i in delta.indices) {
            if (current.counters[i] < previous.counters[i]) return rememberCpu(owner, unavailable(current.sampledAt, DataValue.Reason.INVALID_SAMPLE))
            delta[i] = current.counters[i] - previous.counters[i]
        }
        val total = delta.fold(0L) { sum, value -> runCatching { Math.addExact(sum, value) }.getOrElse { return rememberCpu(owner, unavailable(current.sampledAt, DataValue.Reason.INVALID_SAMPLE)) } }
        if (total <= 0) return rememberCpu(owner, unavailable(current.sampledAt, DataValue.Reason.SAMPLE_PENDING))
        val idle = runCatching { Math.addExact(delta[3], delta[4]) }.getOrElse { return rememberCpu(owner, unavailable(current.sampledAt, DataValue.Reason.INVALID_SAMPLE)) }
        return rememberCpu(owner, DataValue.Number(100.0 * (total - idle) / total, "percent", current.sampledAt))
    }

    private fun rememberCpu(owner: SessionOwner, value: DataValue): DataValue {
        synchronized(lock) { cpuLastValues[owner] = value }
        return value
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            subscriptions.forEach { it.closed.set(true) }
            subscriptions.clear()
            loop?.close(); loop = null
            activeIntervalMs = null
            batteryEvents?.close(); batteryEvents = null
            if (metricsStarted) source.stopSystemMetrics()
            metricsStarted = false
            cpuBaseline.clear()
            cpuLastValues.clear()
        }
        callbackExecutor.shutdownNow()
    }

    private fun dispatch(subscription: Subscription, snapshot: DataSnapshot): Boolean {
        if (subscription.closed.get()) return false
        subscription.latest.set(snapshot.filteredTo(subscription.fields))
        if (!subscription.dispatching.compareAndSet(false, true)) return true
        lateinit var task: Runnable
        task = Runnable {
            subscription.pendingTask.compareAndSet(task, null)
            try {
                val latest = subscription.latest.getAndSet(null)
                if (latest != null && !subscription.closed.get()) runCatching { subscription.callback(latest) }
            } finally {
                subscription.dispatching.set(false)
                val newest = subscription.latest.get()
                if (newest != null && !subscription.closed.get()) dispatch(subscription, newest)
            }
        }
        subscription.pendingTask.set(task)
        return try {
            callbackExecutor.execute(task)
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            subscription.pendingTask.compareAndSet(task, null)
            subscription.dispatching.set(false)
            false
        }
    }

    private fun isBattery(field: DataField) = field == DataField.BATTERY_LEVEL_PERCENT || field == DataField.BATTERY_CHARGING
    private fun isMemory(field: DataField) = field == DataField.SYSTEM_MEMORY_AVAILABLE_BYTES || field == DataField.SYSTEM_MEMORY_TOTAL_BYTES
    private fun needsPolling(field: DataField) = isMemory(field) || field == DataField.SYSTEM_CPU_UTILIZATION_PERCENT
    private fun unavailable(at: Long, reason: DataValue.Reason) = DataValue.Unavailable(reason, at)

    private fun parseCpu(line: String, at: Long): CpuReading? {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 9 || fields[0] != "cpu") return null
        val counters = fields.drop(1).take(8).map { it.toLongOrNull()?.takeIf { value -> value >= 0 } ?: return null }.toLongArray()
        return CpuReading(counters, at)
    }

}

internal object CallbackDispatch {
    fun executor() = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(64), { runnable ->
        Thread(runnable, "platform-data-callback").apply { isDaemon = true }
    }, ThreadPoolExecutor.AbortPolicy())
}
