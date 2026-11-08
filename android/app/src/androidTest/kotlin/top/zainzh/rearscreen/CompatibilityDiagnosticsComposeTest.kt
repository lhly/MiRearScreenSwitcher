package top.zainzh.rearscreen

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompatibilityDiagnosticsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun s(key: String) = NativeStrings.get(compose.activity, key)
    private fun openReport() {
        compose.onNodeWithContentDescription(s("about")).performClick()
        compose.onNodeWithTag("about_list").performScrollToNode(hasText(s("diagnostics")))
        val node = compose.onNodeWithText(s("diagnostics"))
        val bottom = node.fetchSemanticsNode().boundsInRoot.bottom
        val navigationTop = compose.onNodeWithContentDescription(s("screen_title")).fetchSemanticsNode().boundsInRoot.top
        if (bottom > navigationTop - 16f) compose.onNodeWithTag("about_list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, bottom - navigationTop + 32f) }
        node.performClick()
        val copy = compose.activity.getString(R.string.compat_copy)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(copy).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun reportSurvivesRecreationAndCanBeCopiedWithoutShizuku() {
        openReport()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(compose.activity.cacheDir, "compatibility-dialog.png").outputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
            bitmap.recycle()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.compat_copy)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val report = JSONObject(clipboard.primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString())
            assertEquals(BuildConfig.APPLICATION_ID, report.getString("package"))
            assertTrue(report.getInt("sdk") >= 36)
            assertEquals("not_queried: blocked private API on Android 16+", report.getString("moveRootTaskTransactionError"))
            assertTrue(report.has("virtualCasting"))
            assertFalse(report.has("notificationContent"))
        }
    }
    @Test fun reportCanBeSharedAsAReadableJsonFile() {
        openReport()
        compose.onNodeWithText(compose.activity.getString(R.string.compat_share)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { java.io.File(compose.activity.cacheDir, "diagnostics/ReMRSS-compatibility.json").exists() }
        val file = java.io.File(compose.activity.cacheDir, "diagnostics/ReMRSS-compatibility.json")
        assertEquals(BuildConfig.APPLICATION_ID, JSONObject(file.readText()).getString("package"))
        val uri = androidx.core.content.FileProvider.getUriForFile(compose.activity, "${BuildConfig.APPLICATION_ID}.updates", file)
        compose.activity.contentResolver.openInputStream(uri).use { input ->
            assertNotNull(input)
            assertEquals(BuildConfig.VERSION_NAME, JSONObject(input!!.bufferedReader().readText()).getString("version"))
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
    }
}
