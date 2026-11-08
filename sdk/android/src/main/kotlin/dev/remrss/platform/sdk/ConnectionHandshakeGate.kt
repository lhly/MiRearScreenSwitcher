package dev.remrss.platform.sdk

import android.os.RemoteException
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun attemptBinding(bind: () -> Boolean, onFailure: () -> Unit): Boolean {
    val bound = try { bind() } catch (_: RuntimeException) { false }
    if (!bound) onFailure()
    return bound
}

/** Pure connection state used by RearClient; binder identity prevents stale handshakes reviving a link. */
internal class ConnectionHandshakeGate<T : Any>(private val expectedContract: String) {
    sealed interface ConnectDecision {
        data class StartBinding(val attemptId: Long) : ConnectDecision
        data object AwaitingBinding : ConnectDecision
        data object AlreadyConnected : ConnectDecision
        data class Rejected(val code: String) : ConnectDecision
    }

    data class Completion(val waiters: List<(Result<Unit>) -> Unit>, val result: Result<Unit>)
    data class Transition(val wasBound: Boolean, val attemptId: Long?, val completion: Completion?)
    data class BindingResult(val unbind: Boolean, val completion: Completion?)

    private var closed = false
    private var bound = false
    private var bindingInProgress = false
    private var nextAttemptId = 0L
    private var currentAttemptId: Long? = null
    private var activeBinder: T? = null
    private var connectedBinder: T? = null
    private val waiters = ArrayList<(Result<Unit>) -> Unit>()

    @Synchronized fun connect(waiter: (Result<Unit>) -> Unit): ConnectDecision {
        if (closed) return ConnectDecision.Rejected(RearSdkErrorCode.CLIENT_CLOSED)
        if (connectedBinder != null && connectedBinder === activeBinder) return ConnectDecision.AlreadyConnected
        waiters += waiter
        if (currentAttemptId != null) return ConnectDecision.AwaitingBinding
        val attemptId = ++nextAttemptId
        currentAttemptId = attemptId
        bindingInProgress = true
        return ConnectDecision.StartBinding(attemptId)
    }

    @Synchronized fun shouldStartBinding(attemptId: Long): Boolean =
        !closed && bindingInProgress && currentAttemptId == attemptId

    @Synchronized fun bindingCompleted(attemptId: Long, accepted: Boolean): BindingResult {
        if (closed || currentAttemptId != attemptId || !bindingInProgress) return BindingResult(unbind = accepted, completion = null)
        bindingInProgress = false
        if (accepted) {
            bound = true
            return BindingResult(unbind = false, completion = null)
        }
        val transition = reset(attemptId, RearSdkErrorCode.SERVICE_UNAVAILABLE)
        return BindingResult(unbind = false, completion = transition.completion)
    }

    @Synchronized fun serviceConnected(attemptId: Long, binder: T, retirePrevious: () -> Unit = {}): Boolean {
        if (closed || currentAttemptId != attemptId || !(bound || bindingInProgress)) return false
        if (activeBinder != null && (activeBinder !== binder || connectedBinder != null)) {
            connectedBinder = null
            retirePrevious()
        }
        activeBinder = binder
        connectedBinder = null
        return true
    }

    @Synchronized fun handshakeCompleted(
        attemptId: Long,
        binder: T,
        contract: String?,
        publish: () -> Unit = {},
        clear: () -> Unit = {}
    ): Transition? {
        if (closed || currentAttemptId != attemptId || activeBinder !== binder) return null
        if (contract == expectedContract) {
            connectedBinder = binder
            publish()
            return Transition(false, attemptId, takeWaiters(Result.success(Unit)))
        }
        val error = if (contract == null) RearSdkErrorCode.SERVICE_UNAVAILABLE else RearSdkErrorCode.CONTRACT_INCOMPATIBLE
        clear()
        return reset(attemptId, error)
    }

    @Synchronized fun bindingFailed(attemptId: Long, code: String, clear: () -> Unit = {}): Transition? {
        if (currentAttemptId != attemptId || closed) return null
        clear()
        return reset(attemptId, code)
    }

    /** System disconnection is recoverable; retain the binding so Android can reconnect it. */
    @Synchronized fun disconnected(attemptId: Long, clear: () -> Unit = {}): Transition? {
        if (closed || currentAttemptId != attemptId) return null
        activeBinder = null
        connectedBinder = null
        clear()
        return Transition(false, attemptId, takeWaiters(Result.failure(IllegalStateException(RearSdkErrorCode.SERVICE_DISCONNECTED))))
    }

    @Synchronized fun close(clear: () -> Unit = {}): Transition {
        val wasBound = bound
        val attemptId = currentAttemptId
        bound = false
        bindingInProgress = false
        currentAttemptId = null
        activeBinder = null
        connectedBinder = null
        closed = true
        clear()
        return Transition(wasBound, attemptId, takeWaiters(Result.failure(IllegalStateException(RearSdkErrorCode.CLIENT_CLOSED))))
    }

