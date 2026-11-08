package top.zainzh.rearscreen.platform.mods

import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.NativeModuleRendererTestHostActivity

@RunWith(AndroidJUnit4::class)
class ModSceneWindowPolicyTest {
    @get:Rule val compose = createAndroidComposeRule<NativeModuleRendererTestHostActivity>()

    @Test fun readyAndMeasuredWindowStayBlackUntilCoordinatorReportsVisible() {
        val policy = ModSceneWindowPolicy(
            windowReady = true,
            windowAttachedAndVisible = true,
            coordinatorVisible = false,
            keepVisibleRequested = true,
        )

        assertFalse(policy.showSceneContent)
        assertFalse(policy.keepScreenOn)
    }

    @Test fun keepVisibleOnlyControlsFlagAfterBothVisibilitySourcesAgree() {
        val keepVisible = ModSceneWindowPolicy(
            windowReady = true,
            windowAttachedAndVisible = true,
            coordinatorVisible = true,
            keepVisibleRequested = true,
        )
        val ordinary = keepVisible.copy(keepVisibleRequested = false)

        assertTrue(keepVisible.showSceneContent)
        assertTrue(keepVisible.keepScreenOn)
        assertTrue(ordinary.showSceneContent)
        assertFalse(ordinary.keepScreenOn)
    }

    @Test fun aWindowThatLostActualVisibilityIsCoveredEvenIfCoreHasNotDeliveredTheEventYet() {
        val policy = ModSceneWindowPolicy(
            windowReady = true,
            windowAttachedAndVisible = false,
            coordinatorVisible = true,
            keepVisibleRequested = true,
        )

        assertFalse(policy.showSceneContent)
        assertFalse(policy.keepScreenOn)
    }

    @Test fun mainDisplayInputFocusDoesNotClearVisibleRearKeepScreenPolicy() {
        // Input focus is deliberately not a policy input: the main display can
        // own input while the rear Activity window remains actually visible.
        val rearStillVisible = ModSceneWindowPolicy(
            windowReady = true,
            windowAttachedAndVisible = true,
            coordinatorVisible = true,
            keepVisibleRequested = true,
        )

        assertTrue(rearStillVisible.showSceneContent)
        assertTrue(rearStillVisible.keepScreenOn)
        val trulyCoveredByCore = rearStillVisible.copy(coordinatorVisible = false)
        assertFalse(trulyCoveredByCore.showSceneContent)
        assertFalse(trulyCoveredByCore.keepScreenOn)
    }

    @Test fun hiddenSceneSurfaceRendersSolidBlackOverItsContent() {
        compose.setContent {
            ModSceneVisibilitySurface(visible = false) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(Color.Red)
                )
            }
        }

        val image = compose.onRoot().captureToImage()
        val bitmap = image.asAndroidBitmap()
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "mod-scene-hidden-black.png")
        FileOutputStream(screenshot).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val pixels = image.toPixelMap()
        assertTrue(pixels.width > 0 && pixels.height > 0)
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                assertTrue("expected black at ($x,$y), got ${pixels[x, y]}", pixels[x, y].toArgb() == AndroidColor.BLACK)
            }
        }
    }
}
