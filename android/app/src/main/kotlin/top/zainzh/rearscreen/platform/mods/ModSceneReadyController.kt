package top.zainzh.rearscreen.platform.mods

import android.os.Handler
import android.os.Looper
import top.zainzh.rearscreen.platform.DisplaySessionCoordinator
import top.zainzh.rearscreen.platform.SceneError
import top.zainzh.rearscreen.platform.SceneLaunchContext
import top.zainzh.rearscreen.platform.SceneOperationResult
import top.zainzh.rearscreen.platform.ScenePresentationSnapshot
import top.zainzh.rearscreen.platform.SceneWindowReport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The initial READY ticket is process-scoped so Activity recreation cannot fork its one-use call. */
internal class ModSceneReadyController(
    private val launch: SceneLaunchContext,
    private val ticket: String,
    private val port: Port,
    private val ioExecutor: Executor = SharedIo,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    internal interface Port {
        fun query(): ScenePresentationSnapshot?
        fun reportReady(ticket: String, report: SceneWindowReport): SceneOperationResult
        fun reportViewport(report: SceneWindowReport): SceneOperationResult
    }

    internal data class Completion(
        val instance: Long,
        val windowEpoch: Long,
        val result: SceneOperationResult,
    )

    private data class Request(
        val instance: Long,
        val report: SceneWindowReport,
        val windowEpoch: Long,
        val active: Boolean,
        val callback: ((Completion) -> Unit)?,
        val serial: Long,
    )

    private data class AttemptOutcome(val result: SceneOperationResult, val reportedSerial: Long?)

    private val lock = Any()
    private var current: Request? = null
    private var requestSerial = 0L
    private var inFlight = false
    private var readyCommitted = false
    private var terminal = false
    private var attempts = 0
    private var retry: Runnable? = null

    /** Replace the current Activity request; the in-flight ticket call is never cancelled/replayed. */
    fun submit(
        instance: Long,
        report: SceneWindowReport,
        windowEpoch: Long,
        active: Boolean,
        callback: (Completion) -> Unit,
    ) {
        synchronized(lock) {
            if (terminal) {
                mainHandler.post { callback(Completion(instance, windowEpoch, terminalResult())) }
                return
            }
            current = Request(instance, report, windowEpoch, active, callback, ++requestSerial)
            if (active) cancelRetryLocked()
        }
        pump()
    }

    /** Stop only detaches a window request. An already-issued READY may still consume its ticket. */
    fun setActive(instance: Long, active: Boolean) {
        synchronized(lock) {
            val old = current?.takeIf { it.instance == instance } ?: return
            current = old.copy(active = active, callback = if (active) old.callback else null, serial = ++requestSerial)
            if (!active) cancelRetryLocked()
        }
        if (active) pump()
    }

    fun detach(instance: Long) {
        synchronized(lock) {
            if (current?.instance != instance) return
            current = current?.copy(active = false, callback = null, serial = ++requestSerial)
            cancelRetryLocked()
        }
    }

    fun markClosed() {
        synchronized(lock) {
            if (terminal) return
            terminal = true
            cancelRetryLocked()
            current = null
        }
    }

    private fun pump() {
        val request = synchronized(lock) {
            if (terminal || inFlight || retry != null) return
            current?.takeIf { it.active && it.callback != null }?.also { inFlight = true }
        } ?: return

        ioExecutor.execute {
            val outcome = runCatching { perform(request) }
                .getOrElse { AttemptOutcome(SceneOperationResult(false, SceneError.BACKEND_UNAVAILABLE, retryable = true), null) }
            mainHandler.post { finishAttempt(outcome) }
        }
    }

    private fun perform(startingRequest: Request): AttemptOutcome {
        var readyReportedHere = false
        if (!isReady()) {
            val ready = port.reportReady(ticket, startingRequest.report)
            if (ready.success) {
                readyReportedHere = true
                synchronized(lock) { readyCommitted = true }
            } else if (ready.error == SceneError.STALE_SESSION && isReady()) {
                // Another Activity/process may have consumed the one-shot ticket. A live,
                // queryable presentation is the only evidence that permits viewport refresh.
                synchronized(lock) { readyCommitted = true }
            } else {
                return AttemptOutcome(ready, null)
            }
        }

        val latest = synchronized(lock) { current?.takeIf { !terminal && it.active } }
            ?: return AttemptOutcome(SceneOperationResult(true), null)
        if (readyReportedHere && latest.serial == startingRequest.serial) {
            return AttemptOutcome(SceneOperationResult(true), latest.serial)
        }
        return AttemptOutcome(port.reportViewport(latest.report), latest.serial)
    }

    private fun isReady(): Boolean {
        if (synchronized(lock) { readyCommitted }) return true
        val snapshot = port.query() ?: return false
        val valid = snapshot.sessionId == launch.sessionId && snapshot.generation == launch.generation
        if (valid) synchronized(lock) { readyCommitted = true }
        return valid
    }

    private fun finishAttempt(outcome: AttemptOutcome) {
        val result = outcome.result
        val next: Request?
        val retryAttempt: Boolean
        val completion: Completion?
        synchronized(lock) {
            inFlight = false
            if (terminal) return
            val latest = current?.takeIf { it.active && it.callback != null }
            if (result.success && latest != null && latest.serial != outcome.reportedSerial) {
                // The report changed while Binder was in flight. Publish the latest measured
                // viewport before declaring this Activity ready.
                next = latest
                retryAttempt = false
                completion = null
            } else if (!result.success && result.retryable && attempts < MAX_ATTEMPTS && latest != null) {
                attempts++
                next = null
                retryAttempt = true
                completion = null
            } else if (!result.success && result.retryable && latest == null) {
                // Stop/recreation detached the current window while Core was pending. Keep
                // the ticket state nonterminal; the next Activity submits a fresh report.
                next = null
                retryAttempt = false
                completion = null
            } else {
                if (!result.success) terminal = true
                next = null
                retryAttempt = false
                completion = latest?.let { Completion(it.instance, it.windowEpoch, result) }
            }
            if (result.success) attempts = 0
        }

        if (next != null) {
            pump()
            return
        }
        if (retryAttempt) {
            scheduleRetry()
            return
        }
        if (completion != null) {
            val callback = synchronized(lock) { current?.takeIf { it.instance == completion.instance && it.serial == requestSerial }?.callback }
            callback?.invoke(completion)
        }
    }

    private fun scheduleRetry() {
        val runnable = Runnable {
            synchronized(lock) { retry = null }
            pump()
        }
        synchronized(lock) {
            if (terminal || current?.active != true || retry != null) return
            retry = runnable
            mainHandler.postDelayed(runnable, RETRY_DELAY_MS)
        }
    }

    private fun cancelRetryLocked() {
        retry?.let(mainHandler::removeCallbacks)
        retry = null
    }

    private fun terminalResult() = SceneOperationResult(false, SceneError.STALE_SESSION, retryable = false)

    private object SharedIo : Executor {
        private val delegate = Executors.newCachedThreadPool { task ->
            Thread(task, "ModSceneReady").apply { isDaemon = true }
        }
        override fun execute(command: Runnable) = delegate.execute(command)
    }

    companion object {
        private const val MAX_ATTEMPTS = 20
        private const val RETRY_DELAY_MS = 150L
        private val controllers = ConcurrentHashMap<String, ModSceneReadyController>()

        fun forLaunch(launch: SceneLaunchContext, ticket: String, coordinator: DisplaySessionCoordinator): ModSceneReadyController? {
            controllers[ticket]?.let { return it.takeIf { existing -> existing.launch == launch } }
            val created = ModSceneReadyController(launch, ticket, coordinator.asReadyPort(launch))
            val selected = controllers.putIfAbsent(ticket, created) ?: created
            return selected.takeIf { it.launch == launch }
        }

        fun markClosed(ticket: String?) {
            if (ticket.isNullOrBlank()) return
            controllers.remove(ticket)?.markClosed()
        }

        private fun DisplaySessionCoordinator.asReadyPort(launch: SceneLaunchContext) = object : Port {
            override fun query(): ScenePresentationSnapshot? =
                queryScenePresentation(launch.owner, launch.sessionId, launch.generation)

            override fun reportReady(ticket: String, report: SceneWindowReport): SceneOperationResult =
                reportSceneWindowReady(launch.owner, ticket, report)

            override fun reportViewport(report: SceneWindowReport): SceneOperationResult =
                reportSceneViewport(launch.owner, launch.sessionId, launch.generation, report)
        }
    }
}
