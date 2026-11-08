package top.zainzh.rearscreen.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SceneSensorListenerOwnerTest {
    @Test fun closeWaitsForLateRegistrationThenReleasesItsExactListener() {
        val enteredRegister = CountDownLatch(1)
        val allowRegisterReturn = CountDownLatch(1)
        val active = mutableSetOf<String>()
        val unregistered = mutableListOf<String>()
        val owner = SceneSensorListenerOwner(
            registerSource = { listener: String ->
                active += listener
                if (listener == "A") {
                    enteredRegister.countDown()
                    check(allowRegisterReturn.await(2, TimeUnit.SECONDS))
                }
                true
            },
            unregisterSource = { listener: String ->
                active -= listener
                unregistered += listener
            }
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val registerA = pool.submit<Boolean> { owner.register("A") }
            assertTrue("registration entered its blocking source call", enteredRegister.await(2, TimeUnit.SECONDS))
            val closeWhileRegistering = pool.submit { owner.unregister() }
            Thread.sleep(40)
            assertFalse("cleanup serializes behind the in-flight source registration", closeWhileRegistering.isDone)

            allowRegisterReturn.countDown()
            assertTrue(registerA.get(2, TimeUnit.SECONDS))
            closeWhileRegistering.get(2, TimeUnit.SECONDS)
            assertEquals(emptySet<String>(), active)
            assertEquals(listOf("A"), unregistered)

            assertTrue("a later enable can own a fresh listener", owner.register("B"))
            owner.unregister()
            assertEquals(emptySet<String>(), active)
            assertEquals(listOf("A", "B"), unregistered)
        } finally {
            allowRegisterReturn.countDown()
            pool.shutdownNow()
        }
    }
}
