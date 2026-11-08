package dev.remrss.sample

import java.io.Closeable

/** A single in-flight first-READY report per one-use launch ticket, shared across Activity recreation. */
class TicketReadyGate {
    enum class Completion { RETRYABLE_FAILURE, SUCCEEDED, TERMINAL_FAILURE }

    private val lock = Any()
    private val inFlight = mutableSetOf<String>()
    private val completedSuccessfully = mutableSetOf<String>()
    private val terminal = mutableSetOf<String>()
    private val retryWaiters = mutableMapOf<String, MutableSet<() -> Unit>>()

    fun begin(ticket: String): Boolean = synchronized(lock) {
        ticket.isNotBlank() && ticket !in completedSuccessfully && ticket !in terminal && inFlight.add(ticket)
    }

    fun complete(ticket: String, completion: Completion) {
        val waiters = synchronized(lock) {
            inFlight.remove(ticket)
            when (completion) {
                Completion.RETRYABLE_FAILURE -> Unit
                Completion.SUCCEEDED -> completedSuccessfully.add(ticket)
                Completion.TERMINAL_FAILURE -> terminal.add(ticket)
            }
            if (completion == Completion.RETRYABLE_FAILURE) retryWaiters.remove(ticket).orEmpty().toList() else emptyList()
        }
        waiters.forEach { runCatching(it) }
    }

    fun markReady(ticket: String) {
        synchronized(lock) {
            inFlight.remove(ticket)
            completedSuccessfully.add(ticket)
            retryWaiters.remove(ticket)
        }
    }

    fun markClosed(ticket: String) {
        synchronized(lock) {
            inFlight.remove(ticket)
            terminal.add(ticket)
            retryWaiters.remove(ticket)
        }
    }

    /** A replacement Activity waits for the current ticket attempt instead of racing it. */
    fun whenRetryable(ticket: String, observer: () -> Unit): Closeable {
        val shouldNotifyNow = synchronized(lock) {
            when {
                ticket in completedSuccessfully || ticket in terminal -> false
                ticket !in inFlight -> true
                else -> {
                    retryWaiters.getOrPut(ticket) { linkedSetOf() }.add(observer)
                    false
                }
            }
        }
        if (shouldNotifyNow) observer()
        return Closeable {
            synchronized(lock) {
                retryWaiters[ticket]?.let { waiters ->
                    waiters.remove(observer)
                    if (waiters.isEmpty()) retryWaiters.remove(ticket)
                }
            }
        }
    }
}
