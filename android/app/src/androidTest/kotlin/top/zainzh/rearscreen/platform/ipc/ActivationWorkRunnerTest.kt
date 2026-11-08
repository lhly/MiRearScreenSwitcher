package top.zainzh.rearscreen.platform.ipc

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@RunWith(AndroidJUnit4::class)
class ActivationWorkRunnerTest {
    @Test fun workerExceptionAlwaysProducesOneBoundedFailure() {
        val failures = mutableListOf<String>()
        ActivationWorkRunner.execute(Executor { it.run() }, failures::add) { throw IllegalStateException("E_TASK_QUERY_TIMEOUT " + "x".repeat(2000)) }
        assertEquals(listOf("E_TASK_QUERY_TIMEOUT"), failures)
    }

    @Test fun rejectedWorkerSubmissionAlsoCompletesWithBoundedFailure() {
        val failures = mutableListOf<String>()
        ActivationWorkRunner.execute(Executor { throw RejectedExecutionException("closed") }, failures::add) { error("must not run") }
        assertEquals(listOf("E_SERVICE_UNAVAILABLE"), failures)
    }
}
