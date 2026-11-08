package top.zainzh.rearscreen.platform

/** Consumer-side monotonic gate for asynchronous Core presentation snapshots. */
internal class ScenePresentationGate(private val sessionId: String, private val generation: Long) {
    private var appliedRevision = 0L
    private var currentVisible = false

    @Synchronized fun apply(snapshot: ScenePresentationSnapshot): Boolean {
        if (snapshot.sessionId != sessionId || snapshot.generation != generation || snapshot.revision < appliedRevision) return false
        // A restarted Activity can clear its local surface before re-querying unchanged Core state.
        // Accept that exact same revision idempotently, but never accept conflicting state at it.
        if (snapshot.revision == appliedRevision) return snapshot.visible == currentVisible
        appliedRevision = snapshot.revision
        currentVisible = snapshot.visible
        return true
    }

    @Synchronized fun visible(): Boolean = currentVisible
    @Synchronized fun revision(): Long = appliedRevision
}
