package top.zainzh.rearscreen.platform.mods

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.platform.*
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Exercises the production Mod presentation gate against a real in-process Core coordinator.
 * Resource hooks are controlled booleans, not a Runtime broker, sensor, or rear-display claim.
 */
@RunWith(AndroidJUnit4::class)
class ModScenePresentationSnapshotTest {
    private val component = ComponentName("top.zainzh.rearscreen.platformtestm11", "top.zainzh.rearscreen.platform.mods.ModRearSceneActivity")
    private val owner = SessionOwner(
        12000,
        "top.zainzh.rearscreen.platformtestm11",
        "test-cert",
        SessionPrincipal.SignedMod("author-fingerprint", "platform.m11.fixture", "logical-scene-1")
    )

    @Test fun delayedCoreVisibilityCallbackAndOldQueryCannotReviveCoveredModScene() {
        val gateway = FakeGateway(component)
        val callbackQueue = ArrayDeque<Runnable>()
        lateinit var coordinator: RearSceneCoordinator
        lateinit var gate: ModSceneWindowLifecycleGate
        lateinit var handle: SceneSessionHandle
        var ownObserverOpen = false
        var dataSubscriptionOpen = false
        var keepScreenOn = false
        var otherObserverOpen = true
        val initCount = 1

        val observer = object : SceneObserver {
            override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {
                // Mirrors ModRearSceneActivity: callbacks are only triggers. The current
                // snapshot is read on the UI thread at application time, never captured here.
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val epoch = gate.currentWindowRequestEpoch()
                    val current = coordinator.queryScenePresentation(owner, sessionId, generation)
                    gate.applyPresentationSnapshot(current, epoch)
                }
            }
        }

        coordinator = RearSceneCoordinator(
            gateway = gateway,
            activationVerifier = UserActivationVerifier { _, _, _ -> true },
            callbackExecutor = Executor { runnable -> synchronized(callbackQueue) { callbackQueue.addLast(runnable) } },
            clock = { 1000L },
            randomToken = { "m11-ticket" },
            displayOwnershipEpoch = { 0L },
        )

        fun deliverOneCallback(): Boolean {
            val callback = synchronized(callbackQueue) { if (callbackQueue.isEmpty()) null else callbackQueue.removeFirst() } ?: return false
            callback.run()
            return true
        }

        fun drainCallbacks() {
            while (deliverOneCallback()) { }
        }

