package dev.remrss.sample

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleStatusTest {
    @Test fun visibleObserverReceivesConfirmationAndTerminalSceneStateThenDetaches() {
        val state = SampleStatus("Connected")
        val seen = mutableListOf<String>()
        val subscription = state.observe(seen::add)
        state.publish("Host confirmation opened")
        state.publish("Rear scene failed: E_QUERY_TIMEOUT")
        subscription.close()
        state.publish("Later state")
        assertEquals(listOf("Connected", "Host confirmation opened", "Rear scene failed: E_QUERY_TIMEOUT"), seen)
        assertEquals("Later state", state.current())
    }
}
