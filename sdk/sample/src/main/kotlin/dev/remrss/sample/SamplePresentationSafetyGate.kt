package dev.remrss.sample

/** Keeps a Scene hidden until a fresh, accepted Core presentation arrives after a connection loss. */
internal class SamplePresentationSafetyGate {
    private var queryEpoch = 0L
    @Volatile private var awaitingSnapshot = false

    @Synchronized
    fun requireCurrentSnapshot() {
        awaitingSnapshot = true
        queryEpoch++
    }

    @Synchronized
    fun beginQuery(connected: Boolean): Long? = if (connected) ++queryEpoch else null

    @Synchronized
    fun invalidateQueries() {
        queryEpoch++
    }

    @Synchronized
    fun isCurrent(epoch: Long): Boolean = epoch == queryEpoch

    @Synchronized
    fun acceptCurrentSnapshot(epoch: Long, leaseIsCurrent: Boolean, snapshotAccepted: Boolean): Boolean {
        if (epoch != queryEpoch || !leaseIsCurrent || !snapshotAccepted) return false
        awaitingSnapshot = false
        return true
    }

    fun canPresent(coreVisible: Boolean): Boolean = coreVisible && !awaitingSnapshot

    fun publishVisibility(coreVisible: Boolean, observers: Iterable<(Boolean) -> Unit>): Boolean {
        val safeVisible = canPresent(coreVisible)
        observers.forEach { observer -> runCatching { observer(safeVisible) } }
        return safeVisible
    }

}
