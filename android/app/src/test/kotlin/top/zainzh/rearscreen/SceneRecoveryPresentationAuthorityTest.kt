package top.zainzh.rearscreen

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import top.zainzh.rearscreen.platform.SceneError
import top.zainzh.rearscreen.platform.sceneRecoveryResultForPresentationStatus

class SceneRecoveryPresentationAuthorityTest {
    private val taskId = 56091
    private val displayId = 1
    private val scene = "dev.remrss.sample/dev.remrss.sample.RearSceneActivity"

    @Test fun visibleTaskAndCachedOnCannotSucceedWhenPhysicalRearIsDozing() {
        var focusCalls = 0
        val snapshots = QueuedSnapshots(visibleTask(), visibleTask())
        val result = SceneTaskPresentationFlow.present(taskId, scene, displayId, snapshots,
            { focusCalls++; SceneTaskPresentationFlow.FocusResult(true, 0, null) }, { false },
            { SceneTaskPresentationFlow.ActionGateResult.ALLOWED },
            { presentation -> SceneRecoveryPresentationAuthority.confirm(taskId, scene, displayId,
                { physicalState(Display.STATE_DOZE_SUSPEND) }, snapshots::read) { SceneTaskPresentationFlow.ActionGateResult.ALLOWED } })

        assertEquals("rear_display_not_on", result.status)
        assertEquals("physical rejection remains an await-ON coordinator result",
            true, sceneRecoveryResultForPresentationStatus(result.status).awaitDisplayOn)
        assertEquals("logical Task visibility cannot cause a focus or successful recovery", 0, focusCalls)
    }

    @Test fun actualOnExactSelectedRearAndSameCurrentTaskAllowOneNoWakeSuccess() {
        var focusCalls = 0
        var reads = 0
        val snapshots = QueuedSnapshots(visibleTask(), visibleTask())
        val preflight = SceneRecoveryPresentationAuthority.preflightRear(displayId) {
            reads++; physicalState(Display.STATE_ON)
        }
        val result = SceneTaskPresentationFlow.present(taskId, scene, displayId, snapshots,
            { focusCalls++; SceneTaskPresentationFlow.FocusResult(true, 0, null) }, { false },
            { SceneTaskPresentationFlow.ActionGateResult.ALLOWED },
            { SceneRecoveryPresentationAuthority.confirm(taskId, scene, displayId,
                { reads++; physicalState(Display.STATE_ON) }, snapshots::read) { SceneTaskPresentationFlow.ActionGateResult.ALLOWED } })

        assertNull(preflight)
        assertEquals("already_scene_visible", result.status)
        assertTrue("success status represents TaskService's physical-ON and exact-task authority",
            sceneRecoveryResultForPresentationStatus(result.status).verifiedPhysicalPresentation)
        assertEquals("preflight + two post-read physical checks", 3, reads)
        assertEquals("a verified ON return neither focuses nor wakes", 0, focusCalls)
    }

    @Test fun unknownOffAndReplacedPhysicalRearNeverReturnSuccess() {
        assertEquals("rear_state_unknown", SceneRecoveryPresentationAuthority.preflightRear(displayId) { "{}" })
        assertEquals("rear_state_unknown", SceneRecoveryPresentationAuthority.preflightRear(displayId) {
            "{\"selectedId\":1,\"selectedUnique\":\"panel-1\",\"displays\":[{\"id\":1,\"unique\":\"panel-1\"}]}"
        })
        assertEquals("rear_display_not_on", SceneRecoveryPresentationAuthority.preflightRear(displayId) {
            physicalState(Display.STATE_DOZE)
        })
        assertEquals("rear_display_replaced", SceneRecoveryPresentationAuthority.preflightRear(displayId) {
            physicalState(Display.STATE_ON, selectedId = 2, selectedUnique = "panel-2")
        })
    }

