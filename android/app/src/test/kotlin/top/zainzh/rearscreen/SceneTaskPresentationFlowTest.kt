package top.zainzh.rearscreen

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.zainzh.rearscreen.platform.SceneError
import top.zainzh.rearscreen.platform.sceneRecoveryResultForPresentationStatus

class SceneTaskPresentationFlowTest {
    private val scene = "dev.remrss.sample/dev.remrss.sample.RearSceneActivity"
    private val launcher = "com.xiaomi.subscreencenter/.SubScreenLauncher"

    @Test fun rearLauncherCanYieldOnlyTheExactExistingSceneTaskAndReadbackMustBeOnTarget() {
        val snapshots = QueueSnapshots(
            snapshot(record(56091, 1, scene, scene, false), record(4, 1, launcher, launcher, true)),
            snapshot(record(56091, 1, scene, scene, true), record(4, 1, launcher, launcher, false))
        )
        var focusedTask = -1
        val result = SceneTaskPresentationFlow.present(56091, scene, 1, snapshots,
            { task -> focusedTask = task; SceneTaskPresentationFlow.FocusResult(true, 0, null) },
            { it == launcher })

        assertEquals("scene_task_presented", result.status)
        assertEquals(56091, focusedTask)
        assertEquals(1, result.displayId)
        assertEquals(scene, result.baseComponent)
        assertEquals(scene, result.topComponent)
    }

    @Test fun unrelatedTopAndWrongDisplayArePreservedWithoutFocusingOrMovingAnyTask() {
        var focusCalls = 0
        val blocked = SceneTaskPresentationFlow.present(56091, scene, 1,
            QueueSnapshots(snapshot(record(56091, 1, scene, scene, false),
                record(71, 1, "com.example.other/.MainActivity", "com.example.other/.MainActivity", true))),
            { focusCalls++; SceneTaskPresentationFlow.FocusResult(true, 0, null) }, { it == launcher })
        assertEquals("unrelated_top_preserved", blocked.status)
        assertEquals(0, focusCalls)

        val wrongDisplay = SceneTaskPresentationFlow.present(56091, scene, 1,
            QueueSnapshots(snapshot(record(56091, 0, scene, launcher, true))),
            { focusCalls++; SceneTaskPresentationFlow.FocusResult(true, 0, null) }, { true })
        assertEquals("owner_display_mismatch", wrongDisplay.status)
        assertEquals(0, focusCalls)

        val ambiguous = SceneTaskPresentationFlow.present(56091, scene, 1,
            QueueSnapshots(snapshot(record(56091, 1, scene, scene, false),
                record(4, 1, launcher, launcher, true),
                record(5, 1, "com.xiaomi.mirror/.SubscreenLauncher", "com.xiaomi.mirror/.SubscreenLauncher", true))),
            { focusCalls++; SceneTaskPresentationFlow.FocusResult(true, 0, null) }, { true })
        assertEquals("display_visibility_ambiguous", ambiguous.status)
        assertEquals(0, focusCalls)
    }

    @Test fun sameOwnedPackageAlreadyOnTopDoesNotIssueASecondFocusCommand() {
        var focusCalls = 0
        val result = SceneTaskPresentationFlow.present(56091, scene, 1,
            QueueSnapshots(snapshot(record(56091, 1, scene, "dev.remrss.sample/.SettingsActivity", true))),
            { focusCalls++; SceneTaskPresentationFlow.FocusResult(true, 0, null) }, { it == launcher })
        assertEquals("already_scene_visible", result.status)
        assertEquals(0, focusCalls)
        assertEquals("a Scene task with a same-package child remains within the exact owned task", 56091, result.taskId)
    }

    @Test fun focusAcceptanceWithoutExactTopReadbackIsNotReportedAsPresented() {
        val result = SceneTaskPresentationFlow.present(56091, scene, 1,
            QueueSnapshots(
                snapshot(record(56091, 1, scene, scene, false), record(4, 1, launcher, launcher, true)),
                snapshot(record(56091, 1, scene, scene, false), record(4, 1, launcher, launcher, true))
            ),
            { SceneTaskPresentationFlow.FocusResult(true, 0, null) }, { it == launcher })
        assertEquals("scene_not_visible_after_focus", result.status)
        assertTrue("command acceptance alone is not foreground proof", result.commandExitCode == 0)
    }

    @Test fun visibilityUnknownFromLegacySnapshotFailsClosedInsteadOfTreatingOwnedAsFront() {
        val result = SceneTaskPresentationFlow.present(56091, scene, 1,
            QueueSnapshots(snapshot(record(56091, 1, scene, scene))),
            { SceneTaskPresentationFlow.FocusResult(true, 0, null) }, { true })
        assertEquals("task_visibility_unsupported", result.status)
    }

