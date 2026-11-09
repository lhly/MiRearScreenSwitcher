package top.zainzh.rearscreen

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.zainzh.rearscreen.platform.*
import top.zainzh.rearscreen.platform.mods.ModSceneWindowLifecycleGate
import top.zainzh.rearscreen.platform.mods.ModSceneWindowPolicy
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Production task-flow + coordinator composition for R9's bounded foreground reclassification. */
class SceneTaskVisibilityRecheckTest {
    private val owner = SessionOwner(10001, "dev.remrss.test", "cert", SessionPrincipal.SignedMod("author", "scene-mod", "logical"))
    private val component = ComponentName("dev.remrss.test", "dev.remrss.test.RearSceneActivity")
    private val componentName = "dev.remrss.test/dev.remrss.test.RearSceneActivity"
    private val launcher = "com.xiaomi.subscreencenter/.SubScreenLauncher"

    @Test fun ambiguousSnapshotThenUniqueKnownHomeFocusesExactTaskOnceAndRestoresWindowPolicy() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene()
            fixture.gateway.enqueue(
                snapshot(ownerRecord(visible = false), homeRecord(visible = true), otherRecord(visible = true)),
                snapshot(ownerRecord(visible = false), homeRecord(visible = true)),
                snapshot(ownerRecord(visible = true), homeRecord(visible = false)),
            )
            fixture.triggerCoverReturn(handle)

