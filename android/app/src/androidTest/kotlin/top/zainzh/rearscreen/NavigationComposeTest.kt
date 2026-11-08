package top.zainzh.rearscreen

import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NavigationComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun label(key: String) = NativeStrings.get(compose.activity, key)
    private fun tab(key: String) = compose.onNodeWithContentDescription(label(key))
    private val landscape get() = InstrumentationRegistry.getArguments().getString("navigationLandscape") == "true"

    @Before fun configureOrientation() {
        if (landscape) compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
    }

    private fun capture(name: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("navigationCapturePrefix") ?: return
        compose.waitForIdle()
        // Compose idle does not include the system Activity transition.
        Thread.sleep(600)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(instrumentation.targetContext.filesDir, "nav-${prefix.replace(Regex("[^a-zA-Z0-9_-]"), "_")}-$name.png")
            .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun glassLayersKeepOneAccessibleTabAndStableContentHeight() {
        val navigation = compose.onNodeWithTag("main_navigation")
        val before = navigation.fetchSemanticsNode().boundsInRoot
        Thread.sleep(800)
        compose.waitForIdle()
        val after = navigation.fetchSemanticsNode().boundsInRoot
        assertEquals("Drawing glass must not grow the navigation layout", before.height, after.height, 1f)
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Navigation must remain a compact control", after.height < 160f * density)
        for (key in listOf("screen_title", "notifications_title", "nav_modules", "nav_settings")) {
            compose.onAllNodesWithContentDescription(label(key)).assertCountEquals(1)
            tab(key).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        capture("home")
    }

    @Test fun draggingNavigationSelectsPagesAndCanReverse() {
        val navigation = compose.onNodeWithTag("main_navigation")
        navigation.performTouchInput {
            swipe(Offset(width / 6f, height / 2f), Offset(width * 5f / 6f, height / 2f), 500)
        }
        tab("nav_settings").assertIsSelected()
        navigation.performTouchInput {
            swipe(Offset(width * 5f / 6f, height / 2f), Offset(width / 6f, height / 2f), 500)
        }
        tab("screen_title").assertIsSelected()
        compose.activityRule.scenario.recreate()
        tab("screen_title").assertIsSelected()
    }

    @Test fun topReturnStaysReachableWhileReading() {
        ActivityScenario.launch(TutorialActivity::class.java).use { tutorial ->
            if (landscape) tutorial.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val back = compose.onNodeWithTag("tutorial_back")
            back.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            val top = back.fetchSemanticsNode().boundsInRoot.top
            capture("header")
            compose.onNodeWithTag("tutorial_scroll").performScrollToIndex(TutorialActivity.sections.size)
            back.assertIsDisplayed()
            assertEquals(top, back.fetchSemanticsNode().boundsInRoot.top, 1f)
            capture("header-scrolled")
            fun readingPosition(): Pair<Int, Int> {
                var result = 0 to 0
                tutorial.onActivity {
                    val field = TutorialActivity::class.java.getDeclaredField("list").apply { isAccessible = true }
                    val list = field.get(it) as androidx.compose.foundation.lazy.LazyListState
                    result = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
                }
                return result
            }
            val position = readingPosition()
            tutorial.recreate()
            back.assertIsDisplayed()
            assertEquals("Header recreation must preserve the reading position", position, readingPosition())
            back.performClick()
            compose.onNodeWithTag("main_navigation").assertIsDisplayed()
        }
    }
}
