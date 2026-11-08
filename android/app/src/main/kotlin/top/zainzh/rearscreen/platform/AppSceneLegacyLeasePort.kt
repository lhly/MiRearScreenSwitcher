package top.zainzh.rearscreen.platform

import top.zainzh.rearscreen.SceneLegacyServiceLease

/** Production adapter; tests can inject an isolated port without mutating process-global service state. */
internal object AppSceneLegacyLeasePort : SceneLegacyLeasePort {
    override fun acquire(sessionId: String): Boolean = SceneLegacyServiceLease.acquire(sessionId)

    override fun release(sessionId: String, restorePriorMode: Boolean) {
        SceneLegacyServiceLease.release(sessionId, restorePriorMode)
    }
}
