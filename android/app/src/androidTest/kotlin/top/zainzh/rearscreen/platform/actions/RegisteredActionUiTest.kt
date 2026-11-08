package top.zainzh.rearscreen.platform.actions

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.MainActivity
import top.zainzh.rearscreen.R
import top.zainzh.rearscreen.platform.SessionOwner
import top.zainzh.rearscreen.platform.SessionPrincipal
import java.io.File

@RunWith(AndroidJUnit4::class)
class RegisteredActionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativePageRendersAndCancellingLauncherPickerDoesNotPersist() {
        val context = compose.activity
        val owner = owner("native-cancel")
        val registry = PlatformRegisteredActionRegistry.get(context)
        registry.revokeAllForPublisher(owner)
        assertEquals(owner, RegisteredActionsActivity.decodeOwner(
            RegisteredActionsActivity.createIntent(context, owner, selectAction = true)
        ))
        openActionPage(owner, context)

        SystemClock.sleep(600)
        capture(context, "registered-actions-before-assert.png")
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        assertTrue("native action page title missing; active accessibility tree=${treeText(root)}", waitForText(context.getString(R.string.registered_actions_title)))
        assertTrue(waitForText(context.getString(R.string.registered_actions_add_app)))
        assertTrue(waitForText(context.getString(R.string.registered_actions_add)))
        capture(context, "registered-actions.png")

