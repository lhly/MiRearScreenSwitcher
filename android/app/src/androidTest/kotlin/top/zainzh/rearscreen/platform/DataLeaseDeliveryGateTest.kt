package top.zainzh.rearscreen.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataLeaseDeliveryGateTest {
    @Test fun revokeWaitsForAuthorizedInFlightDeliveryThenEmptyIsFinal() {
        val gate = DataLeaseDeliveryGate()
        val applied = CopyOnWriteArrayList<String>()
        val authorizedButPaused = CountDownLatch(1)
        val resumeCallback = CountDownLatch(1)
        val revokeFinished = CountDownLatch(1)

        val oldCallback = Thread {
            gate.deliver {
                // This is the exact C5 pause: permission filtering has completed but consumer
                // application has not yet occurred. The gate must hold the revoke behind it.
                authorizedButPaused.countDown()
                check(resumeCallback.await(2, TimeUnit.SECONDS))
                applied += "old-authorized-value"
            }
        }
        oldCallback.start()
        assertTrue(authorizedButPaused.await(2, TimeUnit.SECONDS))

        val revoke = Thread {
            gate.close { applied += "empty-revoked-snapshot" }
            revokeFinished.countDown()
        }
        revoke.start()
        assertFalse("revoke must serialize behind the in-flight callback", revokeFinished.await(100, TimeUnit.MILLISECONDS))

        resumeCallback.countDown()
        oldCallback.join(2_000)
        revoke.join(2_000)
        assertTrue(revokeFinished.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("old-authorized-value", "empty-revoked-snapshot"), applied.toList())
        assertFalse(gate.deliver { applied += "stale-after-revoke" })
        assertEquals("empty revoke must remain final", "empty-revoked-snapshot", applied.last())
    }

    @Test fun revokeWinningBeforeCallbackSuppressesQueuedOldSnapshot() {
        val gate = DataLeaseDeliveryGate()
        val applied = mutableListOf<String>()
        assertTrue(gate.close { applied += "empty-revoked-snapshot" })
        assertFalse(gate.deliver { applied += "queued-old-value" })
        assertEquals(listOf("empty-revoked-snapshot"), applied)
    }
}
