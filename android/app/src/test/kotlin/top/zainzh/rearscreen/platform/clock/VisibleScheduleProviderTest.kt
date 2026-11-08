package top.zainzh.rearscreen.platform.clock

import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class VisibleScheduleProviderTest {
    @Test fun enforcesMinimumAndFourKeysAndSameKeyReplacement() {
        val f = Fixture()
        val lease = f.provider.open("mod", 1) { f.ticks += it }
        lease.setVisible(true)
        f.scheduler.runDue()
        try { lease.set("bad", 999); fail("interval below 1s accepted") } catch (_: IllegalArgumentException) {}
        repeat(4) { lease.set("k$it", intervalMs = 1_000) }
        try { lease.set("fifth", intervalMs = 1_000); fail("fifth key accepted") } catch (_: IllegalStateException) {}
        lease.set("k0", intervalMs = 2_000) // replacement, not a second registration
        assertEquals(4, f.provider.registeredKeyCount("mod"))
        assertEquals(1, f.scheduler.pendingCount)
        f.advance(1_000)
        assertEquals(listOf("k1", "k2", "k3"), f.ticks.map { it.key }.sorted())
        f.advance(1_000)
        assertEquals(1, f.ticks.count { it.key == "k0" })
        f.provider.close()
    }

    @Test fun ownerEpochReplacementInvalidatesOldLeaseAndTickCarriesIdentity() {
        val f = Fixture()
        val old = f.provider.open("same", 5) { fail("old epoch callback") }
        old.setVisible(true); old.set("clock", intervalMs = 1_000)
        val fresh = f.provider.open("same", 6) { f.ticks += it }
        old.set("stale", intervalMs = 1_000)
        fresh.setVisible(true); fresh.set("clock", intervalMs = 1_000)
        f.advance(1_000)
        assertEquals(1, f.ticks.size)
        assertEquals(6, f.ticks.single().epoch)
        assertEquals("same", f.ticks.single().ownerId)
        f.provider.close()
    }

    @Test fun multipleOwnersShareOneTimerAndHiddenIntervalsAreSkippedUntilFullIntervalAfterResume() {
        val f = Fixture()
        val a = f.provider.open("a", 1) { f.ticks += it }
        val b = f.provider.open("b", 3) { f.ticks += it }
        a.setVisible(true); b.setVisible(true)
        a.set("pulse", intervalMs = 1_000); b.set("pulse", intervalMs = 1_000)
        assertEquals(1, f.scheduler.pendingCount)
        f.advance(1_000); assertEquals(2, f.ticks.size)
        f.ticks.clear(); a.setVisible(false)
        f.advance(4_000)
        assertEquals(listOf("b"), f.ticks.map { it.ownerId })
        f.ticks.clear(); a.setVisible(true)
        f.advance(999); assertTrue(f.ticks.isEmpty())
        f.advance(1); assertEquals(listOf("a", "b"), f.ticks.map { it.ownerId }.sorted())
        f.provider.close()
    }

    @Test fun hiddenDeadlineFiresOnceOnResumeBeforeItCanBeRearmed() {
        val f = Fixture()
        val lease = f.provider.open("mod", 2) { f.ticks += it }
        lease.set("deadline", deadlineElapsedRealtimeMs = 5_000)
        lease.setVisible(false)
        f.advance(6_000); assertTrue(f.ticks.isEmpty())
        lease.setVisible(true)
        f.scheduler.runDue()
        assertEquals(1, f.ticks.size)
        assertEquals(setOf(VisibleTickCause.DEADLINE), f.ticks.single().causes)
        f.advance(10_000)
        assertEquals(1, f.ticks.size)
        assertEquals(0, f.provider.registeredKeyCount("mod"))
        f.provider.close()
    }

    @Test fun closeAndLateTimerCallbackAreSuppressedAndLastCloseCancelsTimer() {
        val f = Fixture()
        val lease = f.provider.open("mod", 4) { f.ticks += it }
        lease.setVisible(true); lease.set("k", intervalMs = 1_000)
        val late = f.scheduler.lastScheduledTask
        lease.close()
        assertEquals(0, f.scheduler.pendingCount)
        late?.invoke() // models a timer callback already dequeued before cancellation
        assertTrue(f.ticks.isEmpty())
        assertEquals(1, f.scheduler.schedules)
        f.provider.close()
    }

    @Test fun executorRejectionIsReportedAndWallClockJumpsDoNotMoveElapsedSchedule() {
        var wall = 100_000L
        var elapsed = 0L
        val errors = mutableListOf<Throwable>()
        val scheduler = FakeScheduler({ elapsed })
        val provider = VisibleScheduleProvider({ elapsed }, scheduler, Executor {
            throw RejectedExecutionException("closed callback executor")
        }) { _, _, error -> errors += error }
        val lease = provider.open("mod", 8) {}
        lease.setVisible(true); lease.set("k", intervalMs = 1_000)
        wall += 86_400_000L
        elapsed = 1_000L
        scheduler.runDue()
        assertEquals(1, errors.size)
        assertEquals(2_000L, scheduler.nextDueAt)
        // Wall-clock movement is not an input to scheduling.
        assertEquals(86_500_000L, wall)
        provider.close()
    }

    @Test fun slowExecutorCoalescesPendingIntervalsAndCloseSuppressesQueuedCallback() {
        var now = 0L
        val scheduler = FakeScheduler({ now })
        val queued = mutableListOf<Runnable>()
        val ticks = mutableListOf<VisibleTick>()
        val provider = VisibleScheduleProvider({ now }, scheduler, Executor { queued += it }) { _, _, e -> throw e }
        val lease = provider.open("mod", 1) { ticks += it }
        lease.setVisible(true); lease.set("pulse", intervalMs = 1_000)
        now = 1_000; scheduler.runDue()
        now = 2_000; scheduler.runDue()
        now = 3_000; scheduler.runDue()
        assertEquals("one pending callback for this key despite a slow executor", 1, queued.size)
        lease.close()
        queued.single().run()
        assertTrue("closed epoch callback is dropped", ticks.isEmpty())
        provider.close()
    }

    @Test fun pendingIntervalMergesDeadlineCauseInsteadOfConsumingItBehindTheQueuedTick() {
        val f = Fixture(queuedExecutor = true)
        lateinit var lease: VisibleScheduleLease
        var accepted = false
        lease = f.provider.open("mod", 1) {
            f.ticks += it
            accepted = lease.tryAccept(it)
        }
        lease.setVisible(true)
        lease.set("clock", intervalMs = 1_000, deadlineElapsedRealtimeMs = 1_500)
        f.advance(1_000)
        assertEquals(1, f.queued.size)
        f.advance(500)
        assertEquals("still one queued callback", 1, f.queued.size)
        f.queued.removeAt(0).run()
        assertEquals(setOf(VisibleTickCause.INTERVAL, VisibleTickCause.DEADLINE), f.ticks.single().causes)
        assertTrue(accepted)
        f.provider.close()
    }

    @Test fun replacingPendingIntervalWithDueDeadlineUsesSameBoundedSlotAndNewGeneration() {
        val f = Fixture(queuedExecutor = true)
        lateinit var lease: VisibleScheduleLease
        var currentAtDelivery = false
        lease = f.provider.open("mod", 2) {
            f.ticks += it
            currentAtDelivery = lease.tryAccept(it)
        }
        lease.setVisible(true)
        lease.set("clock", intervalMs = 1_000)
        f.advance(1_000)
        val oldGeneration = f.queued.size
        lease.set("clock", deadlineElapsedRealtimeMs = f.now)
        f.scheduler.runDue()
        assertEquals("replacement reuses one pending callback slot", 1, f.queued.size)
        f.queued.removeAt(0).run()
        assertEquals(setOf(VisibleTickCause.DEADLINE), f.ticks.single().causes)
        assertEquals(2L, f.ticks.single().registrationGeneration)
        assertTrue(currentAtDelivery)
        assertEquals(1, oldGeneration)
        f.provider.close()
    }

    @Test fun dequeuedOldTimerCannotClearOrReplaceTheNewArmedTimer() {
        val f = Fixture()
        val lease = f.provider.open("mod", 9) { f.ticks += it }
        lease.setVisible(true)
        lease.set("clock", intervalMs = 1_000)
        val oldTimer = f.scheduler.lastScheduledTask!!
        lease.set("clock", intervalMs = 2_000) // cancels old timer and arms replacement
        assertEquals(1, f.scheduler.pendingCount)
        val newPendingCount = f.scheduler.pendingCount
        oldTimer.invoke() // simulate cancel(false) losing a race with a dequeued callback
        assertEquals(newPendingCount, f.scheduler.pendingCount)
        assertEquals(1, f.scheduler.pendingCount)
        f.advance(1_999)
        assertTrue(f.ticks.isEmpty())
        f.advance(1)
        assertEquals(1, f.ticks.size)
        lease.close()
        assertEquals("last close cancels the remaining timer", 0, f.scheduler.pendingCount)
        f.provider.close()
    }

    @Test fun hideShowInvalidatesPendingIntervalButRearmsPendingDeadlineOnce() {
        val f = Fixture(queuedExecutor = true)
        lateinit var lease: VisibleScheduleLease
        val currentAtDelivery = mutableListOf<Boolean>()
        lease = f.provider.open("mod", 10) {
            f.ticks += it
            currentAtDelivery += lease.tryAccept(it)
        }
        lease.setVisible(true)
        lease.set("clock", intervalMs = 1_000, deadlineElapsedRealtimeMs = 1_500)

        f.advance(1_000) // interval tick queued
        assertEquals(1, f.queued.size)
        f.advance(500) // deadline merges into that same bounded slot
        val preHideInterval = VisibleTick(
            ownerId = "mod", epoch = 10, key = "clock", registrationGeneration = 1,
            visibilityGeneration = 1, observedElapsedRealtimeMs = 1_000,
            causes = setOf(VisibleTickCause.INTERVAL),
        )
        lease.setVisible(false) // invalidates the combined pre-hide tick, but preserves deadline
        f.advance(3_400) // now 4,900ms; hidden time creates no callbacks
        assertTrue(f.ticks.isEmpty())
        lease.setVisible(true) // interval waits until 5,900; expired deadline is due now
        assertFalse("pre-hide interval is stale across the visibility generation", lease.tryAccept(preHideInterval))
        f.scheduler.runDue()
        assertEquals("resume still uses the one pre-existing executor slot", 1, f.queued.size)
        f.queued.removeAt(0).run() // this old Runnable must read only the new deadline cause
        assertEquals(1, f.ticks.size)
        assertEquals(setOf(VisibleTickCause.DEADLINE), f.ticks.single().causes)
        assertTrue(currentAtDelivery.single())
        assertEquals(5_900L, f.scheduler.nextDueAt)

        f.advance(1_000)
        assertEquals(1, f.queued.size)
        f.queued.removeAt(0).run()
        assertEquals(setOf(VisibleTickCause.INTERVAL), f.ticks.last().causes)
        assertTrue(currentAtDelivery.last())
        f.provider.close()
    }

    @Test fun hideShowInFirstDueCallbackPreservesSiblingDeadlineClaimedInSameBatch() {
        val f = Fixture()
        lateinit var lease: VisibleScheduleLease
        val acceptedKeys = mutableListOf<String>()
        val deliveredTicks = mutableListOf<VisibleTick>()
        var toggled = false
        lease = f.provider.open("mod", 11) { tick ->
            assertTrue("first acceptance inside callback", lease.tryAccept(tick))
            assertFalse("same delivery cannot be accepted twice", lease.tryAccept(tick))
            acceptedKeys += tick.key
            deliveredTicks += tick
            if (!toggled) {
                toggled = true
                lease.setVisible(false)
                lease.setVisible(true)
            }
        }
        lease.setVisible(true)
        lease.set("first", deadlineElapsedRealtimeMs = 1_000)
        lease.set("second", deadlineElapsedRealtimeMs = 1_000)

        f.advance(1_000) // one timer fires both deadlines; first callback re-enters hide/show
        assertEquals(listOf("first", "second"), acceptedKeys.sorted())
        f.advance(10_000)
        assertEquals("each due one-shot is delivered exactly once", listOf("first", "second"), acceptedKeys.sorted())
        assertTrue("a completed delivery is no longer an admission token", deliveredTicks.all { !lease.tryAccept(it) })
        assertEquals(0, f.provider.registeredKeyCount("mod"))
        f.provider.close()
    }

    @Test fun intervalTickReportsSkippedCadenceWithoutReplayingTheBacklog() {
        val f = Fixture()
        val lease = f.provider.open("mod", 12) { f.ticks += it }
        lease.setVisible(true)
        lease.set("pulse", intervalMs = 1_000)

        f.advance(3_500)

        assertEquals(1, f.ticks.size)
        assertEquals(1_000L, f.ticks.single().scheduledElapsedRealtimeMs)
        assertEquals(3_500L, f.ticks.single().observedElapsedRealtimeMs)
        assertEquals(2, f.ticks.single().missedCount)
        f.provider.close()
    }

    private class Fixture(queuedExecutor: Boolean = false) {
        var now = 0L
        val ticks = mutableListOf<VisibleTick>()
        val queued = mutableListOf<Runnable>()
        val scheduler = FakeScheduler({ now })
        val executor = Executor { if (queuedExecutor) queued += it else it.run() }
        val provider = VisibleScheduleProvider({ now }, scheduler, executor) { _, _, error -> throw error }
        fun advance(ms: Long) { now += ms; scheduler.runDue() }
    }

    private class FakeScheduler(private val now: () -> Long) : OneShotScheduler {
        private data class Task(val at: Long, val order: Long, val body: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        private var order = 0L
        var schedules = 0
            private set
        val pendingCount get() = tasks.count { !it.cancelled }
        val lastScheduledTask get() = tasks.lastOrNull()?.body
        val nextDueAt get() = tasks.filterNot { it.cancelled }.minByOrNull { it.at }?.at

        override fun schedule(delayMs: Long, task: () -> Unit): Closeable {
            schedules++
            val item = Task(now() + delayMs, order++, task)
            tasks += item
            return Closeable { item.cancelled = true }
        }

        fun runDue() {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= now() }.minWithOrNull(compareBy<Task> { it.at }.thenBy { it.order }) ?: return
                next.cancelled = true
                next.body()
            }
        }
    }
}
