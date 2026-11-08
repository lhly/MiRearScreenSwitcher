package dev.remrss.sample

/**
 * Keeps the Activity's measured window state until the Core lease is READY, then reports the
 * current state exactly once. Window visibility can arrive before the first READY callback.
 */
internal class SceneVisibilityBootstrap {
    class Report internal constructor(val windowEpoch: Long, val sequence: Long, val visible: Boolean)

    private var leaseReady = false
    private var hasWindowSample = false
    private var windowVisible = false
    private var revoked = false
    private var windowEpoch = 0L
    private var sequence = 0L
    private var lastSubmitted: Report? = null

    @Synchronized
    fun beginWindowEpoch() {
        windowEpoch++
        revoked = false
        leaseReady = false
        hasWindowSample = false
        lastSubmitted = null
    }

    @Synchronized
    fun onWindowVisibility(visible: Boolean): Report? {
        hasWindowSample = true
        windowVisible = visible
        return nextReport()
    }

    @Synchronized
    fun onLeaseReady(): Report? {
        leaseReady = true
        return nextReport()
    }

    /** A Core rejection must allow a later current-state reconciliation to retry the same edge. */
    @Synchronized
    fun onReportFinished(report: Report, accepted: Boolean) {
        if (report.windowEpoch == windowEpoch && !accepted && lastSubmitted == report) lastSubmitted = null
    }

    @Synchronized
    fun revoke() {
        windowEpoch++
        revoked = true
        leaseReady = false
        hasWindowSample = false
        windowVisible = false
        lastSubmitted = null
    }

    @Synchronized
    fun currentWindowVisibility(): Boolean? = if (hasWindowSample) windowVisible else null

    private fun nextReport(): Report? {
        if (revoked || !leaseReady || !hasWindowSample || lastSubmitted?.visible == windowVisible) return null
        return Report(windowEpoch, ++sequence, windowVisible).also { lastSubmitted = it }
    }
}

/** The sample renders content only when both Core permission and its measured window are visible. */
internal fun sceneBlackCoverVisible(coreVisible: Boolean, windowActuallyVisible: Boolean): Boolean =
    !(coreVisible && windowActuallyVisible)
