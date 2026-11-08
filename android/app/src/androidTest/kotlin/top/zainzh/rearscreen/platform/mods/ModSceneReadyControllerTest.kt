package top.zainzh.rearscreen.platform.mods

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.platform.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the production READY handoff controller; the Port barrier controls Core call timing. */
@RunWith(AndroidJUnit4::class)
class ModSceneReadyControllerTest {
    @Test fun recreationDuringInitialReadyHandsConsumedTicketToNewWindowViewport() {
        val port = BarrierPort()
        val fixture = fixture(port)
        val controller = fixture.controller
        val oldCallback = AtomicInteger()
        val newReady = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.submit(11L, report(101), 1L, active = true) { oldCallback.incrementAndGet() }
        }
        assertTrue("old Activity started Core verification", port.readyEntered.await(3, TimeUnit.SECONDS))

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertSame(
                "the recreated Activity resolves to the same process-scoped ticket controller",
                controller,
                ModSceneReadyController.forLaunch(fixture.launch, fixture.ticket, fixture.coordinator),
            )
            controller.detach(11L)
            controller.submit(12L, report(202), 2L, active = true) {
                assertEquals(12L, it.instance)
                assertEquals(2L, it.windowEpoch)
                assertTrue(it.result.success)
                newReady.countDown()
            }
        }
        port.releaseReady.countDown()
        assertTrue("new Activity waits for first READY then updates its measured viewport", newReady.await(5, TimeUnit.SECONDS))
        assertEquals(1, port.readyCalls.get())
        assertEquals(listOf(202L), port.viewportRevisions.toList())
        assertEquals(0, oldCallback.get())
    }

    @Test fun recreationBeforeFirstReadyStartsUsesNewInstanceReportAndNoLostTicket() {
        val port = BarrierPort().apply { blockReady = false }
        val controller = fixture(port).controller
        val ready = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.submit(21L, report(301), 1L, active = false) { error("stopped Activity must not receive completion") }
            controller.detach(21L)
            controller.submit(22L, report(302), 3L, active = true) {
                assertTrue(it.result.success)
                ready.countDown()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals(1, port.readyCalls.get())
        assertEquals(302L, port.readyRevisions.single())
    }

    @Test fun alreadyReadyRecreationUsesViewportAndNeverReplaysTicket() {
        val port = BarrierPort().apply { readySnapshot = snapshot() }
        val controller = fixture(port).controller
        val ready = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.submit(31L, report(401), 4L, active = true) {
                assertTrue(it.result.success)
                ready.countDown()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals(0, port.readyCalls.get())
        assertEquals(listOf(401L), port.viewportRevisions.toList())
    }

    @Test fun retryablePendingRegistrationRetriesButCloseAndExpiryRemainTerminal() {
        val port = BarrierPort().apply {
            blockReady = false
            readyResults += SceneOperationResult(false, SceneError.INVALID_STATE, retryable = true)
        }
        val controller = fixture(port).controller
        val ready = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.submit(41L, report(501), 5L, active = true) {
                assertTrue(it.result.success)
                ready.countDown()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals(2, port.readyCalls.get())

        val closePort = BarrierPort()
        val closeController = fixture(closePort).controller
        val callbackCount = AtomicInteger()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            closeController.submit(51L, report(601), 6L, active = true) { callbackCount.incrementAndGet() }
        }
        assertTrue(closePort.readyEntered.await(3, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { closeController.markClosed() }
        closePort.releaseReady.countDown()
        assertTrue(closePort.readyFinished.await(3, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(0, closePort.viewportRevisions.size)
        assertEquals(0, callbackCount.get())

        val expiryPort = BarrierPort().apply {
            blockReady = false
            readyResults += SceneOperationResult(false, SceneError.STALE_SESSION)
        }
        val expiryController = fixture(expiryPort).controller
        val expired = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            expiryController.submit(61L, report(701), 7L, active = true) {
                assertFalse(it.result.success)
                assertEquals(SceneError.STALE_SESSION, it.result.error)
                expired.countDown()
            }
        }
        assertTrue(expired.await(5, TimeUnit.SECONDS))
        assertEquals(1, expiryPort.readyCalls.get())
    }

    @Test fun stopDuringRetryableReadyKeepsTicketPendingForSameLeaseStart() {
        val port = BarrierPort().apply {
            readyResults += SceneOperationResult(false, SceneError.INVALID_STATE, retryable = true)
        }
        val controller = fixture(port).controller
        val resumed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.submit(71L, report(801), 8L, active = true) { error("stopped window must not complete") }
        }
        assertTrue(port.readyEntered.await(3, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.setActive(71L, false) }
        port.releaseReady.countDown()
        assertTrue(port.readyFinished.await(3, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.submit(71L, report(802), 9L, active = true) {
                assertTrue(it.result.success)
                assertEquals(9L, it.windowEpoch)
                resumed.countDown()
            }
        }
        assertTrue("the same lease resumes with a fresh measured report", resumed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(801L, 802L), port.readyRevisions.toList())
        assertTrue(port.viewportRevisions.isEmpty())
    }

    private data class Fixture(
        val launch: SceneLaunchContext,
        val ticket: String,
        val coordinator: TestCoordinator,
        val controller: ModSceneReadyController,
    )

    private fun fixture(port: BarrierPort): Fixture {
        val launch = SceneLaunchContext(owner(), "session-m12", 9L, SceneRequest(component()))
        val ticket = "ticket-${System.nanoTime()}"
        val coordinator = TestCoordinator(port)
        return Fixture(launch, ticket, coordinator, requireNotNull(ModSceneReadyController.forLaunch(launch, ticket, coordinator)))
    }

    private fun owner() = SessionOwner(
        12000,
        "top.zainzh.rearscreen.platformtestm12",
        "test-cert",
        SessionPrincipal.SignedMod("author-m12", "platform.m12.scene", "logical-m12"),
    )

    private fun component() = ComponentName("top.zainzh.rearscreen.platformtestm12", "top.zainzh.rearscreen.platform.mods.ModRearSceneActivity")

    private fun report(revision: Long) = SceneWindowReport(
        component(), 42,
        RearViewportSnapshot(
            revision = revision,
            displayId = 1,
            physicalDisplayPx = PixelSize(1200, 800),
            contentBoundsPx = PixelRect(0, 0, 1200, 800),
            windowPx = PixelSize(1200, 800),
            windowDp = DpSize(400f, 266.67f),
            densityDpi = 240,
            renderBufferPx = null,
            displayRotation = 0,
            contentRotation = 0,
            inputTransform = CoordinateTransform(1f, 0f, 0f, 0f, 1f, 0f),
        ),
    )

    private fun snapshot() = ScenePresentationSnapshot("session-m12", 9L, 3L, visible = true, hiddenReason = null)

    private class BarrierPort : ModSceneReadyController.Port {
        val readyCalls = AtomicInteger()
        val readyEntered = CountDownLatch(1)
        val releaseReady = CountDownLatch(1)
        val readyFinished = CountDownLatch(1)
        val readyRevisions = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val viewportRevisions = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val readyResults = java.util.concurrent.ConcurrentLinkedQueue<SceneOperationResult>()
        @Volatile var readySnapshot: ScenePresentationSnapshot? = null
        @Volatile var blockReady = true

        override fun query(): ScenePresentationSnapshot? = readySnapshot

        override fun reportReady(ticket: String, report: SceneWindowReport): SceneOperationResult {
            readyCalls.incrementAndGet()
            readyRevisions += report.viewport.revision
            readyEntered.countDown()
            try {
                if (blockReady) releaseReady.await(3, TimeUnit.SECONDS)
                val result = readyResults.poll() ?: SceneOperationResult(true)
                if (result.success) readySnapshot = snapshotForPort()
                return result
            } finally {
                readyFinished.countDown()
            }
        }

        override fun reportViewport(report: SceneWindowReport): SceneOperationResult {
            viewportRevisions += report.viewport.revision
            readySnapshot = snapshotForPort()
            return SceneOperationResult(true)
        }

        private fun snapshotForPort() = ScenePresentationSnapshot("session-m12", 9L, 3L, visible = true, hiddenReason = null)
    }

    private class TestCoordinator(private val port: BarrierPort) : DisplaySessionCoordinator {
        override fun queryCapabilities(owner: SessionOwner) = CapabilitySnapshot("test/1", true, true, emptyList())
        override fun openScene(owner: SessionOwner, request: SceneRequest, activation: UserActivationToken, observer: SceneObserver): SceneOpenResult =
            SceneOpenResult.Rejected(SceneError.UNSUPPORTED, false)
        override fun closeScene(owner: SessionOwner, sessionId: String) = SceneOperationResult(true)
        override fun reportSceneWindowReady(owner: SessionOwner, launchTicket: String, window: SceneWindowReport) = port.reportReady(launchTicket, window)
        override fun reportSceneViewport(owner: SessionOwner, sessionId: String, generation: Long, window: SceneWindowReport) = port.reportViewport(window)
        override fun attachSceneObserver(owner: SessionOwner, sessionId: String, generation: Long, observer: SceneObserver) = SceneOperationResult(true)
        override fun reportSceneVisibility(owner: SessionOwner, sessionId: String, generation: Long, visible: Boolean) = SceneOperationResult(true)
        override fun queryScenePresentation(owner: SessionOwner, sessionId: String, generation: Long) = port.query()
        override fun closeOwnerSessions(owner: SessionOwner, reason: SceneCloseReason) = Unit
    }
}
