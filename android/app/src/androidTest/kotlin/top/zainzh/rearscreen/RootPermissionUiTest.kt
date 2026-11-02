package top.zainzh.rearscreen

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** UI coverage deliberately never requests su, so this also runs on a non-root emulator. */
class RootPermissionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @get:Rule(order = 0) val selectRoot = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                context.getSharedPreferences(PrivilegeMode.PREFERENCES, 0).edit()
                    .putString("mode", "ROOT").putBoolean("root_approved", false).commit()
                val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                    if (stage == androidx.test.runner.lifecycle.Stage.CREATED && InstrumentationRegistry.getArguments().getString("orientation") == "landscape")
                        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
                try { base.evaluate() } finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun source(): SemanticsNodeInteraction = visible(compose.onNodeWithTag("privilege_source"))
    private fun visible(target: SemanticsNodeInteraction): SemanticsNodeInteraction {
        val node = target.performScrollTo()
        val bottom = node.fetchSemanticsNode().boundsInRoot.bottom
        val navTop = compose.onNodeWithContentDescription(NativeStrings.get(context, "screen_title")).fetchSemanticsNode().boundsInRoot.top
        if (bottom > navTop - 16f) compose.onNodeWithTag("home_list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, bottom - navTop + 32f) }
        return node
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.FileOutputStream(java.io.File(context.filesDir, "root-ui-$name.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun sourceChoicePersistsAndDoesNotRequestRoot() {
        compose.onNodeWithTag("privilege_source").assertTextEquals(context.getString(R.string.privilege_source_value, "Root"))
        compose.onNodeWithText(context.getString(R.string.privilege_root_waiting)).assertExists()
        source().performClick()
        compose.onNodeWithText("Shizuku", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { PrivilegeMode.read(context) == PrivilegeMode.SHIZUKU }
        compose.onNodeWithTag("privilege_source").assertTextEquals(context.getString(R.string.privilege_source_value, "Shizuku"))
        capture("shizuku-home")
        source().performClick()
        compose.onNodeWithText("Root", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { PrivilegeMode.read(context) == PrivilegeMode.ROOT }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("privilege_source").assertTextEquals(context.getString(R.string.privilege_source_value, "Root"))
        compose.onNodeWithText(context.getString(R.string.privilege_root_waiting)).assertExists()
        visible(compose.onNodeWithText(context.getString(R.string.privilege_root_connect))).assertIsDisplayed()
        val button = visible(compose.onNodeWithText(NativeStrings.get(context, "connection_details")))
        button.assertIsDisplayed()
        val bottom = button.fetchSemanticsNode().boundsInRoot.bottom
        val navTop = compose.onNodeWithContentDescription(NativeStrings.get(context, "screen_title")).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Connection action is obscured by navigation", bottom <= navTop)
        capture("root-home")
        assertFalse(context.getSharedPreferences(PrivilegeMode.PREFERENCES, 0).getBoolean("root_approved", false))
    }

    @Test fun rootPermissionPageExplainsTheSelectedSource() {
        compose.runOnIdle { compose.activity.startActivity(Intent(compose.activity, PermissionChecksActivity::class.java)) }
        compose.onNodeWithText(context.getString(R.string.privilege_permission_help)).assertExists()
        compose.onNodeWithTag("permission_action_SHIZUKU").assertTextEquals(context.getString(R.string.privilege_root_connect))
        compose.onNodeWithText(context.getString(R.string.ui_permission_open_shizuku)).assertDoesNotExist()
        capture("root-permissions")
        compose.onNodeWithTag("permissions_back").performClick()
    }
}
