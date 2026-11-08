package top.zainzh.rearscreen.platform

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Reusable handoff for asynchronous Android ServiceConnection callbacks. */
internal class ServiceConnectionSignal<T : Any> {
    private val lock = Any()
    private var active: T? = null
    private var connected = CompletableFuture<T>()

    fun current(): T? = synchronized(lock) { active }

    fun isAwaitingConnection(): Boolean = synchronized(lock) { active == null && !connected.isDone }

    fun onConnected(service: T) = synchronized(lock) {
        active = service
        connected.complete(service)
    }

    fun onDisconnected() = synchronized(lock) {
        active = null
        if (connected.isDone) connected = CompletableFuture()
    }

    fun await(timeoutMs: Long): T? {
        val pending = synchronized(lock) { active?.let { return it } ?: connected }
        return try {
            pending.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }
    }
}