        clickText(context.getString(R.string.registered_actions_add_app))
        assertTrue("system launcher picker is not visible", waitForText("Settings") || waitForText("设置"))
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        assertTrue(waitForText(context.getString(R.string.registered_actions_title)))
        assertTrue("cancelled system selection wrote a binding", registry.listForOwner(owner).isEmpty())
    }

    @Test fun registeredLauncherTargetRendersInNativeList() {
        val context = compose.activity
        val owner = owner("native-register-list")
        val registry = PlatformRegisteredActionRegistry.get(context)
        registry.revokeAllForPublisher(owner)
        val selected = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName("com.android.settings", "com.android.settings.Settings"))
        assertTrue(registry.registerLauncherResult(owner, selected) is ActionRegistrationResult.Registered)
        val action = registry.listForOwner(owner).single()
        assertTrue(action.available)
        openActionPage(owner, context)
        assertTrue("persisted target is missing from the action list", waitForText(action.targetLabel))
        assertTrue(waitForText(context.getString(R.string.registered_action_select)))
        assertFalse(
            "empty state remained visible beside a registered action",
            treeText(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
                .contains(context.getString(R.string.registered_actions_empty))
        )
        capture(context, "registered-actions-persisted-list.png")
    }

    @Test fun systemLauncherSelectionPersistsOpaqueRefAndDispatchesOnDefaultDisplay() {
        val context = compose.activity
        val owner = owner("native-register")
        val registry = PlatformRegisteredActionRegistry.get(context)
        registry.revokeAllForPublisher(owner)
        openActionPage(owner, context)

        assertTrue(waitForText(context.getString(R.string.registered_actions_add_app)))
        clickText(context.getString(R.string.registered_actions_add_app))
        assertTrue("system launcher picker is not visible", waitForText("Settings") || waitForText("设置"))
        clickText("Settings", "设置")
        val saveDeadline = SystemClock.uptimeMillis() + 6_000
        var registered = registry.listForOwner(owner)
        while (registered.isEmpty() && SystemClock.uptimeMillis() < saveDeadline) {
            SystemClock.sleep(100)
            registered = registry.listForOwner(owner)
        }
        assertTrue(
            "selected launcher activity was not saved; active tree=${treeText(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)}",
            registered.isNotEmpty()
        )

        val action = registered.single()
        assertTrue(action.ref.id.matches(Regex("[a-f0-9]{48}")))
        assertEquals(1, action.ref.version)
        assertTrue(action.available)
        assertTrue("registered target is missing from the native list", waitForText(action.targetLabel))
        assertTrue(waitForText(context.getString(R.string.registered_action_select)))
        assertFalse(
            "empty state remained visible after registration",
            treeText(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
                .contains(context.getString(R.string.registered_actions_empty))
        )
        capture(context, "registered-actions-after-launcher-selection.png")
        val resolved = registry.resolve(owner, action.ref) as ActionResolveResult.Resolved
        assertEquals(ActionDispatchResult.Accepted, registry.launch(resolved.action))
    }

    @Test fun settingsShortcutProviderReturnsValidatedRegistrationOrExplicitRejection() {
        val context = compose.activity
        val owner = owner("native-shortcut-provider")
        val registry = PlatformRegisteredActionRegistry.get(context)
        registry.revokeAllForPublisher(owner)
        val query = queryShortcutProviders(context.packageManager)
        val candidateSummary = query?.providers.orEmpty().joinToString { "${it.label}=${it.component.flattenToString()}" }
        val settingsCandidate = query?.providers?.firstOrNull { it.packageName == "com.android.settings" }
        assertNotNull(
            "Settings did not survive ACTION_CREATE_SHORTCUT MATCH_DEFAULT_ONLY filtering; " +
                "query=${query?.queriedCount}, eligible=${query?.eligibleCount}, candidates=[$candidateSummary]",
            settingsCandidate
        )
        openActionPage(owner, context)
        val pageReady = waitForText(context.getString(R.string.registered_actions_add))
        val pageRoot = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        val pageTree = treeText(pageRoot)
        var resumedActivities = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumedActivities = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).joinToString { it.javaClass.name }
        }
        android.util.Log.i(
            "RegisteredActionProviderProbe",
            "afterOpen pageReady=$pageReady, window=${pageRoot?.packageName}, resumed=[$resumedActivities], tree=$pageTree"
        )
        capture(context, "registered-actions-provider-page-before-assert.png")
        assertTrue(
            "RegisteredActionsActivity add-shortcut control missing; pageReady=$pageReady, " +
                "window=${pageRoot?.packageName}, resumed=[$resumedActivities], tree=$pageTree",
            pageReady
        )
        clickText(context.getString(R.string.registered_actions_add))
        val providerDeadline = SystemClock.uptimeMillis() + 6_000
        var activeRoot = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        var providerTree = treeText(activeRoot)
        val expectedProviderLabel = requireNotNull(settingsCandidate).label
        while (!providerTree.contains(expectedProviderLabel, ignoreCase = true) &&
            !providerTree.contains(context.getString(R.string.registered_actions_no_provider)) &&
            !providerTree.contains(context.getString(R.string.registered_actions_provider_limit)) &&
            SystemClock.uptimeMillis() < providerDeadline) {
            swipeUp(context)
            SystemClock.sleep(100)
            activeRoot = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            providerTree = treeText(activeRoot)
        }
        val settingsVisible = providerTree.contains(expectedProviderLabel, ignoreCase = true)
        val providerWindowPackage = activeRoot?.packageName?.toString()
        val uiProbe = RegisteredActionsActivity.lastProviderUiProbe
        android.util.Log.i(
            "RegisteredActionProviderProbe",
            "query=${query?.queriedCount}, eligible=${query?.eligibleCount}, candidates=[$candidateSummary], " +
                "uiProbe=$uiProbe, window=$providerWindowPackage, tree=$providerTree"
        )
        capture(context, "registered-actions-provider-list-before-assert.png")
        assertTrue(
            "host Miuix provider list did not show Settings; query=${query?.queriedCount}, eligible=${query?.eligibleCount}, " +
                "candidates=[$candidateSummary], uiProbe=$uiProbe, feedback/tree=$providerTree, activeWindowPackage=$providerWindowPackage",
            settingsVisible
        )
        capture(context, "registered-actions-provider-list.png")
        clickText(requireNotNull(settingsCandidate).label)

        val providerChoiceVisible = waitForText("互联网") || waitForText("Internet")
        val settingsProviderRoot = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        val settingsProviderTree = treeText(settingsProviderRoot)
        capture(context, "registered-actions-settings-provider-choice.png")
        assertTrue(
            "selected Settings provider did not offer a concrete shortcut choice; " +
                "window=${settingsProviderRoot?.packageName}, tree=$settingsProviderTree",
            providerChoiceVisible
        )
        clickText("互联网", "Internet")

        val resultDeadline = SystemClock.uptimeMillis() + 8_000
        var records = registry.listForOwner(owner)
        var resultRoot = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        var resultTree = treeText(resultRoot)
        val invalidText = context.getString(R.string.registered_action_invalid)
        val cancelledText = context.getString(R.string.registered_action_cancelled)
        val savedText = context.getString(R.string.registered_action_saved)
        var providerProbe = RegisteredActionsActivity.lastProviderUiProbe
        while (records.isEmpty() &&
            !resultTree.contains(invalidText) &&
            !resultTree.contains(savedText) &&
            !resultTree.contains(cancelledText) &&
            SystemClock.uptimeMillis() < resultDeadline) {
            SystemClock.sleep(100)
            records = registry.listForOwner(owner)
            resultRoot = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            resultTree = treeText(resultRoot)
            providerProbe = RegisteredActionsActivity.lastProviderUiProbe
        }
        val resultWindowPackage = resultRoot?.packageName?.toString()
        android.util.Log.i(
            "RegisteredActionProviderProbe",
            "resultCount=${records.size}, providerProbe=$providerProbe, window=$resultWindowPackage, tree=$resultTree"
        )
        capture(context, "registered-actions-settings-provider-result.png")
        assertTrue(
            "Settings provider yielded neither one validated ref nor explicit invalid/cancel feedback; " +
                "providerProbe=$providerProbe, window=$resultWindowPackage, tree=$resultTree",
            records.size == 1 || resultTree.contains(invalidText) || resultTree.contains(cancelledText)
        )
        if (records.isNotEmpty()) {
            assertEquals("provider selection must yield one ref, not a vacuous/ambiguous result", 1, records.size)
            val resolved = registry.resolve(owner, records.single().ref)
            assertTrue("saved Settings result did not resolve: $resolved", resolved is ActionResolveResult.Resolved)
        } else {
            assertTrue("explicit rejection/cancel must not create a binding", registry.listForOwner(owner).isEmpty())
        }
    }

    private fun openActionPage(owner: SessionOwner, context: Context) {
        compose.activity.runOnUiThread {
            compose.activity.startActivity(RegisteredActionsActivity.createIntent(context, owner, selectAction = true))
        }
    }

    private fun waitForText(expected: String, timeoutMs: Long = 6_000): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (containsText(automation.rootInActiveWindow, expected)) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun containsText(node: AccessibilityNodeInfo?, expected: String): Boolean {
        if (node == null) return false
        if (node.text?.toString()?.contains(expected, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(expected, ignoreCase = true) == true) return true
        return (0 until node.childCount).any { containsText(node.getChild(it), expected) }
    }

    private fun clickText(vararg values: String) {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: throw AssertionError("no active accessibility window")
        val node = values.firstNotNullOfOrNull { findTextNode(root, it) }
            ?: throw AssertionError("accessibility text not found: ${values.joinToString()}")
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        checkNotNull(clickable) { "accessibility control is not clickable: ${values.joinToString()}" }
        assertTrue("control click was rejected: ${values.joinToString()}", clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun findTextNode(node: AccessibilityNodeInfo?, expected: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.contains(expected, ignoreCase = true) == true) return node
        for (index in 0 until node.childCount) findTextNode(node.getChild(index), expected)?.let { return it }
        return null
    }

    private fun swipeUp(context: Context) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * .82f
        val endY = metrics.heightPixels * .35f
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, eventTime: Long, y: Float) {
            val event = android.view.MotionEvent.obtain(start, eventTime, action, x, y, 0)
            automation.injectInputEvent(event, true)
            event.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, start, startY)
        val steps = 8
        for (step in 1..steps) {
            val fraction = step.toFloat() / steps
            val y = startY + (endY - startY) * fraction
            val eventTime = start + (450L * fraction).toLong()
            send(android.view.MotionEvent.ACTION_MOVE, eventTime, y)
        }
        val end = start + 500L
        send(android.view.MotionEvent.ACTION_UP, end, endY)
    }

    private fun treeText(node: AccessibilityNodeInfo?): String {
        if (node == null) return "<no active window>"
        return buildString {
            fun appendTree(current: AccessibilityNodeInfo?) {
                if (current == null) return
                current.text?.toString()?.takeIf { it.isNotBlank() }?.let { append(it).append(" | ") }
                for (index in 0 until current.childCount) appendTree(current.getChild(index))
            }
            appendTree(node)
        }.take(2000)
    }

    private fun capture(context: Context, name: String) {
        val bitmap: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val directory = context.getExternalFilesDir(null) ?: return
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun owner(session: String) = SessionOwner(
        10123,
        "top.zainzh.rearscreen",
        "host-cert",
        SessionPrincipal.SignedMod("author-fingerprint", session, session)
    )
}
