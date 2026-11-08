package top.zainzh.rearscreen.platform.ipc

import top.zainzh.rearscreen.platform.DataLeaseDeliveryGate
import java.io.Closeable

/** Two-phase IPC subscription ownership: close is terminal, even before provider observe returns. */
internal class DataSubscriptionLease<T : Any> {
    private val deliveryGate = DataLeaseDeliveryGate()
    private var source: Closeable? = null
    private var activated = false
    private var hasPending = false
    private var pending: T? = null

    /** Retain only the latest pre-activation value; otherwise deliver in close/revoke order. */
    fun deliver(value: T, emit: (T) -> Unit): Boolean = deliveryGate.deliver {
        if (activated) {
            emit(value)
        } else {
            pending = value
            hasPending = true
        }
    }

    /**
     * Attach the provider handle after observe() returns. If close won first, dispose the late
     * handle immediately and never replay buffered data or reopen this lease.
     */
    fun activate(handle: Closeable, emit: (T) -> Unit): Boolean {
        var accepted = false
        val entered = deliveryGate.deliver {
            source = handle
            activated = true
            accepted = true
            if (hasPending) {
                val first = pending
                pending = null
                hasPending = false
                if (first != null) emit(first)
            }
        }
        if (!entered || !accepted) handle.close()
        return entered && accepted
    }

    /**
     * Idempotently commits terminal state under the delivery gate, then closes the provider
     * reference outside that gate. Core provider callbacks acquire their own gate before entering
     * this lease, so nesting source.close() under this lock would create an ABBA deadlock.
     */
    fun close(): Boolean {
        var owned: Closeable? = null
        val closed = deliveryGate.close {
            owned = source
            source = null
            pending = null
            hasPending = false
        }
        if (closed) owned?.close()
        return closed
    }

    val isClosed: Boolean get() = deliveryGate.isClosed
}
