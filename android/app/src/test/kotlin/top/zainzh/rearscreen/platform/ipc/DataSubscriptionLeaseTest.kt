package top.zainzh.rearscreen.platform.ipc

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSubscriptionLeaseTest {
    @Test fun closeBeforeObserveReturnsClosesLateProviderHandleAndNeverRevivesLease() {
        val lease = DataSubscriptionLease<String>()
        val observeEntered = CountDownLatch(1)
        val allowProviderReturn = CountDownLatch(1)
        val closedHandles = AtomicInteger()
        val lateActivationAccepted = AtomicInteger(-1)
        val delivered = mutableListOf<String>()
        val worker = Thread {
            observeEntered.countDown()
            check(allowProviderReturn.await(2, TimeUnit.SECONDS))
            val lateHandle = closeCounter(closedHandles)
            lateActivationAccepted.set(if (lease.activate(lateHandle, delivered::add)) 1 else 0)
        }

        lease.deliver("buffered-before-close", delivered::add)
        worker.start()
        assertTrue("provider observe is in flight", observeEntered.await(2, TimeUnit.SECONDS))
        assertTrue("Binder death / revoke / explicit close terminates the lease", lease.close())
        allowProviderReturn.countDown()
        worker.join(2_000)

        assertFalse("provider worker completed", worker.isAlive)
        assertEquals("closed lease rejects the late handle", 0, lateActivationAccepted.get())
        assertEquals("late shared-provider reference released once", 1, closedHandles.get())
        assertTrue("closed lease drops the pre-activation snapshot", delivered.isEmpty())
        assertFalse("late provider completion cannot reactivate the lease", lease.activate(closeCounter(closedHandles), delivered::add))
        assertEquals("every rejected late handle is closed", 2, closedHandles.get())
        assertFalse("post-close callback is rejected", lease.deliver("stale", delivered::add))
        assertTrue(delivered.isEmpty())
    }

    @Test fun activationDeliversLatestBufferedValueAndCloseReleasesHandleExactlyOnce() {
        val lease = DataSubscriptionLease<String>()
        val closedHandles = AtomicInteger()
        val delivered = mutableListOf<String>()
        lease.deliver("first", delivered::add)
        lease.deliver("latest", delivered::add)

        assertTrue(lease.activate(closeCounter(closedHandles), delivered::add))
        assertEquals(listOf("latest"), delivered)
        assertTrue(lease.deliver("live", delivered::add))
        assertEquals(listOf("latest", "live"), delivered)
        assertTrue("normal close", lease.close())
        assertFalse("duplicate close (revoke/death after normal close)", lease.close())
        assertEquals(1, closedHandles.get())
        assertFalse(lease.deliver("after-close", delivered::add))
        assertEquals(listOf("latest", "live"), delivered)
    }

    @Test fun clientDeathBeforeSubscriptionRegistrationIsRejectedBySameTerminalLease() {
        val lease = DataSubscriptionLease<String>()
        val released = AtomicInteger()
        val values = mutableListOf<String>()

        // Mirrors observeData's post-registration client liveness check followed by a race
        // where death cleanup closes the newly inserted lease before observe returns.
        lease.close()
        assertFalse(lease.activate(closeCounter(released), values::add))
        assertEquals(1, released.get())
        assertTrue(values.isEmpty())
    }

    @Test fun siblingLeasesForSameOwnerReleaseEachProviderReferenceOnCloseRevokeAndDeath() {
        val activeProviderReferences = AtomicInteger()
        val totalClosedReferences = AtomicInteger()
        fun openSibling(): DataSubscriptionLease<String> {
            val lease = DataSubscriptionLease<String>()
            activeProviderReferences.incrementAndGet()
            assertTrue(lease.activate(Closeable {
                totalClosedReferences.incrementAndGet()
                activeProviderReferences.decrementAndGet()
            }) {})
            return lease
        }

        val normalClose = openSibling()
        val grantRevocation = openSibling()
        val clientDeath = openSibling()
        assertEquals(3, activeProviderReferences.get())

        normalClose.close()
        grantRevocation.close() // Core revoke closes the same returned provider subscription.
        clientDeath.close() // Binder death uses the service's owner-wide subscription cleanup.
        clientDeath.close() // Later duplicate cleanup remains harmless.

        assertEquals(0, activeProviderReferences.get())
        assertEquals("each sibling owns and releases one provider reference", 3, totalClosedReferences.get())
    }

    @Test fun closingOuterLeaseDoesNotDeadlockAgainstCoreCallbackHoldingInnerGate() {
        val coreLeaseGate = top.zainzh.rearscreen.platform.DataLeaseDeliveryGate()
        val ipcLease = DataSubscriptionLease<String>()
        val callbackHasInnerGate = CountDownLatch(1)
        val allowCallbackToEnterOuter = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val providerCloseStarted = CountDownLatch(1)
        val providerReferenceReleased = AtomicInteger()
        val callbackAccepted = AtomicBoolean(true)
        val delivered = mutableListOf<String>()
        val coreLeaseHandle = Closeable {
            providerCloseStarted.countDown()
            coreLeaseGate.close { providerReferenceReleased.incrementAndGet() }
        }

        assertTrue(ipcLease.activate(coreLeaseHandle, delivered::add))
        val providerCallback = Thread {
            coreLeaseGate.deliver {
                callbackHasInnerGate.countDown()
                allowCallbackToEnterOuter.await(2, TimeUnit.SECONDS)
                callbackAccepted.set(ipcLease.deliver("stale-after-close", delivered::add))
            }
            callbackFinished.countDown()
        }.apply { isDaemon = true }
        providerCallback.start()
        assertTrue("provider callback holds the Core lease gate", callbackHasInnerGate.await(2, TimeUnit.SECONDS))

        val closeFinished = CountDownLatch(1)
        val closer = Thread {
            ipcLease.close()
            closeFinished.countDown()
        }.apply { isDaemon = true }
        closer.start()
        assertTrue("IPC close detached its Core handle", providerCloseStarted.await(2, TimeUnit.SECONDS))

        // In the old implementation, close held ipcLease's gate while waiting for coreLeaseGate;
        // the callback below then waited for ipcLease's gate: a deterministic ABBA cycle.
        allowCallbackToEnterOuter.countDown()
        assertTrue("callback exits the Core gate after seeing terminal IPC lease", callbackFinished.await(2, TimeUnit.SECONDS))
        assertTrue("close returns after the Core provider reference is released", closeFinished.await(2, TimeUnit.SECONDS))
        assertFalse("closing terminal IPC lease suppresses callback delivery", callbackAccepted.get())
        assertEquals("Core provider observer reference released once", 1, providerReferenceReleased.get())
        assertTrue("no post-close value reaches the consumer", delivered.isEmpty())
    }

    private fun closeCounter(counter: AtomicInteger): Closeable = Closeable { counter.incrementAndGet() }
}
