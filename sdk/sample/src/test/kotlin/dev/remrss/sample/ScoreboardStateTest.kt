package dev.remrss.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ScoreboardStateTest {
    @Test fun acceptedEventBeforeFirstReadyReplyCannotSubmitTicketTwice() {
        val gate = TicketReadyGate()
        assertTrue(gate.begin("one-use-ticket")) // Activity submits first READY RPC.
        assertFalse(gate.begin("one-use-ticket")) // accepted callback arrives before its reply.
        val replacementActivityRetries = AtomicInteger()
        val replacementWaiter = gate.whenRetryable("one-use-ticket") { replacementActivityRetries.incrementAndGet() }
        gate.complete("one-use-ticket", TicketReadyGate.Completion.RETRYABLE_FAILURE)
        assertEquals(1, replacementActivityRetries.get()) // recreation waits on the same logical ticket.
        assertTrue(gate.begin("one-use-ticket")) // retry is owned by the replacement Activity.
        gate.complete("one-use-ticket", TicketReadyGate.Completion.SUCCEEDED)
        val alreadyScheduledRetry = { gate.begin("one-use-ticket") }
        assertFalse(alreadyScheduledRetry()) // accepted/READY wins before the old delayed retry fires.
        replacementWaiter.close()
        gate.markClosed("one-use-ticket") // Later close cannot reopen or consume another ticket.
        assertFalse(gate.begin("one-use-ticket"))
    }

    @Test fun measuredConfigurationChangeProducesANewerFinalViewportRevision() {
        val lease = SampleLease("session", 3, "ticket")
        lease.markReady(1)
        val initial = ViewportSignature.from(report(
            widthPx = 1200, heightPx = 800, widthDp = 800f, heightDp = 533.3f, dpi = 240, rotation = 0
        )).toString()
        val firstRevision = lease.nextViewportRevision(initial)!!
        assertEquals(2L, firstRevision)
        lease.markViewportAccepted(initial, firstRevision)
        assertNull("same final measurements are not resent", lease.nextViewportRevision(initial))

        // Controlled rotation/density/layout change: report measured window coordinates, not only Configuration.
        val rotated = ViewportSignature.from(report(
            widthPx = 800, heightPx = 1200, widthDp = 400f, heightDp = 600f, dpi = 320, rotation = 1
        )).toString()
        val rotatedRevision = lease.nextViewportRevision(rotated)!!
        assertEquals(3L, rotatedRevision)
        lease.markViewportAccepted(rotated, rotatedRevision)

        // Activity recreation shares the same app-owned lease; subsequent layout changes remain monotonic.
        val resized = ViewportSignature.from(report(
            widthPx = 900, heightPx = 1200, widthDp = 450f, heightDp = 600f, dpi = 320, rotation = 1
        )).toString()
        assertEquals(4L, lease.nextViewportRevision(resized))
    }

    @Test fun bothVisibleScreensReceiveEveryUpdateAndStopAfterLeaving() {
        val state = ScoreboardState()
        val mainScreen = mutableListOf<Int>()
        val rearScreen = mutableListOf<Int>()
        val mainListener: (Int) -> Unit = { mainScreen.add(it) }
        val rearListener: (Int) -> Unit = { rearScreen.add(it) }
        val main = state.observe(mainListener)
        val rear = state.observe(rearListener)

        state.addPoint() // Main-screen action; rear observer also receives it.
        state.addPoint() // Rear-screen action; main observer also receives it.
        rear.close() // Rear Activity onStop unregisters; there is no background polling.
        state.addPoint()

        assertEquals(listOf(0, 1, 2, 3), mainScreen)
        assertEquals(listOf(0, 1, 2), rearScreen)
        main.close()
    }

    private fun report(widthPx: Int, heightPx: Int, widthDp: Float, heightDp: Float, dpi: Int, rotation: Int) =
        dev.remrss.platform.sdk.RearWindowReport(
            component = android.content.ComponentName("dev.remrss.sample", "dev.remrss.sample.RearSceneActivity"),
            taskId = 42, revision = 1, displayId = 2, physicalDisplayPx = 2400 to 1200,
            contentBoundsPx = intArrayOf(0, 0, widthPx, heightPx), windowPx = widthPx to heightPx,
            windowDp = widthDp to heightDp, densityDpi = dpi, renderBufferPx = null,
            displayRotation = rotation, contentRotation = rotation,
            inputTransform = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        )
}
