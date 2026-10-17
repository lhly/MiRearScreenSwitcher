package top.zainzh.rearscreen

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class PermissionComposeTest {
    @get:Rule val compose = createAndroidComposeRule<PermissionChecksActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun everyCheckExplainsUseAndReportsRealState() {
        PermissionChecks.Item.values().forEach { item ->
            val tag = "permission_status_" + item.name
            compose.onNodeWithTag("permissions_scroll").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertTextEquals(compose.activity.getString(PermissionChecks.statusLabel(item, PermissionChecks.read(compose.activity, item))))
            compose.onNodeWithText(compose.activity.getString(item.help)).assertExists()
        }
    }
    private fun saved(): Bundle {
        val bundle = Bundle()
        compose.runOnIdle {
            val field = PermissionChecksActivity::class.java.getDeclaredField("list").apply { isAccessible = true }
            val state = field.get(compose.activity) as androidx.compose.foundation.lazy.LazyListState
            bundle.putInt("permissions_scroll_index", state.firstVisibleItemIndex)
            bundle.putInt("permissions_scroll_offset", state.firstVisibleItemScrollOffset)
        }
        return bundle
    }
    @Test fun recreationKeepsIndependentReadingPosition() {
        compose.onNodeWithTag("permissions_scroll").performScrollToIndex(5)
        val before = saved()
        assertTrue(before.getInt("permissions_scroll_index") > 0)
        compose.activityRule.scenario.recreate()
        val after = saved()
        assertEquals(before.getInt("permissions_scroll_index"), after.getInt("permissions_scroll_index"))
        assertEquals(before.getInt("permissions_scroll_offset"), after.getInt("permissions_scroll_offset"))
    }
    @Test fun authorizationActionOpensCorrectVendorDestination() {
        val opened = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                opened.set(intent); return Instrumentation.ActivityResult(0, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithTag("permissions_scroll").performScrollToNode(hasTestTag("permission_action_AUTOSTART"))
            compose.onNodeWithTag("permission_action_AUTOSTART").performClick()
            assertEquals("com.miui.securitycenter", opened.get().component!!.packageName)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }
    @Test fun grantAndRevokeRefreshOnResumeWithoutResettingList() {
        val pkg = compose.activity.packageName
        val original = Regex("SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default|foreground)").find(shell("appops get $pkg SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
        try {
            compose.onNodeWithTag("permissions_scroll").performScrollToNode(hasTestTag("permission_status_OVERLAY"))
            for (mode in listOf("allow", "ignore")) {
                val before = saved()
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                shell("appops set $pkg SYSTEM_ALERT_WINDOW $mode")
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                val expected = compose.activity.getString(if (mode == "allow") R.string.ui_permission_granted else R.string.ui_permission_denied)
                compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("permission_status_OVERLAY") and hasText(expected)).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(before.getInt("permissions_scroll_index"), saved().getInt("permissions_scroll_index"))
                if (mode == "allow") compose.onNodeWithTag("permission_action_OVERLAY").assertDoesNotExist()
                else compose.onNodeWithTag("permission_action_OVERLAY").assertExists()
            }
        } finally { shell("appops set $pkg SYSTEM_ALERT_WINDOW $original") }
    }
}
