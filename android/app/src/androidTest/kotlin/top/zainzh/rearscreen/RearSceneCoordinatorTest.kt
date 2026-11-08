package top.zainzh.rearscreen

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.platform.*
import dev.remrss.platform.sdk.RearScenePresentation
import dev.remrss.platform.sdk.RearScenePresentationGate
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class RearSceneCoordinatorTest {
    private val component = ComponentName("example.scoreboard", "example.scoreboard.RearActivity")
    private val owner = SessionOwner(12000, "example.scoreboard", "apk-cert", SessionPrincipal.AndroidClient(12000, "example.scoreboard", "apk-cert", 7))
    private val other = owner.copy(principal = SessionPrincipal.AndroidClient(12001, "example.other", "other-cert", 1), transportUid = 12001, transportPackageName = "example.other", transportSigningCertificateDigest = "other-cert")

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    @Test fun orphanedMonitorCloseNeverHoldsCoreLockAcrossLateDisableFarCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("FlutterSharedPreferences", 0)
        val priorPreference = prefs.getBoolean("flutter.proximity_sensor_enabled", true)
        prefs.edit().putBoolean("flutter.proximity_sensor_enabled", true).commit()

        val source = FakeProximitySensorPort()
        val monitor = AndroidSceneDisplayLifecycleMonitor(context,
            SceneProximitySensorPortFactory { _, handler -> source.useHandler(handler) })
        val monitorStarted = CountDownLatch(1)
        val allowStartReturn = CountDownLatch(1)
        val nearDelivered = CountDownLatch(1)
        val blockFarOnce = AtomicBoolean(false)
        val farDispatchEntered = CountDownLatch(1)
        val allowFarDispatch = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val lifecycleMonitor = SceneDisplayLifecycleMonitor { displayId, onSignal ->
            val delegate = monitor.start(displayId) { signal ->
                if (signal == SceneDisplaySignal.RearProximity(false) && blockFarOnce.compareAndSet(true, false)) {
                    farDispatchEntered.countDown()
                    check(allowFarDispatch.await(3, TimeUnit.SECONDS))
                }
                onSignal(signal)
                if (signal == SceneDisplaySignal.RearProximity(true)) nearDelivered.countDown()
            }
            monitorStarted.countDown()
            check(allowStartReturn.await(3, TimeUnit.SECONDS))
            Closeable {
                closeEntered.countDown()
                delegate.close()
            }
        }
        val coordinator = coordinator(FakeGateway(component), { true }, lifecycleMonitor = lifecycleMonitor)
        val pool = Executors.newSingleThreadExecutor()

        try {
            val opening = pool.submit<SceneOpenResult> {
                coordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed"),
                    object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} })
            }
            assertTrue("real production monitor registered while open is waiting to hand off lifecycle", monitorStarted.await(2, TimeUnit.SECONDS))
            source.emit(0f)
            assertTrue("near first establishes an active covered state", nearDelivered.await(2, TimeUnit.SECONDS))
            assertTrue(source.registered)

            // This closes the reservation before start() returns its resource to openScene.
            coordinator.closeOwnerSessions(owner, SceneCloseReason.CLIENT_CLOSED)
            blockFarOnce.set(true)
            prefs.edit().putBoolean("flutter.proximity_sensor_enabled", false).commit()
            assertTrue("preference disable reaches its final far callback while holding source lifecycle ownership",
                farDispatchEntered.await(2, TimeUnit.SECONDS))

            allowStartReturn.countDown()
            assertTrue("orphan lease cleanup starts while the far callback is in flight", closeEntered.await(2, TimeUnit.SECONDS))
            allowFarDispatch.countDown()
            val result = opening.get(3, TimeUnit.SECONDS)
            assertTrue("closed reservation is rejected rather than resurrected", result is SceneOpenResult.Rejected)
            assertTrue("late disable and orphan close both finish without Core/source lock inversion", waitUntil(2_000) { !source.registered })
        } finally {
            allowStartReturn.countDown()
            allowFarDispatch.countDown()
            prefs.edit().putBoolean("flutter.proximity_sensor_enabled", priorPreference).commit()
            pool.shutdownNow()
        }
    }

    @Test fun productionMonitorPortCarriesRearOemNearFarIntoCoreAndSdkBlackGateUntilExactClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("FlutterSharedPreferences", 0)
        val priorPreference = prefs.getBoolean("flutter.proximity_sensor_enabled", true)
        prefs.edit().putBoolean("flutter.proximity_sensor_enabled", true).commit()
        val source = FakeProximitySensorPort()
        val monitor = AndroidSceneDisplayLifecycleMonitor(context,
            SceneProximitySensorPortFactory { _, handler -> source.useHandler(handler) })
        val gateway = FakeGateway(component)
        val coordinator = coordinator(gateway, { true }, lifecycleMonitor = monitor)
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }

        try {
            val openResult = coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer)
            assertTrue("production lifecycle monitor starts for the Scene lease; result=$openResult", openResult is SceneOpenResult.Accepted)
            val opened = openResult as SceneOpenResult.Accepted
            assertTrue("OEM type 33171123 is selected by the production lease monitor", source.registered)
            assertEquals(33171123, source.selected?.type)
            val handle = opened.handle
            // The virtual AVD has no rear display 1. This only seeds the actual current-display
            // state for the source→Core→SDK path; it is not a system display/presentation test.
            coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket,
                SceneWindowReport(component, 42, viewport(1))).success)
            assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)

            // Primary screen lock is represented at the Core boundary while the rear-owned
            // Activity remains visible. Sensor registration must remain active throughout.
            coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOff)
            source.emit(0f)
            val covered = waitForPresentation(coordinator, handle, visible = false)
            val sdkGate = RearScenePresentationGate(handle.sessionId, handle.generation)
            assertTrue(sdkGate.apply(RearScenePresentation(covered.sessionId, covered.generation,
                covered.revision, covered.visible, covered.hiddenReason?.name)))
            assertFalse("the SDK sample's shared presentation gate receives Core COVERED state", sdkGate.visible())
            assertTrue("lock signal does not unregister the rear source", source.registered)

            source.emit(5f)
            val uncovered = waitForPresentation(coordinator, handle, visible = true)
            assertTrue(sdkGate.apply(RearScenePresentation(uncovered.sessionId, uncovered.generation,
                uncovered.revision, uncovered.visible, uncovered.hiddenReason?.name)))
            assertTrue("far restores this still-owned Scene's SDK presentation", sdkGate.visible())

            // Preference replacement invalidates callbacks from the old source registration.
            val staleRegistrationCallback = source.callbackAt(0)
            prefs.edit().putBoolean("flutter.proximity_sensor_enabled", false).commit()
            assertTrue("disabling unregisters source", waitUntil(2_000) { !source.registered })
            prefs.edit().putBoolean("flutter.proximity_sensor_enabled", true).commit()
            assertTrue("reenabling creates a new source registration", waitUntil(2_000) { source.registered && source.registrationCount >= 2 })
            source.emit(staleRegistrationCallback, 0f)
            Thread.sleep(400)
            val stillVisible = coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation)
            assertTrue("a delayed callback from the replaced source epoch cannot cover the current lease", stillVisible?.visible == true)

            val lateCallback = source.lastCallback
            assertTrue(coordinator.closeScene(owner, handle.sessionId).success)
            assertFalse("last lease close releases the source listener", source.registered)
            lateCallback?.invoke(0f)
            Thread.sleep(400)
            assertNull("a queued source callback after close cannot revive the Core lease",
                coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation))
        } finally {
            prefs.edit().putBoolean("flutter.proximity_sensor_enabled", priorPreference).commit()
        }
    }

    @Test fun sceneLeaseRejectsOtherOwnersAndStaleGenerationsAndClosesOnlyOwnTask() {
        val gateway = FakeGateway(component)
        val coordinator = coordinator(gateway, consume = { true })
        val events = mutableListOf<SceneEvent>()
        val observer = object : SceneObserver {
            override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) { events += event }
        }
        val opened = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("once"), observer)
        assertTrue("scene should be accepted", opened is SceneOpenResult.Accepted)
        val handle = (opened as SceneOpenResult.Accepted).handle
        assertTrue(coordinator.ownsActiveSession(owner, handle.sessionId))
        assertTrue("a second active scene cannot preempt the first", coordinator.openScene(other, SceneRequest(component), UserActivationToken("once"), observer) is SceneOpenResult.Rejected)

        val viewport = viewport(revision = 1)
        val ready = coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport))
        assertTrue(ready.success)
        assertFalse("one-use ticket cannot replay", coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport)).success)
        assertFalse("another Binder/package principal cannot close the lease", coordinator.closeScene(other, handle.sessionId).success)

        assertFalse("old generation cannot publish layout", coordinator.reportSceneViewport(owner, handle.sessionId, handle.generation + 1, SceneWindowReport(component, 42, viewport(2))).success)
        assertFalse("older revision cannot overwrite the final viewport", coordinator.reportSceneViewport(owner, handle.sessionId, handle.generation, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(coordinator.reportSceneViewport(owner, handle.sessionId, handle.generation, SceneWindowReport(component, 42, viewport(2))).success)
        assertTrue(coordinator.closeScene(owner, handle.sessionId).success)
        assertFalse("close releases service-side active-session membership", coordinator.ownsActiveSession(owner, handle.sessionId))
        assertEquals(listOf(42), gateway.finishedTaskIds)
        assertTrue(events.any { it is SceneEvent.Ready })
        assertTrue(events.any { it is SceneEvent.Closed && it.reason == SceneCloseReason.CLIENT_CLOSED })
    }

    @Test fun legacyCastGateRecognizesOnlyAnExactReadySdkSceneAndPreemptsOnlyAfterCommit() {
        val sdkComponent = ComponentName("dev.remrss.sample", "dev.remrss.sample.RearSceneActivity")
        val sdkOwner = SessionOwner(12002, "dev.remrss.sample", "sample-cert",
            SessionPrincipal.AndroidClient(12002, "dev.remrss.sample", "sample-cert", 1))
        val gateway = FakeGateway(sdkComponent)
        val clock = AtomicLong(1000L)
        val expiryScheduler = ControlledExpiryScheduler()
        val coordinator = coordinator(gateway, { true }, clock::get, scheduler = expiryScheduler)
        val sceneEvents = mutableListOf<SceneEvent>()
        val observer = object : SceneObserver {
            override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) { sceneEvents += event }
        }
        val foreground = "dev.remrss.sample:42"
        val productionTaskMatcher = VirtualCastRequest.SceneTaskIdMatcher { taskId, display ->
            RearCapabilityCore.isActiveSceneTask(taskId, display, coordinator)
        }

        assertFalse("a package match without a ready Core lease is not an ownership exemption",
            RearCapabilityCore.isActiveSceneTaskForeground(foreground, 1, coordinator))
        assertEquals(VirtualCastRequest.RearOwnerDisposition.UNKNOWN,
            VirtualCastRequest.classifyRearOwner(foreground, "top.zainzh.rearscreen", 1) { value, display ->
                RearCapabilityCore.isActiveSceneTaskForeground(value, display, coordinator)
            })

        val opened = coordinator.openScene(sdkOwner, SceneRequest(sdkComponent), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted
        assertFalse("launching but not yet verified/ready does not bypass unknown-app protection",
            RearCapabilityCore.isActiveSceneTaskForeground(foreground, 1, coordinator))
        assertFalse("destructive stale-host cleanup protects the exact launched task while READY is in flight",
            VirtualCastRequest.mayRemoveStaleVirtualHostTask(42, 1, productionTaskMatcher))
        assertTrue(coordinator.reportSceneWindowReady(sdkOwner, opened.handle.launchTicket,
            SceneWindowReport(sdkComponent, 42, viewport(1))).success)

        val productionMatcher = VirtualCastRequest.SceneTaskMatcher { value, display ->
            RearCapabilityCore.isActiveSceneTaskForeground(value, display, coordinator)
        }
        assertFalse("stale virtual-host cleanup cannot remove an exact live Core Scene task/display",
            VirtualCastRequest.mayRemoveStaleVirtualHostTask(42, 1, productionTaskMatcher))
        assertTrue("a different task remains eligible for its own stale-host cleanup",
            VirtualCastRequest.mayRemoveStaleVirtualHostTask(77, 1, productionTaskMatcher))
        assertEquals("only the active ready lease's exact package/task/display is recognized",
            VirtualCastRequest.RearOwnerDisposition.CORE_SCENE,
            VirtualCastRequest.classifyRearOwner(foreground, "top.zainzh.rearscreen", 1, productionMatcher))
        assertFalse("wrong display cannot inherit the exemption", productionMatcher.matches(foreground, 2))
        assertFalse("wrong task cannot inherit the exemption", productionMatcher.matches("dev.remrss.sample:43", 1))
        assertFalse("unrelated package remains unknown", productionMatcher.matches("dev.other.app:42", 1))
        assertEquals(VirtualCastRequest.RearOwnerDisposition.UNKNOWN,
            VirtualCastRequest.classifyRearOwner("dev.other.app:77", "top.zainzh.rearscreen", 1, productionMatcher))
        assertEquals("existing Host-owned entry remains allowed", VirtualCastRequest.RearOwnerDisposition.HOST,
            VirtualCastRequest.classifyRearOwner("top.zainzh.rearscreen:7", "top.zainzh.rearscreen", 1, productionMatcher))
        assertEquals("the known system rear launcher remains allowed", VirtualCastRequest.RearOwnerDisposition.SYSTEM_LAUNCHER,
            VirtualCastRequest.classifyRearOwner("com.xiaomi.subscreencenter:5", "top.zainzh.rearscreen", 1, productionMatcher))

        val forceStops = mutableListOf<String>()
        val samePackageMayContinue = VirtualCastRequest.stopTargetPackageUnlessSceneOwned(
            "dev.remrss.sample",
            VirtualCastRequest.ScenePackageMatcher { pkg -> RearCapabilityCore.hasActiveScenePackage(pkg, coordinator) },
            VirtualCastRequest.PackageStopper { forceStops += "force-stop" })
        assertFalse("same-package package/activity cast is stopped before force-stop can kill its live Scene", samePackageMayContinue)
        assertTrue("the production force-stop operation is not invoked for the live Scene package", forceStops.isEmpty())

        val mismatchCleanup = mutableListOf<String>()
        assertTrue("the requested B package retains the existing retry cleanup when it has no Scene lease",
            VirtualCastRequest.stopTargetPackageUnlessSceneOwned("dev.other.app",
                VirtualCastRequest.ScenePackageMatcher { pkg -> RearCapabilityCore.hasActiveScenePackage(pkg, coordinator) },
                VirtualCastRequest.PackageStopper { mismatchCleanup += "force-stop-B" }))
        assertFalse("when startup still reports Scene owner A, the actual-package retry cleanup is blocked",
            VirtualCastRequest.stopTargetPackageUnlessSceneOwned("dev.remrss.sample",
                VirtualCastRequest.ScenePackageMatcher { pkg -> RearCapabilityCore.hasActiveScenePackage(pkg, coordinator) },
                VirtualCastRequest.PackageStopper { mismatchCleanup += "force-stop-A" }))
        assertEquals("a failed B launch cannot force-stop the live Scene's package A", listOf("force-stop-B"), mismatchCleanup)
        assertTrue("mismatch cleanup refusal leaves Scene A alive and unpreempted",
            coordinator.ownsActiveSession(sdkOwner, opened.handle.sessionId))

        // A failed physical/virtual cast never invokes the production commit hook: the Scene
        // stays live. A successful cast uses this exact shared close hook after move/running.
        assertTrue("an uncommitted cast leaves the active Scene intact", coordinator.ownsActiveSession(sdkOwner, opened.handle.sessionId))
        RearCapabilityCore.preemptActiveScenesForLegacyCast(coordinator)
        assertFalse("successful explicit cast permanently revokes Scene restoration rights",
            coordinator.ownsActiveSession(sdkOwner, opened.handle.sessionId))
        assertTrue("successful cast closes the Scene with the explicit PREEMPTED reason",
            sceneEvents.any { it is SceneEvent.Closed && it.reason == SceneCloseReason.PREEMPTED })
        assertEquals(listOf(42), gateway.finishedTaskIds)
        assertFalse("closed/expired task no longer bypasses occupancy protection",
            productionMatcher.matches(foreground, 1))
        assertTrue("after explicit PREEMPTED close, stale-host cleanup sees no live Scene task id",
            VirtualCastRequest.mayRemoveStaleVirtualHostTask(42, 1, productionTaskMatcher))
        assertEquals(VirtualCastRequest.RearOwnerDisposition.UNKNOWN,
            VirtualCastRequest.classifyRearOwner(foreground, "top.zainzh.rearscreen", 1, productionMatcher))

        val expiring = coordinator.openScene(sdkOwner, SceneRequest(sdkComponent), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted
        clock.set(6000L)
        expiryScheduler.run(1)
        assertFalse("the actual pending-ticket expiry callback removes lease package ownership",
            RearCapabilityCore.hasActiveScenePackage("dev.remrss.sample", coordinator))
        assertFalse("expired ticket cleanup removes its task ownership exemption", productionMatcher.matches(foreground, 1))
        assertEquals("only the exact two Scene tasks are cleaned", listOf(42, 42), gateway.finishedTaskIds)
        expiryScheduler.shutdownNow()
    }

    @Test fun signedModPrincipalIsStableAcrossWorkerReattachmentAndDistinctAcrossMods() {
        val gateway = FakeGateway(component)
        val coordinator = coordinator(gateway, consume = { true })
        val modA = owner.copy(principal = SessionPrincipal.SignedMod("author-a", "score-a", "logical-1"))
        val modB = owner.copy(principal = SessionPrincipal.SignedMod("author-b", "score-b", "logical-2"))
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        val opened = coordinator.openScene(modA, SceneRequest(component), UserActivationToken("user"), observer) as SceneOpenResult.Accepted
        assertFalse("a different Mod sharing the host Android UID cannot attach", coordinator.attachSceneObserver(modB, opened.handle.sessionId, opened.handle.generation, observer).success)
        assertTrue("same logical Mod session can reattach after worker restart", coordinator.attachSceneObserver(modA, opened.handle.sessionId, opened.handle.generation, observer).success)
        coordinator.closeOwnerSessions(modB, SceneCloseReason.OWNER_REVOKED)
        assertTrue("unrelated owner cleanup does not release the lease", coordinator.attachSceneObserver(modA, opened.handle.sessionId, opened.handle.generation, observer).success)
        coordinator.closeOwnerSessions(modA, SceneCloseReason.OWNER_REVOKED)
        assertEquals(listOf(42), gateway.finishedTaskIds)
    }

    @Test fun activationAndWindowIdentityAreValidatedBeforeReady() {
        val gateway = FakeGateway(component)
        val coordinator = coordinator(gateway, consume = { it.opaqueValue == "confirmed" })
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        val denied = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("caller-boolean-is-not-a-token"), observer)
        assertEquals(SceneError.CAPABILITY_DENIED, (denied as SceneOpenResult.Rejected).code)
        val opened = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted
        val wrongTask = SceneWindowReport(component, 99, viewport(1))
        assertEquals(SceneError.INVALID_STATE, coordinator.reportSceneWindowReady(owner, opened.handle.launchTicket, wrongTask).error)
        val ready = SceneWindowReport(component, 42, viewport(1))
        assertTrue(coordinator.reportSceneWindowReady(owner, opened.handle.launchTicket, ready).success)
        assertEquals(1, gateway.verifyCount)
    }

    @Test fun coordinatorDerivesMandatoryCapabilitiesFromSceneFields() {
        val gateway = FakeGateway(component)
        val coordinator = coordinator(gateway, consume = { true })
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        val scene = SceneRequest(component, region = RearRegion.FULL, keepVisible = true, requiredCapabilities = emptySet())
        val opened = coordinator.openScene(owner, scene, UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted
        assertEquals(
            setOf("rear.scene", "rear.scene.keep_visible", "rear.region"),
            gateway.authorizedRequest?.requiredCapabilities?.map { it.name }?.toSet()
        )
        coordinator.closeScene(owner, opened.handle.sessionId)
    }

    @Test fun legacyProjectionDuringAuthorizationWinsTheSceneAdmissionRace() {
        val epoch = AtomicLong(0L)
        val gateway = FakeGateway(component).apply { beforeAuthorize = { epoch.incrementAndGet() } }
        val coordinator = coordinator(gateway, { true }, ownershipEpoch = epoch::get)
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        val result = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed"), observer)
        assertEquals(SceneError.DISPLAY_BUSY, (result as SceneOpenResult.Rejected).code)
        assertEquals("no scene task is launched after a cast claims the display", 0, gateway.launchCount)
    }

    @Test fun staleReturnedPhysicalOwnerPruneDuringAuthorizationDoesNotRejectSceneAdmission() {
        val ownership = LegacyProjectionOwnershipEpoch().apply { projectionCommitted() }
        val gateway = FakeGateway(component).apply { beforeAuthorize = { ownership.staleRecordPruned() } }
        val coordinator = coordinator(gateway, { true }, ownershipEpoch = ownership::current)
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }

        // The old physical task was returned to display 0. Its persisted record is cleared by
        // authorize(); unlike a new cast commit, this must not be mistaken for a competing claim.
        val result = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed"), observer)
        assertTrue("stale owner metadata cleanup permits a fresh lease", result is SceneOpenResult.Accepted)
        assertEquals("fresh Scene reaches the real gateway only after admission", 1, gateway.launchCount)
    }

    @Test fun failedDisplayPreparationReleasesTheLeaseWithoutLaunchingAndDoesNotBlockRetry() {
        val gateway = FakeGateway(component).apply { prepareError = SceneError.DISPLAY_UNAVAILABLE }
        val coordinator = coordinator(gateway, { true })
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }

        val rejected = coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("first"), observer)
        assertEquals(SceneError.DISPLAY_UNAVAILABLE, (rejected as SceneOpenResult.Rejected).code)
        assertEquals("no new task exists when the requested rear panel cannot be made visible", 0, gateway.launchCount)
        assertFalse("failed display preparation releases both ticket and reservation", coordinator.hasActiveOrClosingSceneSessions())

        gateway.prepareError = null
        val retry = coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("second"), observer)
        assertTrue("a display failure does not leave a permanent busy lease", retry is SceneOpenResult.Accepted)
    }

    @Test fun normalSceneWithoutKeepVisibleStillPreparesItsDisplayOnceAndDoesNotAcquireWakeLoop() {
        val gateway = FakeGateway(component).apply { rearDisplayOn = false }
        val coordinator = coordinator(gateway, { true })
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        val opened = coordinator.openScene(owner, SceneRequest(component, keepVisible = false), UserActivationToken("confirmed"), observer)
        assertTrue(opened is SceneOpenResult.Accepted)
        assertEquals("a sleeping rear display receives one scoped wake on first confirmed activation", 1, gateway.wakeCount)
        assertEquals(1, gateway.lastWakeDisplayId)
        assertTrue("the Scene launches only after its rear display is on", gateway.rearDisplayOn)
        assertEquals(false, gateway.preparedKeepVisible)
        assertEquals(1, gateway.launchCount)
        val handle = (opened as SceneOpenResult.Accepted).handle
        assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)

        // A later natural sleep is a visibility change, not another activation or a wake trigger.
        gateway.rearDisplayOn = false
        assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false).success)
        assertFalse("keepVisible=false allows the panel to sleep", gateway.rearDisplayOn)
        assertEquals("natural sleep must not start a periodic or lifecycle wake loop", 1, gateway.wakeCount)
        assertEquals("no second display preparation is scheduled", 1, gateway.prepareCount)
        coordinator.closeScene(owner, handle.sessionId)
    }

    @Test fun keepVisibleRecoversRearOnScreenOffWhilePrimaryRemainsLockedWithoutUnlockEvent() {
        val gateway = FakeGateway(component)
        val recoveryFinished = CountDownLatch(1)
        gateway.onRecovery = { recoveryFinished.countDown() }
        val coordinator = coordinator(gateway, { true })
        val events = java.util.concurrent.CopyOnWriteArrayList<SceneEvent>()
        val observer = object : SceneObserver {
            override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) { events += event }
        }
        val opened = coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted
        val handle = opened.handle
        assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)

        var primaryDisplayOn = true
        val keyguardLocked = true
        primaryDisplayOn = false
        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOff)
        gateway.rearDisplayOn = false
        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))

        assertTrue("one same-lease target-display recovery completes while the primary remains locked", recoveryFinished.await(2, TimeUnit.SECONDS))
        assertFalse("the test never sends a primary SCREEN_ON/unlock event", primaryDisplayOn)
        assertTrue("Keyguard remains locked during the recovery observation", keyguardLocked)
        assertEquals("recovery never launches a replacement task", 1, gateway.launchCount)
        assertTrue("the same exact Scene task remains owned until explicit close", gateway.finishedTaskIds.isEmpty())
        assertFalse("task ownership alone did not report the rear window visible", events.last { it is SceneEvent.VisibilityChanged } == SceneEvent.VisibilityChanged(true))

        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
        assertTrue("the actual Activity visibility report completes restoration", coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
        assertTrue(events.any { it == SceneEvent.Restored })
        coordinator.closeScene(owner, handle.sessionId)
    }

    @Test fun keepVisiblePreservesLockRecoveryWhenWindowAndRearOffArriveBeforePrimaryScreenOff() {
        val gateway = FakeGateway(component)
        val recovered = CountDownLatch(1)
        gateway.onRecovery = { recovered.countDown() }
        val coordinator = coordinator(gateway, { true })
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        val handle = (coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
        assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)

        // Android may report the Activity/window and rear power transition before SCREEN_OFF.
        assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false).success)
        gateway.rearDisplayOn = false
        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOff)

        assertTrue("the earlier visible lease remains eligible despite callback ordering", recovered.await(2, TimeUnit.SECONDS))
        assertEquals(1, gateway.recoveryCount)
        assertEquals("the same task is retained", 1, gateway.launchCount)
        coordinator.closeScene(owner, handle.sessionId)
    }

    @Test fun successfulWakeReportingDisplayOnBeforeBinderReturnStillPresentsSameOwnedTask() {
        val gateway = FakeGateway(component)
        val recoveryFinished = CountDownLatch(1)
        lateinit var core: RearSceneCoordinator
        lateinit var handle: SceneSessionHandle
        gateway.onRecovery = {
            // Models the real DisplayListener callback occurring inside the wake Binder call.
            gateway.rearDisplayOn = true
            core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
            recoveryFinished.countDown()
        }
        core = coordinator(gateway, { true })
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        handle = (core.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
        assertTrue(core.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(core.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
        core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOff)
        gateway.rearDisplayOn = false
        core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))

        assertTrue("display ON inside the successful wake must not cancel the remaining same-task present", recoveryFinished.await(2, TimeUnit.SECONDS))
        assertEquals(1, gateway.recoveryCount)
        assertEquals("recovery presents the existing lease task; it never relaunches", 1, gateway.launchCount)
        assertTrue(gateway.rearDisplayOn)
        core.closeScene(owner, handle.sessionId)
    }

    @Test fun overlayDuringPostWakePresentationDefersSameLeaseUntilOverlayEndsWithoutSecondWake() {
        val gateway = FakeGateway(component).apply { deferWhenCurrentInvalid = true }
        val recoveredAfterOverlay = CountDownLatch(1)
        lateinit var core: RearSceneCoordinator
        lateinit var handle: SceneSessionHandle
        core = coordinator(gateway, { true })
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        handle = (core.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
        assertTrue(core.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(core.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
        core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOff)
        gateway.rearDisplayOn = false
        core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))

        // The first wake succeeds and reports ON, but a cover begins before task focus. The
        // cancelled present preserves a one-shot present-only cause for the same lease.
        val overlayStarted = CountDownLatch(1)
        gateway.onRecovery = {
            if (gateway.recoveryCount == 1) {
                // The wake side effect succeeds before its asynchronous display callback and before
                // a new cover cancels the remaining focus/presentation work.
                gateway.rearDisplayOn = true
                core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
                assertTrue(core.beginTransientOverlay(390L))
                // The owned task is still stopped behind the cover; readiness/task identity alone
                // cannot make it visible. This report creates the pending present-only cause.
                assertTrue(core.reportSceneVisibility(owner, handle.sessionId, handle.generation, false).success)
                overlayStarted.countDown()
            } else recoveredAfterOverlay.countDown()
        }
        assertTrue("recovery enters the simulated post-wake cover", overlayStarted.await(2, TimeUnit.SECONDS))
        assertTrue(core.endTransientOverlay(390L))
        assertTrue("overlay end resumes the same hidden owned task", recoveredAfterOverlay.await(2, TimeUnit.SECONDS))
        assertEquals("no second physical wake is issued after display ON", 1, gateway.wakeCount)
        assertEquals("same logical scene task; no relaunch", 1, gateway.launchCount)
        assertEquals(2, gateway.recoveryCount)
        core.closeScene(owner, handle.sessionId)
    }

    @Test fun delayedCancelledPresentReplyKeepsRecoveryIntentAcrossOverlayAndNearFar() {
        fun runScenario(nearCover: Boolean) {
            val scheduler = ControlledRecoveryScheduler()
            val gateway = FakeGateway(component).apply { deferWhenCurrentInvalid = true }
            val gateReached = CountDownLatch(1)
            val allowGateCheck = CountDownLatch(1)
            val gateObservedObstruction = CountDownLatch(1)
            val releaseOldReply = CountDownLatch(1)
            val secondPresent = CountDownLatch(1)
            val oldReplyReturned = CountDownLatch(1)
            lateinit var core: RearSceneCoordinator
            lateinit var handle: SceneSessionHandle
            gateway.onRecovery = {
                if (gateway.recoveryCount == 1) {
                    // Actual wake has succeeded; its ON callback arrives before the remote
                    // presentation gate decides whether this exact task may be focused.
                    gateway.rearDisplayOn = true
                    core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
                } else secondPresent.countDown()
            }
            gateway.beforeActionGate = { isCurrent ->
                gateReached.countDown()
                check(allowGateCheck.await(2, TimeUnit.SECONDS))
                val allowedAtGate = isCurrent()
                gateObservedObstruction.countDown()
                check(releaseOldReply.await(2, TimeUnit.SECONDS))
                oldReplyReturned.countDown()
                allowedAtGate
            }
            gateway.onRecoveryReturn = { if (gateway.recoveryCount == 1) oldReplyReturned.countDown() }
            core = coordinator(gateway, { true }, scheduler = scheduler)
            val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
            handle = (core.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
            assertTrue(core.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
            assertTrue(core.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
            // The Activity's real hidden edge is the only initial qualification. Rear OFF may
            // arrive before primary SCREEN_OFF; no later synthetic visibility edge is used.
            assertTrue(core.reportSceneVisibility(owner, handle.sessionId, handle.generation, false).success)
            gateway.rearDisplayOn = false
            core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
            core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOff)
            assertEquals("one recovery capture starts the wake", 1, scheduler.pendingRecoveries)

            scheduler.runRecovery(0)
            assertTrue("wake reaches the delayed remote gate", gateReached.await(2, TimeUnit.SECONDS))
            if (nearCover) core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(true))
            else assertTrue(core.beginTransientOverlay(701L))
            allowGateCheck.countDown()
            assertTrue("the gate sees the active obstruction", gateObservedObstruction.await(2, TimeUnit.SECONDS))

            // The obstruction ends before the already-computed cancellation reply returns.
            // This makes callback order—not another visibility report—the only recovery signal.
            if (nearCover) core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(false))
            else assertTrue(core.endTransientOverlay(701L))
            assertEquals("the same still-valid cause schedules one present-only continuation", 2, scheduler.pendingRecoveries)
            releaseOldReply.countDown()
            assertTrue("the remote cancelled reply returns", oldReplyReturned.await(2, TimeUnit.SECONDS))
            scheduler.runRecovery(1)
            assertTrue("same owned task is presented after the obstruction ends", secondPresent.await(2, TimeUnit.SECONDS))
            assertEquals("the wake is never repeated while the target display is already ON", 1, gateway.wakeCount)
            assertEquals("only the original launch task is used", 1, gateway.launchCount)
            assertEquals("one wake attempt plus one present-only continuation", 2, gateway.recoveryCount)
            assertTrue("lease remains live until explicit close", core.ownsActiveSession(owner, handle.sessionId))
            core.closeScene(owner, handle.sessionId)
        }

        runScenario(nearCover = false)
        runScenario(nearCover = true)
    }

    @Test fun delayedCancelledPresentReplyCannotReviveAfterLeaseClose() {
        val scheduler = ControlledRecoveryScheduler()
        val gateway = FakeGateway(component).apply { deferWhenCurrentInvalid = true }
        val gateReached = CountDownLatch(1)
        val allowGateCheck = CountDownLatch(1)
        val gateObservedObstruction = CountDownLatch(1)
        val releaseOldReply = CountDownLatch(1)
        val oldReplyReturned = CountDownLatch(1)
        lateinit var core: RearSceneCoordinator
        lateinit var handle: SceneSessionHandle
        gateway.onRecovery = {
            gateway.rearDisplayOn = true
            core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
        }
        gateway.beforeActionGate = { isCurrent ->
            gateReached.countDown()
            check(allowGateCheck.await(2, TimeUnit.SECONDS))
            val allowedAtGate = isCurrent()
            gateObservedObstruction.countDown()
            check(releaseOldReply.await(2, TimeUnit.SECONDS))
            allowedAtGate
        }
        gateway.onRecoveryReturn = { oldReplyReturned.countDown() }
        core = coordinator(gateway, { true }, scheduler = scheduler)
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        handle = (core.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
        assertTrue(core.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(core.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
        assertTrue(core.reportSceneVisibility(owner, handle.sessionId, handle.generation, false).success)
        gateway.rearDisplayOn = false
        core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
        core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOff)
        scheduler.runRecovery(0)
        assertTrue(gateReached.await(2, TimeUnit.SECONDS))
        assertTrue(core.beginTransientOverlay(702L))
        allowGateCheck.countDown()
        assertTrue(gateObservedObstruction.await(2, TimeUnit.SECONDS))
        assertTrue(core.endTransientOverlay(702L))
        assertEquals(2, scheduler.pendingRecoveries)

        assertTrue(core.closeScene(owner, handle.sessionId).success)
        releaseOldReply.countDown()
        assertTrue("late gateway result returns after close", oldReplyReturned.await(2, TimeUnit.SECONDS))
        scheduler.runRecovery(1)
        assertEquals("late old reply and scheduled continuation do not issue another recovery", 1, gateway.recoveryCount)
        assertEquals("only the lease-owned task is finished", listOf(42), gateway.finishedTaskIds)
        assertFalse(core.ownsActiveSession(owner, handle.sessionId))
    }

    @Test fun primaryScreenOnDoesNotStrandCoverRecoveryAndCloseInvalidatesItsCapture() {
        fun createCoveredFarScene(): Triple<RearSceneCoordinator, SceneSessionHandle, Pair<ControlledRecoveryScheduler, FakeGateway>> {
            val scheduler = ControlledRecoveryScheduler()
            val gateway = FakeGateway(component)
            val core = coordinator(gateway, { true }, scheduler = scheduler)
            val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
            val handle = (core.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
            assertTrue(core.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
            assertTrue(core.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
            core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(true))
            gateway.rearDisplayOn = false
            core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
            core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(false))
            assertEquals("far has one pending cover recovery before the main-screen edge", 1, scheduler.pendingRecoveries)
            return Triple(core, handle, scheduler to gateway)
        }

        val (core, handle, state) = createCoveredFarScene()
        val (scheduler, gateway) = state
        val completed = CountDownLatch(1)
        gateway.onRecovery = { completed.countDown() }
        core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOff)
        core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOn)
        assertEquals("main-screen ON preserves the already scheduled cover capture", 1, scheduler.pendingRecoveries)
        scheduler.runRecovery(0)
        assertTrue("same pending cover cause reaches presentation after main-screen ON", completed.await(2, TimeUnit.SECONDS))
        assertEquals(1, gateway.recoveryCount)
        assertEquals(1, gateway.wakeCount)
        assertEquals(1, gateway.launchCount)
        core.closeScene(owner, handle.sessionId)

        val (closedCore, closedHandle, closedState) = createCoveredFarScene()
        val (closedScheduler, closedGateway) = closedState
        closedCore.handleDisplaySignal(closedHandle.sessionId, closedHandle.generation, SceneDisplaySignal.PrimaryScreenOff)
        closedCore.handleDisplaySignal(closedHandle.sessionId, closedHandle.generation, SceneDisplaySignal.PrimaryScreenOn)
        assertTrue(closedCore.closeScene(owner, closedHandle.sessionId).success)
        closedScheduler.runRecovery(0)
        assertEquals("a delayed cover capture cannot recover after close", 0, closedGateway.recoveryCount)
        assertEquals(listOf(42), closedGateway.finishedTaskIds)
    }

    @Test fun actionGateTransportFailureIsTerminalAndDoesNotScheduleAnotherRecovery() {
        val scheduler = ControlledRecoveryScheduler()
        val gateway = FakeGateway(component).apply { presentationStatusOverride = "action_gate_unavailable" }
        val backendLost = CountDownLatch(1)
        lateinit var core: RearSceneCoordinator
        lateinit var handle: SceneSessionHandle
        gateway.onRecovery = {
            gateway.rearDisplayOn = true
            core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
        }
        val observer = object : SceneObserver {
            override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {
                if (event is SceneEvent.BackendLost) backendLost.countDown()
            }
        }
        core = coordinator(gateway, { true }, scheduler = scheduler)
        handle = (core.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
        assertTrue(core.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(core.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
        assertTrue(core.reportSceneVisibility(owner, handle.sessionId, handle.generation, false).success)
        gateway.rearDisplayOn = false
        core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
        core.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOff)
        assertEquals(1, scheduler.pendingRecoveries)

        scheduler.runRecovery(0)
        assertTrue("gate IPC failure is surfaced as a backend failure", backendLost.await(2, TimeUnit.SECONDS))
        assertEquals("transport failure is one terminal attempt, not a 350ms retry loop", 1, gateway.recoveryCount)
        assertEquals("no retry timer is added after the terminal error", 1, scheduler.pendingRecoveries)
        core.closeScene(owner, handle.sessionId)
        assertEquals("close still owns and removes only the Scene task", listOf(42), gateway.finishedTaskIds)
    }

    @Test fun overlayEndReevaluatesLockedRearRecoveryButNearCoverStillWinsUntilFar() {
        fun createVisible(gateway: FakeGateway): Pair<RearSceneCoordinator, SceneSessionHandle> {
            val coordinator = coordinator(gateway, { true })
            val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
            val handle = (coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
            assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
            assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
            return coordinator to handle
        }

        val overlayGateway = FakeGateway(component)
        val overlayRecovered = CountDownLatch(1)
        overlayGateway.onRecovery = { overlayRecovered.countDown() }
        val (overlayCoordinator, overlayHandle) = createVisible(overlayGateway)
        assertTrue(overlayCoordinator.beginTransientOverlay(100L))
        overlayCoordinator.handleDisplaySignal(overlayHandle.sessionId, overlayHandle.generation, SceneDisplaySignal.PrimaryScreenOff)
        overlayGateway.rearDisplayOn = false
        overlayCoordinator.handleDisplaySignal(overlayHandle.sessionId, overlayHandle.generation, SceneDisplaySignal.RearDisplayPower(false))
        assertEquals("cover suppresses restore while active", 0, overlayGateway.recoveryCount)
        assertTrue(overlayCoordinator.endTransientOverlay(100L))
        assertTrue("ending the temporary overlay reevaluates the still-valid lock cause", overlayRecovered.await(2, TimeUnit.SECONDS))
        assertEquals(1, overlayGateway.recoveryCount)
        overlayCoordinator.closeScene(owner, overlayHandle.sessionId)

        val coveredGateway = FakeGateway(component)
        val coveredRecovered = CountDownLatch(1)
        coveredGateway.onRecovery = { coveredRecovered.countDown() }
        val (coveredCoordinator, coveredHandle) = createVisible(coveredGateway)
        assertTrue(coveredCoordinator.beginTransientOverlay(200L))
        coveredCoordinator.handleDisplaySignal(coveredHandle.sessionId, coveredHandle.generation, SceneDisplaySignal.RearProximity(true))
        coveredCoordinator.handleDisplaySignal(coveredHandle.sessionId, coveredHandle.generation, SceneDisplaySignal.PrimaryScreenOff)
        coveredGateway.rearDisplayOn = false
        coveredCoordinator.handleDisplaySignal(coveredHandle.sessionId, coveredHandle.generation, SceneDisplaySignal.RearDisplayPower(false))
        assertTrue(coveredCoordinator.endTransientOverlay(200L))
        Thread.sleep(450)
        assertEquals("near cover remains authoritative after overlay ends", 0, coveredGateway.recoveryCount)
        coveredCoordinator.handleDisplaySignal(coveredHandle.sessionId, coveredHandle.generation, SceneDisplaySignal.RearProximity(false))
        assertTrue("far is the next valid cause after both overlay and proximity cover end", coveredRecovered.await(2, TimeUnit.SECONDS))
        assertEquals(1, coveredGateway.recoveryCount)
        coveredCoordinator.closeScene(owner, coveredHandle.sessionId)
    }

    @Test fun overlayDoesNotConsumeUnstartedScreenOffRecoveryOrCoverCause() {
        val scheduler = ControlledRecoveryScheduler()
        fun createVisible(gateway: FakeGateway): Pair<RearSceneCoordinator, SceneSessionHandle> {
            val coordinator = coordinator(gateway, { true }, scheduler = scheduler)
            val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
            val handle = (coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
            assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
            assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
            return coordinator to handle
        }

        val lockGateway = FakeGateway(component)
        val lockRecovered = CountDownLatch(1)
        lockGateway.onRecovery = { lockRecovered.countDown() }
        val (lockCoordinator, lockHandle) = createVisible(lockGateway)
        lockCoordinator.handleDisplaySignal(lockHandle.sessionId, lockHandle.generation, SceneDisplaySignal.PrimaryScreenOff)
        lockGateway.rearDisplayOn = false
        lockCoordinator.handleDisplaySignal(lockHandle.sessionId, lockHandle.generation, SceneDisplaySignal.RearDisplayPower(false))
        // The 350ms recovery is scheduled but has not entered the gateway; overlay invalidates
        // that capture without consuming the lock cause as an attempted wake.
        assertEquals(1, scheduler.pendingRecoveries)
        assertTrue(lockCoordinator.beginTransientOverlay(210L))
        assertTrue(lockCoordinator.endTransientOverlay(210L))
        assertEquals("overlay end schedules the still-valid cause as one replacement", 2, scheduler.pendingRecoveries)
        scheduler.runRecovery(0) // late pre-overlay timer is stale
        assertEquals(0, lockGateway.recoveryCount)
        scheduler.runRecovery(1)
        assertTrue("the same still-valid lock cause resumes once after the overlay", lockRecovered.await(2, TimeUnit.SECONDS))
        assertEquals("the stale pre-overlay timer cannot duplicate the resumed recovery", 1, lockGateway.recoveryCount)
        lockCoordinator.closeScene(owner, lockHandle.sessionId)
        val staleRecoveryCount = scheduler.pendingRecoveries

        val coverGateway = FakeGateway(component)
        val coverRecovered = CountDownLatch(1)
        coverGateway.onRecovery = { coverRecovered.countDown() }
        val (coverCoordinator, coverHandle) = createVisible(coverGateway)
        coverCoordinator.handleDisplaySignal(coverHandle.sessionId, coverHandle.generation, SceneDisplaySignal.RearProximity(true))
        coverGateway.rearDisplayOn = false
        coverCoordinator.handleDisplaySignal(coverHandle.sessionId, coverHandle.generation, SceneDisplaySignal.RearDisplayPower(false))
        assertTrue(coverCoordinator.beginTransientOverlay(211L))
        coverCoordinator.handleDisplaySignal(coverHandle.sessionId, coverHandle.generation, SceneDisplaySignal.RearProximity(false))
        assertEquals("far during an overlay preserves its valid cover-release cause", 0, coverGateway.recoveryCount)
        assertTrue(coverCoordinator.endTransientOverlay(211L))
        assertEquals(staleRecoveryCount + 1, scheduler.pendingRecoveries)
        scheduler.runRecovery(2)
        assertTrue("overlay end re-evaluates the preserved cover-release cause", coverRecovered.await(2, TimeUnit.SECONDS))
        assertEquals(1, coverGateway.recoveryCount)
        coverCoordinator.closeScene(owner, coverHandle.sessionId)
        scheduler.shutdownNow()
    }

    @Test fun overlayDuringGatewayInspectionDefersCauseUntilBeforeActualWakeBoundary() {
        val scheduler = ControlledRecoveryScheduler()
        val inspectionEntered = CountDownLatch(1)
        val releaseInspection = CountDownLatch(1)
        val firstGatewayReturned = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val gateway = FakeGateway(component).apply {
            beforeRecoveryInspection = {
                if (recoveryInvocationCount == 1) {
                    inspectionEntered.countDown()
                    releaseInspection.await(2, TimeUnit.SECONDS)
                }
            }
            onRecovery = { recovered.countDown() }
            onRecoveryReturn = { firstGatewayReturned.countDown() }
        }
        val coordinator = coordinator(gateway, { true }, scheduler = scheduler)
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        val handle = (coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
        assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.PrimaryScreenOff)
        gateway.rearDisplayOn = false
        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))

        scheduler.runRecovery(0)
        assertTrue("gateway has entered its potentially blocking ownership inspection", inspectionEntered.await(2, TimeUnit.SECONDS))
        assertTrue(coordinator.beginTransientOverlay(250L))
        releaseInspection.countDown()
        assertTrue("first gateway returns without reaching its wake boundary", firstGatewayReturned.await(2, TimeUnit.SECONDS))
        assertEquals("the overlay invalidates inspection before the hardware attempt", 0, gateway.recoveryCount)
        assertEquals("no display wake was issued while the overlay covered the lease", 0, gateway.wakeCount)

        assertTrue("ending the exact overlay reevaluates the still-valid screen-off cause", coordinator.endTransientOverlay(250L))
        assertEquals("one replacement delay is scheduled, not a retry loop", 2, scheduler.pendingRecoveries)
        scheduler.runRecovery(1)
        assertTrue("the cause is consumed only when the replacement reaches the wake boundary", recovered.await(2, TimeUnit.SECONDS))
        assertEquals(1, gateway.recoveryCount)
        assertEquals(1, gateway.wakeCount)
        coordinator.closeScene(owner, handle.sessionId)
        scheduler.shutdownNow()
    }

    @Test fun currentLeaseRecoveryWaitsBehindSlowObsoleteRecoveryWithoutBeingDropped() {
        val gateway = FakeGateway(component).apply { uniqueTaskIds = true }
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val currentRecovered = CountDownLatch(1)
        gateway.onRecovery = {
            when (gateway.recoveryCount) {
                1 -> { oldStarted.countDown(); releaseOld.await(2, TimeUnit.SECONDS) }
                2 -> currentRecovered.countDown()
            }
        }
        val coordinator = coordinator(gateway, { true })
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        fun openVisible(): SceneSessionHandle {
            val handle = (coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
            assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, gateway.lastLaunchedTaskId, viewport(1))).success)
            assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
            return handle
        }

        val old = openVisible()
        coordinator.handleDisplaySignal(old.sessionId, old.generation, SceneDisplaySignal.PrimaryScreenOff)
        gateway.rearDisplayOn = false
        coordinator.handleDisplaySignal(old.sessionId, old.generation, SceneDisplaySignal.RearDisplayPower(false))
        assertTrue("first gateway recovery is deliberately held at a barrier", oldStarted.await(2, TimeUnit.SECONDS))

        coordinator.closeScene(owner, old.sessionId)
        gateway.rearDisplayOn = true
        val current = openVisible()
        coordinator.handleDisplaySignal(current.sessionId, current.generation, SceneDisplaySignal.PrimaryScreenOff)
        gateway.rearDisplayOn = false
        coordinator.handleDisplaySignal(current.sessionId, current.generation, SceneDisplaySignal.RearDisplayPower(false))
        Thread.sleep(500)
        assertEquals("new cause is retained in the single bounded pending slot", 1, gateway.recoveryCount)

        releaseOld.countDown()
        assertTrue("after the obsolete Binder operation returns, the still-valid new lease runs", currentRecovered.await(2, TimeUnit.SECONDS))
        assertEquals(2, gateway.recoveryCount)
        assertEquals("old task only was closed; current lease keeps its separate task", listOf(42), gateway.finishedTaskIds)
        assertEquals(2, gateway.launchCount)
        coordinator.closeScene(owner, current.sessionId)
    }

    @Test fun delayedRestoredCallbackQueriesCurrentSnapshotAfterNearAndDeduplicatedActivityReports() {
        val gateway = FakeGateway(component)
        val callbacks = java.util.ArrayDeque<Runnable>()
        lateinit var coordinator: RearSceneCoordinator
        lateinit var gate: ScenePresentationGate
        val observer = object : SceneObserver {
            override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {
                if (event !is SceneEvent.VisibilityChanged && event !is SceneEvent.Restored && event !is SceneEvent.Suspended) return
                coordinator.queryScenePresentation(owner, sessionId, generation)?.let(gate::apply)
            }
        }
        coordinator = RearSceneCoordinator(
            gateway,
            UserActivationVerifier { _, _, _ -> true },
            Executor { runnable -> synchronized(callbacks) { callbacks.addLast(runnable) } },
            clock = { 1000L },
            randomToken = TokenSequence()::next,
            displayOwnershipEpoch = { 0L }
        )
        fun drainCallbacks() {
            while (true) {
                val next = synchronized(callbacks) { if (callbacks.isEmpty()) null else callbacks.removeFirst() } ?: break
                next.run()
            }
        }

        val handle = (coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted).handle
        gate = ScenePresentationGate(handle.sessionId, handle.generation)
        assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        drainCallbacks()
        assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
        val delayedFarCallbacks = synchronized(callbacks) { callbacks.toList().also { callbacks.clear() } }

        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(true))
        drainCallbacks() // Consumer sees current near/black snapshot first.
        assertFalse(gate.visible())
        assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, false).success)
        coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation)?.let(gate::apply)
        assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
        coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation)?.let(gate::apply)
        assertFalse("raw stop/start reports remain hidden while Core still owns the near cover", gate.visible())

        delayedFarCallbacks.forEach(Runnable::run)
        assertFalse("late pre-near Restored/true callback reconciles to the current Core revision", gate.visible())
        assertEquals(3L, gate.revision())

        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(false))
        drainCallbacks()
        assertTrue("a fresh far edge returns the same authorized Scene", gate.visible())
        assertEquals(4L, gate.revision())
        coordinator.closeScene(owner, handle.sessionId)
    }

    @Test fun scenePresentationGateAcceptsIdempotentSameRevisionAfterWindowRestart() {
        val gate = ScenePresentationGate("session", 3L)
        val visible = ScenePresentationSnapshot("session", 3L, 8L, true, null)
        assertTrue(gate.apply(visible))
        assertFalse(gate.apply(visible.copy(revision = 7L, visible = false, hiddenReason = SceneSuspendReason.COVERED)))
        assertTrue("a restarted local surface may reapply unchanged Core state", gate.apply(visible))
        assertFalse("same revision cannot convey conflicting state", gate.apply(visible.copy(visible = false, hiddenReason = SceneSuspendReason.COVERED)))
        assertTrue(gate.visible())
        assertEquals(8L, gate.revision())
    }

    @Test fun nearCoverBlackSuspendsAndFarRestoresSameKeepVisibleLeaseWithoutReopeningTask() {
        val gateway = FakeGateway(component)
        val recoveryFinished = CountDownLatch(1)
        gateway.onRecovery = { recoveryFinished.countDown() }
        val coordinator = coordinator(gateway, { true })
        val events = java.util.concurrent.CopyOnWriteArrayList<SceneEvent>()
        val observer = object : SceneObserver {
            override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) { events += event }
        }
        val opened = coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted
        val handle = opened.handle
        assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)

        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(true))
        assertTrue(events.contains(SceneEvent.VisibilityChanged(false)))
        assertTrue(events.contains(SceneEvent.Suspended(SceneSuspendReason.COVERED)))
        gateway.rearDisplayOn = false
        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(false))
        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearProximity(false))
        assertTrue("far is a causal event for one-shot recovery of an eligible lease", recoveryFinished.await(2, TimeUnit.SECONDS))
        assertEquals("far restores the existing task; it does not relaunch", 1, gateway.launchCount)
        assertTrue(coordinator.ownsActiveSession(owner, handle.sessionId))

        coordinator.handleDisplaySignal(handle.sessionId, handle.generation, SceneDisplaySignal.RearDisplayPower(true))
        assertTrue(coordinator.reportSceneVisibility(owner, handle.sessionId, handle.generation, true).success)
        assertTrue(events.contains(SceneEvent.Restored))
        coordinator.closeScene(owner, handle.sessionId)
    }

    @Test fun keepVisibleFalseDoesNotWakeAfterPrimaryScreenOffAndExplicitPreemptionCancelsLateRecovery() {
        val gateway = FakeGateway(component)
        val coordinator = coordinator(gateway, { true })
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        val noKeep = coordinator.openScene(owner, SceneRequest(component, keepVisible = false), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted
        assertTrue(coordinator.reportSceneWindowReady(owner, noKeep.handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(coordinator.reportSceneVisibility(owner, noKeep.handle.sessionId, noKeep.handle.generation, true).success)
        gateway.rearDisplayOn = false
        coordinator.handleDisplaySignal(noKeep.handle.sessionId, noKeep.handle.generation, SceneDisplaySignal.PrimaryScreenOff)
        coordinator.handleDisplaySignal(noKeep.handle.sessionId, noKeep.handle.generation, SceneDisplaySignal.RearDisplayPower(false))
        Thread.sleep(500)
        assertEquals("natural sleep is not undone without keep_visible", 0, gateway.recoveryCount)
        coordinator.closeScene(owner, noKeep.handle.sessionId)

        val recoveryStarted = CountDownLatch(1)
        val allowRecoveryToFinish = CountDownLatch(1)
        gateway.onRecovery = { recoveryStarted.countDown(); allowRecoveryToFinish.await(2, TimeUnit.SECONDS) }
        gateway.rearDisplayOn = true
        val keep = coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted
        assertTrue(coordinator.reportSceneWindowReady(owner, keep.handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(coordinator.reportSceneVisibility(owner, keep.handle.sessionId, keep.handle.generation, true).success)
        coordinator.handleDisplaySignal(keep.handle.sessionId, keep.handle.generation, SceneDisplaySignal.PrimaryScreenOff)
        gateway.rearDisplayOn = false
        coordinator.handleDisplaySignal(keep.handle.sessionId, keep.handle.generation, SceneDisplaySignal.RearDisplayPower(false))
        assertTrue(recoveryStarted.await(2, TimeUnit.SECONDS))
        coordinator.closeAllScenes(SceneCloseReason.PREEMPTED)
        allowRecoveryToFinish.countDown()
        Thread.sleep(100)
        assertFalse(coordinator.ownsActiveSession(owner, keep.handle.sessionId))
        assertEquals("the delayed recovery never reopens/finishes any new task", listOf(42, 42), gateway.finishedTaskIds)
    }

    @Test fun verifiedWindowAndTaskDoNotImplyVisibilityUntilActivityReportsIt() {
        val gateway = FakeGateway(component)
        val coordinator = coordinator(gateway, { true })
        val events = mutableListOf<SceneEvent>()
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) { events += event } }
        val opened = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted

        assertTrue(coordinator.reportSceneWindowReady(owner, opened.handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue("task/window verification is not reported as visible", events.contains(SceneEvent.VisibilityChanged(false)))
        assertTrue("task/window verification alone remains paused until real display/window visibility", events.contains(SceneEvent.Suspended(SceneSuspendReason.DISPLAY_OFF)))
        assertTrue(coordinator.reportSceneVisibility(owner, opened.handle.sessionId, opened.handle.generation, true).success)
        assertEquals(SceneEvent.VisibilityChanged(true), events[events.size - 2])
        assertEquals(SceneEvent.Restored, events.last())
    }

    @Test fun explicitCastTakingOwnershipDuringDisplayPreparationCancelsSceneLaunch() {
        val epoch = AtomicLong(0L)
        val gateway = FakeGateway(component).apply { beforePrepare = { epoch.incrementAndGet() } }
        val coordinator = coordinator(gateway, { true }, ownershipEpoch = epoch::get)
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        val rejected = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed"), observer)
        assertEquals(SceneError.DISPLAY_BUSY, (rejected as SceneOpenResult.Rejected).code)
        assertEquals("a cast that wins while the one-shot display preparation is in flight prevents Scene launch", 0, gateway.launchCount)
    }

    @Test fun notificationAndChargingOverlaysSuspendButDoNotRevokeAVisibleScene() {
        val gateway = FakeGateway(component)
        val coordinator = coordinator(gateway, consume = { true })
        val events = mutableListOf<SceneEvent>()
        val observer = object : SceneObserver {
            override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) { events += event }
        }
        val opened = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted
        assertTrue(coordinator.reportSceneWindowReady(owner, opened.handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(coordinator.reportSceneVisibility(owner, opened.handle.sessionId, opened.handle.generation, true).success)
        events.clear()

        assertTrue(coordinator.beginTransientOverlay(300L))
        assertEquals(listOf(SceneEvent.VisibilityChanged(false), SceneEvent.Suspended(SceneSuspendReason.COVERED)), events)
        assertTrue("charging may replace the current notification overlay owner", coordinator.beginTransientOverlay(302L))
        assertFalse("an expired notification end cannot uncover the replacement charging overlay", coordinator.endTransientOverlay(300L))
        events.clear()
        assertTrue("cover lifecycle can report the Scene Activity paused", coordinator.reportSceneVisibility(owner, opened.handle.sessionId, opened.handle.generation, false).success)
        assertTrue(coordinator.endTransientOverlay(302L))
        assertTrue("closing the overlay does not claim the paused Activity is visible", events.isEmpty())
        assertTrue(coordinator.reportSceneVisibility(owner, opened.handle.sessionId, opened.handle.generation, true).success)
        assertEquals(listOf(SceneEvent.VisibilityChanged(true), SceneEvent.Restored), events)

        events.clear()
        assertTrue(coordinator.beginTransientOverlay(301L))
        coordinator.closeScene(owner, opened.handle.sessionId)
        assertTrue("the old overlay remains barred from restoring a legacy task after Scene ownership", coordinator.endTransientOverlay(301L))
        assertFalse("a closed lease is never restored by a later overlay end", events.any { it == SceneEvent.Restored })
    }

    @Test fun legacyAnimationStartedWithoutSceneCannotRestoreAcrossLaterSceneOwnership() {
        // The real static production bridge runs while the singleton is still absent. No hidden
        // call to Core.get() is allowed just to capture an animation that currently has no Scene.
        assertFalse("the isolated test must exercise the lazy no-Core state", RearCapabilityCore.isInitializedForTesting())
        assertFalse(RearCapabilityCore.beginTransientOverlay(350L))
        val gateway = FakeGateway(component)
        val coordinator = coordinator(gateway, consume = { true })

        // No Scene existed at animation start, so the adapter captured only the process epoch.
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        val opened = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed"), observer)
        assertTrue("an explicit user Scene request can take ownership during an un-covering animation", opened is SceneOpenResult.Accepted)
        val handle = (opened as SceneOpenResult.Accepted).handle
        assertTrue(coordinator.reportSceneWindowReady(owner, handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)

        assertTrue(coordinator.closeScene(owner, handle.sessionId).success)
        assertTrue(
            "ending the old animation remains suppressed after the intervening Scene has already closed",
            RearCapabilityCore.endTransientOverlay(350L)
        )
        assertFalse("the old animation token is consumed exactly once", RearCapabilityCore.endTransientOverlay(350L))
    }

    @Test fun inheritedAnimationReturnKeepsColdLazySceneRevocationProvenance() {
        assertFalse("the real manager path starts before lazy Core construction", RearCapabilityCore.isInitializedForTesting())
        val first = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.NOTIFICATION)
        val inheritedTaskId = 7701
        first.rearTaskId = inheritedTaskId
        assertFalse(RearAnimationManager.isSceneOverlaySession(first.id))

        // A direct real coordinator reservation advances the same process epoch without creating
        // the lazy Core singleton, exactly as the cold A -> Scene B -> close sequence does.
        val coordinator = coordinator(FakeGateway(component), { true })
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }
        val opened = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted
        assertTrue(coordinator.closeScene(owner, opened.handle.sessionId).success)

        val replacement = RearAnimationManager.beginAnimation(RearAnimationManager.AnimationType.CHARGING)
        assertEquals("replacement inherits the actual return task", inheritedTaskId, replacement.rearTaskId)
        assertTrue("the replacement carries the old token's now-revoked return provenance", RearAnimationManager.isSceneOverlaySession(replacement.id))
        assertTrue("the production restore entrypoint exits before any backend/task restore", RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.CHARGING, replacement.id))
        ChargingService.restoreRearScreen(null, inheritedTaskId, replacement.id)
        assertTrue("the guard remains in force for the delayed production restore callback", RearAnimationManager.isSceneOverlaySession(replacement.id))
    }

    @Test fun activeKeepVisibleSceneSurvivesMainLockPauseAndRestoresOnActivityResume() {
        val gateway = FakeGateway(component)
        val coordinator = coordinator(gateway, consume = { true })
        val events = mutableListOf<SceneEvent>()
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) { events += event } }
        val opened = coordinator.openScene(owner, SceneRequest(component, keepVisible = true), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted

        assertTrue(coordinator.reportSceneWindowReady(owner, opened.handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(coordinator.reportSceneVisibility(owner, opened.handle.sessionId, opened.handle.generation, true).success)
        events.clear()

        // Locking the primary screen pauses this Activity and drops its window flag, but the
        // owner's active Scene lease remains eligible for its own resume callback.
        assertTrue(coordinator.reportSceneVisibility(owner, opened.handle.sessionId, opened.handle.generation, false).success)
        assertTrue(coordinator.ownsActiveSession(owner, opened.handle.sessionId))
        assertEquals(listOf(SceneEvent.VisibilityChanged(false), SceneEvent.Suspended(SceneSuspendReason.DISPLAY_OFF)), events)
        events.clear()

        assertTrue(coordinator.reportSceneVisibility(owner, opened.handle.sessionId, opened.handle.generation, true).success)
        assertEquals(listOf(SceneEvent.VisibilityChanged(true), SceneEvent.Restored), events)
        assertEquals("unlock/resume restores the existing lease without a second activation or wake", 1, gateway.prepareCount)
        assertEquals(1, gateway.launchCount)
        assertTrue(coordinator.ownsActiveSession(owner, opened.handle.sessionId))
    }

    @Test fun revokedOwnerAndLateOverlayEndCannotRestoreOrFinishAnotherScene() {
        val gateway = FakeGateway(component)
        val coordinator = coordinator(gateway, consume = { true })
        val events = mutableListOf<SceneEvent>()
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) { events += event } }
        val opened = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed"), observer) as SceneOpenResult.Accepted
        assertTrue(coordinator.reportSceneWindowReady(owner, opened.handle.launchTicket, SceneWindowReport(component, 42, viewport(1))).success)
        assertTrue(coordinator.reportSceneVisibility(owner, opened.handle.sessionId, opened.handle.generation, true).success)
        assertTrue(coordinator.beginTransientOverlay(400L))
        coordinator.closeOwnerSessions(owner, SceneCloseReason.OWNER_REVOKED)
        val eventsAtRevocation = events.toList()

        assertFalse("revoked owner cannot revive a stale generation", coordinator.reportSceneVisibility(owner, opened.handle.sessionId, opened.handle.generation, true).success)
        assertTrue("a closed owner cannot revive legacy restore rights after Scene ownership", coordinator.endTransientOverlay(400L))
        assertEquals(eventsAtRevocation, events)
        assertFalse(coordinator.ownsActiveSession(owner, opened.handle.sessionId))
        assertEquals("revocation ends only the one task owned by this lease", listOf(42), gateway.finishedTaskIds)
        assertFalse("overlay end cannot append restoration after the owner was revoked", events.drop(eventsAtRevocation.size).any { it == SceneEvent.Restored })
    }

    @Test fun launchReturningAfterTicketExpiryReleasesItsReservationAndOnlyItsTask() {
        val clock = AtomicLong(1000L)
        val gateway = FakeGateway(component).apply { beforeLaunchReturn = { clock.set(6000L) } }
        val coordinator = coordinator(gateway, { true }, clock::get)
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {} }

        val expired = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("first"), observer)
        assertEquals(SceneError.STALE_SESSION, (expired as SceneOpenResult.Rejected).code)
        assertEquals("late task is ended exactly once", listOf(42), gateway.finishedTaskIds)

        gateway.beforeLaunchReturn = {}
        val next = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("second"), observer)
        assertTrue("expired reservation must not leave a permanent DISPLAY_BUSY lease", next is SceneOpenResult.Accepted)
        assertEquals("new accepted scene is not cleaned as if it were the late task", listOf(42), gateway.finishedTaskIds)
    }

    private fun coordinator(gateway: FakeGateway, consume: (UserActivationToken) -> Boolean, clock: () -> Long = { 1000L }, ownershipEpoch: () -> Long = { 0L }, scheduler: ScheduledThreadPoolExecutor? = null, lifecycleMonitor: SceneDisplayLifecycleMonitor? = null): RearSceneCoordinator {
        val verifier = UserActivationVerifier { _, _, token -> consume(token) }
        val callbackExecutor = Executor { it.run() }
        return if (scheduler == null) {
            RearSceneCoordinator(gateway, verifier, callbackExecutor, clock = clock,
                randomToken = TokenSequence()::next, displayOwnershipEpoch = ownershipEpoch, lifecycleMonitor = lifecycleMonitor)
        } else {
            RearSceneCoordinator(gateway, verifier, callbackExecutor, expiryScheduler = scheduler, clock = clock,
                randomToken = TokenSequence()::next, displayOwnershipEpoch = ownershipEpoch, lifecycleMonitor = lifecycleMonitor)
        }
    }

    private fun waitForPresentation(coordinator: RearSceneCoordinator, handle: SceneSessionHandle, visible: Boolean): ScenePresentationSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            val snapshot = coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation)
            if (snapshot?.visible == visible) return snapshot
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for Core presentation visible=$visible; current=${coordinator.queryScenePresentation(owner, handle.sessionId, handle.generation)}")
    }

    private fun viewport(revision: Long) = RearViewportSnapshot(
        revision = revision, displayId = 1, physicalDisplayPx = PixelSize(1200, 800),
        contentBoundsPx = PixelRect(0, 0, 1200, 800), windowPx = PixelSize(1200, 800),
        windowDp = DpSize(400f, 266.66f), densityDpi = 240, renderBufferPx = PixelSize(1200, 800),
        displayRotation = 0, contentRotation = 0,
        inputTransform = CoordinateTransform(1f, 0f, 0f, 0f, 1f, 0f)
    )

    private class TokenSequence {
        private var next = 0
        fun next() = "ticket-${++next}"
    }

    private class ControlledRecoveryScheduler : ScheduledThreadPoolExecutor(1) {
        private val recoveryCallbacks = mutableListOf<Runnable>()
        val pendingRecoveries: Int get() = synchronized(recoveryCallbacks) { recoveryCallbacks.size }

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if (unit.toMillis(delay) == 350L) {
                synchronized(recoveryCallbacks) { recoveryCallbacks += command }
                return super.schedule(command, 1L, TimeUnit.DAYS).also { it.cancel(false) }
            }
            return super.schedule(command, delay, unit)
        }

        fun runRecovery(index: Int) {
            val callback = synchronized(recoveryCallbacks) { recoveryCallbacks[index] }
            callback.run()
        }
    }

    private class ControlledExpiryScheduler : ScheduledThreadPoolExecutor(1) {
        private val callbacks = mutableListOf<Runnable>()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            synchronized(callbacks) { callbacks += command }
            return super.schedule(command, 1L, TimeUnit.DAYS).also { it.cancel(false) }
        }
        fun run(index: Int) { synchronized(callbacks) { callbacks[index] }.run() }
    }

    private class FakeGateway(private val component: ComponentName) : RearSceneGateway {
        val finishedTaskIds = mutableListOf<Int>()
        var verifyCount = 0
        var launchCount = 0
        var authorizedRequest: SceneRequest? = null
        var prepareError: SceneError? = null
        var prepareCount = 0
        var wakeCount = 0
        var lastWakeDisplayId = -1
        var rearDisplayOn = true
        var preparedKeepVisible: Boolean? = null
        var recoveryCount = 0
        var recoveryInvocationCount = 0
        var onRecovery: () -> Unit = {}
        var onRecoveryReturn: () -> Unit = {}
        var beforeRecoveryInspection: () -> Unit = {}
        var beforeActionGate: ((() -> Boolean) -> Boolean)? = null
        var presentationStatusOverride: String? = null
        var deferWhenCurrentInvalid = false
        var uniqueTaskIds = false
        private var nextTaskId = 42
        var lastLaunchedTaskId = 42
        var beforeLaunchReturn: () -> Unit = {}
        var beforeAuthorize: () -> Unit = {}
        var beforePrepare: () -> Unit = {}
        var targetDisplay = 1
        var launchDisplay = 1
        override fun queryCapabilities(owner: SessionOwner) = CapabilitySnapshot("remrss.platform/1", true, true, emptyList())
        override fun authorize(owner: SessionOwner, request: SceneRequest): SceneError? { authorizedRequest = request; beforeAuthorize(); return null }
        override fun prepareDisplay(owner: SessionOwner, request: SceneRequest): SceneError? {
            prepareCount++
            preparedKeepVisible = request.keepVisible
            beforePrepare()
            if (!rearDisplayOn) {
                wakeCount++
                lastWakeDisplayId = 1
                rearDisplayOn = true
            }
            return prepareError
        }
        override fun targetDisplayId(): Int = targetDisplay
        override fun recoverOwnedDisplay(
            owner: SessionOwner,
            request: SceneRequest,
            task: LaunchedScene,
            isCurrent: () -> Boolean,
            onWakeAttempt: () -> SceneRecoveryWakeDecision
        ): SceneRecoveryResult {
            recoveryInvocationCount++
            try {
                if (!isCurrent()) return SceneRecoveryResult(SceneError.STALE_SESSION)
                beforeRecoveryInspection()
                if (!isCurrent()) return SceneRecoveryResult(SceneError.STALE_SESSION)
                val wakeDecision = onWakeAttempt()
                if (wakeDecision == SceneRecoveryWakeDecision.STALE) return SceneRecoveryResult(SceneError.STALE_SESSION)
                recoveryCount++
                if (wakeDecision == SceneRecoveryWakeDecision.CLAIMED) {
                    wakeCount++
                    lastWakeDisplayId = task.displayId
                }
                onRecovery()
                val allowedToPresent = beforeActionGate?.invoke(isCurrent) ?: isCurrent()
                if (!allowedToPresent) return if (deferWhenCurrentInvalid) SceneRecoveryResult(deferredByOverlay = true)
                    else SceneRecoveryResult(SceneError.STALE_SESSION)
                presentationStatusOverride?.let { status ->
                    return sceneRecoveryResultForPresentationStatus(status)
                }
                rearDisplayOn = true
                return SceneRecoveryResult()
            } finally { onRecoveryReturn() }
        }
        override fun launchNewTask(owner: SessionOwner, request: SceneRequest, launchTicket: String, expiresAtElapsedRealtimeMs: Long): LaunchedScene {
            launchCount++
            beforeLaunchReturn()
            lastLaunchedTaskId = if (uniqueTaskIds) nextTaskId++ else 42
            return LaunchedScene(component, lastLaunchedTaskId, launchDisplay)
        }
        override fun verifyLaunchedTask(owner: SessionOwner, expected: LaunchedScene, report: SceneWindowReport): Boolean {
            verifyCount++
            return expected.component == report.component && expected.taskId == report.taskId && expected.displayId == report.viewport.displayId
        }
        override fun finishOwnedTask(owner: SessionOwner, task: LaunchedScene) { finishedTaskIds += task.taskId }
    }

    private class FakeProximitySensorPort : SceneProximitySensorPort {
        override val managerAvailable: Boolean = true
        private var callback: ((Float) -> Unit)? = null
        private val callbacks = mutableListOf<(Float) -> Unit>()
        @Volatile var lastCallback: ((Float) -> Unit)? = null
            private set
        @Volatile var registered: Boolean = false
            private set
        var selected: RearProximitySensorCandidate? = null
            private set
        @Volatile var registrationCount: Int = 0
            private set
        private var callbackHandler: Handler = Handler(Looper.getMainLooper())

        override fun discover() = listOf(
            RearProximitySensorCandidate(8, "proximity Proximity Sensor", "android.sensor.proximity", true, 5f),
            RearProximitySensorCandidate(33171123, "tcs3760_sec_back Proximity Back Sensor", "xiaomi.sensor.proxmity_back", false, 5f),
            RearProximitySensorCandidate(33171123, "tcs3760_sec_back Proximity Back Sensor", "xiaomi.sensor.proxmity_back", true, 5f),
            RearProximitySensorCandidate(33171124, "tcs3760_sec_back Proximity Back Sensor Strm", "xiaomi.sensor.proxmity_back.factory", false, 5f)
        )

        override fun register(source: RearProximitySensorCandidate, onDistance: (Float) -> Unit): Boolean {
            selected = source
            callback = onDistance
            lastCallback = onDistance
            synchronized(callbacks) { callbacks += onDistance }
            registrationCount++
            registered = true
            return true
        }

        override fun unregister() { registered = false; callback = null }
        fun useHandler(handler: Handler): SceneProximitySensorPort { callbackHandler = handler; return this }
        fun emit(distance: Float) { callbackHandler.post { callback?.invoke(distance) } }
        fun callbackAt(index: Int): (Float) -> Unit = synchronized(callbacks) { callbacks[index] }
        fun emit(callback: (Float) -> Unit, distance: Float) { callbackHandler.post { callback(distance) } }
    }
}
