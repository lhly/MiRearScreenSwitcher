package top.zainzh.rearscreen.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ServiceConnectionSignalTest {
    @Test fun asynchronousAcquireCanCompleteAfterCapabilityQueryStarts() {
        val signal = ServiceConnectionSignal<Any>()
        val service = Any()
        val callbackExecutor = Executors.newSingleThreadExecutor()
        val explicitRequestExecutor = Executors.newSingleThreadExecutor()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val requestWaiting = CountDownLatch(1)
        try {
            // Mirrors TaskServiceManager.acquire(): enqueue the ServiceConnection callback on its
            // main-thread handler rather than delivering it inline with the caller.
            callbackExecutor.execute {
                callbackEntered.countDown()
                releaseCallback.await()
                signal.onConnected(service)
            }
            assertTrue("acquire callback is queued on its dispatcher", callbackEntered.await(1, TimeUnit.SECONDS))
            assertTrue("connection is pending while acquire callback is queued", signal.isAwaitingConnection())
            // Production capability discovery performs this same immediate current() read.
            assertNull("passive capability query reads the current binder instead of waiting", signal.current())

            val waitingAction = explicitRequestExecutor.submit<Any?> {
                requestWaiting.countDown()
                signal.await(1_000)
            }
            assertTrue("explicit action reached the bounded connection wait", requestWaiting.await(1, TimeUnit.SECONDS))
            releaseCallback.countDown()
            assertSame("the explicit action receives the late service", service, waitingAction.get(1, TimeUnit.SECONDS))
            assertFalse(signal.isAwaitingConnection())
        } finally {
            releaseCallback.countDown()
            callbackExecutor.shutdownNow()
            explicitRequestExecutor.shutdownNow()
        }
    }

    @Test fun disconnectAllowsALaterServiceConnectionToBeObserved() {
        val signal = ServiceConnectionSignal<Any>()
        val first = Any()
        val replacement = Any()
        signal.onConnected(first)
        assertSame(first, signal.current())

        signal.onDisconnected()
        assertTrue(signal.isAwaitingConnection())
        signal.onConnected(replacement)
        assertSame(replacement, signal.await(10))
    }
}
