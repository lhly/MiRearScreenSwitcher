package top.zainzh.rearscreen

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.lazy.LazyListState
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationMotionComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun controller(): SettingsController {
        val field = MainActivity::class.java.getDeclaredField("nativeUi").apply { isAccessible = true }
        return field.get(compose.activity) as SettingsController
    }

    private fun route(): Int {
        val field = SettingsController::class.java.getDeclaredField("page").apply { isAccessible = true }
        return field.getInt(controller())
    }

    private fun composeUi(): MiuixSettingsUi {
        val field = SettingsController::class.java.getDeclaredField("composeUi").apply { isAccessible = true }
        return field.get(controller()) as MiuixSettingsUi
    }

    private fun animationGeneration(): Int {
        val field = MiuixSettingsUi::class.java.getDeclaredField("animationGeneration\$delegate").apply { isAccessible = true }
        return (field.get(composeUi()) as androidx.compose.runtime.MutableIntState).intValue
    }

    private fun pickerList(model: Any): LazyListState {
        val field = AppPickerModel::class.java.getDeclaredField("list").apply { isAccessible = true }
        return field.get(model) as LazyListState
    }

    private fun pickerModel(): AppPickerModel? {
        val field = SettingsController::class.java.getDeclaredField("picker").apply { isAccessible = true }
        return field.get(controller()) as? AppPickerModel
    }

    private fun navigationIndicatorProgress(): Float {
        val navigation = compose.onNodeWithTag("main_navigation").fetchSemanticsNode().boundsInRoot
        val indicator = compose.onNodeWithTag("navigation_indicator").fetchSemanticsNode().boundsInRoot
        val edge = 4f * compose.activity.resources.displayMetrics.density
        val tabWidth = (navigation.width - edge * 2f) / 4f
        return (indicator.left - navigation.left - edge) / tabWidth
    }

    private fun isFocused(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun runDeviceShell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private fun injectPhysicalTouch(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            assertTrue("the real touchscreen event must reach Android's system gesture recognizer",
                InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun injectPhysicalEdgeGesture(points: List<Float>, y: Float): Long {
        val downTime = SystemClock.uptimeMillis()
        injectPhysicalTouch(MotionEvent.ACTION_DOWN, downTime, 1f, y)
        points.forEach { x ->
            SystemClock.sleep(32)
            injectPhysicalTouch(MotionEvent.ACTION_MOVE, downTime, x, y)
        }
        return downTime
    }

    private fun motionDurationScale(): Float {
        val field = MiuixSettingsUi::class.java.getDeclaredField("motionDurationScale\$delegate").apply { isAccessible = true }
        return (field.get(composeUi()) as androidx.compose.runtime.MutableFloatState).floatValue
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun childBounds(): Rect = compose.onNodeWithTag("navigation_motion_child", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun assertSameBounds(message: String, expected: Rect, actual: Rect) {
        assertEquals("$message (left)", expected.left, actual.left, .5f)
        assertEquals("$message (top)", expected.top, actual.top, .5f)
        assertEquals("$message (right)", expected.right, actual.right, .5f)
        assertEquals("$message (bottom)", expected.bottom, actual.bottom, .5f)
    }

    private fun navigationFrameDiagnostic(): String {
        val navBounds = runCatching { compose.onNodeWithTag("main_navigation").fetchSemanticsNode().boundsInRoot }
            .getOrNull()
        val insets = compose.activity.window.decorView.rootWindowInsets
        val progress = runCatching { navigationIndicatorProgress() }.getOrNull()
        return "route=${route()} navBounds=$navBounds indicator=$progress " +
            "rootInsets=${insets?.let { "${it.systemWindowInsetLeft},${it.systemWindowInsetTop},${it.systemWindowInsetRight},${it.systemWindowInsetBottom}" }}"
    }

    private fun uiField(name: String): Any? = MiuixSettingsUi::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(composeUi())

    private fun uiStateValue(name: String): Any? = when (val state = uiField("$name\$delegate")) {
        is androidx.compose.runtime.MutableState<*> -> state.value
        is androidx.compose.runtime.MutableFloatState -> state.floatValue
        is androidx.compose.runtime.MutableIntState -> state.intValue
        else -> state
    }

    private fun motionStateDiagnostic(): String = "${navigationFrameDiagnostic()} scale=${motionDurationScale()} " +
        "backPreview=${uiStateValue("backPreviewProgress")} progressOverride=${uiStateValue("progressOverride")} " +
        "lastDisplayed=${uiStateValue("lastDisplayedProgress")} transition=${uiStateValue("transitionPair")} " +
        "animationRequest=${uiStateValue("animationRequest")} generation=${uiStateValue("animationGeneration")}"

    private fun advanceImmediateMotionUntilSettled(label: String) {
        repeat(20) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            if (uiStateValue("animationRequest") == null && uiStateValue("transitionPair") == null) return
        }
        error("$label did not clear its committed motion request: ${motionStateDiagnostic()}")
    }

    private fun observeNavigationGesture(): MutableList<String> =
        Collections.synchronizedList(mutableListOf<String>()).also { events ->
            NavigationMotionProbe.recorder = { event -> events.add(event) }
        }

    private fun addNavigationDiagnostic(label: String, events: List<String>, frame: String) {
        InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
            putString("motion_diag_$label", "$frame events=${synchronized(events) { events.joinToString(" | ") }}")
        })
    }

    private fun awaitStableNavigationSurface(): String {
        awaitStableRenderedFrames()
        compose.onNodeWithTag("main_navigation").assertIsDisplayed()
        awaitPresentedFrame()
        return navigationFrameDiagnostic()
    }

    /** Pick an actual point inside the visible part of a child after its horizontal layer shift. */
    private fun visiblePointAfterTranslation(source: Rect, deltaX: Float, verticalFraction: Float = .5f): Offset {
        val viewport = compose.onNodeWithTag("main_pager").fetchSemanticsNode().boundsInRoot
        val left = maxOf(viewport.left, source.left + deltaX)
        val right = minOf(viewport.right, source.right + deltaX)
        val top = maxOf(viewport.top, source.top)
        val bottom = minOf(viewport.bottom, source.bottom)
        assertTrue("the real outgoing control/list must still intersect the screen", right - left > 8f && bottom - top > 8f)
        return Offset((left + right) / 2f, top + (bottom - top) * verticalFraction.coerceIn(0f, 1f))
    }

    private fun performMainPagerClick(screenPoint: Offset) {
        val viewport = compose.onNodeWithTag("main_pager").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("main_pager").performTouchInput {
            click(Offset(screenPoint.x - viewport.left, screenPoint.y - viewport.top))
        }
    }

    private fun performMainPagerSwipe(screenStart: Offset, screenEnd: Offset, durationMillis: Long) {
        val viewport = compose.onNodeWithTag("main_pager").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("main_pager").performTouchInput {
            swipe(
                start = Offset(screenStart.x - viewport.left, screenStart.y - viewport.top),
                end = Offset(screenEnd.x - viewport.left, screenEnd.y - viewport.top),
                durationMillis = durationMillis,
            )
        }
    }

    private fun visibleAppPackageBounds(): Rect {
        val nodes = compose.onAllNodes(hasText("com.", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        return nodes.firstOrNull { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.startsWith("com.") } &&
                node.boundsInRoot.width > 8f && node.boundsInRoot.height > 8f
        }?.boundsInRoot ?: error("No visible package-name row is available for the exit touch test")
    }

    private fun openHomeForEvidence() {
        compose.runOnIdle {
            if (route() != 0) controller().openNavigationTarget(0)
        }
        compose.waitUntil(10_000) { route() == 0 }
        compose.waitForIdle()
    }

    private fun captureHomeMode(label: String) {
        openHomeForEvidence()
        var captured = false
        try {
            awaitStableRenderedFrames()
            // Keep the failed visual state too: a node's existence alone is not visibility evidence.
            captureEvidence(label)
            captured = true
            compose.onNodeWithTag("home_list").assertIsDisplayed()
        } finally {
            if (!captured) runCatching { captureEvidence(label) }
        }
    }

    private fun currentNightMode(): Int = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    private fun nightModeSetting(): Int = compose.activity.getSystemService(android.app.UiModeManager::class.java).nightMode

    private fun nightModeShellValue(mode: Int): String = when (mode) {
        android.app.UiModeManager.MODE_NIGHT_YES -> "yes"
        android.app.UiModeManager.MODE_NIGHT_NO -> "no"
        android.app.UiModeManager.MODE_NIGHT_CUSTOM -> "custom"
        else -> "auto"
    }

    private fun captureEvidence(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val captured = captureRenderedFrame()
        val bitmap = if (captured.width > 600) {
            Bitmap.createScaledBitmap(captured, 600, (captured.height * (600f / captured.width)).toInt(), true)
        } else captured
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            instrumentation.addResults(Bundle().apply {
                putString("motion_png_$label", Base64.encodeToString(bytes, Base64.NO_WRAP))
                val configuration = compose.activity.resources.configuration
                putString("motion_config_$label", "uiMode=${configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK}; " +
                    "fontScale=${configuration.fontScale}; orientation=${configuration.orientation}")
            })
        } finally {
            bitmap.recycle()
            if (bitmap !== captured) captured.recycle()
        }
    }

    private fun captureRenderedFrame(): Bitmap {
        awaitPresentedFrame()
        return compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
    }

    /** Require three visually equivalent submitted frames; no wall-clock sleep or animation-clock jump. */
    private fun awaitStableRenderedFrames() {
        var previous: Bitmap? = null
        var stablePairs = 0
        try {
            repeat(40) {
                val current = captureRenderedFrame()
                val before = previous
                if (before != null && framesVisuallyMatch(before, current)) stablePairs++ else stablePairs = 0
                previous?.recycle()
                previous = current
                if (stablePairs >= 2) {
                    previous?.recycle()
                    previous = null
                    return
                }
            }
            error("Rendered configuration did not settle to three equivalent frames")
        } finally {
            previous?.recycle()
        }
    }

    private fun framesVisuallyMatch(first: Bitmap, second: Bitmap): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        val step = maxOf(1, first.width / 240)
        var samples = 0
        var changed = 0
        for (y in 0 until first.height step step) {
            for (x in 0 until first.width step step) {
                val a = first.getPixel(x, y)
                val b = second.getPixel(x, y)
                val largestChannelDelta = maxOf(
                    kotlin.math.abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
                    kotlin.math.abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)),
                    kotlin.math.abs((a and 0xff) - (b and 0xff)),
                )
                if (largestChannelDelta > 16) changed++
                samples++
            }
        }
        return samples > 0 && changed.toFloat() / samples < .002f
    }

    /** Wait for Compose work and one real View draw without advancing the test animation clock. */
    private fun awaitPresentedFrame() {
        compose.runOnIdle { }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val decor = compose.activity.window.decorView
        val latch = CountDownLatch(1)
        var observer: ViewTreeObserver? = null
        var listener: ViewTreeObserver.OnDrawListener? = null
        compose.runOnIdle {
            observer = decor.viewTreeObserver
            listener = ViewTreeObserver.OnDrawListener { latch.countDown() }
            observer!!.addOnDrawListener(listener!!)
            decor.invalidate()
        }
        try {
            instrumentation.waitForIdleSync()
            check(latch.await(2, TimeUnit.SECONDS)) { "Activity draw did not complete before screenshot capture" }
        } finally {
            compose.runOnIdle {
                val currentObserver = observer
                val currentListener = listener
                if (currentObserver?.isAlive == true && currentListener != null) {
                    currentObserver.removeOnDrawListener(currentListener)
                }
            }
        }
    }

    private data class AccessibilitySnapshot(
        val matches: List<String>,
        val firstVisibleMatch: AccessibilityNodeInfo?,
    )

    private fun describeAccessibilityNode(node: AccessibilityNodeInfo): String {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val actions = node.actionList.joinToString(",") { "${it.id}:${it.label}" }
        return "window=${node.windowId} package=${node.packageName} class=${node.className} " +
            "text=${node.text} desc=${node.contentDescription} bounds=$bounds visible=${node.isVisibleToUser} actions=[$actions]"
    }

    /** Refresh every parent before requesting children, bypassing the framework node cache. */
    private fun accessibilitySnapshot(text: String): AccessibilitySnapshot {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return AccessibilitySnapshot(emptyList(), null)
        val matches = mutableListOf<String>()
        var first: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo, parentPath: List<String>) {
            val refreshed = runCatching { node.refresh() }.getOrDefault(false)
            val description = describeAccessibilityNode(node)
            val path = parentPath + description
            if (refreshed && node.isVisibleToUser &&
                (node.text?.toString() == text || node.contentDescription?.toString() == text)) {
                matches += path.joinToString(" > ")
                if (first == null) first = AccessibilityNodeInfo.obtain(node)
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    visit(child, path)
                } finally {
                    child.recycle()
                }
            }
        }
        return try {
            visit(root, emptyList())
            AccessibilitySnapshot(matches, first)
        } finally {
            root.recycle()
        }
    }

    private fun hasVisibleAccessibilityText(text: String): Boolean =
        accessibilitySnapshot(text).let { snapshot ->
            snapshot.firstVisibleMatch?.recycle()
            snapshot.matches.isNotEmpty()
        }

    private fun backEvent(progress: Float) = BackEventCompat(0f, 240f, progress, BackEventCompat.EDGE_LEFT)

    @Test fun secondaryPageHasVisibleMiddleFramesAndPredictiveBackCanCancelReverseAndCommit() {
        awaitStableRenderedFrames()
        captureEvidence("home")
        val parent = compose.onNodeWithTag("navigation_motion_parent")
        val parentBefore = parent.fetchSemanticsNode().boundsInRoot

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("wake_settings_entry").performClick()
            compose.mainClock.advanceTimeBy(120)
            captureEvidence("subpage_mid")
            compose.onNodeWithTag("navigation_motion_parent").assertExists()
            compose.onNodeWithTag("navigation_motion_child").assertExists()
            val parentMiddle = compose.onNodeWithTag("navigation_motion_parent").fetchSemanticsNode().boundsInRoot
            val childMiddle = compose.onNodeWithTag("navigation_motion_child").fetchSemanticsNode().boundsInRoot
            assertTrue("the parent retreats with a smaller visible width or right edge",
                parentMiddle.width < parentBefore.width - .5f || parentMiddle.right < parentBefore.right - .5f)

            compose.mainClock.advanceTimeBy(240)
            captureEvidence("subpage_complete")
            compose.onNodeWithTag("wake_settings_back").assertExists()
            val childComplete = compose.onNodeWithTag("navigation_motion_child").fetchSemanticsNode().boundsInRoot
            assertTrue("the child travels horizontally from logical end toward its final position", childMiddle.left > childComplete.left)
            assertEquals(11, route())
            val notificationsTab = NativeStrings.get(compose.activity, "notifications_title")
            assertFalse("the inactive global tab subtree is absent from Android accessibility",
                hasVisibleAccessibilityText(notificationsTab))

            val dispatcher = compose.activity.onBackPressedDispatcher
            onMain { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
            onMain { dispatcher.dispatchOnBackProgressed(backEvent(.35f)) }
            compose.mainClock.advanceTimeBy(16)
            val firstBackFrame = compose.onNodeWithTag("navigation_motion_child").fetchSemanticsNode().boundsInRoot
            onMain { dispatcher.dispatchOnBackProgressed(backEvent(.6f)) }
            compose.mainClock.advanceTimeBy(16)
            val reversedFrame = compose.onNodeWithTag("navigation_motion_child").fetchSemanticsNode().boundsInRoot
            onMain { dispatcher.dispatchOnBackProgressed(backEvent(.2f)) }
            compose.mainClock.advanceTimeBy(16)
            val reverseBackFrame = compose.onNodeWithTag("navigation_motion_child").fetchSemanticsNode().boundsInRoot
            assertTrue("back preview must move the outgoing child horizontally with gesture progress", firstBackFrame.left > childComplete.left)
            assertTrue("increasing back progress moves the same child farther toward logical end", reversedFrame.left > firstBackFrame.left)
            assertTrue("reversing back toward the child moves it back continuously", reverseBackFrame.left < reversedFrame.left)
            assertEquals("preview and cancellation do not commit the business route", 11, route())
            assertFalse("predictive preview does not expose inactive global tabs",
                hasVisibleAccessibilityText(notificationsTab))
            onMain { dispatcher.dispatchOnBackCancelled() }
            compose.mainClock.advanceTimeBy(360)
            assertEquals(11, route())
            assertFalse("cancelling predictive back keeps the child route's tabs hidden",
                hasVisibleAccessibilityText(notificationsTab))
            assertTrue("cancel must restore the child screen", childBounds().left < reversedFrame.left)
            val backDescription = compose.activity.getString(R.string.ui_back)
            val activeBackTree = accessibilitySnapshot(backDescription)
            assertTrue("the active subpage back button is exposed to Android accessibility",
                activeBackTree.matches.isNotEmpty())
            val retainedBackNode = activeBackTree.firstVisibleMatch
                ?: error("Fresh accessibility traversal did not retain the active Back node")
            val activeBackPathBeforeCommit = activeBackTree.matches.joinToString(" | ")

            onMain { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
            onMain { dispatcher.dispatchOnBackProgressed(backEvent(.72f)) }
            compose.mainClock.advanceTimeBy(16)
            onMain { dispatcher.onBackPressed() }
            assertEquals("completion commits the existing SettingsController mapping once", 0, route())
            compose.mainClock.advanceTimeBy(48)
            assertTrue("committing to the parent restores its global tab accessibility",
                hasVisibleAccessibilityText(notificationsTab))
            val generationBeforeStaleAction = animationGeneration()
            val staleNodeRefreshed = retainedBackNode.refresh()
            val staleNodeDescription = describeAccessibilityNode(retainedBackNode)
            val staleNodeClickResult = retainedBackNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val freshBackTree = accessibilitySnapshot(backDescription)
            val staleActionDiagnostic = "before=$activeBackPathBeforeCommit oldNodeAfterRefresh={$staleNodeDescription} " +
                "refreshed=$staleNodeRefreshed " +
                "actionClick=$staleNodeClickResult route=${route()} generationBefore=$generationBeforeStaleAction " +
                "generationAfter=${animationGeneration()} finishing=${compose.activity.isFinishing} " +
                "freshMatches=${freshBackTree.matches}"
            InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
                putString("motion_diag_stale_back", staleActionDiagnostic)
            })
            assertTrue("refreshing the Android tree removes the committed page's Back node: $staleActionDiagnostic",
                freshBackTree.matches.isEmpty())
            assertEquals("an action on the retained old accessibility node cannot navigate again: $staleActionDiagnostic",
                0, route())
            assertEquals("the stale accessibility action cannot start another navigation animation: $staleActionDiagnostic",
                generationBeforeStaleAction, animationGeneration())
            assertFalse("the stale Back action cannot finish the parent activity: $staleActionDiagnostic",
                compose.activity.isFinishing)
            retainedBackNode.recycle()
            freshBackTree.firstVisibleMatch?.recycle()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("navigation_motion_child").assertDoesNotExist()
            compose.onNodeWithTag("main_navigation").assertExists()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun realSystemEdgeBackTracksProgressCancelsAndThenCommits() {
        val trace = mutableListOf<String>()
        awaitStableRenderedFrames()
        compose.onNodeWithTag("wake_settings_entry").performClick()
        compose.waitUntil(10_000) {
            route() == 11 && runCatching { compose.onNodeWithTag("wake_settings_back").assertIsDisplayed(); true }.getOrDefault(false)
        }
        awaitStableRenderedFrames()
        val childAtRest = childBounds()
        val decor = compose.activity.window.decorView
        val screenWidth = decor.width.toFloat()
        val touchY = decor.height * .5f
        assertTrue("the app window must be laid out before physical edge injection", screenWidth > 0f && touchY > 0f)
        trace += "start route=${route()} child=$childAtRest bounds=${decor.width}x${decor.height}"

        try {
            val cancelDown = injectPhysicalEdgeGesture(listOf(18f, 42f, 80f, 126f, 180f), touchY)
            compose.waitUntil(5_000) { childBounds().left > childAtRest.left + 12f }
            val cancelPreview = childBounds()
            val cancelPreviewRoute = route()
            trace += "cancel-preview down=$cancelDown child=$cancelPreview route=$cancelPreviewRoute"
            assertEquals("native back preview does not commit the child route", 11, cancelPreviewRoute)
            assertTrue("the real system edge gesture moves the outgoing child", cancelPreview.left > childAtRest.left + 12f)

            listOf(132f, 78f, 34f, 10f).forEach { x ->
                SystemClock.sleep(32)
                injectPhysicalTouch(MotionEvent.ACTION_MOVE, cancelDown, x, touchY)
            }
            SystemClock.sleep(32)
            injectPhysicalTouch(MotionEvent.ACTION_UP, cancelDown, 10f, touchY)
            compose.waitUntil(10_000) {
                route() == 11 && kotlin.math.abs(childBounds().left - childAtRest.left) < 1f
            }
            awaitStableRenderedFrames()
            assertSameBounds("pulling a native back preview to the edge cancels to the child", childAtRest, childBounds())
            trace += "cancel-complete route=${route()} child=${childBounds()}"

            val commitPoints = listOf(20f, 64f, 132f, 230f, 360f, 520f, screenWidth * .72f)
            val commitDown = injectPhysicalEdgeGesture(commitPoints, touchY)
            compose.waitUntil(5_000) { childBounds().left > childAtRest.left + screenWidth * .25f }
            val commitPreview = childBounds()
            trace += "commit-preview down=$commitDown child=$commitPreview route=${route()}"
            assertEquals("route remains committed to the child until the real gesture completes", 11, route())
            injectPhysicalTouch(MotionEvent.ACTION_UP, commitDown, commitPoints.last(), touchY)
            compose.waitUntil(10_000) { route() == 0 }
            awaitStableRenderedFrames()
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("navigation_motion_child").assertDoesNotExist(); true }.getOrDefault(false) }
            trace += "commit-complete route=${route()} child=absent"
        } finally {
            InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
                putString("motion_diag_real_system_edge", trace.joinToString(" | "))
            })
        }
    }

    @Test fun realPagerSwipesSettleControllerAndActivateTheDisplayedTab() {
        fun swipeTo(targetRoute: Int) {
            compose.onNodeWithTag("main_pager").performTouchInput {
                swipeLeft(startX = width * .78f, endX = width * .2f)
            }
            val targetTag = when (targetRoute) {
                1 -> "notifications_list"
                2 -> "modules_tab"
                else -> "settings_list"
            }
            compose.waitUntil(10_000) {
                route() == targetRoute && runCatching {
                    compose.onNodeWithTag(targetTag).assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
        }

        swipeTo(1)
        compose.onNodeWithTag("notifications_list").assertExists()
        swipeTo(2)
        assertEquals("the Modules Pager page must become business-active after settling", 2, route())
        swipeTo(3)
        compose.onNodeWithTag("settings_list").assertExists()
        assertEquals("the Settings Pager page must become business-active after settling", 3, route())
    }

    @Test fun exitingSubpageBlocksItsVisibleControlButGlobalNavigationCanInterruptEarly() {
        awaitStableRenderedFrames()
        compose.onNodeWithTag("wake_settings_entry").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("wake_settings_entry").performClick()
            compose.mainClock.advanceTimeBy(320)
            assertEquals(11, route())
            val dispatcher = compose.activity.onBackPressedDispatcher
            val childAtRest = childBounds()
            val backControlAtRest = compose.onNodeWithTag("wake_settings_back", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot

            onMain { dispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(64)
            assertEquals("committed back returns to the parent before its visual exit ends", 0, route())
            assertTrue("the parent bottom navigation is already visible during the exit",
                compose.onNodeWithTag("main_navigation").fetchSemanticsNode().boundsInRoot.height > 0f)
            assertTrue("the outgoing control has moved with the page", childBounds().left > childAtRest.left)
            assertTrue("the outgoing page is absent from accessibility", compose.onAllNodesWithTag("wake_settings_back")
                .fetchSemanticsNodes().isEmpty())
            assertTrue("global tabs regain accessibility as soon as the parent route commits",
                hasVisibleAccessibilityText(NativeStrings.get(compose.activity, "notifications_title")))

            val blockedGeneration = animationGeneration()
            val controlPoint = visiblePointAfterTranslation(backControlAtRest, childBounds().left - childAtRest.left)
            performMainPagerClick(controlPoint)
            assertEquals("a physical tap at the control's actual current position cannot activate the exiting page",
                blockedGeneration, animationGeneration())
            assertEquals(0, route())

            // Unlike that hidden child, the global bar remains an intentional interruption target.
            val nav = compose.onNodeWithTag("main_navigation").fetchSemanticsNode().boundsInRoot
            val rtl = compose.activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
            val settingsX = nav.left + nav.width * if (rtl) .125f else .875f
            compose.onNodeWithTag("main_navigation").performTouchInput {
                click(Offset(settingsX - nav.left, nav.center.y - nav.top))
            }
            compose.waitUntil(10_000) { route() == 3 }
            compose.mainClock.autoAdvance = true
            awaitStableRenderedFrames()
            compose.onNodeWithTag("settings_list").assertIsDisplayed()
            compose.onNodeWithTag("navigation_motion_child").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun pagerSwipeInterruptsBottomIndicatorSettleAndRejoinsActualPageProgress() {
        val events = observeNavigationGesture()
        try {
            val stableFrame = awaitStableNavigationSurface()
            assertEquals("the tested drag starts from the settled first tab", 0, route())
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("main_navigation").performTouchInput {
                down(Offset(width * .2f, height * .5f))
                moveTo(Offset(width * .69f, height * .5f), delayMillis = 90)
                up()
            }
            compose.mainClock.advanceTimeBy(16)
            addNavigationDiagnostic("fast_drag", events, "before=[$stableFrame] after=[${navigationFrameDiagnostic()}]")
            assertEquals("releasing the tab drag still commits its rounded target", 2, route())

            compose.onNodeWithTag("main_pager").performTouchInput {
                swipeLeft(startX = width * .78f, endX = width * .12f, durationMillis = 100)
            }
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5_000) { route() == 1 }
            compose.mainClock.advanceTimeBy(500)
            assertEquals("an interrupted settle must not leave the indicator at its abandoned tab", 1f,
                navigationIndicatorProgress(), .08f)
        } finally {
            NavigationMotionProbe.recorder = null
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun bottomDragContinuesWhenASecondFingerTakesOverAfterSlop() {
        val events = observeNavigationGesture()
        try {
            val stableFrame = awaitStableNavigationSurface()
            assertEquals("the pointer handoff starts from the settled first tab", 0, route())
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("main_navigation").performTouchInput {
                down(pointerId = 0, position = Offset(width * .1f, height * .5f))
                moveTo(pointerId = 0, position = Offset(width * .30f, height * .5f), delayMillis = 60)
                advanceEventTime(20)
                down(pointerId = 1, position = Offset(width * .55f, height * .5f))
                advanceEventTime(20)
                up(pointerId = 0)
                // A real drag is already recognized before the second pointer takes ownership.
                // The continuing finger's local movement should not inherit pointer 0's origin.
                moveTo(pointerId = 1, position = Offset(width * .70f, height * .5f), delayMillis = 80)
                up(pointerId = 1)
            }
            compose.mainClock.advanceTimeBy(16)
            addNavigationDiagnostic("pointer_handoff", events, "before=[$stableFrame] after=[${navigationFrameDiagnostic()}]")
            assertTrue("this case must enter the drag recognizer before handoff: $events",
                events.any { it.startsWith("dragStart") })
            assertTrue("the active pointer must actually be handed off: $events",
                events.any { it.startsWith("handoff") })
            assertTrue("the continued gesture must end through the drag path: $events",
                events.any { it.startsWith("dragEnd target=1") })
            assertEquals("the active replacement pointer completes the gesture", 1, route())
            assertTrue("handoff re-anchors instead of jumping from the first finger", navigationIndicatorProgress() < 1.6f)
        } finally {
            NavigationMotionProbe.recorder = null
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun disabledSystemAnimationsFreezePredictivePreviewButStillCommitOnce() {
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val previous = Settings.Global.getFloat(compose.activity.contentResolver, key, 1f)
        try {
            awaitStableRenderedFrames()
            runDeviceShell("settings put global $key 0")
            try {
                compose.waitUntil(5_000) { motionDurationScale() == 0f }
            } catch (failure: Throwable) {
                throw AssertionError("animation-scale observer did not publish zero; shell=" +
                    Settings.Global.getFloat(compose.activity.contentResolver, key, -1f) + " " + motionStateDiagnostic(), failure)
            }
            awaitStableRenderedFrames()
            compose.onNodeWithTag("wake_settings_entry").performClick()
            compose.waitUntil(5_000) {
                route() == 11 && compose.activity.window.decorView.rootWindowInsets != null && runCatching {
                    compose.onNodeWithTag("wake_settings_back").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            awaitPresentedFrame()
            compose.mainClock.autoAdvance = false
            val childBeforePreview = childBounds()
            val dispatcher = compose.activity.onBackPressedDispatcher
            onMain { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
            onMain { dispatcher.dispatchOnBackProgressed(backEvent(.8f)) }
            compose.mainClock.advanceTimeBy(16)
            val childDuringPreview = compose.onNodeWithTag("navigation_motion_child").fetchSemanticsNode().boundsInRoot
            assertSameBounds("zero animation scale freezes the full child presentation during predictive back",
                childBeforePreview, childDuringPreview)
            assertEquals("preview does not commit the route", 11, route())
            onMain { dispatcher.dispatchOnBackCancelled() }
            compose.waitForIdle()
            assertEquals(11, route())

            onMain { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
            onMain { dispatcher.dispatchOnBackProgressed(backEvent(.8f)) }
            onMain { dispatcher.onBackPressed() }
            advanceImmediateMotionUntilSettled("zero-scale final back commit")
            assertEquals("the final back event still commits immediately exactly once", 0, route())
            compose.mainClock.advanceTimeBy(16)
            awaitPresentedFrame()
            compose.onNodeWithTag("navigation_motion_child").assertDoesNotExist()
        } finally {
            runDeviceShell("settings put global $key $previous")
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun disablingAnimationsDuringPickerPreviewRestoresTheUncommittedRoute() {
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val previous = Settings.Global.getFloat(compose.activity.contentResolver, key, 1f)
        try {
            awaitStableRenderedFrames()
            runDeviceShell("settings put global $key 1")
            compose.waitUntil(5_000) { motionDurationScale() == 1f }
            compose.onNodeWithContentDescription(NativeStrings.get(compose.activity, "notifications_title")).performClick()
            compose.waitUntil(10_000) {
                route() == 1 && runCatching { compose.onNodeWithTag("notifications_list").assertIsDisplayed(); true }.getOrDefault(false)
            }
            compose.onNodeWithText(NativeStrings.get(compose.activity, "notification_apps")).performClick()
            compose.mainClock.advanceTimeBy(320)
            val originalPicker = pickerModel() ?: error("Picker model was not created")
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("app_search").assertIsDisplayed(); true }.getOrDefault(false) }
            awaitStableRenderedFrames()
            compose.mainClock.autoAdvance = false
            val childBeforePreview = childBounds()
            val dispatcher = compose.activity.onBackPressedDispatcher
            onMain { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
            onMain { dispatcher.dispatchOnBackProgressed(backEvent(.65f)) }
            compose.mainClock.advanceTimeBy(16)
            val childDuringPreview = childBounds()
            assertTrue("the picker preview must be visibly in flight before the system scale changes",
                childDuringPreview.left > childBeforePreview.left)
            runDeviceShell("settings put global $key 0")
            // Let the observer, zero-scale effect, immediate request and its completion each
            // receive real Compose frames. No arbitrary wait or test-clock jump can stand in for
            // those state transitions.
            compose.mainClock.autoAdvance = true
            try {
                compose.waitUntil(5_000) {
                    motionDurationScale() == 0f && runCatching {
                        uiStateValue("backPreviewProgress") == null && uiStateValue("animationRequest") == null &&
                            uiStateValue("transitionPair") == null
                    }.getOrDefault(false)
                }
            } catch (failure: Throwable) {
                throw AssertionError("zero-scale exit request did not settle: setting=" +
                    Settings.Global.getFloat(compose.activity.contentResolver, key, -1f) + " " + motionStateDiagnostic(), failure)
            }
            awaitStableRenderedFrames()
            assertSameBounds("turning animations off restores the uncommitted selector to its original frame",
                childBeforePreview, childBounds())
            assertEquals("changing scale to zero cancels, rather than commits, the picker preview", 100, route())
            assertTrue("an uncommitted selector remains the live model", originalPicker === pickerModel())
            compose.onNodeWithTag("app_search").assertExists()

            onMain { dispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            assertEquals("the subsequent back commit still uses the existing route mapping", 1, route())
            assertTrue("the selector is released only after the final commit", executorShutdown(originalPicker))
        } finally {
            runDeviceShell("settings put global $key $previous")
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun capturesLightModeHomeAndRestoresThePriorMode() {
        val previous = nightModeSetting()
        try {
            runDeviceShell("cmd uimode night no")
            compose.waitUntil(10_000) { currentNightMode() == Configuration.UI_MODE_NIGHT_NO }
            captureHomeMode("light_home")
        } finally {
            runDeviceShell("cmd uimode night ${nightModeShellValue(previous)}")
            compose.waitUntil(10_000) { nightModeSetting() == previous }
        }
    }

    @Test fun capturesDarkModeHomeAndRestoresThePriorMode() {
        val previous = nightModeSetting()
        try {
            runDeviceShell("cmd uimode night yes")
            compose.waitUntil(10_000) { currentNightMode() == Configuration.UI_MODE_NIGHT_YES }
            captureHomeMode("dark_home")
        } finally {
            runDeviceShell("cmd uimode night ${nightModeShellValue(previous)}")
            compose.waitUntil(10_000) { nightModeSetting() == previous }
        }
    }

    @Test fun capturesLargeFontHomeAndRestoresThePriorScale() {
        val key = Settings.System.FONT_SCALE
        val previous = Settings.System.getFloat(compose.activity.contentResolver, key, 1f)
        try {
            runDeviceShell("settings put system $key 1.35")
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale >= 1.34f }
            captureHomeMode("large_font_home")
        } finally {
            runDeviceShell("settings put system $key $previous")
            compose.waitUntil(10_000) { kotlin.math.abs(compose.activity.resources.configuration.fontScale - previous) < .02f }
        }
    }

    @Test fun capturesLandscapeHomeAndRestoresThePriorOrientation() {
        val previous = compose.activity.requestedOrientation
        val previousConfigurationOrientation = compose.activity.resources.configuration.orientation
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    compose.activity.window.decorView.width > compose.activity.window.decorView.height
            }
            captureHomeMode("landscape_home")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                compose.activity.requestedOrientation = previous
            }
            compose.waitUntil(10_000) {
                compose.activity.resources.configuration.orientation == previousConfigurationOrientation &&
                    (if (previousConfigurationOrientation == Configuration.ORIENTATION_LANDSCAPE)
                        compose.activity.window.decorView.width > compose.activity.window.decorView.height
                    else compose.activity.window.decorView.height >= compose.activity.window.decorView.width)
            }
        }
    }

    @Test fun pickerPreviewKeepsModelAndCommittedExitUsesReadOnlySnapshot() {
        compose.onNodeWithContentDescription(NativeStrings.get(compose.activity, "notifications_title")).performClick()
        compose.waitUntil(10_000) {
            route() == 1 && runCatching { compose.onNodeWithTag("notifications_list").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onNodeWithText(NativeStrings.get(compose.activity, "notification_apps")).performClick()
        compose.waitUntil(10_000) {
            route() == 100 && runCatching { compose.onNodeWithTag("app_picker_list").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.waitUntil(10_000) { pickerModel()?.let { !it.loading } == true }
        val originalPicker = pickerModel() ?: error("Picker model was not created")
        compose.onNodeWithText(NativeStrings.get(compose.activity, "system_apps")).performClick()
        compose.waitUntil(10_000) { !originalPicker.loading && originalPicker.includeSystem }
        compose.onNodeWithText(NativeStrings.get(compose.activity, "system_apps")).assertIsOn()
        compose.onNodeWithTag("app_search").performTextReplacement("com")
        val matchingApps = originalPicker.apps.count {
            it.name.contains("com", ignoreCase = true) || it.info.packageName.contains("com", ignoreCase = true)
        }
        assertTrue("including real system packages must provide enough matching rows to scroll", matchingApps >= 8)
        compose.runOnIdle {
            compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("app_search").performTouchInput { click() }
        compose.onNodeWithTag("app_search").assertIsFocused()
        compose.runOnIdle {
            compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        val pickerField = SettingsController::class.java.getDeclaredField("picker").apply { isAccessible = true }
        val list = pickerList(originalPicker)
        compose.onNodeWithTag("app_picker_list").performTouchInput {
            swipe(start = Offset(width * .5f, height * .62f), end = Offset(width * .5f, height * .58f), durationMillis = 220)
        }
        compose.waitUntil(5_000) { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 }
        compose.onNodeWithTag("app_search").assertIsDisplayed()
        compose.waitForIdle()
        val listBoundsAtRest = compose.onNodeWithTag("app_picker_list", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val appBoundsAtRest = visibleAppPackageBounds()
        val pickerChildAtRest = childBounds()
        val selectedBeforeExit = originalPicker.selected
        val scrollBeforeExit = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        val ui = controller()
        assertEquals(100, route())
        awaitStableRenderedFrames()
        val backDescription = compose.activity.getString(R.string.ui_back)
        val notificationsTab = NativeStrings.get(compose.activity, "notifications_title")
        assertTrue("the active selector back button is exposed to Android accessibility",
            hasVisibleAccessibilityText(backDescription))
        assertFalse("selector presentation hides the inactive tab subtree from Android accessibility",
            hasVisibleAccessibilityText(notificationsTab))

        compose.mainClock.autoAdvance = false
        try {
            val dispatcher = compose.activity.onBackPressedDispatcher
            onMain { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
            onMain { dispatcher.dispatchOnBackProgressed(backEvent(.45f)) }
            compose.mainClock.advanceTimeBy(16)
            assertEquals(100, route())
            assertTrue("preview must retain the active picker model", originalPicker === pickerField.get(ui))
            onMain { dispatcher.dispatchOnBackCancelled() }
            compose.mainClock.advanceTimeBy(360)
            assertEquals("preview cancellation keeps the active search query", "com", originalPicker.search)
            assertEquals("preview cancellation keeps the exact scrolled-list position", scrollBeforeExit,
                list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
            compose.onNodeWithTag("app_search").assertIsDisplayed()
            assertFalse("cancelling preview keeps tabs hidden while the picker is active",
                hasVisibleAccessibilityText(notificationsTab))

            onMain { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
            onMain { dispatcher.dispatchOnBackProgressed(backEvent(.3f)) }
            compose.mainClock.advanceTimeBy(16)
            onMain { dispatcher.onBackPressed() }
            assertEquals(1, route())
            // The parent tabs are restored as soon as the route commits; advance one real
            // transition frame so their nonzero alpha and accessibility state reach Compose.
            compose.mainClock.advanceTimeBy(16)
            awaitPresentedFrame()
            compose.onNodeWithTag("main_navigation").assertExists()
            assertTrue("committing back restores the parent tab subtree to Android accessibility",
                hasVisibleAccessibilityText(notificationsTab))
            assertTrue("committing exit shuts down the original app-loading executor", executorShutdown(originalPicker))
            val originalGranted = AppPickerModel::class.java.getDeclaredField("granted").apply { isAccessible = true }
                .getBoolean(originalPicker)
            val exitSnapshotState = MiuixSettingsUi::class.java.getDeclaredField("pickerExitSnapshot\$delegate")
                .apply { isAccessible = true }.get(composeUi()) as androidx.compose.runtime.MutableState<*>
            val exitSnapshot = exitSnapshotState.value ?: error("Picker exit snapshot was not retained during the transition")
            val snapshotGranted = exitSnapshot.javaClass.getDeclaredField("granted").apply { isAccessible = true }
                .getBoolean(exitSnapshot)
            assertEquals("the read-only picker snapshot must preserve the permission affordance state", originalGranted, snapshotGranted)
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("navigation_motion_child").assertExists()
            assertTrue("the committed picker subtree is hidden from the real Android accessibility tree",
                !hasVisibleAccessibilityText(backDescription))
            val childDeltaX = childBounds().left - pickerChildAtRest.left
            performMainPagerClick(visiblePointAfterTranslation(appBoundsAtRest, childDeltaX))
            assertFalse("the exiting selector cannot retain TextField focus", isFocused("app_search"))
            assertEquals("a physical tap on the visible outgoing app row cannot change selection",
                selectedBeforeExit, originalPicker.selected)
            assertEquals("a physical tap cannot navigate through the read-only exit snapshot", 1, route())
            val scrollStart = visiblePointAfterTranslation(listBoundsAtRest, childDeltaX, .78f)
            val scrollEnd = visiblePointAfterTranslation(listBoundsAtRest, childDeltaX, .34f)
            performMainPagerSwipe(scrollStart, scrollEnd, 100)
            compose.mainClock.advanceTimeBy(16)
            assertEquals("a physical swipe cannot scroll the exiting picker snapshot", scrollBeforeExit,
                list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("navigation_motion_child").assertDoesNotExist()

            compose.mainClock.autoAdvance = true
            compose.onNodeWithText(NativeStrings.get(compose.activity, "notification_apps")).performClick()
            compose.waitUntil(10_000) {
                route() == 100 && runCatching { compose.onNodeWithTag("app_picker_list").assertIsDisplayed(); true }.getOrDefault(false)
            }
            val restoredPicker = pickerModel() ?: error("Reopened picker model was not created")
            compose.waitUntil(10_000) { !restoredPicker.loading }
            assertTrue("system apps remain included after committed exit", restoredPicker.includeSystem)
            assertEquals("search state is restored after reopening the selector", "com", restoredPicker.search)
            assertEquals("the scrolled list position is restored after reopening the selector", scrollBeforeExit,
                pickerList(restoredPicker).firstVisibleItemIndex to pickerList(restoredPicker).firstVisibleItemScrollOffset)
            compose.onNodeWithTag("app_picker_list").performScrollToNode(hasTestTag("app_search"))
            compose.onNodeWithTag("app_search").assertTextContains("com")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun executorShutdown(model: Any): Boolean {
        val field = AppPickerModel::class.java.getDeclaredField("executor").apply { isAccessible = true }
        return (field.get(model) as java.util.concurrent.ExecutorService).isShutdown
    }
}
