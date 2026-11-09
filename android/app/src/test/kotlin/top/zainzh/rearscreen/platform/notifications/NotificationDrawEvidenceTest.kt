package top.zainzh.rearscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDrawEvidenceTest {
    private fun evidence(
        token: Boolean = true,
        generation: Boolean = true,
        hostReady: Boolean = true,
        displayOn: Boolean = true,
        viewport: Boolean = true,
        bounds: Boolean = true,
        attached: Boolean = true,
        visible: Boolean = true,
        windowVisible: Boolean = true,
    ) = OwnedNotificationDrawEvidence.qualifies(
        token, generation, hostReady, displayOn, viewport, bounds, attached, visible, windowVisible
    )

    @Test fun onlyCompleteCurrentVisibleDrawEvidenceQualifies() {
        assertTrue(evidence())
        assertFalse(evidence(token = false))
        assertFalse(evidence(generation = false))
        assertFalse(evidence(hostReady = false))
        assertFalse(evidence(displayOn = false))
        assertFalse(evidence(viewport = false))
        assertFalse(evidence(bounds = false))
        assertFalse(evidence(attached = false))
        assertFalse(evidence(visible = false))
        assertFalse(evidence(windowVisible = false))
    }
}
