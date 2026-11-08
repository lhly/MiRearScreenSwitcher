package top.zainzh.rearscreen.platform.mods

import java.util.concurrent.atomic.AtomicLong

/** Prevents callbacks already queued on Main from repopulating a closed data lease. */
internal class ModDataSubscriptionEpoch {
    private val current = AtomicLong(0)

    fun start(): Long = current.incrementAndGet()

    fun stop(): Long = current.incrementAndGet()

    fun isCurrent(epoch: Long): Boolean = epoch == current.get()
}
