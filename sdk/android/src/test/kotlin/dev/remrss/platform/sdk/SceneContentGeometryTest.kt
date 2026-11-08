package dev.remrss.platform.sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneContentGeometryTest {
    @Test fun cameraSafeBoundsAreAbsoluteAndInputMapsWindowOriginToContentOrigin() {
        val bounds = SceneContentGeometry.safeBounds(12, 24, 976, 596, 20, 34, 16, 8)
        assertArrayEquals(intArrayOf(32, 58, 972, 612), bounds)

        val transform = SceneContentGeometry.inputTransform(20, 34)
        assertEquals(1f, transform[0])
        assertEquals(-20f, transform[2])
        assertEquals(1f, transform[4])
        assertEquals(-34f, transform[5])
    }

    @Test fun alreadyRotatedWindowUsesItsMeasuredInsetsWithoutRotatingDpiOrGuessingPanelDimensions() {
        // The window/decor and cutout values are in the display's current orientation. Rotation is
        // reported separately, so this helper must not swap or rotate either measured rectangle.
        val landscape = SceneContentGeometry.safeBounds(0, 0, 596, 976, 32, 8, 0, 8)
        assertArrayEquals(intArrayOf(32, 8, 596, 968), landscape)
    }

    @Test fun missingCutoutIsExplicitlyRepresentedByUnchangedWindowBounds() {
        val bounds = SceneContentGeometry.safeBounds(0, 0, 976, 596, 0, 0, 0, 0)
        assertArrayEquals(intArrayOf(0, 0, 976, 596), bounds)
    }
}
