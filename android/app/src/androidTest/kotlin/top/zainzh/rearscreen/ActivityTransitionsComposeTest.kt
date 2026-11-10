package top.zainzh.rearscreen

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the native Activity-window transaction, not a Compose/back-dispatcher substitute. */
class ActivityTransitionsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private data class Host(
        val type: Class<out ComponentActivity>,
        val backTag: String,
    )

    private val tutorial = Host(TutorialActivity::class.java, "tutorial_back")

    @After
    fun closeAnyOpenedHost() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filter { activity -> activity !is MainActivity && !activity.isFinishing && hostTypes.contains(activity.javaClass) }
                .forEach { it.finish() }
        }
    }

    @Test
    fun eachWhitelistedHostOpensAndReturnsThroughItsRealBackButton() {
        waitForResumed(MainActivity::class.java)
        val hosts = listOf(
            Host(RearScreenshotActivity::class.java, "screenshot_back"),
            tutorial,
            Host(DensityCompatibilityActivity::class.java, "density_back"),
            Host(PermissionChecksActivity::class.java, "permissions_back"),
        )
        hosts.forEach { host ->
            // Do not start a new Activity while the previous CLOSE is still drawing. A resumed
            // MainActivity is not itself proof that the window transition has finished.
            val homeBeforeOpen = stableDisplayedFrame()
            assertScreenshotHasPageContent(homeBeforeOpen, "stable home before ${host.type.simpleName}")
            val openedAt = SystemClock.uptimeMillis()
            val openSampler = DisplayFrameSampler().start()
            val openMark = openSampler.mark()
            launch(host)
            val target = waitForResumed(host.type)
            awaitVisibleBack(target, host.backTag)
            val openFrames = openSampler.awaitChangedAndStableAfter(openMark, homeBeforeOpen)
            val targetFrame = requireNotNull(openFrames.lastOrNull())
            assertScreenshotHasPageContent(targetFrame, "stable ${host.type.simpleName}")
            val openDifference = frameDifferenceRatio(homeBeforeOpen, targetFrame)
            assertTrue("${host.type.simpleName} must replace the stable home frame; difference=$openDifference", openDifference > .012f)

            // This drives the production PageFrame click handler, which calls finish().
            val closeSampler = DisplayFrameSampler().start()
            val closeMark = closeSampler.mark()
            compose.onNodeWithTag(host.backTag).performTouchInput { click() }
            val sourceDrawnAt = awaitFirstDraw(compose.activity.window.decorView)
            waitForResumed(MainActivity::class.java)
            val closeFrames = closeSampler.awaitChangedAndStableAfter(closeMark, targetFrame)
            try {
                val settledHome = requireNotNull(closeFrames.lastOrNull())
                assertScreenshotHasPageContent(settledHome, "stable home after ${host.type.simpleName}")
                val closeDifference = frameDifferenceRatio(targetFrame, settledHome)
                assertTrue("${host.type.simpleName} CLOSE must reach the actual home frame; difference=$closeDifference", closeDifference > .012f)
                val stableHome = stableDisplayedFrame()
                try {
                    assertScreenshotHasPageContent(stableHome, "verified full-screen home after ${host.type.simpleName}")
                    val endpointDifference = frameDifferenceRatio(settledHome, stableHome)
                    assertTrue("the displayed home must remain settled before the next Activity; difference=$endpointDifference", endpointDifference < .006f)
                } finally {
                    stableHome.recycle()
                }
            } finally {
                closeFrames.forEach(Bitmap::recycle)
                openFrames.forEach(Bitmap::recycle)
                homeBeforeOpen.recycle()
            }

            val elapsed = SystemClock.uptimeMillis() - openedAt
            instrumentation.addResults(Bundle().apply {
                putString("activity_transition_${host.type.simpleName}",
                    "targetResumed=${target.javaClass.simpleName}; firstSourceDrawAfterClose=${sourceDrawnAt}; " +
                        "roundTripElapsedMs=$elapsed; backTag=${host.backTag}")
            })
        }
    }

    @Test
    fun tutorialActivityCapturesActualOpenAndCloseMotionFrames() {
        val key = Settings.Global.TRANSITION_ANIMATION_SCALE
        val priorScale = Settings.Global.getString(context.contentResolver, key)
        try {
            runShell("settings put global $key 2")
            compose.waitUntil(5_000) { Settings.Global.getFloat(context.contentResolver, key, -1f) == 2f }
            instrumentation.addResults(Bundle().apply {
                putString("activity_transition_diagnostic_scale", "$key=${Settings.Global.getFloat(context.contentResolver, key, -1f)}")
            })

            val homeBeforeOpen = stableDisplayedFrame()
            assertScreenshotHasPageContent(homeBeforeOpen, "stable home before Tutorial")
            val openSampler = DisplayFrameSampler().start()
            val openMark = openSampler.mark()
            launch(tutorial)
            val target = waitForResumed(TutorialActivity::class.java)
            awaitVisibleBack(target, tutorial.backTag)
            val openFrames = openSampler.awaitChangedAndStableAfter(openMark, homeBeforeOpen)
            val targetFrame = requireNotNull(openFrames.lastOrNull())
            assertScreenshotHasPageContent(targetFrame, "stable Tutorial before CLOSE")

            val closeSampler = DisplayFrameSampler().start()
            val closeMark = closeSampler.mark()
            compose.onNodeWithTag(tutorial.backTag).performTouchInput { click() }
            awaitFirstDraw(compose.activity.window.decorView)
            waitForResumed(MainActivity::class.java)
            val closeFrames = closeSampler.awaitChangedAndStableAfter(closeMark, targetFrame)
            try {
                val settledHome = requireNotNull(closeFrames.lastOrNull())
                assertScreenshotHasPageContent(settledHome, "stable home after Tutorial CLOSE")
                captureRawFrameSequence("tutorial_close_raw", closeFrames)
                assertAndCaptureHorizontalMotionFrame(
                    targetFrame, openFrames, logicalEndSign(target), "tutorial_open_mid"
                )
                assertAndCaptureBetweenEndpoints(
                    targetFrame, settledHome, closeFrames, "tutorial_button_close_mid"
                )
                val stableHome = stableDisplayedFrame()
                try {
                    assertScreenshotHasPageContent(stableHome, "verified home after Tutorial CLOSE")
                    val endpointDifference = frameDifferenceRatio(settledHome, stableHome)
                    assertTrue("Tutorial CLOSE sampler must reach the settled home; difference=$endpointDifference", endpointDifference < .006f)
                } finally {
                    stableHome.recycle()
                }
            } finally {
                closeFrames.forEach(Bitmap::recycle)
                openFrames.forEach(Bitmap::recycle)
                homeBeforeOpen.recycle()
            }
        } finally {
            if (priorScale == null) runShell("settings delete global $key")
            else runShell("settings put global $key $priorScale")
        }
    }

    @Test
    fun realSystemEdgeBackCanCancelAndThenCommit() {
        assertEquals(
            "this test requires Android gesture navigation so the left-edge input reaches native predictive Back",
            2,
            Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1),
        )
        launch(tutorial)
        val target = waitForResumed(TutorialActivity::class.java)
        awaitVisibleBack(target, tutorial.backTag)
        val targetFrame = stableDisplayedFrame()
        assertScreenshotHasPageContent(targetFrame, "settled tutorial before native Back")

        val width = target.windowManager.currentWindowMetrics.bounds.width().toFloat()
        val height = target.windowManager.currentWindowMetrics.bounds.height().toFloat()
        val y = height * .52f
        val edgeSign = logicalEndSign(target)
        val edgeX = if (edgeSign > 0) 1f else width - 1f

        val cancelPoints = listOf(18f, 48f, 84f, 126f)
        val cancelSampler = DisplayFrameSampler().start()
        val cancelMark = cancelSampler.mark()
        val cancelDown = injectEdgeProgress(cancelPoints.map { edgeX + edgeSign * it }, y, edgeX)
        val cancelPreviewFrames = cancelSampler.awaitChangingFrom(cancelMark, targetFrame)
        assertAndCaptureChangingFrame(targetFrame, cancelPreviewFrames, "native_back_preview_mid")
        SystemClock.sleep(32)
        injectTouch(MotionEvent.ACTION_MOVE, cancelDown, edgeX + edgeSign * 64f, y)
        SystemClock.sleep(32)
        injectTouch(MotionEvent.ACTION_MOVE, cancelDown, edgeX + edgeSign * 20f, y)
        SystemClock.sleep(32)
        injectTouch(MotionEvent.ACTION_MOVE, cancelDown, edgeX, y)
        SystemClock.sleep(32)
        injectTouch(MotionEvent.ACTION_UP, cancelDown, edgeX, y)
        val cancelFrames = cancelSampler.awaitStableAfter(cancelMark)
        try {
            val state = activityStateDiagnostic(target)
            instrumentation.addResults(Bundle().apply { putString("activity_transition_cancel_state", state) })
            assertTrue("cancelling the real edge gesture keeps the same Activity alive; $state", !target.isFinishing && !target.isDestroyed)
            assertTrue("the native Back cancel restores Tutorial as the active Activity; $state",
                resumedActivity(TutorialActivity::class.java) === target)
            compose.onNodeWithTag(tutorial.backTag).assertIsDisplayed()
            val afterCancel = cancelFrames.last()
            val restoredDifference = frameDifferenceRatio(targetFrame, afterCancel)
            assertTrue("cancelled native Back must restore the actual child Activity frame; difference=$restoredDifference; $state",
                restoredDifference < .006f)
            addPngResult("native_back_cancelled", afterCancel)
        } finally {
            cancelFrames.forEach(Bitmap::recycle)
        }

        val commitPoints = listOf(20f, 64f, 132f, 230f, 360f, 520f, width * .72f)
        val commitSampler = DisplayFrameSampler().start()
        val commitMark = commitSampler.mark()
        val commitDown = injectEdgeProgress(commitPoints.map { edgeX + edgeSign * it }, y, edgeX)
        val commitPreviewFrames = commitSampler.awaitChangingFrom(commitMark, targetFrame)
        assertAndCaptureChangingFrame(targetFrame, commitPreviewFrames, "native_back_commit_preview_mid")
        val commitFrames = commitSampler.stop()
        injectTouch(MotionEvent.ACTION_UP, commitDown, edgeX + edgeSign * commitPoints.last(), y)
        waitForResumed(MainActivity::class.java)
        assertTrue("the completed native edge gesture finishes the child Activity", target.isFinishing || target.isDestroyed)
        try {
            val homeFrame = stableDisplayedFrame()
            assertScreenshotHasPageContent(homeFrame, "home after committed native Back")
            addPngResult("native_back_committed_home", homeFrame)
            homeFrame.recycle()
        } finally {
            commitFrames.forEach(Bitmap::recycle)
            targetFrame.recycle()
        }
    }

    @Test
    fun configurationRecreationKeepsTheHostAndDoesNotStartAnotherNavigation() {
        launch(tutorial)
        val first = waitForResumed(TutorialActivity::class.java)
        awaitVisibleBack(first, tutorial.backTag)
        val originalOrientation = first.resources.configuration.orientation
        val recreateOrientation = if (originalOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val restoreOrientation = if (originalOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        try {
            onMain { first.requestedOrientation = recreateOrientation }
            val recreated = waitForValue(10_000) {
                resumedActivity(TutorialActivity::class.java)?.takeIf { it !== first }
            }
            awaitVisibleBack(recreated, tutorial.backTag)
            assertEquals(recreateOrientation, recreated.requestedOrientation)
            compose.onNodeWithTag(tutorial.backTag).assertIsDisplayed()
            captureDisplayedFrame("tutorial_after_configuration_recreation")

            onMain { recreated.requestedOrientation = restoreOrientation }
            val restored = waitForValue(10_000) {
                resumedActivity(TutorialActivity::class.java)?.takeIf { it !== recreated }
            }
            awaitVisibleBack(restored, tutorial.backTag)
            compose.onNodeWithTag(tutorial.backTag).assertIsDisplayed()
        } finally {
            resumedActivity(TutorialActivity::class.java)?.let { activity ->
                onMain { activity.requestedOrientation = restoreOrientation }
            }
            runCatching {
                compose.waitUntil(10_000) {
                    resumedActivity(TutorialActivity::class.java)?.resources?.configuration?.orientation == originalOrientation
                }
            }
        }
    }

    @Test
    fun activityWindowResourcesMirrorTheirLogicalDirectionForRtl() {
        val base = compose.activity
        val ltrConfig = Configuration(base.resources.configuration).apply { setLayoutDirection(Locale.ENGLISH) }
        val rtlConfig = Configuration(base.resources.configuration).apply { setLayoutDirection(Locale.forLanguageTag("ar")) }
        val ltr = base.createConfigurationContext(ltrConfig).resources
        val rtl = base.createConfigurationContext(rtlConfig).resources

        assertEquals(100f, firstAnimationPercent(ltr, R.anim.miuix_activity_open_enter, "fromXDelta"), .01f)
        assertEquals(-100f, firstAnimationPercent(rtl, R.anim.miuix_activity_open_enter, "fromXDelta"), .01f)
        assertEquals("260", firstAnimationValue(ltr, R.anim.miuix_activity_open_enter, "duration"))
        assertEquals("260", firstAnimationValue(rtl, R.anim.miuix_activity_open_enter, "duration"))
        assertEquals(-2f, firstAnimationPercent(ltr, R.anim.miuix_activity_open_exit, "toXDelta"), .01f)
        assertEquals(2f, firstAnimationPercent(rtl, R.anim.miuix_activity_open_exit, "toXDelta"), .01f)
        assertEquals(-2f, firstAnimationPercent(ltr, R.anim.miuix_activity_close_enter, "fromXDelta"), .01f)
        assertEquals(2f, firstAnimationPercent(rtl, R.anim.miuix_activity_close_enter, "fromXDelta"), .01f)
        assertEquals("220", firstAnimationValue(ltr, R.anim.miuix_activity_close_enter, "duration"))
        assertEquals("220", firstAnimationValue(rtl, R.anim.miuix_activity_close_enter, "duration"))
        assertEquals(100f, firstAnimationPercent(ltr, R.anim.miuix_activity_close_exit, "toXDelta"), .01f)
        assertEquals(-100f, firstAnimationPercent(rtl, R.anim.miuix_activity_close_exit, "toXDelta"), .01f)
    }

    @Test
    fun zeroSystemAnimationScaleSkipsTheWindowMotionAndStillReturns() {
        val scaleKeys = listOf(
            Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
        )
        val priorScales = scaleKeys.associateWith { Settings.Global.getString(context.contentResolver, it) }
        try {
            scaleKeys.forEach { runShell("settings put global $it 0") }
            compose.waitUntil(5_000) {
                scaleKeys.all { key -> Settings.Global.getFloat(context.contentResolver, key, -1f) == 0f }
            }

            val openSampler = DisplayFrameSampler().start()
            launch(tutorial)
            val target = waitForResumed(TutorialActivity::class.java)
            awaitVisibleBack(target, tutorial.backTag)
            val targetFrame = stableDisplayedFrame()
            val openFrames = openSampler.stop()
            try {
                assertNoHorizontalWindowMotion(targetFrame, openFrames, logicalEndSign(target), "scale-zero OPEN")
            } finally {
                openFrames.forEach(Bitmap::recycle)
            }

            val closeSampler = DisplayFrameSampler().start()
            compose.onNodeWithTag(tutorial.backTag).performTouchInput { click() }
            awaitFirstDraw(compose.activity.window.decorView)
            waitForResumed(MainActivity::class.java)
            val closeFrames = closeSampler.stop()
            try {
                assertNoHorizontalWindowMotion(targetFrame, closeFrames, logicalEndSign(target), "scale-zero CLOSE")
            } finally {
                closeFrames.forEach(Bitmap::recycle)
                targetFrame.recycle()
            }
            captureStableDisplayedFrame("home_after_scale_zero")
            instrumentation.addResults(Bundle().apply {
                putString("activity_transition_scale_zero", scaleKeys.joinToString { key ->
                    "$key=${Settings.Global.getFloat(context.contentResolver, key, -1f)}"
                } + "; hostFinished=${target.isFinishing || target.isDestroyed}")
            })
        } finally {
            priorScales.forEach { (key, value) ->
                if (value == null) runShell("settings delete global $key")
                else runShell("settings put global $key $value")
            }
        }
    }

    private fun launch(host: Host) {
        onMain { compose.activity.startActivity(Intent(compose.activity, host.type)) }
    }

    private fun awaitVisibleBack(activity: Activity, tag: String) {
        compose.waitUntil(10_000) {
            resumedActivity(activity.javaClass) === activity && runCatching {
                compose.onNodeWithTag(tag).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        compose.onAllNodesWithTag(tag).assertCountEquals(1)
        awaitFirstDraw(activity.window.decorView)
    }

    private fun waitForResumed(type: Class<out Activity>): Activity {
        return waitForValue(10_000) { resumedActivity(type) }
    }

    private fun resumedActivity(type: Class<out Activity>): Activity? {
        var result: Activity? = null
        onMain {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { type.isInstance(it) }
        }
        return result
    }

    private fun awaitFirstDraw(decor: View): Long {
        val latch = CountDownLatch(1)
        val drawn = AtomicBoolean(false)
        var drawAt = 0L
        var observer: ViewTreeObserver? = null
        var listener: ViewTreeObserver.OnDrawListener? = null
        onMain {
            val currentObserver = decor.viewTreeObserver
            val currentListener = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (!drawn.compareAndSet(false, true)) return
                    drawAt = SystemClock.uptimeMillis()
                    latch.countDown()
                }
            }
            observer = currentObserver
            listener = currentListener
            currentObserver.addOnDrawListener(currentListener)
            decor.invalidate()
        }
        val submitted = latch.await(2, TimeUnit.SECONDS)
        // ViewTreeObserver forbids removal from inside onDraw on recent target SDKs.
        onMain {
            val currentObserver = observer
            val currentListener = listener
            if (currentObserver?.isAlive == true && currentListener != null) {
                currentObserver.removeOnDrawListener(currentListener)
            }
        }
        assertTrue("the active Activity must submit a real View frame", submitted)
        return drawAt
    }

    private fun captureDisplayedFrame(label: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: error("UiAutomation.takeScreenshot returned no frame for $label")
        addPngResult(label, bitmap)
        bitmap.recycle()
    }

    private fun captureStableDisplayedFrame(label: String) {
        val stable = stableDisplayedFrame()
        try {
            addPngResult(label, stable)
        } finally {
            stable.recycle()
        }
    }

    private fun captureRawFrameSequence(label: String, frames: List<Bitmap>) {
        require(frames.isNotEmpty()) { "The $label sample sequence must not be empty" }
        val selectedIndices = frames.indices.take(4).toList()
        instrumentation.addResults(Bundle().apply {
            putString("activity_transition_samples_$label", "${frames.size} frames; selected=first4:${selectedIndices.joinToString()}; width=240")
        })
        selectedIndices.forEachIndexed { outputIndex, sourceIndex ->
            val source = frames[sourceIndex]
            val width = minOf(240, source.width)
            val height = (source.height * width.toFloat() / source.width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(source, width, height, true)
            try {
                addPngResult("${label}_${outputIndex + 1}", scaled)
            } finally {
                scaled.recycle()
            }
        }
    }

    private fun assertScreenshotHasPageContent(bitmap: Bitmap, label: String) {
        var edgePixels = 0
        val left = bitmap.width / 12
        val right = bitmap.width * 11 / 12
        val top = bitmap.height / 8
        val bottom = bitmap.height * 7 / 8
        val xStep = maxOf(3, bitmap.width / 120)
        val yStep = maxOf(4, bitmap.height / 120)
        for (y in top until bottom step yStep) for (x in left until right step xStep) {
            val here = bitmap.getPixel(x, y)
            if (pixelDistance(here, bitmap.getPixel(minOf(x + 2, right), y)) > 24 ||
                pixelDistance(here, bitmap.getPixel(x, minOf(y + 2, bottom))) > 24) edgePixels++
        }
        assertTrue("$label must have visible screen content, not only a settled blank/window background; edgePixels=$edgePixels", edgePixels > 24)
    }

    /** A visually stable, scaled full-display image for endpoint comparison. */
    private fun stableDisplayedFrame(): Bitmap {
        var previous: Bitmap? = null
        var stablePairs = 0
        try {
            repeat(18) {
                val full = instrumentation.uiAutomation.takeScreenshot()
                    ?: error("UiAutomation.takeScreenshot returned no frame")
                val current = scaledFrame(full)
                full.recycle()
                val before = previous
                if (before != null && frameDifferenceRatio(before, current) < .001f) stablePairs++ else stablePairs = 0
                previous?.recycle()
                previous = current
                if (stablePairs >= 2) {
                    val result = requireNotNull(previous)
                    previous = null
                    return result
                }
                SystemClock.sleep(32)
            }
            error("The actual displayed window did not settle")
        } finally {
            previous?.recycle()
        }
    }

    private fun addPngResult(label: String, screenshot: Bitmap) {
        val scaled = if (screenshot.width > 480)
            Bitmap.createScaledBitmap(screenshot, 480, (screenshot.height * 480f / screenshot.width).toInt(), true)
        else screenshot
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            instrumentation.addResults(Bundle().apply {
                putString("activity_transition_png_$label", Base64.encodeToString(bytes, Base64.NO_WRAP))
                putString("activity_transition_frame_$label",
                    "${screenshot.width}x${screenshot.height}; scaled=${scaled.width}x${scaled.height}; " +
                        "orientation=${context.resources.configuration.orientation}; " +
                        "layoutDirection=${context.resources.configuration.layoutDirection}")
            })
        } finally {
            if (scaled !== screenshot) scaled.recycle()
        }
    }

    private fun scaledFrame(screenshot: Bitmap): Bitmap = if (screenshot.width > 480)
        Bitmap.createScaledBitmap(screenshot, 480, (screenshot.height * 480f / screenshot.width).toInt(), true)
    else screenshot.copy(Bitmap.Config.ARGB_8888, false)

    private inner class DisplayFrameSampler {
        @Volatile private var capturing = true
        private val frames = Collections.synchronizedList(mutableListOf<Bitmap>())
        private val worker = Thread({
            while (capturing && frames.size < 24) {
                val full = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
                if (full != null) {
                    val scaled = scaledFrame(full)
                    full.recycle()
                    synchronized(frames) {
                        if (capturing && frames.size < 24) frames.add(scaled) else scaled.recycle()
                    }
                }
                SystemClock.sleep(20)
            }
        }, "activity-window-frame-sampler").apply { isDaemon = true }

        fun start(): DisplayFrameSampler {
            worker.start()
            return this
        }

        fun mark(): Int = synchronized(frames) { frames.size }

        fun awaitChangingFrom(mark: Int, template: Bitmap): List<Bitmap> {
            val deadline = SystemClock.uptimeMillis() + 2_500
            var latest: List<Bitmap> = emptyList()
            while (SystemClock.uptimeMillis() < deadline && worker.isAlive) {
                latest = synchronized(frames) { frames.drop(mark) }
                if (latest.any { it.width == template.width && frameDifferenceRatio(template, it) > .012f }) return latest
                SystemClock.sleep(16)
            }
            error("The actual system preview did not alter a sampled display frame; samples=${latest.map { frameDifferenceRatio(template, it) }}")
        }

        fun awaitStableAfter(mark: Int): List<Bitmap> {
            val deadline = SystemClock.uptimeMillis() + 5_000
            var latest: List<Bitmap> = emptyList()
            while (SystemClock.uptimeMillis() < deadline) {
                latest = synchronized(frames) { frames.toList() }
                val after = latest.drop(mark)
                if (after.size >= 3) {
                    val tail = after.takeLast(3)
                    if (frameDifferenceRatio(tail[0], tail[1]) < .001f &&
                        frameDifferenceRatio(tail[1], tail[2]) < .001f) {
                        capturing = false
                        worker.join(2_000)
                        return synchronized(frames) { frames.toList() }
                    }
                }
                if (!worker.isAlive) break
                SystemClock.sleep(16)
            }
            capturing = false
            worker.join(2_000)
            latest = synchronized(frames) { frames.toList() }
            error("The post-transition display did not settle; mark=$mark frames=${latest.size}; " +
                "tail=${latest.takeLast(4).zipWithNext().map { frameDifferenceRatio(it.first, it.second) }}")
        }

        /** Waits until the sampled screen has left its source frame and the new window settles. */
        fun awaitChangedAndStableAfter(mark: Int, source: Bitmap): List<Bitmap> {
            val deadline = SystemClock.uptimeMillis() + 5_000
            var latest: List<Bitmap> = emptyList()
            while (SystemClock.uptimeMillis() < deadline && worker.isAlive) {
                latest = synchronized(frames) { frames.drop(mark) }
                val changedAt = latest.indexOfFirst { frameDifferenceRatio(source, it) > .012f }
                if (changedAt >= 0) {
                    val changed = latest.drop(changedAt)
                    if (changed.size >= 3) {
                        val tail = changed.takeLast(3)
                        if (frameDifferenceRatio(tail[0], tail[1]) < .001f &&
                            frameDifferenceRatio(tail[1], tail[2]) < .001f) {
                            capturing = false
                            worker.join(2_000)
                            return synchronized(frames) { frames.drop(mark) }
                        }
                    }
                }
                SystemClock.sleep(16)
            }
            capturing = false
            worker.join(2_000)
            latest = synchronized(frames) { frames.drop(mark) }
            error("The displayed Activity never left its source frame and settled; " +
                "sourceDifference=${latest.map { frameDifferenceRatio(source, it) }}; " +
                "tail=${latest.takeLast(4).zipWithNext().map { frameDifferenceRatio(it.first, it.second) }}")
        }

        fun stop(): List<Bitmap> {
            capturing = false
            worker.join(2_000)
            assertTrue("the display-frame sampler must stop cleanly", !worker.isAlive)
            return synchronized(frames) { frames.toList() }
        }
    }

    private data class TranslationEstimate(val offsetPx: Int, val matchRatio: Float, val zeroOffsetRatio: Float)

    private fun assertAndCaptureHorizontalMotionFrame(
        template: Bitmap,
        samples: List<Bitmap>,
        expectedSign: Int,
        label: String,
    ) {
        val estimates = samples.map { it to estimateHorizontalTranslation(template, it, expectedSign) }
        val minimum = maxOf(8, template.width / 40)
        val candidate = estimates
            .filter { (frame, estimate) ->
                frame.width == template.width &&
                    estimate.offsetPx * expectedSign > minimum &&
                    estimate.matchRatio > .28f &&
                    estimate.matchRatio - estimate.zeroOffsetRatio > .08f
            }
            .maxByOrNull { (_, estimate) -> estimate.offsetPx * expectedSign }
        assertTrue(
            "$label must contain a real horizontal Activity-plane offset; " +
                "samples=${estimates.joinToString { "${it.second.offsetPx}px/${"%.2f".format(it.second.matchRatio)}" }}",
            candidate != null,
        )
        val selected = requireNotNull(candidate)
        addPngResult(label, selected.first)
        instrumentation.addResults(Bundle().apply {
            putString("activity_transition_motion_$label", "offset=${selected.second.offsetPx}px; " +
                "match=${selected.second.matchRatio}; atRest=${selected.second.zeroOffsetRatio}; " +
                "samples=${samples.size}; direction=$expectedSign")
        })
    }

    /** CLOSE may expose a clipped outgoing Activity plane, so compare real endpoints instead of a full-screen translation template. */
    private fun assertAndCaptureBetweenEndpoints(
        source: Bitmap,
        destination: Bitmap,
        samples: List<Bitmap>,
        label: String,
    ) {
        val candidates = samples.map { frame ->
            frame to (frameDifferenceRatio(source, frame) to frameDifferenceRatio(destination, frame))
        }.filter { (frame, differences) ->
            frame.width == source.width && frame.height == source.height &&
                differences.first > .012f && differences.second > .012f
        }
        val candidate = candidates.maxByOrNull { (_, differences) -> minOf(differences.first, differences.second) }
        assertTrue(
            "$label must contain a visible frame distinct from both settled endpoints; " +
                "source=${samples.map { frameDifferenceRatio(source, it) }}; " +
                "destination=${samples.map { frameDifferenceRatio(destination, it) }}",
            candidate != null,
        )
        val selected = requireNotNull(candidate)
        addPngResult(label, selected.first)
        instrumentation.addResults(Bundle().apply {
            putString("activity_transition_motion_$label",
                "sourceDifference=${selected.second.first}; destinationDifference=${selected.second.second}; samples=${samples.size}")
        })
    }

    /** Predictive Back preview is system-seeked; Android owns its scale/shape/translation. */
    private fun assertAndCaptureChangingFrame(template: Bitmap, samples: List<Bitmap>, label: String) {
        val changed = samples.map { it to frameDifferenceRatio(template, it) }
            .filter { (frame, difference) -> frame.width == template.width && difference > .012f }
            .maxByOrNull { it.second }
        assertTrue(
            "$label must show Android's actual predictive-Back preview while the pointer is down; " +
                "samples=${samples.map { frameDifferenceRatio(template, it) }}",
            changed != null,
        )
        val selected = requireNotNull(changed)
        addPngResult(label, selected.first)
        instrumentation.addResults(Bundle().apply {
            putString("activity_transition_motion_$label", "systemFrameDifference=${selected.second}; samples=${samples.size}; previewOwnedBy=Android")
        })
    }

    private fun assertNoHorizontalWindowMotion(template: Bitmap, samples: List<Bitmap>, expectedSign: Int, label: String) {
        val minimum = maxOf(8, template.width / 40)
        val moved = samples.map { estimateHorizontalTranslation(template, it, expectedSign) }
            .filter { it.matchRatio > .18f && it.matchRatio - it.zeroOffsetRatio > .025f && it.offsetPx * expectedSign > minimum }
        assertTrue("$label must be completed by Android without an app-visible horizontal transition; moved=$moved", moved.isEmpty())
    }

    private fun estimateHorizontalTranslation(template: Bitmap, frame: Bitmap, expectedSign: Int): TranslationEstimate {
        if (template.width != frame.width || template.height != frame.height) return TranslationEstimate(0, 0f, 0f)
        val left = template.width / 12
        val right = template.width * 11 / 12
        val top = template.height / 8
        val bottom = template.height * 7 / 8
        val xStep = maxOf(3, template.width / 120)
        val yStep = maxOf(4, template.height / 120)
        val points = ArrayList<Pair<Int, Int>>()
        for (y in top until bottom step yStep) for (x in left until right step xStep) {
            val here = template.getPixel(x, y)
            val horizontal = template.getPixel(minOf(x + 2, right), y)
            val vertical = template.getPixel(x, minOf(y + 2, bottom))
            if (pixelDistance(here, horizontal) > 32 || pixelDistance(here, vertical) > 32) points.add(x to y)
        }
        if (points.isEmpty()) return TranslationEstimate(0, 0f, 0f)

        fun matchRatio(deltaX: Int): Float {
            var matched = 0
            points.forEach { (x, y) ->
                val shiftedX = x + deltaX
                if (shiftedX in 0 until frame.width && pixelDistance(template.getPixel(x, y), frame.getPixel(shiftedX, y)) < 28) matched++
            }
            return matched.toFloat() / points.size
        }

        val zero = matchRatio(0)
        var bestDelta = 0
        var bestRatio = zero
        val maxShift = template.width * 3 / 4
        for (magnitude in 2..maxShift step 2) {
            val delta = expectedSign * magnitude
            val score = matchRatio(delta)
            if (score > bestRatio) {
                bestDelta = delta
                bestRatio = score
            }
        }
        return TranslationEstimate(bestDelta, bestRatio, zero)
    }

    private fun pixelDistance(first: Int, second: Int): Int = maxOf(
        kotlin.math.abs(((first shr 16) and 0xff) - ((second shr 16) and 0xff)),
        kotlin.math.abs(((first shr 8) and 0xff) - ((second shr 8) and 0xff)),
        kotlin.math.abs((first and 0xff) - (second and 0xff)),
    )

    private fun logicalEndSign(activity: Activity): Int =
        if (activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1

    private fun activityStateDiagnostic(target: Activity): String {
        val stages = mutableListOf<String>()
        onMain {
            Stage.values().forEach { stage ->
                if (ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).any { it === target }) {
                    stages += stage.name
                }
            }
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val rootSummary = root?.let { node ->
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            "package=${node.packageName}; class=${node.className}; bounds=$bounds; " +
                "visible=${node.isVisibleToUser}; window=${node.windowId}"
        } ?: "root=null"
        return "target=${target.javaClass.simpleName}; finishing=${target.isFinishing}; destroyed=${target.isDestroyed}; " +
            "stages=$stages; $rootSummary"
    }

    private fun firstAnimationValue(resources: android.content.res.Resources, animationId: Int, attribute: String): String {
        val parser = resources.getXml(animationId)
        try {
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) parser.next()
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "translate") {
                    return requireNotNull(parser.getAttributeValue("http://schemas.android.com/apk/res/android", attribute))
                }
            }
        } finally {
            parser.close()
        }
        error("No translate animation was found in resource $animationId")
    }

    private fun firstAnimationPercent(resources: android.content.res.Resources, animationId: Int, attribute: String): Float =
        firstAnimationValue(resources, animationId, attribute).removeSuffix("%p").toFloat()

    private fun frameDifferenceRatio(first: Bitmap, second: Bitmap): Float {
        if (first.width != second.width || first.height != second.height) return 1f
        val xStart = first.width / 10
        val xEnd = first.width * 9 / 10
        val yStart = first.height / 10
        val yEnd = first.height * 9 / 10
        val step = maxOf(2, first.width / 240)
        var samples = 0
        var changed = 0
        for (y in yStart until yEnd step step) for (x in xStart until xEnd step step) {
            val a = first.getPixel(x, y)
            val b = second.getPixel(x, y)
            val delta = maxOf(
                kotlin.math.abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
                kotlin.math.abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)),
                kotlin.math.abs((a and 0xff) - (b and 0xff)),
            )
            if (delta > 20) changed++
            samples++
        }
        return if (samples == 0) 1f else changed.toFloat() / samples
    }

    private fun injectEdgeProgress(points: List<Float>, y: Float, startX: Float): Long {
        val downTime = SystemClock.uptimeMillis()
        injectTouch(MotionEvent.ACTION_DOWN, downTime, startX, y)
        points.forEach { x ->
            SystemClock.sleep(32)
            injectTouch(MotionEvent.ACTION_MOVE, downTime, x, y)
        }
        return downTime
    }

    private fun injectTouch(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            assertTrue("real touchscreen event must reach Android's system gesture recognizer",
                instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun runShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }

    private fun <T : Any> waitForValue(timeoutMillis: Long, condition: () -> T?): T {
        var value: T? = null
        compose.waitUntil(timeoutMillis) {
            value = condition()
            value != null
        }
        return requireNotNull(value)
    }

    private companion object {
        val hostTypes: Set<Class<*>> = setOf(
            RearScreenshotActivity::class.java,
            TutorialActivity::class.java,
            DensityCompatibilityActivity::class.java,
            PermissionChecksActivity::class.java,
        )
    }
}
