package dev.remrss.sample

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

/** Application-scoped observable status; subscriptions are attached only while a screen is visible. */
class SampleStatus(initial: String) {
    @Volatile private var value = initial
    private val observers = CopyOnWriteArraySet<(String) -> Unit>()

    fun current(): String = value

    fun publish(next: String) {
        value = next
        observers.forEach { it(next) }
    }

    fun observe(observer: (String) -> Unit): Closeable {
        observers += observer
        observer(value)
        return Closeable { observers -= observer }
    }
}
