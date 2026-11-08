package top.zainzh.rearscreen.platform.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.zainzh.rearscreen.platform.SessionOwner
import top.zainzh.rearscreen.platform.SessionPrincipal
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Deterministic provider contract tests; no Android service, clock, timer, or device input is used. */
class SharedPlatformDataProviderTest {
    private val source = FakeSource()
    private val scheduler = FakeSchedule()
    private val clock = FakeClock()
    private val provider = SharedPlatformDataProvider(source, clock, scheduler, DirectExecutor())

    @After fun closeProvider() = provider.close()

    @Test fun ownersShareOneMetricsSamplerAndReceiveOnlyTheirRequestedFields() {
        val first = owner(100, "first")
        val second = owner(101, "second")
        val a = mutableListOf<DataSnapshot>()
        val b = mutableListOf<DataSnapshot>()
        val one = provider.observe(first, setOf(DataField.SYSTEM_CPU_UTILIZATION_PERCENT), 5_000) { a += it }
        val two = provider.observe(second, setOf(DataField.SYSTEM_CPU_UTILIZATION_PERCENT, DataField.SYSTEM_GPU_UTILIZATION_PERCENT), 8_000) { b += it }

        assertEquals(1, source.startMetricsCalls)
        assertEquals(1, scheduler.starts)
        assertEquals(5_000L, scheduler.intervalMs)
        assertEquals(setOf(DataField.SYSTEM_CPU_UTILIZATION_PERCENT), a.last().fields.keys)
        assertEquals(setOf(DataField.SYSTEM_CPU_UTILIZATION_PERCENT, DataField.SYSTEM_GPU_UTILIZATION_PERCENT), b.last().fields.keys)
        assertEquals(DataValue.Reason.SAMPLE_PENDING, (a.last().fields.getValue(DataField.SYSTEM_CPU_UTILIZATION_PERCENT) as DataValue.Unavailable).reason)

        source.cpuSamples += CpuCounterSample("cpu 100 0 50 50 0 0 0 0", 100)
        clock.now = 100
        scheduler.tick()
        source.cpuSamples += CpuCounterSample("cpu 150 0 50 100 0 0 0 0", 200)
        clock.now = 200
        scheduler.tick()

        assertEquals(2, source.cpuReadCalls)
        assertEquals(50.0, (a.last().fields.getValue(DataField.SYSTEM_CPU_UTILIZATION_PERCENT) as DataValue.Number).value, 0.001)
        assertEquals("percent", (a.last().fields.getValue(DataField.SYSTEM_CPU_UTILIZATION_PERCENT) as DataValue.Number).unit)
        assertEquals(DataValue.Reason.UNSUPPORTED, (b.last().fields.getValue(DataField.SYSTEM_GPU_UTILIZATION_PERCENT) as DataValue.Unavailable).reason)

        one.close(); one.close()
        assertEquals(1, scheduler.cancels)
        assertEquals(2, scheduler.starts)
        assertEquals(8_000L, scheduler.intervalMs)
        two.close()
        assertEquals(2, scheduler.cancels)
        assertEquals(1, source.stopMetricsCalls)
        assertNull(scheduler.task)
    }

    @Test fun sameOwnerCpuObserversReuseOneDiffAndSnapshotValuePerSample() {
        val owner = owner(111, "same")
        val a = mutableListOf<DataSnapshot>()
        val b = mutableListOf<DataSnapshot>()
        val one = provider.observe(owner, setOf(DataField.SYSTEM_CPU_UTILIZATION_PERCENT)) { a += it }
        val two = provider.observe(owner, setOf(DataField.SYSTEM_CPU_UTILIZATION_PERCENT)) { b += it }

        source.cpuSamples += CpuCounterSample("cpu 100 0 50 50 0 0 0 0", 100)
        clock.now = 100
        scheduler.tick()
        source.cpuSamples += CpuCounterSample("cpu 150 0 50 100 0 0 0 0", 200)
        clock.now = 200
        scheduler.tick()

        val expected = DataValue.Number(50.0, "percent", 200)
        assertEquals(expected, a.last().fields.getValue(DataField.SYSTEM_CPU_UTILIZATION_PERCENT))
        assertEquals(expected, b.last().fields.getValue(DataField.SYSTEM_CPU_UTILIZATION_PERCENT))
        assertEquals(expected, provider.snapshot(owner, setOf(DataField.SYSTEM_CPU_UTILIZATION_PERCENT)).fields.getValue(DataField.SYSTEM_CPU_UTILIZATION_PERCENT))
        assertEquals(2, source.cpuReadCalls)
        one.close(); two.close()
    }

