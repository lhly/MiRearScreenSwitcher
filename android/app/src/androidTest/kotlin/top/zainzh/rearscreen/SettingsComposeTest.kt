package top.zainzh.rearscreen

import java.io.FileOutputStream

import androidx.lifecycle.ViewModelProvider

import android.graphics.Bitmap

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
    private fun s(resource: Int) = compose.activity.getString(resource)
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
        compose.waitUntil(15_000) { compose.onAllNodesWithText(s("license")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(s("license")).performScrollTo().performClick()
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
            compose.runOnIdle {
                compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
            }
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
        assertEquals("0", compose.onNodeWithTag("number_input").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithText(s("apply")).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("number_input").assertExists().performTextReplacement("137")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("number_input").assertTextContains("137")
    }
    @Test fun dpiDraftSurvivesRecreation() {
        val mode = RearCastMode.preferences(compose.activity)
        val saved = mode.all[RearCastMode.VIRTUAL]
        try {
            compose.runOnIdle { RearCastMode.setVirtual(compose.activity, false) }
            row("dpi_caption", "home_list").performClick()
            compose.onNodeWithTag("number_input").performTextReplacement("420")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("number_input").assertTextContains("420")
        } finally {
            compose.runOnIdle {
                val edit = mode.edit().remove(RearCastMode.VIRTUAL)
                if (saved is Boolean) edit.putBoolean(RearCastMode.VIRTUAL, saved)
                edit.apply()
            }
        }
    }
    private fun checker(automatic: Boolean = false, fetch: ReleaseUpdateChecker.Fetcher): SettingsController {
        val result = AtomicReference<SettingsController>()
        compose.runOnIdle {
            val field = MainActivity::class.java.getDeclaredField("nativeUi").apply { isAccessible = true }
            (field.get(compose.activity) as SettingsController).destroy()
            val state = android.os.Bundle().apply { putInt("native_page", 2) }
            val ui = SettingsController(compose.activity, state, ReleaseUpdateChecker(fetch), automatic)
            field.set(compose.activity, ui); compose.activity.setContentView(ui.view); ui.onResume(); result.set(ui)
        }
        return result.get()
    }
    @Test fun updateCheckPreventsDuplicateRequestsAndHasSingleTargetVersion() {
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val target = "v${BuildConfig.VERSION_NAME.substringBefore('.').toInt() + 1}.0.0"
        val ui = checker { attempts.incrementAndGet(); release.await(); ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.release(target)) }
        try {
            compose.runOnIdle { ui.checkForUpdates(); ui.checkForUpdates() }
            row("check_updates", "about_list").assertIsNotEnabled()
            compose.runOnIdle { release.countDown() }
            compose.waitUntil(5000) { compose.onAllNodesWithText(compose.activity.getString(R.string.ui_download_update)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, attempts.get())
            compose.onNode(hasText(target, substring = true) and hasAnyAncestor(isDialog())).assertExists()
            compose.onNodeWithText(s(R.string.ui_view_release)).assertExists()
        } finally { release.countDown() }
    }
    @Test fun automaticCheckRunsOnceAcrossResumeAndShowsGroupedReleaseNotes() {
        val attempts = AtomicInteger()
        val target = "v${BuildConfig.VERSION_NAME.substringBefore('.').toInt() + 1}.0.0"
        val args = InstrumentationRegistry.getArguments()
        val longNotes = args.getString("longNotes") == "true"
        val added = if (longNotes) "新增背屏提醒，" + "详细功能说明。".repeat(28) else "新增背屏提醒"
        val improved = if (longNotes) "优化更新流程，" + "兼容性与稳定性优化。".repeat(20) else "优化更新流程"
        val fixed = if (longNotes) "修复状态恢复，" + "覆盖异常退出和页面重建。".repeat(16) else "修复状态恢复"
        val body = ReleaseUpdateCheckerTest.withNotes(ReleaseUpdateCheckerTest.release(target),
            "{\"added\":[\"$added\"],\"improved\":[\"$improved\"],\"fixed\":[\"$fixed\"]}")
        val ui = checker(automatic = true) { attempts.incrementAndGet(); ReleaseUpdateChecker.Response(200, body) }
        try {
            compose.runOnIdle { ui.onResume(); ui.onResume() }
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("新增背屏提醒", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, attempts.get())
            compose.onNodeWithText(s(R.string.ui_release_notes_added)).assertExists()
            compose.onNodeWithText(s(R.string.ui_release_notes_improved)).assertExists()
            compose.onNodeWithText(s(R.string.ui_release_notes_fixed)).assertExists()
            compose.onNodeWithText("修复状态恢复", substring = true).assertExists()
            if (longNotes) compose.onNodeWithText(s(R.string.ui_download_update)).performScrollTo()
            compose.waitForIdle()
            Thread.sleep(600)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            val filename = args.getString("screenshotName", "update-notes-zh-normal.png")
            val file = java.io.File(instrumentation.targetContext.cacheDir, filename)
            FileOutputStream(file).use { screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { compose.runOnIdle { ui.destroy() } }
    }
    @Test fun automaticCheckIsRetainedAcrossActivityRecreation() {
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val target = "v${BuildConfig.VERSION_NAME.substringBefore('.').toInt() + 1}.0.0"
        val ui = checker(automatic = true) {
            attempts.incrementAndGet()
            release.await()
            ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.release(target))
        }
        try {
            compose.waitUntil(5000) { attempts.get() == 1 }
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.runOnIdle { (compose.activity as MainActivity).checkForUpdates() }
            release.countDown()
            compose.waitUntil(5000) {
                compose.onAllNodesWithText(compose.activity.getString(R.string.ui_download_update)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("recreation/manual takeover must reuse the automatic request", 1, attempts.get())
            compose.onNode(hasText(target, substring = true) and hasAnyAncestor(isDialog())).assertExists()
        } finally { release.countDown() }
    }
    @Test fun automaticNetworkFailureStaysSilent() {
        val attempts = AtomicInteger()
        val ui = checker(automatic = true) { attempts.incrementAndGet(); ReleaseUpdateChecker.Response(503, "") }
        try {
            compose.waitUntil(5000) { attempts.get() == 1 && !ui.isCheckingUpdates() }
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            assertEquals(1, attempts.get())
        } finally { compose.runOnIdle { ui.destroy() } }
    }
    @Test fun lateAutomaticOfferDoesNotReplaceExistingDialogResult() {
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val major = BuildConfig.VERSION_NAME.substringBefore('.').toInt()
        val existingTag = "v${major + 1}.1.0"
        val incomingTag = "v${major + 2}.0.0"
        val ui = checker(automatic = true) {
            attempts.incrementAndGet()
            release.await()
            ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.release(incomingTag))
        }
        val session = ViewModelProvider(compose.activity, ViewModelProvider.NewInstanceFactory())
            .get(UpdateDialogController.Session::class.java)
        try {
            compose.waitUntil(5000) { attempts.get() == 1 }
            val existing = ReleaseUpdateChecker.evaluate(
                ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.release(existingTag)),
                BuildConfig.SOURCE_TAG, BuildConfig.VERSION_CODE)
            compose.runOnIdle {
                session.result = existing
                session.visible = true
                session.dialogAutomatic = false
            }
            release.countDown()
            compose.waitUntil(5000) { !ui.isCheckingUpdates() }
            assertSame("an automatic completion must not overwrite the active dialog's bound release",
                existing, session.result)
            assertNull(session.pendingAutomaticResult)
            assertTrue(session.automaticOfferConsumed)
        } finally { release.countDown(); compose.runOnIdle { ui.destroy() } }
    }
    @Test fun processRestoreRetriesInterruptedCheckAndRestoresPendingNotesWithoutReplacingLegacyDialog() {
        val session = ViewModelProvider(compose.activity, ViewModelProvider.NewInstanceFactory())
            .get(UpdateDialogController.Session::class.java)
        val attempts = AtomicInteger()
        val target = "v${BuildConfig.VERSION_NAME.substringBefore('.').toInt() + 1}.0.0"
        val manifest = ReleaseUpdateCheckerTest.withNotes(ReleaseUpdateCheckerTest.release(target),
            "{\"added\":[\"进程恢复后保留说明\"],\"improved\":[],\"fixed\":[]}")
        lateinit var first: UpdateDialogController
        lateinit var restored: UpdateDialogController
        fun resetSession() {
            session.checker?.close()
            session.clearDialog()
            session.checker = null
            session.listener = null
            session.initialized = false
            session.checking = false
            session.automaticCheckClaimed = false
            session.currentCheckAutomatic = false
            session.manualRequested = false
            session.pendingAutomaticOffer = false
            session.pendingAutomaticResult = null
            session.pendingManualResult = null
            session.automaticOfferConsumed = false
        }
        fun controller(saved: android.os.Bundle, fetcher: ReleaseUpdateChecker.Fetcher,
                       result: AtomicReference<UpdateDialogController?>): UpdateDialogController {
            val value = UpdateDialogController(compose.activity, saved, ReleaseUpdateChecker(fetcher),
                true, false, UpdateDialogController.CheckListener { _, _ -> },
                java.util.function.BooleanSupplier { true }, Runnable {})
            result.set(value)
            return value
        }
        val controllerRef = AtomicReference<UpdateDialogController?>()
        try {
            val interrupted = android.os.Bundle().apply {
                putBoolean("update_session_checking", true)
                putBoolean("update_session_auto_claimed", true)
                putBoolean("update_session_current_auto", true)
            }
            compose.runOnIdle {
                resetSession()
                first = controller(interrupted, ReleaseUpdateChecker.Fetcher {
                    attempts.incrementAndGet()
                    ReleaseUpdateChecker.Response(200, manifest)
                }, controllerRef)
                first.onResume(false)
                first.startAutomaticCheck()
            }
            compose.waitUntil(5000) { attempts.get() == 1 && !first.isChecking() }
            assertTrue(session.pendingAutomaticOffer)
            assertEquals("进程恢复后保留说明", session.pendingAutomaticResult?.notes?.getAdded()?.single())

            val snapshot = android.os.Bundle()
            compose.runOnIdle { first.saveState(snapshot); first.destroy(true) }
            compose.runOnIdle {
                resetSession()
                restored = controller(snapshot, ReleaseUpdateChecker.Fetcher {
                    attempts.incrementAndGet()
                    ReleaseUpdateChecker.Response(500, "unexpected repeat")
                }, controllerRef)
                restored.onResume(false)
            }
            assertEquals("a saved pending offer must not issue a second check", 1, attempts.get())
            assertTrue(session.visible)
            assertEquals("进程恢复后保留说明", session.result?.notes?.getAdded()?.single())

            val oldOffer = ReleaseUpdateChecker.evaluate(
                ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.release("v${target.drop(1).split('.')[0]}.1.0")),
                BuildConfig.SOURCE_TAG, BuildConfig.VERSION_CODE)
            val legacyBundle = android.os.Bundle().apply {
                putBoolean("update_dialog_visible", true)
                putString("update_dialog_manifest", oldOffer.toManifestJson())
                // Intentionally omit the newer automatic-check claim field.
            }
            compose.runOnIdle {
                restored.destroy(true)
                resetSession()
                first = controller(legacyBundle, ReleaseUpdateChecker.Fetcher {
                    attempts.incrementAndGet()
                    ReleaseUpdateChecker.Response(200, manifest)
                }, controllerRef)
                first.onResume(false)
                first.startAutomaticCheck()
            }
            assertEquals("a restored visible legacy offer must suppress an automatic check", 1, attempts.get())
            assertTrue(session.visible)
            assertEquals(oldOffer.tag, session.result?.tag)
        } finally {
            compose.runOnIdle {
                first.destroy(false)
                resetSession()
            }
        }
    }
    private fun retryCheck(first: ReleaseUpdateChecker.Response, expected: String) {
        val attempts = AtomicInteger()
        val ui = checker { if (attempts.incrementAndGet() == 1) first else ReleaseUpdateChecker.Response(200, ReleaseUpdateCheckerTest.manifest(BuildConfig.SOURCE_TAG, BuildConfig.VERSION_CODE)) }
        compose.runOnIdle { ui.checkForUpdates() }
        compose.waitUntil(5000) { compose.onAllNodesWithText(s(R.string.ui_retry)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(s(expected)) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText(s(R.string.ui_retry)).performClick()
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
