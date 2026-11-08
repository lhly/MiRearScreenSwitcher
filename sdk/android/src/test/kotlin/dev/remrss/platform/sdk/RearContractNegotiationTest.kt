package dev.remrss.platform.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RearContractNegotiationTest {
    @Test fun acceptsTheCurrentWireContract() {
        assertNull(RearContractNegotiation.failureCode("remrss.platform/1"))
    }

    @Test fun rejectsAnUnknownWireContractExplicitly() {
        assertEquals(RearSdkErrorCode.CONTRACT_INCOMPATIBLE,
            RearContractNegotiation.failureCode("remrss.platform/2"))
    }

    @Test fun reportsAnUnavailableContractSeparately() {
        assertEquals(RearSdkErrorCode.SERVICE_UNAVAILABLE, RearContractNegotiation.failureCode(null))
    }
}