    @Test fun batterySnapshotUsesScaleAndDoesNotLeakOtherFields() {
        val owner = owner(202, "battery")
        source.battery = BatterySample(level = 26, scale = 52, charging = true, sampledAtElapsedRealtimeMs = 44)
        val snapshot = provider.snapshot(owner, setOf(DataField.BATTERY_LEVEL_PERCENT))
        assertEquals(setOf(DataField.BATTERY_LEVEL_PERCENT), snapshot.fields.keys)
        val level = snapshot.fields.getValue(DataField.BATTERY_LEVEL_PERCENT) as DataValue.Number
        assertEquals(50.0, level.value, 0.001)
        assertEquals("percent", level.unit)
        assertEquals(44L, level.sampledAtElapsedRealtimeMs)
    }

    @Test fun invalidSourcesAreExplicitAndInitialCpuSampleIsNeverInventedAsZero() {
        val owner = owner(303, "unavailable")
        source.battery = BatterySample(level = 40, scale = 0, charging = null, sampledAtElapsedRealtimeMs = 10)
        source.memory = MemorySample(-1, 0, 10)
        val snapshot = provider.snapshot(owner, setOf(
            DataField.BATTERY_LEVEL_PERCENT,
            DataField.BATTERY_CHARGING,
            DataField.SYSTEM_CPU_UTILIZATION_PERCENT,
            DataField.SYSTEM_MEMORY_AVAILABLE_BYTES,
            DataField.SYSTEM_MEMORY_TOTAL_BYTES
        ))
        assertEquals(DataValue.Reason.INVALID_SAMPLE, (snapshot.fields.getValue(DataField.BATTERY_LEVEL_PERCENT) as DataValue.Unavailable).reason)
        assertEquals(DataValue.Reason.INVALID_SAMPLE, (snapshot.fields.getValue(DataField.BATTERY_CHARGING) as DataValue.Unavailable).reason)
        assertEquals(DataValue.Reason.SAMPLE_PENDING, (snapshot.fields.getValue(DataField.SYSTEM_CPU_UTILIZATION_PERCENT) as DataValue.Unavailable).reason)
        assertEquals(DataValue.Reason.SOURCE_UNAVAILABLE, (snapshot.fields.getValue(DataField.SYSTEM_MEMORY_AVAILABLE_BYTES) as DataValue.Unavailable).reason)
        assertEquals(DataValue.Reason.SOURCE_UNAVAILABLE, (snapshot.fields.getValue(DataField.SYSTEM_MEMORY_TOTAL_BYTES) as DataValue.Unavailable).reason)
    }

    @Test fun observerCloseAndOwnerHideStopSamplingThenResubscribeCleanly() {
        val owner = owner(404, "mod")
        val fields = setOf(DataField.SYSTEM_MEMORY_TOTAL_BYTES)
        val first = provider.observe(owner, fields, intervalMs = 1) { }
        assertTrue(scheduler.isActive)
        assertEquals(PlatformDataProvider.MIN_INTERVAL_MS, scheduler.intervalMs)
        first.close() // Core closes this lease when the owner is hidden/revoked.
        assertFalse(scheduler.isActive)
        assertEquals(0, source.startMetricsCalls) // Memory is public Android data and needs no privileged service bind.
        assertEquals(0, source.stopMetricsCalls)
        val readCount = source.memoryReadCalls
        clock.now += 5_000
        scheduler.tickIfActive()
        assertEquals(readCount, source.memoryReadCalls)

        val restored = provider.observe(owner, fields) { }
        assertTrue(scheduler.isActive)
        assertEquals(0, source.startMetricsCalls)
        restored.close()
        assertFalse(scheduler.isActive)
        assertEquals(0, source.stopMetricsCalls)
    }

    @Test fun disconnectedCounterBackendDoesNotHideAvailableMemory() {
        val owner = owner(455, "disconnected")
        val snapshots = mutableListOf<DataSnapshot>()
        val lease = provider.observe(owner, setOf(DataField.SYSTEM_CPU_UTILIZATION_PERCENT, DataField.SYSTEM_MEMORY_TOTAL_BYTES)) { snapshots += it }
        scheduler.tick()
        assertEquals(DataValue.Reason.SOURCE_UNAVAILABLE,
            (snapshots.last().fields.getValue(DataField.SYSTEM_CPU_UTILIZATION_PERCENT) as DataValue.Unavailable).reason)
        val memory = snapshots.last().fields.getValue(DataField.SYSTEM_MEMORY_TOTAL_BYTES) as DataValue.Number
        assertEquals(2048.0, memory.value, 0.001)
        assertEquals("byte", memory.unit)
        assertEquals(1, source.startMetricsCalls)
        lease.close()
        assertEquals(1, source.stopMetricsCalls)
    }