        fun applyCurrentSnapshot(): ScenePresentationSnapshot? {
            var result: ScenePresentationSnapshot? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val epoch = gate.currentWindowRequestEpoch()
                result = coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation)
                gate.applyPresentationSnapshot(result, epoch)
            }
            return result
        }

        fun reportWindow(started: Boolean, visible: Boolean) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                gate.onActivityStarted(started)
                gate.onWindowStateChanged(attached = visible, viewShown = visible, windowVisible = visible)
            }
            assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, visible).success)
            applyCurrentSnapshot()
        }

        val opened = coordinator.openScene(
            owner,
            SceneRequest(component, keepVisible = true),
            UserActivationToken("user-confirmed"),
            observer,
        ) as SceneOpenResult.Accepted
        handle = opened.handle
        gate = ModSceneWindowLifecycleGate(handle.sessionId, handle.generation) { state ->
            val policy = ModSceneWindowPolicy(
                windowReady = true,
                windowAttachedAndVisible = state.windowVisible,
                coordinatorVisible = state.coordinatorVisible,
                keepVisibleRequested = true,
            )
            ownObserverOpen = policy.showSceneContent
            dataSubscriptionOpen = policy.showSceneContent
            keepScreenOn = policy.keepScreenOn
        }

        assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport())).success)
        drainCallbacks()
        assertFalse("Ready is not presentation authority", ownObserverOpen)
        reportWindow(started = true, visible = true)
        drainCallbacks()
        assertTrue(ownObserverOpen)
        assertTrue(dataSubscriptionOpen)
        assertTrue(keepScreenOn)

        val unchangedVisibleSnapshot = assertNotNullSnapshot(coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation))
        assertTrue(unchangedVisibleSnapshot.visible)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            gate.onActivityStarted(false)
            gate.onWindowStateChanged(attached = false, viewShown = false, windowVisible = false)
            gate.onActivityStarted(true)
            gate.onWindowStateChanged(attached = true, viewShown = true, windowVisible = true)
            assertTrue(
                "same-lease current revision can reauthorize a new local window epoch",
                gate.applyPresentationSnapshot(unchangedVisibleSnapshot, gate.currentWindowRequestEpoch()),
            )
        }
        assertTrue("local reattachment at the unchanged Core revision restores the valid current window", ownObserverOpen)

        // First deliver a real covered=false state so normal duplicate stop/start reports
        // exercise the Core deduplication path, rather than skipping the hidden transition.
        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(true))
        drainCallbacks()
        assertFalse(ownObserverOpen)
        assertFalse(dataSubscriptionOpen)
        assertFalse(keepScreenOn)
        val covered = assertNotNullSnapshot(coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation))
        assertFalse(covered.visible)

        // Core far queues a visible event; before its callback runs, near makes the current
        // authoritative state hidden again. Preserve the old query reply as a stale response.
        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(false))
        val staleFarSnapshot = assertNotNullSnapshot(coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation))
        assertTrue(staleFarSnapshot.visible)
        var staleWindowEpoch = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync { staleWindowEpoch = gate.currentWindowRequestEpoch() }
        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(true))
        val queuedBeforeLocalEdges = synchronized(callbackQueue) { callbackQueue.size }
        assertTrue("far and near presentation transitions are still waiting in the callback executor", queuedBeforeLocalEdges >= 2)
        val nearBeforeActivity = assertNotNullSnapshot(coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation))
        assertFalse(nearBeforeActivity.visible)

        // Activity stop/start and reports are deduplicated while Core still says covered.
        val queueBeforeDeduplicatedReports = synchronized(callbackQueue) { callbackQueue.size }
        reportWindow(started = false, visible = false)
        reportWindow(started = true, visible = true)
        assertEquals("identical covered visibility reports do not enqueue duplicate observer events", queueBeforeDeduplicatedReports, synchronized(callbackQueue) { callbackQueue.size })
        val currentCovered = assertNotNullSnapshot(coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation))
        assertFalse(currentCovered.visible)
        assertEquals("local re-reports do not manufacture new Core presentation revisions", nearBeforeActivity.revision, currentCovered.revision)
        assertTrue("only this scene's resources stop; another observer remains", otherObserverOpen)
        assertFalse(ownObserverOpen)
        assertFalse(dataSubscriptionOpen)
        assertFalse(keepScreenOn)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertFalse("an old query result cannot cross stop/start", gate.applyPresentationSnapshot(staleFarSnapshot, staleWindowEpoch))
        }
        assertFalse(ownObserverOpen)
        assertFalse(dataSubscriptionOpen)
        assertFalse(keepScreenOn)

        // Deliver the queued old far callback first. It must query Core now (still near),
        // not reuse the callback's old event bool or the captured far snapshot.
        assertTrue(deliverOneCallback())
        assertFalse("late far callback reconciles against current covered snapshot", ownObserverOpen)
        assertFalse(dataSubscriptionOpen)
        assertFalse(keepScreenOn)
        drainCallbacks()
        assertFalse(ownObserverOpen)
        assertTrue(otherObserverOpen)
        assertEquals("resume never re-runs Mod init", 1, initCount)

        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(false))
        drainCallbacks()
        assertTrue("a new current far snapshot restores the same Scene", ownObserverOpen)
        assertTrue(dataSubscriptionOpen)
        assertTrue(keepScreenOn)
        assertTrue(coordinator.ownsActiveSession(owner, handle.sessionId))
        assertEquals("the same task and logical worker state are reused", 1, gateway.launchCount)
        assertEquals(1, initCount)
        assertTrue(otherObserverOpen)
        coordinator.closeScene(owner, handle.sessionId)
    }

    private fun assertNotNullSnapshot(snapshot: ScenePresentationSnapshot?): ScenePresentationSnapshot {
        assertNotNull(snapshot)
        return requireNotNull(snapshot)
    }

    private fun viewport() = RearViewportSnapshot(
        revision = 1,
        displayId = 1,
        physicalDisplayPx = PixelSize(1200, 800),
        contentBoundsPx = PixelRect(0, 0, 1200, 800),
        windowPx = PixelSize(1200, 800),
        windowDp = DpSize(400f, 266.66f),
        densityDpi = 240,
        renderBufferPx = PixelSize(1200, 800),
        displayRotation = 0,
        contentRotation = 0,
        inputTransform = CoordinateTransform(1f, 0f, 0f, 0f, 1f, 0f),
    )

    private class FakeGateway(private val component: ComponentName) : RearSceneGateway {
        var launchCount = 0
        override fun queryCapabilities(owner: SessionOwner) = CapabilitySnapshot("remrss.platform/1", true, true, emptyList())
        override fun authorize(owner: SessionOwner, request: SceneRequest): SceneError? = null
        override fun targetDisplayId(): Int = 1
        override fun launchNewTask(owner: SessionOwner, request: SceneRequest, launchTicket: String, expiresAtElapsedRealtimeMs: Long): LaunchedScene {
            launchCount++
            return LaunchedScene(component, 42, 1)
        }
        override fun verifyLaunchedTask(owner: SessionOwner, expected: LaunchedScene, report: SceneWindowReport): Boolean =
            expected.component == report.component && expected.taskId == report.taskId && expected.displayId == report.viewport.displayId
        override fun finishOwnedTask(owner: SessionOwner, task: LaunchedScene) = Unit
    }
}
