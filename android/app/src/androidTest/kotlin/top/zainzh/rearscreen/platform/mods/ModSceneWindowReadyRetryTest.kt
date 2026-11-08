package top.zainzh.rearscreen.platform.mods

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.platform.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Covers the launch-task registration race and terminal ticket states without a physical display. */
@RunWith(AndroidJUnit4::class)
class ModSceneWindowReadyRetryTest {
    @Test fun earlyWindowReadyIsRetryableUntilLaunchRegistersThenConsumedTicketCannotReplayOrOutliveExpiry() {
        val component = ComponentName("top.zainzh.rearscreen.platformtestm11", "top.zainzh.rearscreen.platform.mods.ModRearSceneActivity")
        val owner = SessionOwner(
            12000,
            "top.zainzh.rearscreen.platformtestm11",
            "test-cert",
            SessionPrincipal.SignedMod("author-fingerprint", "platform.m11.ready", "logical-scene-ready"),
        )
        lateinit var coordinator: RearSceneCoordinator
        var earlyResult: SceneOperationResult? = null
        val gateway = LaunchBarrierGateway(component) { ticket ->
            // Models the real Activity measuring/reporting during gateway.launchNewTask,
            // before Core has recorded the returned task as record.launch.
            earlyResult = coordinator.reportSceneWindowReady(owner, ticket, windowReport(component))
        }
        coordinator = createCoordinator(gateway, { 1000L })
        val observer = object : SceneObserver { override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) = Unit }

        val accepted = coordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed"), observer)
        assertTrue(accepted is SceneOpenResult.Accepted)
        val handle = (accepted as SceneOpenResult.Accepted).handle
        assertEquals(SceneError.INVALID_STATE, earlyResult?.error)
        assertFalse(earlyResult?.success ?: true)
        assertTrue("the task is not torn down for the documented pending-registration race", earlyResult?.retryable == true)
        assertEquals(0, gateway.finishedTaskIds.size)

        val firstReadyRetry = coordinator.reportSceneWindowReady(owner, handle.launchTicket, windowReport(component))
        assertTrue("retrying the exact live ticket after launch returns succeeds", firstReadyRetry.success)
        assertEquals(1, gateway.verifyCount)
        assertFalse("READY consumes the one-use ticket", coordinator.reportSceneWindowReady(owner, handle.launchTicket, windowReport(component)).success)
        assertTrue(coordinator.closeScene(owner, handle.sessionId).success)
        assertFalse("a closed ticket cannot be retried by a late Activity callback", coordinator.reportSceneWindowReady(owner, handle.launchTicket, windowReport(component)).success)
        assertEquals(listOf(42), gateway.finishedTaskIds)

        val clock = AtomicLong(2000L)
        val expiryGateway = LaunchBarrierGateway(component)
        val expiryCoordinator = createCoordinator(expiryGateway, clock::get)
        val expiring = expiryCoordinator.openScene(owner, SceneRequest(component), UserActivationToken("confirmed-2"), observer) as SceneOpenResult.Accepted
        clock.set(expiring.handle.launchTicketExpiresAtElapsedRealtimeMs + 1)
        val expiredRetry = expiryCoordinator.reportSceneWindowReady(owner, expiring.handle.launchTicket, windowReport(component))
        assertFalse(expiredRetry.success)
        assertEquals(SceneError.STALE_SESSION, expiredRetry.error)
        assertFalse("expired tickets are terminal, not another bounded retry", expiredRetry.retryable)
        expiryCoordinator.closeScene(owner, expiring.handle.sessionId)
    }

    private fun createCoordinator(gateway: LaunchBarrierGateway, clock: () -> Long) = RearSceneCoordinator(
        gateway = gateway,
        activationVerifier = UserActivationVerifier { _, _, _ -> true },
        callbackExecutor = Executor { it.run() },
        clock = clock,
        randomToken = { "one-ticket" },
        displayOwnershipEpoch = { 0L },
    )

    private fun windowReport(component: ComponentName) = SceneWindowReport(
        component,
        42,
        RearViewportSnapshot(
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
        ),
    )

    private class LaunchBarrierGateway(
        private val component: ComponentName,
        private val reportBeforeReturningTask: ((String) -> Unit)? = null,
    ) : RearSceneGateway {
        val finishedTaskIds = mutableListOf<Int>()
        var verifyCount = 0
        override fun queryCapabilities(owner: SessionOwner) = CapabilitySnapshot("remrss.platform/1", true, true, emptyList())
        override fun authorize(owner: SessionOwner, request: SceneRequest): SceneError? = null
        override fun targetDisplayId(): Int = 1
        override fun launchNewTask(owner: SessionOwner, request: SceneRequest, launchTicket: String, expiresAtElapsedRealtimeMs: Long): LaunchedScene {
            reportBeforeReturningTask?.invoke(launchTicket)
            return LaunchedScene(component, 42, 1)
        }
        override fun verifyLaunchedTask(owner: SessionOwner, expected: LaunchedScene, report: SceneWindowReport): Boolean {
            verifyCount++
            return expected.component == report.component && expected.taskId == report.taskId && expected.displayId == report.viewport.displayId
        }
        override fun finishOwnedTask(owner: SessionOwner, task: LaunchedScene) { finishedTaskIds += task.taskId }
    }
}
