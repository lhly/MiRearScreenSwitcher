package top.zainzh.rearscreen.platform.ipc

import android.os.RemoteException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Ensures confirmation work cannot disappear in an uncaught worker exception. */
internal object ActivationWorkRunner {
    private val codePattern = Regex("E_[A-Z0-9_]{1,48}")

    fun execute(executor: Executor, onFailure: (String) -> Unit, work: () -> Unit) {
        val guarded = Runnable {
            try { work() } catch (error: Throwable) { onFailure(errorCode(error)) }
        }
        try { executor.execute(guarded) }
        catch (error: Throwable) { onFailure(errorCode(error)) }
    }

    fun errorCode(error: Throwable): String {
        val explicit = error.message?.let(codePattern::find)?.value
        if (explicit != null) return explicit
        return when (error) {
            is SecurityException -> "E_CAPABILITY_DENIED"
            is RemoteException -> "E_BACKEND_UNAVAILABLE"
            is RejectedExecutionException -> "E_SERVICE_UNAVAILABLE"
            else -> "E_INVALID_STATE"
        }
    }
}
