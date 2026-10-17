package top.zainzh.rearscreen

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class TutorialComposeTest {
    @get:Rule val compose = createAndroidComposeRule<TutorialActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun localGuideIncludesAllChaptersAndAttribution() {
        TutorialActivity.sections.forEach { section ->
            compose.onNodeWithTag("tutorial_scroll").performScrollToNode(hasTestTag("tutorial_section_$section"))
            compose.onNodeWithTag("tutorial_section_$section").assertExists()
        }
        compose.onNodeWithTag("tutorial_scroll").performScrollToNode(hasTestTag("tutorial_source"))
        compose.onNodeWithText(compose.activity.getString(R.string.tutorial_source_help)).assertExists()
    }
    private fun saved(): Bundle {
        val bundle = Bundle()
        compose.runOnIdle {
            val field = TutorialActivity::class.java.getDeclaredField("list").apply { isAccessible = true }
            val state = field.get(compose.activity) as androidx.compose.foundation.lazy.LazyListState
            bundle.putInt("tutorial_scroll_index", state.firstVisibleItemIndex)
            bundle.putInt("tutorial_scroll_offset", state.firstVisibleItemScrollOffset)
        }
        return bundle
    }
    @Test fun chapterJumpAndRecreationKeepPositionAndTopActionReturns() {
        compose.onNodeWithTag("tutorial_scroll").performScrollToNode(hasTestTag("tutorial_jump_capture"))
        compose.onNodeWithTag("tutorial_jump_capture").performClick()
        compose.onNodeWithTag("tutorial_section_capture").assertIsDisplayed()
        val before = saved()
        compose.activityRule.scenario.recreate()
        assertEquals(before.getInt("tutorial_scroll_index"), saved().getInt("tutorial_scroll_index"))
        compose.onNodeWithTag("tutorial_scroll").performScrollToNode(hasTestTag("tutorial_top"))
        compose.onNodeWithTag("tutorial_top").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.tutorial_intro)).assertIsDisplayed()
    }
    @Test fun permissionShortcutOpensInternalPageAndBackReturnsToGuide() {
        val monitor = instrumentation.addMonitor(PermissionChecksActivity::class.java.name, null, false)
        var checks: PermissionChecksActivity? = null
        try {
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
            compose.onNodeWithTag("tutorial_scroll").performScrollToNode(hasTestTag("tutorial_original"))
            assertNull(opened.get())
            compose.onNodeWithTag("tutorial_original").performClick()
            assertEquals(ProjectInfo.TUTORIAL_SOURCE, opened.get().dataString)
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