            assertTrue("the initial ambiguous snapshot and one settled recheck complete", fixture.gateway.twoReturns.await(3, TimeUnit.SECONDS))
            assertEquals(2, fixture.gateway.recoveryCalls.get())
            assertEquals("only the unique known rear Home result issues an exact-task focus", 1, fixture.gateway.focusCalls.get())
            assertEquals("a valid lease may spend only its original wake budget", 0, fixture.gateway.wakeCalls.get())

            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true)
            val snapshot = fixture.coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation)
            assertTrue("successful physical/task authority plus the real window report restores Core visibility", snapshot?.visible == true)

            var keepFlag = false
            val window = ModSceneWindowLifecycleGate(handle.sessionId, handle.generation) { state ->
                keepFlag = ModSceneWindowPolicy(
                    windowReady = true,
                    windowAttachedAndVisible = state.windowVisible,
                    coordinatorVisible = state.coordinatorVisible,
                    keepVisibleRequested = true,
                ).keepScreenOn
            }
            window.onActivityStarted(true)
            window.onWindowStateChanged(attached = true, viewShown = true, windowVisible = true)
            assertTrue(window.applyPresentationSnapshot(snapshot!!, window.currentWindowRequestEpoch()))
            assertTrue("the accepted Coordinator snapshot reaches the existing Activity keep policy", keepFlag)

            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true)
            Thread.sleep(450)
            assertEquals("duplicate current events do not re-run a completed cause", 2, fixture.gateway.recoveryCalls.get())
            assertEquals(1, fixture.gateway.focusCalls.get())
        } finally { fixture.close() }
    }

    @Test fun zeroMultipleUnknownAndUnrelatedSnapshotsNeverBecomeAnImplicitHomeFocus() {
        val cases = listOf(
            "zero" to listOf(snapshot(ownerRecord(visible = false), homeRecord(visible = false))),
            "multiple" to listOf(snapshot(ownerRecord(visible = false), homeRecord(visible = true), otherRecord(visible = true))),
            "unknown" to listOf(snapshot(ownerRecord(visible = false), homeRecord(visible = null))),
            "other" to listOf(snapshot(ownerRecord(visible = false), otherRecord(visible = true))),
        )
        cases.forEach { (name, recheckSteps) ->
            val fixture = Fixture()
            try {
                val handle = fixture.openVisibleScene()
                fixture.gateway.enqueue(
                    snapshot(ownerRecord(visible = false), homeRecord(visible = true), otherRecord(visible = true)),
                    *recheckSteps.toTypedArray(),
                )
                fixture.triggerCoverReturn(handle)
                assertTrue("$name case receives one settled classification result", fixture.gateway.twoReturns.await(3, TimeUnit.SECONDS))
                assertEquals("$name never focuses a task without a unique exact own/Home classification", 0,
                    fixture.gateway.focusCalls.get())
                assertEquals("$name gets no self-driven third attempt", 2, fixture.gateway.recoveryCalls.get())

                fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
                fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true)
                Thread.sleep(400)
                assertEquals("$name remains stopped after duplicate events", 2, fixture.gateway.recoveryCalls.get())
            } finally { fixture.close() }
        }
    }

    @Test fun interruptedRecheckResumesSameCauseAfterCoverWithoutSpendingAnotherWake() {
        val fixture = Fixture()
        val recheckEntered = CountDownLatch(1)
        val releaseRecheck = CountDownLatch(1)
        try {
            val handle = fixture.openVisibleScene()
            fixture.gateway.physicalRearOn = false
            fixture.gateway.snapshotReadGate = { recovery, read ->
                if (recovery == 2 && read == 1) {
                    recheckEntered.countDown()
                    check(releaseRecheck.await(2, TimeUnit.SECONDS))
                }
            }
            fixture.gateway.enqueue(
                snapshot(ownerRecord(visible = false), homeRecord(visible = true), otherRecord(visible = true)),
                snapshot(ownerRecord(visible = false), homeRecord(visible = true)),
                snapshot(ownerRecord(visible = false), homeRecord(visible = true)),
                snapshot(ownerRecord(visible = true), homeRecord(visible = false)),
            )
            fixture.gateway.onPhysicalWake = {
                fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            }
            fixture.triggerCoverReturn(handle, rearOff = true)

            assertTrue("the single settled recheck enters its task read", recheckEntered.await(3, TimeUnit.SECONDS))
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            assertEquals("duplicate ON while the same recheck is in flight cannot enqueue a second capture",
                2, fixture.gateway.recoveryCalls.get())
            fixture.coordinator.beginTransientOverlay(202L)
            releaseRecheck.countDown()
            assertTrue("the interrupted current-gated read returns", fixture.gateway.twoReturns.await(3, TimeUnit.SECONDS))
            fixture.coordinator.endTransientOverlay(202L)
            assertTrue("the same still-valid cause resumes through its remaining classification opportunity",
                fixture.gateway.threeReturns.await(3, TimeUnit.SECONDS))

            assertEquals("the original OFF recovery spent exactly one wake", 1, fixture.gateway.wakeCalls.get())
            assertEquals("only the resumed, unoccluded known-Home snapshot focuses", 1, fixture.gateway.focusCalls.get())
            assertEquals("initial + interrupted recheck + resumed same-cause recheck", 3, fixture.gateway.recoveryCalls.get())
        } finally {
            releaseRecheck.countDown()
            fixture.close()
        }
    }

    @Test fun freshOffDuringReadOnlyRecheckWaitsForOnWithoutClaimingUnusedWakeBudget() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene()
            fixture.gateway.beforeRecovery = { call -> if (call == 2) fixture.gateway.physicalRearOn = false }
            fixture.gateway.enqueue(
                snapshot(ownerRecord(visible = false), homeRecord(visible = true), otherRecord(visible = true)),
                snapshot(ownerRecord(visible = false), homeRecord(visible = true)),
                snapshot(ownerRecord(visible = true), homeRecord(visible = false)),
            )
            fixture.triggerCoverReturn(handle)

            assertTrue("the OFF recheck returns to the real-ON wait", fixture.gateway.twoReturns.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(SceneRecoveryWakeDecision.ALREADY_ON, SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY),
                fixture.gateway.wakeDecisions)
            assertEquals("a read-only recheck cannot claim a not-yet-used wake", 0, fixture.gateway.wakeCalls.get())
            assertEquals(0, fixture.gateway.focusCalls.get())
            Thread.sleep(450)
            assertEquals("stable OFF does not requeue from cached ON", 2, fixture.gateway.recoveryCalls.get())

            fixture.gateway.physicalRearOn = true
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            assertTrue("the same unclassified opportunity resumes at a real ON edge", fixture.gateway.threeReturns.await(3, TimeUnit.SECONDS))
            assertEquals(3, fixture.gateway.recoveryCalls.get())
            assertEquals(1, fixture.gateway.focusCalls.get())
            assertEquals(0, fixture.gateway.wakeCalls.get())
        } finally { fixture.close() }
    }

    @Test fun focusWindowPendingDoesNotRegrantSpentRecheckWithinTheSameCause() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene()
            fixture.gateway.enqueue(
                snapshot(ownerRecord(visible = false), homeRecord(visible = true), otherRecord(visible = true)),
                snapshot(ownerRecord(visible = false), homeRecord(visible = true)),
                snapshot(ownerRecord(visible = false), homeRecord(visible = true)),
                snapshot(ownerRecord(visible = false), homeRecord(visible = true), otherRecord(visible = true)),
            )
            fixture.triggerCoverReturn(handle)

            assertTrue("the R7 window confirmation runs after the first recheck focus", fixture.gateway.threeReturns.await(3, TimeUnit.SECONDS))
            assertEquals("the post-focus ambiguity ends this same-cause opportunity", 3, fixture.gateway.recoveryCalls.get())
            assertEquals("focus occurs only for the unique known Home classification", 1, fixture.gateway.focusCalls.get())
            assertEquals(0, fixture.gateway.wakeCalls.get())
            Thread.sleep(450)
            assertEquals("spent classification is not reissued after the R7 confirmation", 3, fixture.gateway.recoveryCalls.get())
        } finally { fixture.close() }
    }

    @Test fun classifiedFocusRejectedByFinalPhysicalOffWaitsForOnWithoutRegrantingRecheck() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene()
            fixture.gateway.enqueue(
                snapshot(ownerRecord(visible = false), homeRecord(visible = true), otherRecord(visible = true)),
                snapshot(ownerRecord(visible = false), homeRecord(visible = true)),
                snapshot(ownerRecord(visible = true), homeRecord(visible = false)),
                snapshot(ownerRecord(visible = true), homeRecord(visible = false)),
            )
            fixture.gateway.afterValidation = { call ->
                if (call == 2) fixture.gateway.physicalRearOn = false
            }
            fixture.gateway.beforeRecovery = { call ->
                if (call == 3) fixture.gateway.physicalRearOn = true
            }
            fixture.triggerCoverReturn(handle)

            assertTrue("TaskFlow classifies Home, focuses once, then final physical authority observes OFF",
                fixture.gateway.twoReturns.await(3, TimeUnit.SECONDS))
            assertEquals("final OFF is not reported as a successful presentation", SceneError.DISPLAY_UNAVAILABLE,
                fixture.gateway.lastRecoveryResult?.error)
            assertTrue(fixture.gateway.lastRecoveryResult?.awaitDisplayOn == true)
            assertTrue("the validated TaskFlow result spends this cause's classification opportunity",
                fixture.gateway.lastRecoveryResult?.taskVisibilityClassificationComplete == true)
            assertEquals(1, fixture.gateway.focusCalls.get())
            assertEquals(0, fixture.gateway.wakeCalls.get())

            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            assertTrue("the still-live exact cause can resume on the real ON edge", fixture.gateway.threeReturns.await(3, TimeUnit.SECONDS))
            assertEquals("the now-owned exact task is confirmed without a second focus", 1, fixture.gateway.focusCalls.get())
            assertEquals(0, fixture.gateway.wakeCalls.get())
            Thread.sleep(450)
            assertEquals("spent classification does not schedule another read/focus", 3, fixture.gateway.recoveryCalls.get())
        } finally { fixture.close() }
    }

    @Test fun closingDuringTheOneRecheckPreventsLateFocusAndCauseRevival() {
        val fixture = Fixture()
        val recheckEntered = CountDownLatch(1)
        val releaseRecheck = CountDownLatch(1)
        try {
            val handle = fixture.openVisibleScene()
            fixture.gateway.snapshotReadGate = { recovery, read ->
                if (recovery == 2 && read == 1) {
                    recheckEntered.countDown()
                    check(releaseRecheck.await(2, TimeUnit.SECONDS))
                }
            }
            fixture.gateway.enqueue(
                snapshot(ownerRecord(visible = false), homeRecord(visible = true), otherRecord(visible = true)),
                snapshot(ownerRecord(visible = false), homeRecord(visible = true)),
            )
            fixture.triggerCoverReturn(handle)
            assertTrue(recheckEntered.await(3, TimeUnit.SECONDS))
            fixture.coordinator.closeScene(owner, handle.sessionId)
            releaseRecheck.countDown()
            assertTrue(fixture.gateway.twoReturns.await(3, TimeUnit.SECONDS))
            Thread.sleep(400)
            assertEquals("a closed owner never reaches task focus", 0, fixture.gateway.focusCalls.get())
            assertEquals("the late query cannot create another recovery", 2, fixture.gateway.recoveryCalls.get())
            assertEquals(0, fixture.gateway.wakeCalls.get())
        } finally {
            releaseRecheck.countDown()
            fixture.close()
        }
    }

    private fun ownerRecord(visible: Boolean?) = SceneTaskIdentity.Record(
        56091, 1, componentName, componentName, visible)
    private fun homeRecord(visible: Boolean?) = SceneTaskIdentity.Record(
        4, 1, "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher",
        "com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher", visible)
    private fun otherRecord(visible: Boolean?) = SceneTaskIdentity.Record(
        5, 1, "dev.other/.OtherActivity", "dev.other/.OtherActivity", visible)
    private fun snapshot(vararg rows: SceneTaskIdentity.Record) =
        SceneTaskLaunchFlow.TaskSnapshot.available(rows.toList(), "activity_task_fake")

    private inner class Fixture {
        val gateway = TaskFlowGateway()
        private val scheduler = ScheduledThreadPoolExecutor(1)
        private var token = 0
        val coordinator = RearSceneCoordinator(
            gateway = gateway,
            activationVerifier = UserActivationVerifier { _, _, _ -> true },
            callbackExecutor = Executor { it.run() },
            expiryScheduler = scheduler,
            clock = { 1_000L },
            randomToken = { "r10-token-${++token}" },
            displayOwnershipEpoch = { 7L },
        )

        fun openVisibleScene(): SceneSessionHandle {
            val opened = coordinator.openScene(owner,
                SceneRequest(component, keepVisible = true), UserActivationToken("host-action"), object : SceneObserver {
                    override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) = Unit
                }) as SceneOpenResult.Accepted
            val handle = opened.handle
            val viewport = RearViewportSnapshot(
                revision = 1L, displayId = 1, physicalDisplayPx = PixelSize(720, 1280),
                contentBoundsPx = PixelRect(0, 0, 720, 1280), windowPx = PixelSize(720, 1280),
                windowDp = DpSize(360f, 640f), densityDpi = 320, renderBufferPx = PixelSize(720, 1280),
                displayRotation = 0, contentRotation = 0,
                inputTransform = CoordinateTransform(1f, 0f, 0f, 0f, 1f, 0f),
            )
            assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket,
                SceneWindowReport(component, 56091, viewport)).success)
            coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
            return handle
        }

        fun triggerCoverReturn(handle: SceneSessionHandle, rearOff: Boolean = false) {
            coordinator.beginTransientOverlay(201L)
            coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            if (rearOff) coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
            coordinator.endTransientOverlay(201L)
        }

        fun close() {
            scheduler.shutdownNow()
        }
    }

    private inner class TaskFlowGateway : RearSceneGateway {
        val recoveryCalls = AtomicInteger()
        val focusCalls = AtomicInteger()
        val wakeCalls = AtomicInteger()
        val wakeDecisions = java.util.Collections.synchronizedList(mutableListOf<SceneRecoveryWakeDecision>())
        val twoReturns = CountDownLatch(1)
        val threeReturns = CountDownLatch(1)
        private val snapshots = ArrayDeque<SceneTaskLaunchFlow.TaskSnapshot>()
        @Volatile var physicalRearOn: Boolean? = true
        @Volatile var lastRecoveryResult: SceneRecoveryResult? = null
        @Volatile var onPhysicalWake: (() -> Unit)? = null
        @Volatile var beforeRecovery: (call: Int) -> Unit = { }
        @Volatile var afterValidation: (call: Int) -> Unit = { }
        @Volatile var snapshotReadGate: (recovery: Int, read: Int) -> Unit = { _, _ -> }

        @Synchronized fun enqueue(vararg rows: SceneTaskLaunchFlow.TaskSnapshot) { rows.forEach(snapshots::addLast) }
        override fun queryCapabilities(owner: SessionOwner) = CapabilitySnapshot("test", true, true, emptyList())
        override fun authorize(owner: SessionOwner, request: SceneRequest): SceneError? = null
        override fun targetDisplayId(): Int? = 1
        override fun prepareDisplay(owner: SessionOwner, request: SceneRequest): SceneError? = null
        override fun launchNewTask(owner: SessionOwner, request: SceneRequest, launchTicket: String, expiresAtElapsedRealtimeMs: Long) =
            LaunchedScene(request.component, 56091, 1)
        override fun verifyLaunchedTask(owner: SessionOwner, expected: LaunchedScene, report: SceneWindowReport) = true
        override fun finishOwnedTask(owner: SessionOwner, task: LaunchedScene) = Unit

        override fun recoverOwnedDisplay(owner: SessionOwner, request: SceneRequest, task: LaunchedScene,
                                         cachedRearOn: Boolean?, isCurrent: () -> Boolean,
                                         onWakeAttempt: (Boolean?) -> SceneRecoveryWakeDecision): SceneRecoveryResult {
            val call = recoveryCalls.incrementAndGet()
            fun completed() {
                if (call == 2) twoReturns.countDown()
                if (call == 3) threeReturns.countDown()
            }
            if (!isCurrent()) {
                completed()
                return SceneRecoveryResult(deferredByOverlay = true)
            }
            beforeRecovery(call)
            val decision = onWakeAttempt(physicalRearOn ?: cachedRearOn)
            wakeDecisions += decision
            when (decision) {
                SceneRecoveryWakeDecision.STALE -> {
                    completed()
                    return SceneRecoveryResult(deferredByOverlay = true)
                }
                SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY -> {
                    completed()
                    return SceneRecoveryResult(SceneError.DISPLAY_UNAVAILABLE, awaitDisplayOn = true)
                }
                SceneRecoveryWakeDecision.CLAIMED -> {
                    wakeCalls.incrementAndGet()
                    physicalRearOn = true
                    onPhysicalWake?.invoke()
                }
                SceneRecoveryWakeDecision.ALREADY_ON -> Unit
            }
            if (!isCurrent()) {
                completed()
                return SceneRecoveryResult(deferredByOverlay = true)
            }
            var read = 0
            val flow = SceneTaskPresentationFlow.present(
                task.taskId, componentName, task.displayId,
                SceneTaskPresentationFlow.TaskSnapshots {
                    snapshotReadGate(call, ++read)
                    synchronized(this) { snapshots.removeFirst() }
                },
                SceneTaskPresentationFlow.TaskFocus { taskId ->
                    focusCalls.incrementAndGet()
                    if (taskId == task.taskId) SceneTaskPresentationFlow.FocusResult(true, 0, null)
                    else SceneTaskPresentationFlow.FocusResult(false, -1, "unexpected_task")
                },
                SceneTaskPresentationFlow.RearHome {
                    SceneTaskPresentationFlow.matchesKnownRearHome(listOf(launcher), it)
                },
                SceneTaskPresentationFlow.ActionGate {
                    if (isCurrent()) SceneTaskPresentationFlow.ActionGateResult.ALLOWED
                    else SceneTaskPresentationFlow.ActionGateResult.CANCELLED
                },
                SceneTaskPresentationFlow.SuccessValidator { result ->
                    afterValidation(call)
                    if (!isCurrent()) "cancelled_before_focus"
                    else if (physicalRearOn != true || result.taskId != task.taskId || result.displayId != task.displayId)
                        "rear_display_not_on"
                    else null
                },
            )
            if (!isCurrent()) {
                completed()
                return SceneRecoveryResult(deferredByOverlay = true)
            }
            val mapped = sceneRecoveryResultForPresentationStatus(flow.status, flow.source, flow.commandExitCode)
            lastRecoveryResult = mapped
            completed()
            return mapped
        }
    }
}
