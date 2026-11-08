package dev.remrss.sample

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

/** Application-scoped business state; observers exist only while each screen is visible. */
class ScoreboardState {
    private val lock = Any()
    private var value = 0
    private val observers = CopyOnWriteArraySet<(Int) -> Unit>()

    fun current(): Int = synchronized(lock) { value }

    fun addPoint(): Int {
        val next = synchronized(lock) { ++value }
        observers.forEach { it(next) }
        return next
    }

    fun observe(observer: (Int) -> Unit): Closeable {
        observers += observer
        observer(current())
        return Closeable { observers -= observer }
    }
}
