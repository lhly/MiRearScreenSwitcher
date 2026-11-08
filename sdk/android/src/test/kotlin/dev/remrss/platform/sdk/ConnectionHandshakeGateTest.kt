package dev.remrss.platform.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConnectionHandshakeGateTest {
    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { synchronized(tasks) { tasks.addLast(command) } }
        fun drain() {
            while (true) {
                val next = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() } ?: return
                next.run()
            }
        }
        fun hasTasks() = synchronized(tasks) { tasks.isNotEmpty() }
    }

    @Test fun closeDuringHandshakeFailsWaiterAndRejectsLateReady() {
        val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
        val waiterResults = mutableListOf<Result<Unit>>()
        val start = gate.connect { waiterResults += it } as ConnectionHandshakeGate.ConnectDecision.StartBinding
        val oldBinder = Any()
        assertTrue(gate.serviceConnected(start.attemptId, oldBinder))

        val closed = gate.close()
        assertFalse(closed.wasBound) // bindService has not returned yet
        val dispatcher = ClientResultDispatcher(Executor { it.run() }) { true }
        val completion = closed.completion!!
        completion.waiters.forEach { waiter -> dispatcher.dispatch(waiter, completion.result) }
        assertEquals(RearSdkErrorCode.CLIENT_CLOSED, waiterResults.single().exceptionOrNull()?.message)
        var published = false
        assertNull(gate.handshakeCompleted(start.attemptId, oldBinder, "remrss.platform/1", publish = { published = true }))
        assertFalse(published)
    }

    @Test fun bindCompletingAfterCloseIsCompensatedAndCannotReopenClient() {
        val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
        val start = gate.connect {} as ConnectionHandshakeGate.ConnectDecision.StartBinding
        val bindEntered = CountDownLatch(1)
        val completeBind = CountDownLatch(1)
        val unbinds = AtomicInteger(0)
        val bindThread = Thread {
            val accepted = attemptBinding({
                bindEntered.countDown()
                check(completeBind.await(5, TimeUnit.SECONDS))
                true
            }) {}
            val result = gate.bindingCompleted(start.attemptId, accepted)
            if (result.unbind) unbinds.incrementAndGet()
        }
        bindThread.start()
        assertTrue(bindEntered.await(5, TimeUnit.SECONDS))

        val closed = gate.close()
        assertFalse(closed.wasBound)
        completeBind.countDown()
        bindThread.join(5_000)
        assertFalse(bindThread.isAlive)
        assertEquals(1, unbinds.get())
        assertFalse(gate.serviceConnected(start.attemptId, Any()))
        assertEquals(ConnectionHandshakeGate.ConnectDecision.Rejected(RearSdkErrorCode.CLIENT_CLOSED), gate.connect {})
    }

    @Test fun oldBinderHandshakeCannotReplaceAReconnectedRemote() {
        val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
        val results = mutableListOf<Result<Unit>>()
        val first = gate.connect { results += it } as ConnectionHandshakeGate.ConnectDecision.StartBinding
        assertTrue(gate.serviceConnected(first.attemptId, "old"))
        assertFalse(gate.bindingCompleted(first.attemptId, accepted = true).unbind)
        val disconnected = gate.disconnected(first.attemptId)!!
        val disconnectedWaiters = disconnected.completion!!
        disconnectedWaiters.waiters.forEach { it(disconnectedWaiters.result) }
        assertEquals(RearSdkErrorCode.SERVICE_DISCONNECTED, results.single().exceptionOrNull()?.message)

        results.clear()
        assertEquals(ConnectionHandshakeGate.ConnectDecision.AwaitingBinding, gate.connect { results += it })
        assertTrue(gate.serviceConnected(first.attemptId, "new"))
        var currentRemote: String? = null
        assertNull(gate.handshakeCompleted(first.attemptId, "old", "remrss.platform/1", publish = { currentRemote = "old" }))
        val ready = gate.handshakeCompleted(first.attemptId, "new", "remrss.platform/1", publish = { currentRemote = "new" })
        assertNotNull(ready)
        assertEquals("new", currentRemote)
        assertTrue(gate.isCurrent(first.attemptId, "new"))
        assertFalse(gate.isCurrent(first.attemptId, "old"))
    }

    @Test fun bindFalseOrThrowDrainsWaitersAndAllowsExplicitRetry() {
        val failedBinds = listOf<() -> Boolean>({ false }, { throw IllegalStateException("bind failed") })
        failedBinds.forEach { bind ->
            val gate = ConnectionHandshakeGate<Any>("remrss.platform/1")
            val results = mutableListOf<Result<Unit>>()
            val failedAttempt = gate.connect { results += it } as ConnectionHandshakeGate.ConnectDecision.StartBinding
            assertFalse(attemptBinding(bind) {
                val failed = gate.bindingFailed(failedAttempt.attemptId, RearSdkErrorCode.SERVICE_UNAVAILABLE)!!
                val completion = failed.completion!!
                completion.waiters.forEach { it(completion.result) }
            })
            assertEquals(RearSdkErrorCode.SERVICE_UNAVAILABLE, results.single().exceptionOrNull()?.message)

            results.clear()
            val retry = gate.connect { results += it } as ConnectionHandshakeGate.ConnectDecision.StartBinding
            assertTrue(retry.attemptId > failedAttempt.attemptId)
            assertTrue(attemptBinding({ true }) {})
            assertFalse(gate.bindingCompleted(retry.attemptId, accepted = true).unbind)
            assertTrue(gate.serviceConnected(retry.attemptId, "binder"))
            val ready = gate.handshakeCompleted(retry.attemptId, "binder", "remrss.platform/1")!!
            ready.completion!!.waiters.forEach { it(ready.completion.result) }
            assertTrue(results.single().isSuccess)
        }
    }

    @Test fun staleEventsAndQueuedOperationsCannotCrossConnectionEpochs() {
        var closed = false
        val current = AtomicReference<Any?>(Any())
        val oldEpoch = current.get()!!
        val newEpoch = Any()
        val worker = QueueExecutor()
        val callbacks = QueueExecutor()
        val results = ClientResultDispatcher(callbacks) { closed }
        val invalidatedOperations = mutableListOf<Any>()
        val operations = ClientOperationQueue(worker, results, { closed }, { current.get() === it }) {
            invalidatedOperations += it
        }
        val events = ClientEventDispatcher(callbacks, { closed }) { current.get() === it }
        val oldEvents = mutableListOf<String>()
        val oldResults = mutableListOf<Result<Int>>()
        var oldActionCalls = 0

        events.dispatch(oldEpoch) { oldEvents += "queued-before-disconnect" }
        operations.submit(oldEpoch, { oldActionCalls++; 1 }) { oldResults += it }
        current.set(newEpoch)
        events.dispatch(oldEpoch) { oldEvents += "arrived-after-reconnect" }
        worker.drain()
        callbacks.drain()
        assertTrue(oldEvents.isEmpty())
        assertEquals(0, oldActionCalls)
        assertEquals(RearSdkErrorCode.SERVICE_DISCONNECTED, oldResults.single().exceptionOrNull()?.message)

        val currentEvents = mutableListOf<String>()
        val currentResults = mutableListOf<Result<Int>>()
        events.dispatch(newEpoch) { currentEvents += "current" }
        operations.submit(newEpoch, { 7 }) { currentResults += it }
        worker.drain()
        callbacks.drain()
        assertEquals(listOf("current"), currentEvents)
        assertEquals(7, currentResults.single().getOrThrow())
        assertTrue(invalidatedOperations.isEmpty())
    }

    @Test fun closeCompletesInflightLocalResultAndUnbindsWithoutWaitingForBinderWorker() {
        var closed = false
        val currentEpoch = Any()
        val worker = Executors.newSingleThreadExecutor()
        val enteredBinderCall = CountDownLatch(1)
        val unblockBinderCall = CountDownLatch(1)
        val compensationFinished = CountDownLatch(1)
        val resultsSeen = mutableListOf<Result<Int>>()
        var hostOwnerExists = false
        var hostReleaseCalls = 0
        val resultDispatcher = ClientResultDispatcher(Executor { it.run() }) { closed }
        val operations = ClientOperationQueue(worker, resultDispatcher, { closed }, { it === currentEpoch }) {
            hostOwnerExists = false
            hostReleaseCalls++
            compensationFinished.countDown()
        }
        var unbindCalls = 0
        try {
            operations.submit(currentEpoch, {
                enteredBinderCall.countDown()
                check(unblockBinderCall.await(5, TimeUnit.SECONDS))
                hostOwnerExists = true // Host creates the owner after the eager close release.
                99
            }) { resultsSeen += it }
            assertTrue(enteredBinderCall.await(5, TimeUnit.SECONDS))

            closed = true
            ClientCloseSequence(
                operations = operations,
                unbind = { unbindCalls++ },
                scheduleHostRelease = {
                    hostOwnerExists = false
                    hostReleaseCalls++
                },
                shutdownWorker = { worker.shutdown() }
            ).close()

            assertEquals(1, resultsSeen.size)
            assertEquals(RearSdkErrorCode.CLIENT_CLOSED, resultsSeen.single().exceptionOrNull()?.message)
            assertEquals(1, unbindCalls)
            assertEquals(1, hostReleaseCalls)
            assertFalse(hostOwnerExists)
            assertFalse(worker.isTerminated)

            unblockBinderCall.countDown()
            assertTrue(compensationFinished.await(5, TimeUnit.SECONDS))
            assertFalse(hostOwnerExists)
            assertEquals(2, hostReleaseCalls)
        } finally {
            unblockBinderCall.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(1, resultsSeen.size)
    }

    @Test fun staleOldEpochOperationFinishesAfterReconnectAndReleasesOnlyItsOwner() {
        var closed = false
        val oldEpoch = Any()
        val newEpoch = Any()
        val currentEpoch = AtomicReference<Any?>(oldEpoch)
        val worker = Executors.newSingleThreadExecutor()
        val enteredBinderCall = CountDownLatch(1)
        val unblockBinderCall = CountDownLatch(1)
        val compensationFinished = CountDownLatch(1)
        val resultDelivered = CountDownLatch(1)
        val owners = mutableSetOf<Any>()
        val currentResults = mutableListOf<Result<Int>>()
        val callbacks = ClientResultDispatcher(Executor { it.run() }) { closed }
        val operations = ClientOperationQueue(
            worker, callbacks, { closed }, { currentEpoch.get() === it },
            { epoch -> synchronized(owners) { owners.remove(epoch) }; compensationFinished.countDown() }
        )
        try {
            operations.submit(oldEpoch, {
                enteredBinderCall.countDown()
                check(unblockBinderCall.await(5, TimeUnit.SECONDS))
                synchronized(owners) { owners += oldEpoch }
                12
            }) { currentResults += it; resultDelivered.countDown() }
            assertTrue(enteredBinderCall.await(5, TimeUnit.SECONDS))

            currentEpoch.set(newEpoch)
            assertTrue(synchronized(owners) { owners.add(newEpoch) })
            unblockBinderCall.countDown()
            assertTrue(compensationFinished.await(5, TimeUnit.SECONDS))
            assertTrue(resultDelivered.await(5, TimeUnit.SECONDS))
            assertEquals(1, synchronized(owners) { owners.size })
            assertTrue(synchronized(owners) { owners.contains(newEpoch) })
            assertEquals(RearSdkErrorCode.SERVICE_DISCONNECTED, currentResults.single().exceptionOrNull()?.message)
        } finally {
            unblockBinderCall.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun invalidatedOperationFailureStillTriggersCompensatingRelease() {
        var closed = false
        val epoch = Any()
        val worker = Executors.newSingleThreadExecutor()
        val enteredBinderCall = CountDownLatch(1)
        val unblockBinderCall = CountDownLatch(1)
        val compensationFinished = CountDownLatch(1)
        val resultDelivered = CountDownLatch(1)
        var ownerExists = false
        val resultsSeen = mutableListOf<Result<Int>>()
        val callbacks = ClientResultDispatcher(Executor { it.run() }) { closed }
        val operations = ClientOperationQueue(worker, callbacks, { closed }, { it === epoch }) {
            ownerExists = false
            compensationFinished.countDown()
        }
        try {
            operations.submit(epoch, {
                enteredBinderCall.countDown()
                check(unblockBinderCall.await(5, TimeUnit.SECONDS))
                ownerExists = true
                throw IllegalStateException("Host registered before failing")
            }) { resultsSeen += it; resultDelivered.countDown() }
            assertTrue(enteredBinderCall.await(5, TimeUnit.SECONDS))
            closed = true
            operations.close()
            assertEquals(RearSdkErrorCode.CLIENT_CLOSED, resultsSeen.single().exceptionOrNull()?.message)
            unblockBinderCall.countDown()
            assertTrue(compensationFinished.await(5, TimeUnit.SECONDS))
            assertTrue(resultDelivered.await(5, TimeUnit.SECONDS))
            assertFalse(ownerExists)
        } finally {
            unblockBinderCall.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
