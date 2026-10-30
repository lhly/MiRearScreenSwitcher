package top.zainzh.rearscreen

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class TutorialComposeTest {
    @get:Rule val compose = createAndroidComposeRule<TutorialActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun header(section: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("tutorial_scroll").performScrollToNode(hasTestTag("tutorial_header_$section"))
        return compose.onNodeWithTag("tutorial_header_$section")
    }
    private fun state(section: String, expanded: Boolean) {
        header(section).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            compose.activity.getString(if (expanded) R.string.tutorial_expanded else R.string.tutorial_collapsed)))
    }
    @Test fun localGuideIncludesAllChaptersAndAttribution() {
        var previous: String? = null
        TutorialActivity.sections.forEach { section ->
            state(section, false)
            compose.onNodeWithTag("tutorial_body_$section").assertDoesNotExist()
            header(section).performClick()
            state(section, true)
            compose.onNodeWithTag("tutorial_body_$section").assertExists()
            previous?.let { compose.onNodeWithTag("tutorial_body_$it").assertDoesNotExist() }
            previous = section
        }
        header("source").performClick()
        compose.onNodeWithTag("tutorial_scroll").performScrollToNode(hasText(compose.activity.getString(R.string.tutorial_source_help)))
        compose.onNodeWithText(compose.activity.getString(R.string.tutorial_source_help)).assertExists()
    }
    private fun saved(): Bundle {
        val bundle = Bundle()
        compose.runOnIdle {
            val field = TutorialActivity::class.java.getDeclaredField("list").apply { isAccessible = true }
            val list = field.get(compose.activity) as androidx.compose.foundation.lazy.LazyListState
            bundle.putInt("tutorial_scroll_index", list.firstVisibleItemIndex)
            bundle.putInt("tutorial_scroll_offset", list.firstVisibleItemScrollOffset)
        }
        return bundle
    }
    @Test fun chapterExpansionAndRecreationKeepStateAndAllowCollapse() {
        header("capture").performClick()
        val before = saved()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tutorial_body_capture").assertExists()
        assertEquals(before.getInt("tutorial_scroll_index"), saved().getInt("tutorial_scroll_index"))
        assertEquals(before.getInt("tutorial_scroll_offset"), saved().getInt("tutorial_scroll_offset"))
        state("capture", true)
        header("capture").performClick()
        state("capture", false)
        compose.onNodeWithTag("tutorial_body_capture").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        try {
            header("display").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("tutorial_header_display").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("tutorial_header_display").performClick()
            compose.mainClock.advanceTimeBy(400)
        } finally { compose.mainClock.autoAdvance = true }
        state("display", true)
        compose.onNodeWithTag("tutorial_body_display").assertExists()
        compose.onNodeWithTag("tutorial_body_capture").assertDoesNotExist()
    }
    @Test fun permissionShortcutOpensInternalPageAndBackReturnsToGuide() {
        val monitor = instrumentation.addMonitor(PermissionChecksActivity::class.java.name, null, false)
        var checks: PermissionChecksActivity? = null
        try {
            header("permissions").performClick()
            compose.onNodeWithTag("tutorial_scroll").performScrollToNode(hasTestTag("tutorial_permissions"))
            compose.onNodeWithTag("tutorial_permissions").performClick()
            checks = monitor.waitForActivityWithTimeout(5000) as? PermissionChecksActivity
            assertNotNull(checks)
            compose.onNodeWithTag("permissions_scroll").assertExists()
            compose.onNodeWithTag("permissions_back").performClick()
            compose.onNodeWithTag("tutorial_permissions").assertIsDisplayed()
        } finally { instrumentation.removeMonitor(monitor); instrumentation.runOnMainSync { checks?.finish() } }
    }
    @Test fun originalDocumentOnlyOpensOnExplicitClick() {
        val opened = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened.set(intent); return Instrumentation.ActivityResult(0, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            header("source").performClick()
            compose.onNodeWithTag("tutorial_scroll").performScrollToNode(hasTestTag("tutorial_original"))
            assertNull(opened.get())
            compose.onNodeWithTag("tutorial_original").performClick()
            assertEquals(ProjectInfo.TUTORIAL_SOURCE, opened.get().dataString)
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
