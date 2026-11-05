package top.zainzh.rearscreen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RearDensityBulkUiTest {
    @get:Rule val compose = createAndroidComposeRule<DensityCompatibilityActivity>()

    @Test fun bulkSwitchIgnoresSearchAndPreservesCustomDpi() {
        val context = compose.activity
        val preferences = RearDensityPreferences.preferences(context)
        val original = preferences.all.toMap()

        try {
            val packages = context.packageManager
                .queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }
                .filter { it != context.packageName }
                .distinct()
            assertTrue("test requires multiple castable apps", packages.size > 1)

            RearDensityPreferences.setAllEnabled(context, packages, true)
            RearDensityPreferences.setLayoutDensity(context, "com.android.settings", 240)
            compose.waitForIdle()

            val search = compose.onNodeWithTag("density_app_search")
            search.performClick()
            search.performTextClearance()
            search.performTextInput("settings")
            compose.waitForIdle()
            compose.runOnIdle {
                val input = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                input?.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
            }
            Thread.sleep(250)
            compose.waitForIdle()
            compose.onNodeWithTag("density_app_com.android.settings").assertIsDisplayed()
            compose.onNodeWithTag("density_bulk_controls").assertIsDisplayed()
            compose.onAllNodesWithTag("density_bulk_card").assertCountEquals(0)
            compose.onNodeWithTag("density_bulk_enable").assertIsDisplayed()
            compose.onNodeWithTag("density_bulk_disable").assertIsDisplayed()
            compose.runOnIdle {
                val input = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                input?.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
            }
            Thread.sleep(250)
            captureScreenshot("density-inline-normal-zh.png")
            compose.onNodeWithTag("density_bulk_enable").assertIsDisplayed().performClick()
            waitUntil { packages.all { RearDensityPreferences.enabled(context, it) } }
            assertEquals(240, RearDensityPreferences.layoutDensity(context, "com.android.settings"))

            search.performTextClearance()
            search.performTextInput("settings")
            compose.waitForIdle()
            compose.onNodeWithTag("density_bulk_disable").assertIsDisplayed().performClick()
            waitUntil { packages.none { RearDensityPreferences.enabled(context, it) } }
            assertFalse(RearDensityPreferences.enabled(context, "com.android.settings"))
            assertEquals("bulk toggles preserve saved per-app DPI", 240,
                RearDensityPreferences.layoutDensity(context, "com.android.settings"))
        } finally {
            val editor = preferences.edit().clear()
            original.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("bulk preference update did not complete", condition())
    }

    private fun captureScreenshot(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("screencap -p /sdcard/Download/$name"),
        ).use { it.readBytes() }
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("ls -l /sdcard/Download/$name"),
        ).use { it.readBytes().decodeToString() }
        assertTrue("screenshot must be exported for review", output.contains(name))
    }
}
