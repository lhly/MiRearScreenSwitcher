package dev.remrss.platform.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class RegisteredActionCapabilityTest {
    @Test fun oldHostMayKeepTheSameContractWhileActionApiIsTypedUnsupported() {
        val state = registeredActionCapabilityState(listOf(RegisteredActionCapabilityDeclaration("rear.scene", true)))
        assertEquals(RegisteredActionCapabilityState(supported = false, granted = false), state)
        assertEquals(null, RearContractNegotiation.failureCode("remrss.platform/1"))
    }

    @Test fun supportedActionCapabilityCanStillRequirePerPublisherUserSelection() {
        assertEquals(RegisteredActionCapabilityState(true, false), registeredActionCapabilityState(
            listOf(RegisteredActionCapabilityDeclaration("action.invoke", false))
        ))
        assertEquals(RegisteredActionCapabilityState(true, true), registeredActionCapabilityState(
            listOf(RegisteredActionCapabilityDeclaration("action.invoke", true))
        ))
    }
}