    @Test fun rateIsClampedAndBatteryEventsAreReferenceCountedByProvider() {
        val first = provider.observe(owner(505, "a"), setOf(DataField.BATTERY_LEVEL_PERCENT), 1) { }
        val second = provider.observe(owner(506, "b"), setOf(DataField.BATTERY_CHARGING), 1) { }
        assertEquals(1, source.startBatteryCalls)
        assertEquals(0, source.stopBatteryCalls)
        first.close()
        assertEquals(0, source.stopBatteryCalls)
        second.close()
        assertEquals(1, source.stopBatteryCalls)
        assertEquals(0, scheduler.starts)
    }

    @Test fun blockedCallbackRejectsAtCapacityThenDeliversAfterCapacityRecovers() {
        val slowSource = FakeSource()
        val slowSchedule = FakeSchedule()
        val callbackExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64))
        val slowProvider = SharedPlatformDataProvider(slowSource, FakeClock(), slowSchedule, callbackExecutor)
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val remainingCallbacks = CountDownLatch(PlatformDataProvider.MAX_OBSERVERS - 1)
        val leases = mutableListOf<Closeable>()
        try {
            leases += slowProvider.observe(owner(600, "blocked"), setOf(DataField.BATTERY_LEVEL_PERCENT)) {
                callbackStarted.countDown()
                releaseCallback.await(5, TimeUnit.SECONDS)
            }
            assertTrue("first callback did not start", callbackStarted.await(5, TimeUnit.SECONDS))

            repeat(PlatformDataProvider.MAX_OBSERVERS - 1) { index ->
                leases += slowProvider.observe(owner(601 + index, "queued-$index"), setOf(DataField.BATTERY_LEVEL_PERCENT)) {
                    remainingCallbacks.countDown()
                }
            }
            try {
                slowProvider.observe(owner(700, "over-capacity"), setOf(DataField.BATTERY_LEVEL_PERCENT)) { }
                throw AssertionError("observer beyond capacity was silently accepted")
            } catch (_: RejectedExecutionException) { }

            releaseCallback.countDown()
            assertTrue("queued observers did not drain", remainingCallbacks.await(5, TimeUnit.SECONDS))
            leases[1].close()
            val recoveredCallback = CountDownLatch(1)
            val recovered = slowProvider.observe(owner(701, "recovered"), setOf(DataField.BATTERY_LEVEL_PERCENT)) {
                recoveredCallback.countDown()
            }
            leases += recovered
            assertTrue("callback did not resume after capacity recovered", recoveredCallback.await(5, TimeUnit.SECONDS))
        } finally {
            releaseCallback.countDown()
            leases.forEach { it.close() }
            slowProvider.close()
        }
    }

    private fun owner(uid: Int, session: String) = SessionOwner(
        uid, "top.zainzh.rearscreen", "certificate", SessionPrincipal.SignedMod("author", "mod.$session", session)
    )

    private class FakeClock : PlatformDataClock { var now = 0L; override fun nowMs() = now }

    private class FakeSchedule : PlatformDataSchedule {
        var task: (() -> Unit)? = null
        var intervalMs = 0L
        var starts = 0
        var cancels = 0
        val isActive get() = task != null
        override fun every(intervalMs: Long, task: () -> Unit): Closeable {
            this.intervalMs = intervalMs
            this.task = task
            starts++
            return Closeable { this.task = null; cancels++ }
        }
        fun tick() = task!!.invoke()
        fun tickIfActive() { task?.invoke() }
    }

    private class FakeSource : PlatformDataSource {
        var battery: BatterySample? = BatterySample(80, 100, false, 1)
        var memory: MemorySample? = MemorySample(1024, 2048, 1)
        val cpuSamples = ArrayDeque<CpuCounterSample>()
        var cpuReadCalls = 0
        var memoryReadCalls = 0
        var startBatteryCalls = 0
        var stopBatteryCalls = 0
        var startMetricsCalls = 0
        var stopMetricsCalls = 0
        override fun readBattery() = battery
        override fun readCpuCounters(): CpuCounterSample? { cpuReadCalls++; return if (cpuSamples.isEmpty()) null else cpuSamples.removeFirst() }
        override fun readMemory(): MemorySample? { memoryReadCalls++; return memory }
        override fun startBatteryEvents(onChanged: () -> Unit): Closeable { startBatteryCalls++; return Closeable { stopBatteryCalls++ } }
        override fun startSystemMetrics() { startMetricsCalls++ }
        override fun stopSystemMetrics() { stopMetricsCalls++ }
    }

    private class DirectExecutor : ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) {
        override fun execute(command: Runnable) = command.run()
    }
}
