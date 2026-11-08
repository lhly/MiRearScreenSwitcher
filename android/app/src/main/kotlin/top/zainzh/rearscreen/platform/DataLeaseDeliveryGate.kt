package top.zainzh.rearscreen.platform

/** Orders provider callback application against lease close/revocation. */
internal class DataLeaseDeliveryGate {
    private val lock = Any()
    private var closed = false

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** [deliver] includes grant revalidation and the final consumer callback. */
    fun deliver(deliver: () -> Unit): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        deliver()
        true
    }

    /** The optional revoke callback executes once and is ordered after any in-flight delivery. */
    fun close(onRevoke: (() -> Unit)? = null): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        closed = true
        onRevoke?.invoke()
        true
    }
}
