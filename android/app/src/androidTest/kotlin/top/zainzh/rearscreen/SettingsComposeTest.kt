package top.zainzh.rearscreen

import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SettingsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun s(key: String) = NativeStrings.get(compose.activity, key)
    private fun tab(key: String) { compose.onNodeWithContentDescription(s(key)).performClick() }
    private fun row(key: String, list: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(hasText(s(key)))
        val node = compose.onNodeWithText(s(key))
        val bottom = node.fetchSemanticsNode().boundsInRoot.bottom
        val top = compose.onNodeWithContentDescription(s("screen_title")).fetchSemanticsNode().boundsInRoot.top
        if (bottom > top - 16f) compose.onNodeWithTag(list).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, bottom - top + 32f) }
        return node
    }
    @Test fun aboutKeepsCreditsAndCompleteSelectableOfflineLicense() {
        tab("about")
        compose.onNodeWithText("ReMRSS").assertExists()
        compose.onNodeWithText(s("version_label").replace("{0}", BuildConfig.VERSION_NAME)).assertExists()
        row("author_anti", "about_list").assertExists()
        row("diagnostics", "about_list").performClick()
        compose.onNodeWithText(s("license")).performClick()
        compose.onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertExists()
        compose.onNodeWithText("https://www.gnu.org/licenses/why-not-lgpl.html", substring = true).assertExists()
        compose.onNodeWithText("MIUIX-LICENSE.txt", substring = true).assertExists()
    }
    @Test fun aboutLinksAndAutomationKeepTheirDestinations() {
        val opened = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened.set(intent); return Instrumentation.ActivityResult(0, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            tab("about")
            listOf("project_repository" to ProjectInfo.REPOSITORY, "issue_feedback" to ProjectInfo.urlForAction("openFeedback"),
                "author_anti" to ProjectInfo.urlForAction("openOriginalProject")).forEach { (key, target) ->
                row(key, "about_list").performClick(); assertEquals(target, opened.get().dataString)
            }
            row("uri_title", "about_list").performClick()
            compose.onNodeWithText(BuildConfig.URI_SCHEME + "://switch?current=1", substring = true).assertExists()
            compose.onNodeWithText(s("uri_copy")).assertExists()
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun proxyChoiceSurvivesPageAndActivityRecreation() {
        val prefs = compose.activity.getSharedPreferences("mrss_settings", 0)
        val old = UpdateNetwork.proxyEnabled(compose.activity)
        try {
            tab("about"); row("update_proxy", "about_list").performClick()
            assertEquals(!old, UpdateNetwork.proxyEnabled(compose.activity))
            tab("notifications_title"); tab("about")
            compose.activityRule.scenario.recreate()
            row("update_proxy", "about_list").let { if (old) it.assertIsOff() else it.assertIsOn() }
        } finally { prefs.edit().putBoolean("update_proxy_enabled", old).commit() }
    }
    @Test fun privacyTogglePreservesServicePreferencesOnRecreation() {
        val prefs = NativePreferences(compose.activity)
        val old = prefs.getBoolean("notification_privacy_hide_title")
        try {
            tab("notifications_title"); row("hide_notification_title", "notifications_list").performClick()
            assertEquals(!old, prefs.getBoolean("notification_privacy_hide_title"))
            assertEquals(!old, compose.activity.getSharedPreferences("FlutterSharedPreferences", 0).getBoolean("flutter.notification_privacy_hide_title", old))
            compose.activityRule.scenario.recreate()
            row("hide_notification_title", "notifications_list").let { if (old) it.assertIsOff() else it.assertIsOn() }
        } finally { prefs.putBoolean("notification_privacy_hide_title", old) }
    }
    @Test fun appSearchSystemFilterAndBulkActionsOnlyAffectVisibleApps() {
        val prefs = compose.activity.getSharedPreferences("mrss_settings", 0)
        val old = prefs.getStringSet("notification_selected_apps", emptySet())!!.toSet()
        try {
            prefs.edit().putStringSet("notification_selected_apps", setOf("outside.app")).commit()
            tab("notifications_title"); row("notification_apps", "notifications_list").performClick()
            compose.onNodeWithText(s("system_apps")).performClick()
            compose.onNodeWithTag("app_search").performTextReplacement("com.android.settings")
            compose.waitUntil(5000) { compose.onAllNodes(hasText("com.android.settings") and isToggleable()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(s("select_all")).performClick()
            val selected = prefs.getStringSet("notification_selected_apps", emptySet())!!
            assertTrue(selected.containsAll(setOf("outside.app", "com.android.settings")))
            assertTrue(selected.all { it == "outside.app" || it.contains("com.android.settings") })
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("app_search").assertTextContains("com.android.settings")
            compose.onNodeWithText(s("system_apps")).assertIsOn()
            compose.onNodeWithText(s("deselect_all")).performClick()
            assertEquals(setOf("outside.app"), prefs.getStringSet("notification_selected_apps", emptySet()))
        } finally { prefs.edit().putStringSet("notification_selected_apps", old).commit() }
    }
    @Test fun notificationDurationDraftSurvivesRecreationAndInvalidInputStaysOpen() {
        tab("notifications_title"); row("auto_destroy_time", "notifications_list").performClick()
        compose.onNodeWithTag("number_input").performTextReplacement("0")
        compose.onNodeWithText(s("apply")).performClick()
        compose.onNodeWithTag("number_input").assertExists().performTextReplacement("137")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("number_input").assertTextContains("137")
    }
    @Test fun dpiDraftSurvivesRecreation() {
        row("dpi_caption", "home_list").performClick()
        compose.onNodeWithTag("number_input").performTextReplacement("420")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("number_input").assertTextContains("420")
    }
    private fun checker(fetch: ReleaseUpdateChecker.Fetcher): SettingsController {
        val result = AtomicReference<SettingsController>()
        compose.runOnIdle {
            val field = MainActivity::class.java.getDeclaredField("nativeUi").apply { isAccessible = true }
            (field.get(compose.activity) as SettingsController).destroy()
            val state = android.os.Bundle().apply { putInt("native_page", 2) }
            val ui = SettingsController(compose.activity, state, ReleaseUpdateChecker(fetch))
            field.set(compose.activity, ui); compose.activity.setContentView(ui.view); ui.onResume(); result.set(ui)
        }
        return result.get()
    }
    @Test fun updateCheckPreventsDuplicateRequestsAndHasSingleTargetVersion() {
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val ui = checker { attempts.incrementAndGet(); release.await(); ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.release("v3.4.0")) }
        try {
            compose.runOnIdle { ui.checkForUpdates(); ui.checkForUpdates() }
            row("check_updates", "about_list").assertIsNotEnabled()
            compose.runOnIdle { release.countDown() }
            compose.waitUntil(5000) { compose.onAllNodesWithText(compose.activity.getString(R.string.ui_download_update)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, attempts.get())
            compose.onNode(hasText("v3.4.0", substring = true) and hasAnyAncestor(isDialog())).assertExists()
            compose.onNodeWithText(s("view_release")).assertExists()
        } finally { release.countDown() }
    }
    private fun retryCheck(first: ReleaseUpdateChecker.Response, expected: String) {
        val attempts = AtomicInteger()
        val ui = checker { if (attempts.incrementAndGet() == 1) first else ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.manifest(BuildConfig.SOURCE_TAG, BuildConfig.VERSION_CODE)) }
        compose.runOnIdle { ui.checkForUpdates() }
        compose.waitUntil(5000) { compose.onAllNodesWithText(s("retry")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(s(expected)) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText(s("retry")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(s("update_current")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, attempts.get())
    }
    @Test fun networkFailureOffersRetryToCurrentVersion() = retryCheck(ReleaseUpdateChecker.Response(500, ""), "update_network_error")
    @Test fun missingManifestNeverClaimsCurrentVersionAndCanRetry() = retryCheck(ReleaseUpdateChecker.Response(404, ""), "update_none")
    @Test fun aboutPermissionMenuOpensSeparatePageAndReturnsToSamePosition() {
        val monitor = instrumentation.addMonitor(PermissionChecksActivity::class.java.name, null, false)
        var checks: PermissionChecksActivity? = null
        try {
            tab("about")
            compose.onNodeWithTag("about_list").performScrollToNode(hasTestTag("permission_checks_menu"))
            compose.onNodeWithTag("permission_checks_menu").performClick()
            checks = monitor.waitForActivityWithTimeout(5000) as? PermissionChecksActivity
            assertNotNull(checks)
            compose.onNodeWithTag("permissions_back").performClick()
            compose.onNodeWithTag("permission_checks_menu").assertIsDisplayed()
            tab("about").let { compose.onNodeWithContentDescription(s("about")).assertIsSelected() }
        } finally { instrumentation.removeMonitor(monitor); instrumentation.runOnMainSync { checks?.finish() } }
    }
}
