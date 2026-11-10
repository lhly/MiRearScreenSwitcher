package top.zainzh.rearscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationMotionTest {
    @Test fun secondaryRoutePairsPreserveTheExistingParentMapping() {
        assertEquals(NavigationRoutePair(0, 10), NavigationMotion.pairBetween(0, 10))
        assertEquals(NavigationRoutePair(1, 100), NavigationMotion.pairBetween(100, 1))
        assertEquals(NavigationRoutePair(1, 20), NavigationMotion.pairBetween(1, 20))
        assertEquals(NavigationRoutePair(2, 30), NavigationMotion.pairBetween(30, 2))
        assertEquals(NavigationRoutePair(3, 40), NavigationMotion.pairBetween(3, 40))
        assertNull(NavigationMotion.pairBetween(0, 2))
        assertEquals(0, NavigationMotion.routeForTab(10))
        assertEquals(1, NavigationMotion.routeForTab(100))
        assertEquals(2, NavigationMotion.routeForTab(30))
        assertEquals(3, NavigationMotion.routeForTab(40))
    }

    @Test fun previewTracksDirectProgressAndPreservesDirectionOnCancellation() {
        val motion = NavigationMotion()
        assertEquals(.8f, motion.beginBackPreview(.8f), .0001f)
        assertEquals(.6f, motion.updateBackProgress(.25f, 1_000L)!!, .0001f)
        assertEquals(.4f, motion.updateBackProgress(.5f, 1_100L)!!, .0001f)
        assertEquals(.52f, motion.updateBackProgress(.35f, 1_150L)!!, .0001f)
        val settle = motion.cancelBackPreview()!!
        assertEquals(.8f, settle.progress, .0001f)
        assertTrue("reversal toward the child must keep its positive release velocity", settle.velocity > 0f)
        assertFalse(motion.isPreviewing)
        assertNull("a completed cancellation cannot also commit", motion.commitBackPreview())
    }

    @Test fun commitReturnsTheParentEndpointAndOnlyCommitsOnce() {
        val motion = NavigationMotion()
        motion.beginBackPreview(1f)
        motion.updateBackProgress(.4f, 1_000L)
        motion.updateBackProgress(.7f, 1_100L)
        val settle = motion.commitBackPreview()!!
        assertEquals(0f, settle.progress, .0001f)
        assertTrue("backward gesture velocity should be negative in route space", settle.velocity < 0f)
        assertNull("a second completion must not dispatch another route commit", motion.commitBackPreview())
        assertNull(motion.cancelBackPreview())
    }

    @Test fun backProgressCanReverseWithoutChangingTheCommittedRoute() {
        assertEquals(.65f, NavigationMotion.routeProgressForBack(1f, .35f), .0001f)
        assertEquals(.82f, NavigationMotion.routeProgressForBack(.82f, 0f), .0001f)
        assertEquals(0f, NavigationMotion.routeProgressForBack(.82f, 1f), .0001f)
    }

    @Test fun retargetVelocityComesFromRenderedProgressSamples() {
        val tracker = MotionVelocityTracker()
        assertEquals(0f, tracker.sample(.2f, 1_000L), .0001f)
        assertEquals(1f, tracker.sample(.4f, 1_200L), .0001f)
        assertEquals(-1f, tracker.sample(.2f, 1_400L), .0001f)
    }

    @Test fun tabDragPreservesTheGrabOffsetAndClampsAtBothEnds() {
        val startAt = tabProgressAt(x = 142f, width = 400f, edge = 4f, tabCount = 4, isRtl = false)
        val offset = 1f - startAt
        assertEquals(1f, dragProgressWithGrabOffset(startAt, offset, 4), .0001f)
        assertEquals(2f, dragProgressWithGrabOffset(startAt + 1f, offset, 4), .0001f)
        assertEquals(0f, dragProgressWithGrabOffset(-5f, offset, 4), .0001f)
        assertEquals(3f, dragProgressWithGrabOffset(20f, offset, 4), .0001f)
        assertEquals(tabProgressAt(x = 258f, width = 400f, edge = 4f, tabCount = 4, isRtl = false),
            tabProgressAt(x = 142f, width = 400f, edge = 4f, tabCount = 4, isRtl = true), .0001f)
    }

    @Test fun offCenterGrabsNearEitherEndMoveImmediatelyWithoutAnEdgeDeadZone() {
        val startProgress = .25f
        val firstStart = tabProgressAt(x = 10f, width = 400f, edge = 4f, tabCount = 4, isRtl = false)
        val firstOffset = startProgress - firstStart
        assertEquals(startProgress, dragProgressWithGrabOffset(firstStart, firstOffset, 4), .0001f)
        assertTrue("moving farther left from an off-center first-tab grab must not stick",
            dragProgressWithGrabOffset(tabProgressAt(0f, 400f, 4f, 4, false), firstOffset, 4) < startProgress)

        val lastStartProgress = 2.75f
        val lastStart = tabProgressAt(x = 390f, width = 400f, edge = 4f, tabCount = 4, isRtl = false)
        val lastOffset = lastStartProgress - lastStart
        assertEquals(lastStartProgress, dragProgressWithGrabOffset(lastStart, lastOffset, 4), .0001f)
        assertTrue("moving farther right from an off-center last-tab grab must not stick",
            dragProgressWithGrabOffset(tabProgressAt(400f, 400f, 4f, 4, false), lastOffset, 4) > lastStartProgress)
    }
}
