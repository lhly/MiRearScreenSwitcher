package top.zainzh.rearscreen.platform

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import top.zainzh.rearscreen.platform.mods.ModSceneWindowLifecycleGate
import top.zainzh.rearscreen.platform.mods.ModSceneWindowPolicy

class SceneTransientOverlayReturnTest {
    private val owner = SessionOwner(10001, "dev.remrss.test", "cert", SessionPrincipal.SignedMod("author", "scene-mod", "logical"))
    private val component = ComponentName("dev.remrss.test", "dev.remrss.test.RearSceneActivity")

    @Test fun endedOverlayRestoresExactPreviouslyVisibleSceneOnCurrentRearDisplay() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene()
            assertTrue(fixture.coordinator.beginTransientOverlay(10L))
            assertTrue(fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false).success)

            assertTrue(fixture.coordinator.endTransientOverlay(10L))
            assertTrue("same Scene task is returned through the existing recovery gateway", fixture.recovered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091), fixture.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)
            assertEquals("only the initial confirmed Scene launch prepared the display", 1, fixture.gateway.prepareCalls)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true)
            assertEquals("a successful exact-task present followed by its visibility report does not loop", 1, fixture.gateway.callCount)
        } finally { fixture.close() }
    }

    @Test fun keepVisibleFalseReturnsExactSceneWhenOnButWaitsWithoutWakeUntilRearIsOn() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene(keepVisible = false)
            fixture.coordinator.beginTransientOverlay(11L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.endTransientOverlay(11L)

            assertTrue("keepVisible=false still returns an exact live Scene while already ON", fixture.recovered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091), fixture.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)
            assertEquals("no wake/prepare beyond the original Scene launch", 1, fixture.gateway.prepareCalls)
        } finally { fixture.close() }
    }

    @Test fun keepVisibleFalseEndingWhileOffWaitsForRealOnEdgeWithoutWakeOrError() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene(keepVisible = false)
            fixture.coordinator.beginTransientOverlay(12L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
            fixture.coordinator.endTransientOverlay(12L)

            assertFalse("OFF keepVisible=false stays pending instead of reaching a wake gateway", fixture.recovered.await(450, TimeUnit.MILLISECONDS))
            assertTrue(fixture.gateway.recoveredTaskIds.isEmpty())
            assertTrue("there is no false CLAIMED result", fixture.gateway.wakeDecisions.isEmpty())
            assertEquals("only the original Scene launch prepared the display", 1, fixture.gateway.prepareCalls)
            assertEquals("no BackendLost is emitted for a deferred ON-only return", 0, fixture.backendLostEvents.get())

            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            assertTrue("the same pending lease returns after the real ON edge", fixture.recovered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091), fixture.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)
            assertEquals(1, fixture.gateway.prepareCalls)
        } finally { fixture.close() }
    }

    @Test fun keepVisibleFalseNearDuringOverlayDefersOnReturnUntilFar() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene(keepVisible = false)
            fixture.coordinator.beginTransientOverlay(14L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(true))
            fixture.coordinator.endTransientOverlay(14L)

            assertFalse("near is a temporary barrier; it must not discard the exact return cause", fixture.recovered.await(450, TimeUnit.MILLISECONDS))
            assertTrue(fixture.gateway.recoveredTaskIds.isEmpty())
            assertTrue(fixture.gateway.wakeDecisions.isEmpty())
            assertEquals(1, fixture.gateway.prepareCalls)

            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(false))
            assertTrue("far plus already-ON rear returns the same Scene task", fixture.recovered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091), fixture.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)
            assertEquals("far restoration does not wake or prepare again", 1, fixture.gateway.prepareCalls)
        } finally { fixture.close() }
    }

    @Test fun keepVisibleFalseNearThenFarWhileOffWaitsForOnAndNeverWakes() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene(keepVisible = false)
            fixture.coordinator.beginTransientOverlay(15L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(true))
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
            fixture.coordinator.endTransientOverlay(15L)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(false))

            assertFalse("far while rear is OFF still cannot send the keepVisible=false task through wake", fixture.recovered.await(450, TimeUnit.MILLISECONDS))
            assertTrue(fixture.gateway.recoveredTaskIds.isEmpty())
            assertTrue(fixture.gateway.wakeDecisions.isEmpty())
            assertEquals(1, fixture.gateway.prepareCalls)

            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            assertTrue("the same valid return cause resumes only at real ON", fixture.recovered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091), fixture.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)
            assertEquals(1, fixture.gateway.prepareCalls)
        } finally { fixture.close() }
    }

    @Test fun keepVisibleFalsePendingOnOffCannotOutliveCloseOrProjectionEpochChange() {
        listOf("close", "owner_revoke", "projection_takeover", "projection_epoch").forEachIndexed { index, invalidation ->
            val fixture = Fixture()
            try {
                val handle = fixture.openVisibleScene(keepVisible = false)
                fixture.coordinator.beginTransientOverlay(13L + index)
                fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
                fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(true))
                fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
                fixture.coordinator.endTransientOverlay(13L + index)
                when (invalidation) {
                    "close" -> fixture.coordinator.closeScene(owner, handle.sessionId)
                    "owner_revoke" -> fixture.coordinator.closeOwnerSessions(owner, SceneCloseReason.OWNER_REVOKED)
                    "projection_takeover" -> fixture.coordinator.closeOwnerSessions(owner, SceneCloseReason.PREEMPTED)
                    else -> fixture.ownershipEpoch.incrementAndGet()
                }
                fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(false))
                fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))

                assertFalse("a stale OFF→ON callback cannot revive $invalidation Scene", fixture.recovered.await(450, TimeUnit.MILLISECONDS))
                assertTrue(fixture.gateway.recoveredTaskIds.isEmpty())
                assertTrue(fixture.gateway.wakeDecisions.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test fun notificationToChargingReplacementPreservesReturnAcrossRearOffAndOn() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene()
            assertTrue(fixture.coordinator.beginTransientOverlay(20L))
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            assertTrue(fixture.coordinator.beginTransientOverlay(21L, replacesOverlayId = 20L))
            assertFalse("stale end cannot uncover the replacement", fixture.coordinator.endTransientOverlay(20L))

            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOff)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOn)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            assertTrue(fixture.coordinator.endTransientOverlay(21L))

            assertTrue("the surviving display-on edge returns the same task after the overlay chain", fixture.recovered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091), fixture.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)
        } finally { fixture.close() }
    }

    @Test fun overlayEndingWhileRearIsOffGetsOneLeaseScopedRestoreAndNoRepeatedWake() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene()
            fixture.coordinator.beginTransientOverlay(22L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
            fixture.coordinator.endTransientOverlay(22L)

            assertTrue("the original keepVisible Scene is restored once through its lease", fixture.recovered.await(2, TimeUnit.SECONDS))
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
            fixture.coordinator.endTransientOverlay(22L)
            assertEquals(listOf(56091), fixture.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.CLAIMED), fixture.gateway.wakeDecisions)
            assertEquals("initial Scene preparation plus one recovery action", 2, fixture.gateway.prepareCalls)
        } finally { fixture.close() }
    }

    @Test fun failedSingleWakeRetainsExactReturnUntilRealOnWithoutRepeatingWake() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene()
            fixture.gateway.failFirstWake = true
            fixture.gateway.holdFailedWake = true
            fixture.coordinator.beginTransientOverlay(23L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
            fixture.coordinator.endTransientOverlay(23L)

            assertTrue("the owned lease makes exactly one wake attempt", fixture.gateway.firstWakeEntered.await(2, TimeUnit.SECONDS))
            assertTrue("fake backend's final OFF observation is complete", fixture.gateway.failedWakeObserved.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091), fixture.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.CLAIMED), fixture.gateway.wakeDecisions)
            assertEquals("failed one-shot never falls back to another prepare/wake", 1, fixture.gateway.prepareCalls)

            // Restore the valid race: the backend has already observed physical OFF and is
            // holding its failed reply; a genuine ON edge arrives before Core consumes it.
            // That newer edge must authorize one no-wake exact-task confirmation, not require
            // another OFF->ON cycle.
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            fixture.gateway.releaseFailedWake.countDown()
            assertTrue("the already-arrived real ON edge permits one no-wake exact presentation",
                fixture.gateway.secondRecovery.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091, 56091), fixture.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.CLAIMED, SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)
            assertEquals("ON presentation does not prepare/wake again", 1, fixture.gateway.prepareCalls)
        } finally { fixture.close() }
    }

    @Test fun cachedOnButPhysicalDozeUsesOneKeepLeaseWakeThenConfirmsTheSameTaskOn() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene()
            fixture.gateway.physicalRearOn = false
            fixture.gateway.wakeChangesPhysicalState = true
            fixture.gateway.requirePhysicalConfirmation = true
            fixture.coordinator.beginTransientOverlay(24L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.endTransientOverlay(24L)

            assertTrue("fresh physical DOZE authorizes only the existing keep lease's one-shot wake", fixture.recovered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(SceneRecoveryWakeDecision.CLAIMED), fixture.gateway.wakeDecisions)
            assertEquals(1, fixture.gateway.callCount)
            assertEquals("initial Scene preparation plus exactly one lease-scoped wake", 2, fixture.gateway.prepareCalls)
            assertEquals(true, fixture.gateway.physicalRearOn)
            assertEquals("exact-task success has a fresh physical-ON confirmation", 1, fixture.gateway.physicalConfirmations)
            assertEquals(0, fixture.backendLostEvents.get())
        } finally { fixture.close() }
    }

    @Test fun cachedOnButPhysicalOffNonKeepSceneWaitsForRealOnWithoutWaking() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene(keepVisible = false)
            fixture.gateway.physicalRearOn = false
            fixture.gateway.requirePhysicalConfirmation = true
            fixture.coordinator.beginTransientOverlay(241L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.endTransientOverlay(241L)

            assertTrue("fresh physical OFF parks a non-keep Scene return", fixture.recovered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY), fixture.gateway.wakeDecisions)
            assertEquals(1, fixture.gateway.callCount)
            assertEquals("false keep lease never prepares or wakes", 1, fixture.gateway.prepareCalls)
            assertEquals(0, fixture.gateway.physicalConfirmations)

            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation,
                SceneDisplaySignal.RearDisplayPower(false))
            fixture.gateway.physicalRearOn = true
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation,
                SceneDisplaySignal.RearDisplayPower(true))
            assertTrue("actual ON permits one same-task no-wake confirmation", fixture.gateway.secondRecovery.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY, SceneRecoveryWakeDecision.ALREADY_ON),
                fixture.gateway.wakeDecisions)
            assertEquals(1, fixture.gateway.prepareCalls)
            assertEquals(1, fixture.gateway.physicalConfirmations)
        } finally { fixture.close() }
    }

    @Test fun actionGateTransportFailureClearsCaptureAndEmitsBackendLostWithoutDeferredRetry() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene()
            fixture.gateway.failWithActionGateTransport = true
            fixture.coordinator.beginTransientOverlay(241L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.endTransientOverlay(241L)

            assertTrue("the mapped gate transport failure reaches coordinator terminal handling",
                fixture.recovered.await(2, TimeUnit.SECONDS))
            assertEquals(1, fixture.gateway.callCount)
            assertTrue("Core emits the original terminal BackendLost outcome",
                fixture.backendLost.await(2, TimeUnit.SECONDS))
            assertEquals(1, fixture.backendLostEvents.get())
            assertFalse("an unavailable validator is not an overlay deferral/requeue",
                fixture.gateway.secondRecovery.await(450, TimeUnit.MILLISECONDS))

            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation,
                SceneDisplaySignal.RearDisplayPower(false))
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation,
                SceneDisplaySignal.RearDisplayPower(true))
            assertEquals("the failed backend capture was released, not left as hidden pending work", 1,
                fixture.gateway.callCount)
        } finally { fixture.close() }
    }

    @Test fun mismatchedReplacementTokenCannotRebaseAnOldSceneReturnLease() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene()
            fixture.coordinator.beginTransientOverlay(25L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.beginTransientOverlay(26L, replacesOverlayId = 999L)
            fixture.coordinator.endTransientOverlay(26L)
            assertFalse("a replacement without the exact prior token cannot restore its Scene", fixture.recovered.await(450, TimeUnit.MILLISECONDS))
            assertTrue(fixture.gateway.recoveredTaskIds.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun resumedActivityIsRevalidatedByExactTaskGatewayWithoutWakeOrDuplicatePresent() {
        val resumed = Fixture()
        try {
            val handle = resumed.openVisibleScene()
            resumed.coordinator.beginTransientOverlay(30L)
            resumed.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            resumed.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true)
            resumed.coordinator.endTransientOverlay(30L)
            assertTrue("Activity visibility alone is not the exact-task presentation proof", resumed.recovered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091), resumed.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.ALREADY_ON), resumed.gateway.wakeDecisions)
            resumed.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true)
            assertEquals("post-present visibility feedback does not schedule another exact-task action", 1, resumed.gateway.callCount)
        } finally { resumed.close() }

        listOf("explicit_close", "owner_revoke", "projection_takeover", "projection_epoch").forEachIndexed { index, invalidation ->
            val fixture = Fixture()
            try {
                val handle = fixture.openVisibleScene()
                fixture.coordinator.beginTransientOverlay(40L + index)
                fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
                when (invalidation) {
                    "explicit_close" -> assertTrue(fixture.coordinator.closeScene(owner, handle.sessionId).success)
                    "owner_revoke" -> fixture.coordinator.closeOwnerSessions(owner, SceneCloseReason.OWNER_REVOKED)
                    "projection_takeover" -> fixture.coordinator.closeOwnerSessions(owner, SceneCloseReason.PREEMPTED)
                    else -> fixture.ownershipEpoch.incrementAndGet() // verified legacy projection changes the display owner epoch
                }
                fixture.coordinator.endTransientOverlay(40L + index)
                assertFalse("$invalidation must invalidate the original restore lease", fixture.recovered.await(450, TimeUnit.MILLISECONDS))
                assertTrue(fixture.gateway.recoveredTaskIds.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test fun focusAcceptedBeforeWindowSnapshotParksThenReturnsOnRealWindowEdgeWithoutPowerChange() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene(keepVisible = true)
            fixture.gateway.physicalRearOn = true
            fixture.gateway.rejectUntilWindowVisible = true
            fixture.coordinator.beginTransientOverlay(31L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.endTransientOverlay(31L)

            assertTrue("first exact snapshot can lag focus acceptance", fixture.gateway.firstWindowRace.await(2, TimeUnit.SECONDS))
            assertTrue("one bounded post-focus confirmation is attempted", fixture.gateway.secondWindowRace.await(2, TimeUnit.SECONDS))
            assertFalse("a stable non-visible window does not trigger focus polling", fixture.recovered.await(450, TimeUnit.MILLISECONDS))
            assertEquals(2, fixture.gateway.callCount)
            assertEquals(1, fixture.gateway.prepareCalls)
            assertEquals(listOf(SceneRecoveryWakeDecision.ALREADY_ON, SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)

            // Mirrors ModRearSceneActivity's actual window edge report; no rear power event
            // is injected. The parked exact lease can now confirm the same task once.
            fixture.gateway.windowActuallyVisible = true
            assertTrue(fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
            assertTrue("the same task returns after its real visibility edge", fixture.recovered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091, 56091, 56091), fixture.gateway.recoveredTaskIds)
            assertEquals(3, fixture.gateway.callCount)
            assertEquals(1, fixture.gateway.prepareCalls)
            assertEquals(0, fixture.backendLostEvents.get())
            Thread.sleep(450)
            assertEquals("visible feedback is terminal, not a retry loop", 3, fixture.gateway.callCount)
        } finally { fixture.close() }
    }

    @Test fun cachedOffSingleWakeThenPresentationOnlyConfirmationUpdatesRealCoordinatorSnapshot() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene(keepVisible = true)
            fixture.gateway.physicalRearOn = false
            fixture.gateway.wakeChangesPhysicalState = true
            fixture.gateway.requirePhysicalConfirmation = true
            fixture.gateway.completeWakeBeforeWindowRejection = true
            fixture.gateway.rejectUntilWindowVisible = true
            fixture.gateway.returnVerifiedPresentation = true
            fixture.coordinator.beginTransientOverlay(32L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
            fixture.coordinator.endTransientOverlay(32L)

            assertTrue("first focus can race a window that has not resumed", fixture.gateway.firstWindowRace.await(2, TimeUnit.SECONDS))
            assertTrue("one no-wake presentation check proceeds despite the stale OFF cache", fixture.gateway.secondWindowRace.await(2, TimeUnit.SECONDS))
            assertFalse("presentation-only checking must not spend a second wake", fixture.recovered.await(450, TimeUnit.MILLISECONDS))
            assertEquals(listOf(SceneRecoveryWakeDecision.CLAIMED, SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)
            assertEquals("initial Scene preparation plus exactly one wake", 2, fixture.gateway.prepareCalls)
            assertEquals(true, fixture.gateway.physicalRearOn)

            var appliedWindowKeepFlag = false
            val windowGate = ModSceneWindowLifecycleGate(handle.sessionId, handle.generation) { state ->
                appliedWindowKeepFlag = ModSceneWindowPolicy(
                    windowReady = true,
                    windowAttachedAndVisible = state.windowVisible,
                    coordinatorVisible = state.coordinatorVisible,
                    keepVisibleRequested = true,
                ).keepScreenOn
            }
            windowGate.onActivityStarted(true)
            windowGate.onWindowStateChanged(attached = true, viewShown = true, windowVisible = true)
            var snapshot = fixture.coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation)
            assertFalse("the logical Scene snapshot stays hidden until its Activity reports the actual window", snapshot!!.visible)
            var epoch = windowGate.currentWindowRequestEpoch()
            assertTrue(windowGate.applyPresentationSnapshot(snapshot, epoch))
            fun policy(): ModSceneWindowPolicy {
                val state = windowGate.currentState()
                return ModSceneWindowPolicy(true, state.windowVisible, state.coordinatorVisible, keepVisibleRequested = true)
            }
            assertFalse("the window must not claim keep before Core's authoritative result", policy().keepScreenOn)

            fixture.gateway.windowActuallyVisible = true
            assertTrue(fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
            assertTrue("real window visibility allows a final exact-task physical-ON confirmation",
                fixture.gateway.verifiedPresentation.await(2, TimeUnit.SECONDS))
            assertTrue("Core emits visible only after the backend-authorized result", fixture.sceneVisibleEvents.await(2, TimeUnit.SECONDS))
            snapshot = fixture.coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation)
            assertTrue("the production Coordinator snapshot now reports the actually visible task", snapshot!!.visible)
            epoch = windowGate.currentWindowRequestEpoch()
            assertTrue(windowGate.applyPresentationSnapshot(snapshot, epoch))
            assertTrue("Mod reapplies the authorized keep lease from the fresh Core snapshot", policy().keepScreenOn)
            assertTrue("the production Gate callback applies the restored Window flag", appliedWindowKeepFlag)
            appliedWindowKeepFlag = false // model an external transient loss of the platform window attribute
            assertTrue(windowGate.applyPresentationSnapshot(snapshot, epoch))
            assertTrue("the same current Core snapshot reasserts the Activity-scoped Window flag", appliedWindowKeepFlag)
            assertEquals(listOf(SceneRecoveryWakeDecision.CLAIMED, SceneRecoveryWakeDecision.ALREADY_ON,
                SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)
            assertEquals("presentation retries do not renew the one-shot wake", 2, fixture.gateway.prepareCalls)
            assertEquals(3, fixture.gateway.callCount)
            assertEquals(1, fixture.gateway.physicalConfirmations)
        } finally { fixture.close() }
    }

    @Test fun presentationOnlyProbeStillOffWaitsWithoutSecondWakeUntilRealOn() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene(keepVisible = true)
            fixture.gateway.physicalRearOn = false
            fixture.gateway.completeWakeBeforeWindowRejection = true
            fixture.gateway.requirePhysicalConfirmation = true
            fixture.gateway.returnVerifiedPresentation = true
            fixture.gateway.rejectUntilWindowVisible = true
            fixture.gateway.windowRaceRejectLimit = 1
            fixture.coordinator.beginTransientOverlay(34L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation,
                SceneDisplaySignal.RearDisplayPower(false))
            fixture.coordinator.endTransientOverlay(34L)

            assertTrue(fixture.gateway.firstWindowRace.await(2, TimeUnit.SECONDS))
            assertTrue("the post-focus check is presentation-only even though the rear is still OFF",
                fixture.gateway.secondRecovery.await(2, TimeUnit.SECONDS))
            Thread.sleep(450)
            assertEquals(listOf(SceneRecoveryWakeDecision.CLAIMED, SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY),
                fixture.gateway.wakeDecisions)
            assertEquals("only the initial Scene launch and original one-shot wake prepare", 2, fixture.gateway.prepareCalls)
            assertEquals(2, fixture.gateway.callCount)

            fixture.gateway.physicalRearOn = true
            fixture.gateway.windowActuallyVisible = true
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            assertTrue("a real ON event resumes the same lease without another wake", fixture.gateway.verifiedPresentation.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(SceneRecoveryWakeDecision.CLAIMED, SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY,
                SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)
            assertEquals(2, fixture.gateway.prepareCalls)
            assertTrue(fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
            assertTrue(fixture.coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation)!!.visible)
        } finally { fixture.close() }
    }

    @Test fun presentationPhaseAndConsumedWakeBudgetSurviveOverlayInterruptingGatewayReply() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene(keepVisible = true)
            fixture.gateway.physicalRearOn = false
            fixture.gateway.wakeChangesPhysicalState = true
            fixture.gateway.completeWakeBeforeWindowRejection = true
            fixture.gateway.rejectUntilWindowVisible = true
            fixture.gateway.windowRaceRejectLimit = 2
            fixture.gateway.blockWindowRaceAtCount = 2
            fixture.coordinator.beginTransientOverlay(35L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation,
                SceneDisplaySignal.RearDisplayPower(false))
            fixture.coordinator.endTransientOverlay(35L)

            assertTrue("the original keep lease consumes exactly one wake", fixture.gateway.wakeSettled.await(2, TimeUnit.SECONDS))
            assertTrue("the post-focus presentation-only query is held in flight", fixture.gateway.blockedWindowRace.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(SceneRecoveryWakeDecision.CLAIMED, SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)

            // A new notification invalidates the per-capture token while the same cause is awaiting
            // a window. Its end resumes the cause, not a fresh wake budget or pre-focus mode.
            assertTrue(fixture.coordinator.beginTransientOverlay(36L))
            fixture.gateway.releaseWindowRace.countDown()
            Thread.sleep(100) // let the stale-token await reply persist its phase on the exact cause
            fixture.gateway.physicalRearOn = false
            assertTrue(fixture.coordinator.endTransientOverlay(36L))

            assertTrue("interrupted cause resumes once through the gateway", fixture.gateway.thirdRecovery.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091, 56091, 56091), fixture.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.CLAIMED, SceneRecoveryWakeDecision.ALREADY_ON,
                SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY), fixture.gateway.wakeDecisions)
            assertEquals("an already-consumed wake is not reclaimed after overlay end", 2, fixture.gateway.prepareCalls)
            Thread.sleep(350)
            assertEquals("stable OFF is parked rather than retried from cached ON", 3, fixture.gateway.callCount)

            fixture.gateway.physicalRearOn = true
            fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation,
                SceneDisplaySignal.RearDisplayPower(true))
            assertTrue("real ON completes the same exact Scene cause", fixture.gateway.fourthRecovery.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(56091, 56091, 56091, 56091), fixture.gateway.recoveredTaskIds)
            assertEquals(listOf(SceneRecoveryWakeDecision.CLAIMED, SceneRecoveryWakeDecision.ALREADY_ON,
                SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY, SceneRecoveryWakeDecision.ALREADY_ON), fixture.gateway.wakeDecisions)
            assertEquals(2, fixture.gateway.prepareCalls)
        } finally {
            fixture.gateway.releaseWindowRace.countDown()
            fixture.close()
        }
    }

    @Test fun presentationPhaseDoesNotConsumeUnusedWakeAfterAlreadyOnRacesToOff() {
        val fixture = Fixture()
        try {
            val handle = fixture.openVisibleScene(keepVisible = true)
            fixture.gateway.physicalRearOn = true
            fixture.gateway.rejectUntilWindowVisible = true
            fixture.gateway.windowRaceRejectLimit = 1
            fixture.gateway.holdAfterFirstAlreadyOn = true
            fixture.gateway.completeWakeBeforeWindowRejection = true
            fixture.coordinator.beginTransientOverlay(37L)
            fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
            fixture.coordinator.endTransientOverlay(37L)

            assertTrue("initial exact check sees rear ON before any wake is claimed", fixture.gateway.firstAlreadyOn.await(2, TimeUnit.SECONDS))
            fixture.gateway.physicalRearOn = false
            fixture.gateway.releaseFirstAlreadyOn.countDown()
            assertTrue("same-cause presentation confirmation can claim its still-unused keep wake",
                fixture.gateway.secondRecovery.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(SceneRecoveryWakeDecision.ALREADY_ON, SceneRecoveryWakeDecision.CLAIMED), fixture.gateway.wakeDecisions)
            assertEquals("initial launch plus only one late OFF wake", 2, fixture.gateway.prepareCalls)
        } finally {
            fixture.gateway.releaseFirstAlreadyOn.countDown()
            fixture.close()
        }
    }

    @Test fun newerOffOrSceneInvalidationCannotBeOverwrittenByLateVerifiedPresentation() {
        listOf("rear_off", "close", "revoke", "cast", "epoch").forEach { invalidation ->
            val fixture = Fixture()
            try {
                val handle = fixture.openVisibleScene(keepVisible = true)
                fixture.gateway.physicalRearOn = false
                fixture.gateway.wakeChangesPhysicalState = true
                fixture.gateway.requirePhysicalConfirmation = true
                fixture.gateway.completeWakeBeforeWindowRejection = true
                fixture.gateway.rejectUntilWindowVisible = true
                fixture.gateway.returnVerifiedPresentation = true
                fixture.gateway.holdVerifiedPresentation = true
                fixture.coordinator.beginTransientOverlay(33L)
                fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false)
                fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
                fixture.coordinator.endTransientOverlay(33L)
                assertTrue(fixture.gateway.secondWindowRace.await(2, TimeUnit.SECONDS))
                fixture.gateway.windowActuallyVisible = true
                fixture.coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true)
                assertTrue("backend has exact ON/task proof before the app receives its reply",
                    fixture.gateway.verifiedPresentationEntered.await(2, TimeUnit.SECONDS))

                when (invalidation) {
                    "rear_off" -> {
                        fixture.gateway.physicalRearOn = false
                        fixture.coordinator.handleDisplaySignal(handle.sessionId, handle.generation,
                            SceneDisplaySignal.RearDisplayPower(false))
                    }
                    "close" -> fixture.coordinator.closeScene(owner, handle.sessionId)
                    "revoke" -> fixture.coordinator.closeOwnerSessions(owner, SceneCloseReason.OWNER_REVOKED)
                    "cast" -> fixture.coordinator.closeOwnerSessions(owner, SceneCloseReason.PREEMPTED)
                    "epoch" -> fixture.ownershipEpoch.incrementAndGet()
                }
                fixture.gateway.releaseVerifiedPresentation.countDown()
                assertTrue(fixture.gateway.verifiedPresentation.await(2, TimeUnit.SECONDS))
                Thread.sleep(450) // allow the coordinator to consume the deliberately late backend reply
                val snapshot = fixture.coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation)
                assertTrue("late backend success cannot revive $invalidation", snapshot == null || !snapshot.visible)
                assertEquals("no extra wake is admitted during late-result cleanup", 2, fixture.gateway.prepareCalls)
            } finally {
                fixture.gateway.releaseVerifiedPresentation.countDown()
                fixture.close()
            }
        }
    }

    private inner class Fixture {
        val recovered = CountDownLatch(1)
        val gateway = FakeGateway(recovered)
        private val scheduler = ScheduledThreadPoolExecutor(1)
        private val tokenSequence = AtomicLong(0L)
        val ownershipEpoch = AtomicLong(7L)
        val backendLostEvents = AtomicInteger(0)
        val backendLost = CountDownLatch(1)
        val sceneVisibleEvents = CountDownLatch(2)
        val coordinator = RearSceneCoordinator(
            gateway = gateway,
            activationVerifier = UserActivationVerifier { _, _, _ -> true },
            callbackExecutor = java.util.concurrent.Executor { it.run() },
            expiryScheduler = scheduler,
            clock = { 1_000L },
            randomToken = { "fixture-token-${tokenSequence.incrementAndGet()}" },
            displayOwnershipEpoch = { ownershipEpoch.get() }
        )

        fun openVisibleScene(keepVisible: Boolean = true): SceneSessionHandle {
            val result = coordinator.openScene(owner, SceneRequest(component, keepVisible = keepVisible), UserActivationToken("user-action"), object : SceneObserver {
                override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {
                    if (event == SceneEvent.BackendLost) {
                        backendLostEvents.incrementAndGet()
                        backendLost.countDown()
                    }
                    if (event is SceneEvent.VisibilityChanged && event.visible) sceneVisibleEvents.countDown()
                }
            })
            val handle = (result as SceneOpenResult.Accepted).handle
            val viewport = RearViewportSnapshot(
                revision = 1L, displayId = 1, physicalDisplayPx = PixelSize(720, 1280),
                contentBoundsPx = PixelRect(0, 0, 720, 1280), windowPx = PixelSize(720, 1280),
                windowDp = DpSize(360f, 640f), densityDpi = 320, renderBufferPx = PixelSize(720, 1280),
                displayRotation = 0, contentRotation = 0,
                inputTransform = CoordinateTransform(1f, 0f, 0f, 0f, 1f, 0f)
            )
            assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 56091, viewport)).success)
            coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
            return handle
        }

        fun close() {
            scheduler.shutdownNow()
        }
    }

    private class FakeGateway(private val recovered: CountDownLatch) : RearSceneGateway {
        val recoveredTaskIds = mutableListOf<Int>()
        val wakeDecisions = mutableListOf<SceneRecoveryWakeDecision>()
        val secondRecovery = CountDownLatch(1)
        val thirdRecovery = CountDownLatch(1)
        val fourthRecovery = CountDownLatch(1)
        val wakeSettled = CountDownLatch(1)
        val blockedWindowRace = CountDownLatch(1)
        val releaseWindowRace = CountDownLatch(1)
        val firstAlreadyOn = CountDownLatch(1)
        val releaseFirstAlreadyOn = CountDownLatch(1)
        val firstWakeEntered = CountDownLatch(1)
        val failedWakeObserved = CountDownLatch(1)
        val releaseFailedWake = CountDownLatch(1)
        private val recoveryCount = AtomicInteger(0)
        var failFirstWake = false
        var holdFailedWake = false
        var failWithActionGateTransport = false
        var physicalRearOn: Boolean? = null
        var wakeChangesPhysicalState = false
        var requirePhysicalConfirmation = false
        var physicalConfirmations = 0
        var prepareCalls = 0
        var rejectUntilWindowVisible = false
        var completeWakeBeforeWindowRejection = false
        var returnVerifiedPresentation = false
        var holdVerifiedPresentation = false
        var windowRaceRejectLimit = Int.MAX_VALUE
        var blockWindowRaceAtCount = -1
        var holdAfterFirstAlreadyOn = false
        @Volatile var windowActuallyVisible = false
        val firstWindowRace = CountDownLatch(1)
        val secondWindowRace = CountDownLatch(1)
        val verifiedPresentationEntered = CountDownLatch(1)
        val releaseVerifiedPresentation = CountDownLatch(1)
        val verifiedPresentation = CountDownLatch(1)
        override fun queryCapabilities(owner: SessionOwner) = CapabilitySnapshot("test", true, true, emptyList())
        override fun authorize(owner: SessionOwner, request: SceneRequest): SceneError? = null
        override fun targetDisplayId(): Int? = null
        override fun prepareDisplay(owner: SessionOwner, request: SceneRequest): SceneError? { prepareCalls++; return null }
        override fun launchNewTask(owner: SessionOwner, request: SceneRequest, launchTicket: String, expiresAtElapsedRealtimeMs: Long) =
            LaunchedScene(request.component, 56091, 1)
        override fun verifyLaunchedTask(owner: SessionOwner, expected: LaunchedScene, report: SceneWindowReport) = true
        override fun finishOwnedTask(owner: SessionOwner, task: LaunchedScene) = Unit
        override fun recoverOwnedDisplay(owner: SessionOwner, request: SceneRequest, task: LaunchedScene,
                                         cachedRearOn: Boolean?, isCurrent: () -> Boolean,
                                         onWakeAttempt: (Boolean?) -> SceneRecoveryWakeDecision): SceneRecoveryResult {
            if (!isCurrent()) return SceneRecoveryResult(SceneError.STALE_SESSION)
            val decision = onWakeAttempt(physicalRearOn ?: cachedRearOn)
            val count = recoveryCount.incrementAndGet()
            synchronized(this) {
                recoveredTaskIds += task.taskId
                wakeDecisions += decision
            }
            if (count >= 2) secondRecovery.countDown()
            if (count >= 3) thirdRecovery.countDown()
            if (count >= 4) fourthRecovery.countDown()
            if (holdAfterFirstAlreadyOn && count == 1 && decision == SceneRecoveryWakeDecision.ALREADY_ON) {
                firstAlreadyOn.countDown()
                releaseFirstAlreadyOn.await(2, TimeUnit.SECONDS)
            }
            if (completeWakeBeforeWindowRejection && decision == SceneRecoveryWakeDecision.CLAIMED) {
                val error = prepareDisplay(owner, request)
                if (error != null) return SceneRecoveryResult(error)
                if (wakeChangesPhysicalState) physicalRearOn = true
            }
            if (rejectUntilWindowVisible && !windowActuallyVisible && count <= windowRaceRejectLimit) {
                if (count == 1) firstWindowRace.countDown()
                if (count == 2) secondWindowRace.countDown()
                if (count == blockWindowRaceAtCount) {
                    blockedWindowRace.countDown()
                    releaseWindowRace.await(2, TimeUnit.SECONDS)
                }
                if (count == 1 && decision == SceneRecoveryWakeDecision.CLAIMED) wakeSettled.countDown()
                return sceneRecoveryResultForPresentationStatus("scene_not_visible_after_focus")
            }
            if (failFirstWake && count == 1 && decision == SceneRecoveryWakeDecision.CLAIMED) {
                if (holdFailedWake) {
                    firstWakeEntered.countDown()
                    failedWakeObserved.countDown()
                    releaseFailedWake.await(2, TimeUnit.SECONDS)
                }
                recovered.countDown()
                return SceneRecoveryResult(SceneError.DISPLAY_UNAVAILABLE, awaitDisplayOn = true)
            }
            if (failWithActionGateTransport) {
                recovered.countDown()
                return sceneRecoveryResultForPresentationStatus("action_gate_unavailable")
            }
            val result = when (decision) {
                SceneRecoveryWakeDecision.ALREADY_ON -> SceneRecoveryResult()
                SceneRecoveryWakeDecision.CLAIMED -> {
                    if (completeWakeBeforeWindowRejection) SceneRecoveryResult()
                    else {
                    val error = prepareDisplay(owner, request)
                    if (error == null && wakeChangesPhysicalState) physicalRearOn = true
                    SceneRecoveryResult(error)
                    }
                }
                SceneRecoveryWakeDecision.WAIT_FOR_DISPLAY -> SceneRecoveryResult(SceneError.DISPLAY_UNAVAILABLE, awaitDisplayOn = true)
                SceneRecoveryWakeDecision.STALE -> SceneRecoveryResult(SceneError.STALE_SESSION)
            }
            if (requirePhysicalConfirmation && result.error == null) {
                if (physicalRearOn != true) {
                    recovered.countDown()
                    return sceneRecoveryResultForPresentationStatus("rear_display_not_on")
                }
                physicalConfirmations++
            }
            if (returnVerifiedPresentation && result.error == null) {
                if (holdVerifiedPresentation) {
                    verifiedPresentationEntered.countDown()
                    releaseVerifiedPresentation.await(2, TimeUnit.SECONDS)
                }
                verifiedPresentation.countDown()
                return sceneRecoveryResultForPresentationStatus("already_scene_visible")
            }
            recovered.countDown()
            return result
        }

        val callCount: Int get() = recoveryCount.get()
    }
}
