package top.zainzh.rearscreen.platform.mods

import java.util.concurrent.atomic.AtomicLong

/** Rejects replies from a worker generation after its slot is rebound. */
internal class ModRuntimeEpochGate(initial: Long) {
    private val value = AtomicLong(initial)
    val current: Long get() = value.get()
    fun advance(next: Long) { value.set(next) }
    fun accepts(responseEpoch: Long): Boolean = responseEpoch == value.get()
}