    @Synchronized fun isCurrent(attemptId: Long, binder: T): Boolean =
        !closed && currentAttemptId == attemptId && connectedBinder === binder && activeBinder === binder

    private fun reset(attemptId: Long, code: String): Transition {
        val wasBound = bound
        bound = false
        bindingInProgress = false
        currentAttemptId = null
        activeBinder = null
        connectedBinder = null
        return Transition(wasBound, attemptId, takeWaiters(Result.failure(IllegalStateException(code))))
    }

    private fun takeWaiters(result: Result<Unit>): Completion? {
        if (waiters.isEmpty()) return null
        return Completion(waiters.toList(), result).also { waiters.clear() }
    }
}

/** Routes results through the configured callback executor and never strands a queued callback on close. */
internal class ClientResultDispatcher(
    private val callbackExecutor: Executor,
    private val isClosed: () -> Boolean
) {
    fun <T> dispatch(callback: (Result<T>) -> Unit, result: Result<T>, isCurrent: () -> Boolean = { true }) {
        callbackExecutor.execute {
            val delivered = when {
                isClosed() -> Result.failure(IllegalStateException(RearSdkErrorCode.CLIENT_CLOSED))
                !isCurrent() -> Result.failure(IllegalStateException(RearSdkErrorCode.SERVICE_DISCONNECTED))
                else -> result
            }
            runCatching { callback(delivered) }
        }
    }
}

/** Events are ignored when the callback Binder that queued them belongs to a retired connection epoch. */
internal class ClientEventDispatcher(
    private val callbackExecutor: Executor,
    private val isClosed: () -> Boolean,
    private val isCurrent: (Any) -> Boolean
) {
    fun dispatch(epoch: Any, action: () -> Unit) {
        callbackExecutor.execute {
            if (!isClosed() && isCurrent(epoch)) runCatching(action)
        }
    }
}

/** Worker queue used by Binder methods; queued work is cancelled by client close and reports that cancellation. */
internal class ClientOperationQueue(
    private val worker: Executor,
    private val results: ClientResultDispatcher,
    private val isClosed: () -> Boolean,
    private val isCurrent: (Any) -> Boolean,
    private val onInvalidatedOperationFinished: (Any) -> Unit = {}
) {
    private val closed = AtomicBoolean(false)
    private val pending = ConcurrentHashMap.newKeySet<Pending<*>>()

    private inner class Pending<T>(
        val epoch: Any,
        val action: () -> T,
        val callback: (Result<T>) -> Unit
    ) {
        private val state = AtomicInteger(0) // queued, running, completed
        fun begin(): Boolean = state.compareAndSet(0, 1)
        fun cancel() = complete(Result.failure(IllegalStateException(RearSdkErrorCode.CLIENT_CLOSED)))
        fun complete(result: Result<T>) {
            if (state.getAndSet(2) == 2) return
            pending.remove(this)
            results.dispatch(callback, result) { isCurrent(epoch) }
        }
    }

    fun <T> submit(epoch: Any, action: () -> T, callback: (Result<T>) -> Unit) {
        val operation = Pending(epoch, action, callback)
        if (closed.get() || isClosed()) {
            operation.complete(Result.failure(IllegalStateException(RearSdkErrorCode.CLIENT_CLOSED)))
            return
        }
        pending.add(operation)
        if (closed.get() || isClosed()) {
            operation.complete(Result.failure(IllegalStateException(RearSdkErrorCode.CLIENT_CLOSED)))
            return
        }
        try {
            worker.execute {
                if (!operation.begin()) return@execute
                var invoked = false
                val result = try {
                    when {
                        closed.get() || isClosed() -> Result.failure(IllegalStateException(RearSdkErrorCode.CLIENT_CLOSED))
                        !isCurrent(epoch) -> Result.failure(IllegalStateException(RearSdkErrorCode.SERVICE_DISCONNECTED))
                        else -> {
                            invoked = true
                            runCatching(action).recoverCatching { error ->
                                if (error is RemoteException) throw IllegalStateException(RearSdkErrorCode.SERVICE_DISCONNECTED, error)
                                throw error
                            }
                        }
                    }
                } finally {
                    if (invoked && (closed.get() || isClosed() || !isCurrent(epoch))) {
                        runCatching { onInvalidatedOperationFinished(epoch) }
                    }
                }
                operation.complete(result)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            operation.complete(Result.failure(IllegalStateException(
                if (isClosed()) RearSdkErrorCode.CLIENT_CLOSED else RearSdkErrorCode.SERVICE_UNAVAILABLE
            )))
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.toList().forEach { it.cancel() }
    }
}

/** Coordinates nonblocking close effects without waiting for the Binder operation queue to drain. */
internal class ClientCloseSequence(
    private val operations: ClientOperationQueue,
    private val unbind: () -> Unit,
    private val scheduleHostRelease: () -> Unit,
    private val shutdownWorker: () -> Unit
) {
    fun close() {
        operations.close()
        scheduleHostRelease()
        unbind()
        shutdownWorker()
    }
}
