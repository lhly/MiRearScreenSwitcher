package top.zainzh.rearscreen

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTaskLaunchFlowTest {
    private val component = "dev.remrss.sample/dev.remrss.sample.RearSceneActivity"

    @Test fun exactFreshRearTaskWinsEvenIfAmStatusIsErrorAndPrimaryTaskIsRetained() {
        val before = listOf(record(51, 0, component), record(10, 0, "app/.MainActivity"))
        val after = before + record(72, 2, component)
        val runner = FakeRunner(SceneTaskShellRunner.Result("Error: status text is not authority", 1, false, false, null))
        val result = launch(listOf(snapshot(before), snapshot(after)), runner)

        assertEquals(72, result.taskId)
        assertEquals("exit_1", result.commandStatus)
        assertEquals(1, result.beforeTargetCount)
        assertEquals(2, result.afterTargetCount)
        val command = requireNotNull(runner.command)
        assertTrue(command.contains("-f 0x18000000"))
        assertFalse(command.contains("--activity-new-task"))
        assertTrue(command.contains("-n 'dev.remrss.sample/dev.remrss.sample.RearSceneActivity'"))
        assertTrue(command.contains("--es remrss_scene_ticket 'ticket-12345678901234567890'"))
        assertEquals(1, result.lastSearch.freshExpectedDisplayCount)
        assertTrue(result.diagnostic().contains("command_marker=reported_error"))
        assertFalse("diagnostics never include the launch ticket", result.diagnostic().contains("ticket-12345678901234567890"))
    }

    @Test fun statusOkWithoutUniqueTargetTaskIsNotAccepted() {
        val sameTasks = listOf(record(51, 0, component))
        val runner = FakeRunner(SceneTaskShellRunner.Result("Status: ok\n", 0, false, false, null))
        val clock = FakeTime()
        val result = SceneTaskLaunchFlow.launch(component, 2, "ticket-12345678901234567890",
            QueueSnapshots(listOf(snapshot(sameTasks), snapshot(sameTasks))), runner::run, noOpCleanup, clock)

        assertEquals(-1, result.taskId)
        assertEquals("no_unique_fresh_target_task", result.failure)
        assertEquals("status_ok", result.commandStatus)
        assertEquals(3000L, clock.sleptMs)
    }

    @Test fun preexistingMainTaskMovedToRearNeverBecomesFreshSceneOwnership() {
        val before = listOf(record(51, 0, component))
        val after = listOf(record(51, 2, component))
        val result = launch(listOf(snapshot(before), snapshot(after)), FakeRunner(successResult()))

        assertEquals(-1, result.taskId)
        assertEquals("no_unique_fresh_target_task", result.failure)
        assertEquals(1, result.lastSearch.targetCount)
        assertEquals(0, result.lastSearch.freshTargetCount)
    }

    @Test fun multipleFreshTargetsAndWrongDisplayAreRefusedWithDistinctReasons() {
        val before = listOf(record(51, 0, component))
        val multiple = listOf(record(51, 0, component), record(72, 2, component), record(73, 2, component))
        val ambiguous = launch(listOf(snapshot(before), snapshot(multiple)), FakeRunner(successResult()))
        assertEquals(-1, ambiguous.taskId)
        assertEquals("ambiguous_fresh_target_tasks", ambiguous.failure)

        val wrongDisplay = launch(listOf(snapshot(before), snapshot(before + record(72, 0, component))), FakeRunner(successResult()))
        assertEquals(-1, wrongDisplay.taskId)
        assertEquals("fresh_target_wrong_display", wrongDisplay.failure)
    }

    @Test fun uniqueWrongDisplayFreshTaskIsRemovedByExactIdentityAndOldMainIsPreserved() {
        val oldMain = record(51, 0, component)
        val otherOldMain = record(10, 0, "app/.MainActivity")
        val orphan = record(72, 0, component)
        val cleanup = RecordingCleanup(SceneTaskLaunchFlow.CleanupResult(true, "removed_and_absent"))
        val result = SceneTaskLaunchFlow.launch(component, 2, "ticket-12345678901234567890",
            QueueSnapshots(listOf(snapshot(listOf(oldMain, otherOldMain)), snapshot(listOf(oldMain, otherOldMain, orphan)))),
            FakeRunner(successResult())::run, cleanup, FakeTime())

        assertEquals(-1, result.taskId)
        assertEquals("fresh_target_wrong_display", result.failure)
        assertEquals(72, cleanup.taskId)
        assertEquals(component, cleanup.component)
        assertEquals(0, cleanup.actualDisplayId)
        assertEquals("removed_removed_and_absent", result.cleanupStatus)
        assertTrue(result.diagnostic().contains("cleanup=removed_removed_and_absent"))
        assertEquals(listOf(51, 10, 72), listOf(oldMain.taskId, otherOldMain.taskId, orphan.taskId))
    }

    @Test fun wrongDisplayCleanupFailureIsReportedWithoutClaimingRemoval() {
        val oldMain = record(51, 0, component)
        val orphan = record(72, 0, component)
        val cleanup = RecordingCleanup(SceneTaskLaunchFlow.CleanupResult(false, "owner_display_mismatch"))
        val result = SceneTaskLaunchFlow.launch(component, 2, "ticket-12345678901234567890",
            QueueSnapshots(listOf(snapshot(listOf(oldMain)), snapshot(listOf(oldMain, orphan)))),
            FakeRunner(successResult())::run, cleanup, FakeTime())

        assertEquals(-1, result.taskId)
        assertEquals(72, cleanup.taskId)
        assertEquals(0, cleanup.actualDisplayId)
        assertEquals("failed_owner_display_mismatch", result.cleanupStatus)
        assertFalse(result.cleanupStatus.contains("removed"))
    }

    @Test fun beforeTaskIdNeverBecomesFreshWhenItsBaseChanges() {
        val beforeMain = record(51, 0, "app/.MainActivity")
        val afterChangedBase = record(51, 0, component)
        val cleanup = RecordingCleanup(SceneTaskLaunchFlow.CleanupResult(true, "removed_and_absent"))
        val result = SceneTaskLaunchFlow.launch(component, 2, "ticket-12345678901234567890",
            QueueSnapshots(listOf(snapshot(listOf(beforeMain)), snapshot(listOf(afterChangedBase)))),
            FakeRunner(successResult())::run, cleanup, FakeTime())

        assertEquals(-1, result.taskId)
        assertEquals("no_unique_fresh_target_task", result.failure)
        assertEquals("not_needed", result.cleanupStatus)
        assertNull(cleanup.taskId)
    }

    @Test fun unusableBeforeSnapshotDoesNotIssueLaunchCommand() {
        val runner = FakeRunner(successResult())
        val result = SceneTaskLaunchFlow.launch(component, 2, "ticket-12345678901234567890",
            QueueSnapshots(listOf(SceneTaskLaunchFlow.TaskSnapshot.unavailable("am_stack_list", "parse_error"))),
            runner::run, noOpCleanup, FakeTime())

        assertEquals(-1, result.taskId)
        assertEquals("before_snapshot_parse_error", result.failure)
        assertFalse(runner.called)
    }

    @Test fun commandTimeoutIsPreservedWhenNoFreshTaskAppears() {
        val tasks = listOf(record(51, 0, component))
        val runner = FakeRunner(SceneTaskShellRunner.Result("partial output", -1, true, false, null))
        val result = launch(listOf(snapshot(tasks), snapshot(tasks)), runner)

        assertEquals(-1, result.taskId)
        assertEquals("timeout", result.commandStatus)
        assertTrue(result.commandTimedOut)
        assertEquals("start_timeout_no_unique_fresh_task", result.failure)
    }

    private fun launch(snapshots: List<SceneTaskLaunchFlow.TaskSnapshot>, runner: FakeRunner) =
        SceneTaskLaunchFlow.launch(component, 2, "ticket-12345678901234567890",
            QueueSnapshots(snapshots), runner::run, noOpCleanup, FakeTime())

    private fun record(id: Int, display: Int, base: String) =
        SceneTaskIdentity.Record(id, display, base, base)

    private fun snapshot(records: List<SceneTaskIdentity.Record>) =
        SceneTaskLaunchFlow.TaskSnapshot.available(records, "fake-all-displays")

    private fun successResult() = SceneTaskShellRunner.Result("Status: ok\n", 0, false, false, null)

    private val noOpCleanup = SceneTaskLaunchFlow.TaskCleanup { _, _, _ ->
        SceneTaskLaunchFlow.CleanupResult(true, "not_needed")
    }

    private class QueueSnapshots(values: List<SceneTaskLaunchFlow.TaskSnapshot>) : SceneTaskLaunchFlow.TaskSnapshots {
        private val snapshots = ArrayDeque(values)
        private var last: SceneTaskLaunchFlow.TaskSnapshot? = null
        override fun read(): SceneTaskLaunchFlow.TaskSnapshot {
            val next = snapshots.pollFirst()
            if (next != null) last = next
            return next ?: last
            ?: SceneTaskLaunchFlow.TaskSnapshot.unavailable("fake", "missing_snapshot")
        }
    }

    private class FakeRunner(private val result: SceneTaskShellRunner.Result) {
        var called = false
        var command: String? = null
        fun run(command: String, timeoutMs: Long): SceneTaskShellRunner.Result {
            called = true
            this.command = command
            assertEquals(SceneTaskLaunchFlow.START_TIMEOUT_MS, timeoutMs)
            return result
        }
    }

    private class FakeTime : SceneTaskLaunchFlow.Time {
        private var now = 0L
        var sleptMs = 0L
        override fun elapsedRealtime() = now
        override fun sleep(millis: Long) { now += millis; sleptMs += millis }
    }

    private class RecordingCleanup(private val result: SceneTaskLaunchFlow.CleanupResult) : SceneTaskLaunchFlow.TaskCleanup {
        var taskId: Int? = null
        var component: String? = null
        var actualDisplayId: Int? = null
        override fun remove(taskId: Int, component: String, actualDisplayId: Int): SceneTaskLaunchFlow.CleanupResult {
            this.taskId = taskId
            this.component = component
            this.actualDisplayId = actualDisplayId
            return result
        }
    }
}