    @Test fun leaseGateIsRecheckedAfterBlockingTaskInspectionAndLauncherLookupImmediatelyBeforeFocus() {
        val current = AtomicBoolean(true)
        val inspectionEntered = CountDownLatch(1)
        val releaseInspection = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var firstRead = true
        val snapshots = object : SceneTaskPresentationFlow.TaskSnapshots {
            override fun read(): SceneTaskLaunchFlow.TaskSnapshot {
                if (firstRead) {
                    firstRead = false
                    inspectionEntered.countDown()
                    check(releaseInspection.await(2, TimeUnit.SECONDS))
                }
                return snapshot(record(56091, 1, scene, scene, false), record(4, 1, launcher, launcher, true))
            }
        }
        var launcherResolved = false
        var gateChecks = 0
        var focusCalls = 0
        try {
            val resultFuture = executor.submit<SceneTaskPresentationFlow.Result> {
                SceneTaskPresentationFlow.present(56091, scene, 1, snapshots,
                    { focusCalls++; SceneTaskPresentationFlow.FocusResult(true, 0, null) },
                    { launcherResolved = true; it == launcher },
                    { gateChecks++; if (current.get()) SceneTaskPresentationFlow.ActionGateResult.ALLOWED else SceneTaskPresentationFlow.ActionGateResult.CANCELLED })
            }
            assertTrue("the privileged task snapshot is in flight", inspectionEntered.await(2, TimeUnit.SECONDS))
            current.set(false) // overlay/lease revocation lands while the remote inspection is blocked
            releaseInspection.countDown()
            val result = resultFuture.get(2, TimeUnit.SECONDS)

            assertEquals("cancelled_before_focus", result.status)
            assertTrue("the exact known-launcher lookup completes before the final side-effect gate", launcherResolved)
            assertEquals(1, gateChecks)
            assertEquals("the stale lease cannot issue a delayed focus command", 0, focusCalls)
        } finally {
            releaseInspection.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun actionGateTransportFailureIsNotADeferredOverlayCancellation() {
        var focusCalls = 0
        val result = SceneTaskPresentationFlow.present(56091, scene, 1,
            QueueSnapshots(snapshot(record(56091, 1, scene, scene, false), record(4, 1, launcher, launcher, true))),
            { focusCalls++; SceneTaskPresentationFlow.FocusResult(true, 0, null) },
            { it == launcher },
            { SceneTaskPresentationFlow.ActionGateResult.UNAVAILABLE })
        assertEquals("action_gate_unavailable", result.status)
        assertEquals("no focus side effect follows an unavailable lease gate", 0, focusCalls)

        val remote = SceneTaskPresentationFlow.invokeRemoteActionGate { throw RemoteException("test binder death") }
        assertEquals(SceneTaskPresentationFlow.ActionGateResult.UNAVAILABLE, remote)
        assertEquals(SceneError.BACKEND_UNAVAILABLE,
            sceneRecoveryResultForPresentationStatus("action_gate_unavailable").error)
        assertEquals("only a confirmed stale lease is deferred", true,
            sceneRecoveryResultForPresentationStatus("cancelled_before_focus").deferredByOverlay)
    }

    @Test fun knownRearLauncherComparisonNormalizesShortAndFlattenedComponentNamesOnly() {
        val candidates = listOf("com.xiaomi.subscreencenter/.SubScreenLauncher")
        assertTrue(SceneTaskPresentationFlow.matchesKnownRearHome(candidates,
            "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher"))
        assertTrue(SceneTaskPresentationFlow.matchesKnownRearHome(
            listOf("com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher"),
            "com.xiaomi.subscreencenter/.SubScreenLauncher"))
        assertFalse(SceneTaskPresentationFlow.matchesKnownRearHome(candidates,
            "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.OtherActivity"))
        assertFalse(SceneTaskPresentationFlow.matchesKnownRearHome(candidates,
            "com.other/.SubScreenLauncher"))
    }

    private fun record(id: Int, display: Int, base: String, top: String) =
        SceneTaskIdentity.Record(id, display, base, top)

    private fun record(id: Int, display: Int, base: String, top: String, visible: Boolean) =
        SceneTaskIdentity.Record(id, display, base, top, visible)

    private fun snapshot(vararg records: SceneTaskIdentity.Record) =
        SceneTaskLaunchFlow.TaskSnapshot.available(records.toList(), "fixture/all-displays")

    private class QueueSnapshots(vararg items: SceneTaskLaunchFlow.TaskSnapshot) : SceneTaskPresentationFlow.TaskSnapshots {
        private val queue = ArrayDeque(items.toList())
        private var previous: SceneTaskLaunchFlow.TaskSnapshot? = null
        override fun read(): SceneTaskLaunchFlow.TaskSnapshot {
            queue.pollFirst()?.let { previous = it }
            return previous ?: SceneTaskLaunchFlow.TaskSnapshot.unavailable("fixture", "no_snapshot")
        }
    }
}