    @Test fun slowTaskReadCannotTurnDozeOrRevokedSceneIntoSuccess() {
        val taskStillCurrent = AtomicBoolean(true)
        var rearRead = 0
        val dozeAfterTaskRead = SceneRecoveryPresentationAuthority.confirm(taskId, scene, displayId,
            { if (rearRead++ == 0) physicalState(Display.STATE_ON) else physicalState(Display.STATE_DOZE) },
            { visibleTask() }, { if (taskStillCurrent.get()) SceneTaskPresentationFlow.ActionGateResult.ALLOWED
                else SceneTaskPresentationFlow.ActionGateResult.CANCELLED })
        assertEquals("rear_display_not_on", dozeAfterTaskRead)

        val revokedDuringTaskRead = SceneRecoveryPresentationAuthority.confirm(taskId, scene, displayId,
            { physicalState(Display.STATE_ON) },
            {
                taskStillCurrent.set(false)
                visibleTask()
            }, { if (taskStillCurrent.get()) SceneTaskPresentationFlow.ActionGateResult.ALLOWED
                else SceneTaskPresentationFlow.ActionGateResult.CANCELLED })
        assertEquals("cancelled_before_focus", revokedDuringTaskRead)
    }

    @Test fun unavailableLeaseTransportAtAlreadyVisibleSuccessIsBackendFailureNotCancellation() {
        val snapshots = QueuedSnapshots(visibleTask())
        val result = SceneTaskPresentationFlow.present(taskId, scene, displayId, snapshots,
            { SceneTaskPresentationFlow.FocusResult(true, 0, null) }, { false },
            { SceneTaskPresentationFlow.ActionGateResult.ALLOWED },
            { SceneRecoveryPresentationAuthority.confirm(taskId, scene, displayId,
                { physicalState(Display.STATE_ON) }, snapshots::read) { SceneTaskPresentationFlow.ActionGateResult.UNAVAILABLE } })

        assertEquals("action_gate_unavailable", result.status)
        val recovery = sceneRecoveryResultForPresentationStatus(result.status)
        assertEquals(SceneError.BACKEND_UNAVAILABLE, recovery.error)
        assertFalse(recovery.deferredByOverlay)
    }

    @Test fun unavailableLeaseTransportAfterFocusIsBackendFailureNotCancellation() {
        var focusCalls = 0
        val home = "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.Home"
        val snapshots = QueuedSnapshots(
            SceneTaskLaunchFlow.TaskSnapshot.available(listOf(
                SceneTaskIdentity.Record(taskId, displayId, scene, scene, false),
                SceneTaskIdentity.Record(42, displayId, home, home, true)), "before"),
            visibleTask(), visibleTask())
        val result = SceneTaskPresentationFlow.present(taskId, scene, displayId, snapshots,
            { focusCalls++; SceneTaskPresentationFlow.FocusResult(true, 0, null) }, { it == home },
            { SceneTaskPresentationFlow.ActionGateResult.ALLOWED },
            { SceneRecoveryPresentationAuthority.confirm(taskId, scene, displayId,
                { physicalState(Display.STATE_ON) }, snapshots::read) { SceneTaskPresentationFlow.ActionGateResult.UNAVAILABLE } })

        assertEquals(1, focusCalls)
        assertEquals("action_gate_unavailable", result.status)
        val recovery = sceneRecoveryResultForPresentationStatus(result.status)
        assertEquals(SceneError.BACKEND_UNAVAILABLE, recovery.error)
        assertFalse(recovery.deferredByOverlay)
    }

    private fun visibleTask(): SceneTaskLaunchFlow.TaskSnapshot = SceneTaskLaunchFlow.TaskSnapshot.available(
        listOf(SceneTaskIdentity.Record(taskId, displayId, scene, scene, true)), "fake/task-snapshot")

    private fun physicalState(state: Int, selectedId: Int = displayId, selectedUnique: String = "rear-unique") =
        "{\"selectedId\":$selectedId,\"selectedUnique\":\"$selectedUnique\",\"displays\":[" +
            "{\"id\":$selectedId,\"unique\":\"$selectedUnique\",\"state\":$state}]}"

    private class QueuedSnapshots(vararg snapshots: SceneTaskLaunchFlow.TaskSnapshot) : SceneTaskPresentationFlow.TaskSnapshots {
        private val values = snapshots.toMutableList()
        override fun read(): SceneTaskLaunchFlow.TaskSnapshot = values.removeAt(0)
    }
}
