package top.zainzh.rearscreen

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Base64
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SharedInteractionMotionComposeTest {
    private val motionDurationScale = MutableTestMotionDurationScale()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>(motionDurationScale)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private class MutableTestMotionDurationScale : MotionDurationScale {
        @Volatile override var scaleFactor: Float = 1f
    }

    private fun settingsController(): SettingsController {
        val field = MainActivity::class.java.getDeclaredField("nativeUi").apply { isAccessible = true }
        return field.get(compose.activity) as SettingsController
    }

    private fun route(): Int {
        val field = SettingsController::class.java.getDeclaredField("page").apply { isAccessible = true }
        return field.getInt(settingsController())
    }

    private fun pickerModel(): AppPickerModel? {
        val field = SettingsController::class.java.getDeclaredField("picker").apply { isAccessible = true }
        return field.get(settingsController()) as? AppPickerModel
    }

    private fun modal(): MiuixDialogUi {
        val field = SettingsController::class.java.getDeclaredField("modal").apply { isAccessible = true }
        return field.get(settingsController()) as MiuixDialogUi
    }

    private fun updateDialog(): UpdateDialogUi {
        val controller = SettingsController::class.java.getDeclaredField("updateDialog").apply { isAccessible = true }
            .get(settingsController())
        val field = UpdateDialogController::class.java.getDeclaredField("ui").apply { isAccessible = true }
        return field.get(controller) as UpdateDialogUi
    }

    @After
    fun restoreTheComposeClockAndCloseTransientContent() {
        compose.mainClock.autoAdvance = true
        motionDurationScale.scaleFactor = 1f
        runCatching { if (modal().isVisible()) modal().dismiss() }
        runCatching { updateDialog().dismiss() }
        runCatching {
            if (route() == 100) compose.onNodeWithTag("app_picker_back").performClick()
        }
    }

    @Test
    fun appToggleHighlightsFromPressAndDragCancellationDoesNotCommit() {
        val picker = openPicker()
        val priorIncludeSystem = picker.includeSystem
        try {
            compose.runOnIdle { picker.includeSystem = false }
            compose.waitForIdle()
            val toggle = compose.onNodeWithTag("picker_system_apps_toggle")
            val idleImage = toggle.captureToImage().asAndroidBitmap()
            val center = Offset(idleImage.width / 2f, idleImage.height / 2f)
            val idleColor = idleImage.getPixel(center.x.toInt(), center.y.toInt())
            addPngResult("toggle_idle_before_press", idleImage)
            idleImage.recycle()

            compose.mainClock.autoAdvance = false
            toggle.performTouchInput { down(center) }
            val pressSamples = mutableListOf<Int>()
            for (frame in 0 until 3) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val sample = toggle.captureToImage().asAndroidBitmap()
                pressSamples += sample.getPixel(center.x.toInt(), center.y.toInt())
                sample.recycle()
                if (colorDistance(idleColor, pressSamples.last()) >= 4) break
            }
            val pressed = toggle.captureToImage().asAndroidBitmap()
            val pressedColor = pressed.getPixel(center.x.toInt(), center.y.toInt())
            addPngResult("toggle_pressed", pressed)
            pressed.recycle()
            val pressDelta = colorDistance(idleColor, pressedColor)
            instrumentation.addResults(Bundle().apply {
                putString("shared_interaction_press_probe",
                    "clockMs=${compose.mainClock.currentTime}; frames=${pressSamples.size}; " +
                        "idlePixel=$idleColor; samples=$pressSamples; pressedPixel=$pressedColor; delta=$pressDelta; " +
                        "toggleStillOff=${!picker.includeSystem}")
            })

            toggle.performTouchInput { up() }
            assertTrue("the switch submits its new value on release", picker.includeSystem)
            compose.mainClock.advanceTimeBy(40)
            compose.waitForIdle()
            val releaseTail = toggle.captureToImage().asAndroidBitmap()
            val tailColor = releaseTail.getPixel(center.x.toInt(), center.y.toInt())
            addPngResult("toggle_release_tail", releaseTail)
            releaseTail.recycle()
            compose.mainClock.advanceTimeBy(120)
            compose.waitForIdle()
            val idleAfterRelease = toggle.captureToImage().asAndroidBitmap()
            val releasedColor = idleAfterRelease.getPixel(center.x.toInt(), center.y.toInt())
            idleAfterRelease.recycle()
            val tailDelta = colorDistance(tailColor, releasedColor)
            assertTrue("the shared row gives immediate visible press feedback after bounded draw frames; " +
                "idle=$idleColor pressed=$pressedColor delta=$pressDelta samples=$pressSamples", pressDelta >= 4)
            assertTrue("the released highlight has a visible, shorter-than-full tail; delta=$tailDelta pressed=$pressDelta",
                tailDelta > 0 && tailDelta < pressDelta)

            compose.runOnIdle { picker.includeSystem = false }
            val currentToggle = compose.onNodeWithTag("picker_system_apps_toggle")
            val currentBounds = currentToggle.fetchSemanticsNode().boundsInRoot
            currentToggle.performTouchInput {
                val start = Offset(center.x, currentBounds.height / 2f)
                down(start)
                moveTo(Offset(start.x, start.y + currentBounds.height * 2f))
                up()
            }
            compose.waitForIdle()
            assertFalse("dragging out of a Switch row cancels rather than commits", picker.includeSystem)
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { picker.includeSystem = priorIncludeSystem }
            leavePicker()
        }
    }

    @Test
    fun settingsRevealReversesAtItsCurrentHeightAndBlocksTheExitingDpiControl() {
        assumeTrue("the virtual/physical cast mode controls this shared reveal", BuildConfig.VIRTUAL_CAST_ENABLED)
        val preferences = RearCastMode.preferences(compose.activity)
        val prior = preferences.getBoolean(RearCastMode.VIRTUAL, true)
        try {
            preferences.edit().putBoolean(RearCastMode.VIRTUAL, true).commit()
            compose.onNodeWithTag("cast_settings_entry").performClick()
            compose.waitUntil(10_000) { route() == 10 && mergedTagExists("rear_cast_mode_toggle") }
            val reveal = compose.onNodeWithTag("physical_settings_reveal", useUnmergedTree = true)
            val hidden = reveal.fetchSemanticsNode().boundsInRoot
            assertEquals("physical options begin collapsed", 0f, hidden.height, .5f)

            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("rear_cast_mode_toggle").performClick()
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            val entering = reveal.fetchSemanticsNode().boundsInRoot
            assertTrue("the physical settings group has a real intermediate height", entering.height > 0f)
            captureComposePng("physical_reveal_enter_mid")

            compose.onNodeWithTag("rear_cast_mode_toggle").performClick()
            compose.mainClock.advanceTimeBy(40)
            compose.waitForIdle()
            val reversed = reveal.fetchSemanticsNode().boundsInRoot
            assertTrue("a quick reverse continues from the current height", reversed.height < entering.height)
            assertFalse("the outgoing DPI action leaves accessibility as soon as exit begins",
                mergedTagExists("physical_dpi_entry"))

            compose.onNodeWithTag("rear_cast_mode_toggle").performClick()
            compose.mainClock.advanceTimeBy(240)
            compose.waitForIdle()
            val expanded = reveal.fetchSemanticsNode().boundsInRoot
            val dpiBounds = compose.onNodeWithTag("physical_dpi_entry").fetchSemanticsNode().boundsInRoot
            assertTrue("the physical option is fully present before its exit", expanded.height > entering.height && dpiBounds.height > 0f)

            compose.onNodeWithTag("rear_cast_mode_toggle").performClick()
            compose.mainClock.advanceTimeBy(56)
            compose.waitForIdle()
            val exiting = reveal.fetchSemanticsNode().boundsInRoot
            assertFalse("exiting controls are no longer exposed to accessibility", mergedTagExists("physical_dpi_entry"))
            captureComposePng("physical_reveal_exit_mid")
            val outgoingDpiBounds = compose.onNodeWithTag("physical_dpi_entry", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val visibleTop = maxOf(outgoingDpiBounds.top, exiting.top)
            val visibleBottom = minOf(outgoingDpiBounds.bottom, exiting.bottom)
            val oldControlPoint = Offset(outgoingDpiBounds.center.x, (visibleTop + visibleBottom) / 2f)
            instrumentation.addResults(Bundle().apply {
                putString("shared_interaction_reveal_hit_probe",
                    "outgoing=$outgoingDpiBounds; visibleReveal=$exiting; intersectionY=$visibleTop..$visibleBottom; point=$oldControlPoint")
            })
            assertTrue("the old row has an actually visible hit region during the shrink; " +
                "row=$outgoingDpiBounds reveal=$exiting intersection=$visibleTop..$visibleBottom",
                visibleBottom > visibleTop)
            tapMainPager(oldControlPoint)
            assertEquals("the outgoing DPI row cannot open its dialog", 10, route())
            assertFalse("the outgoing row did not submit its action", modal().isVisible())

            compose.mainClock.advanceTimeBy(180)
            compose.waitForIdle()
            assertEquals("the reveal completes at zero height", 0f,
                reveal.fetchSemanticsNode().boundsInRoot.height, .5f)
        } finally {
            compose.mainClock.autoAdvance = true
            preferences.edit().putBoolean(RearCastMode.VIRTUAL, prior).commit()
            runCatching { if (route() == 10) compose.onNodeWithTag("cast_settings_back").performClick() }
        }
    }

    @Test
    fun settingsRevealCompletesImmediatelyWhenAnimatorScaleIsZero() {
        assumeTrue("the virtual/physical cast mode controls this shared reveal", BuildConfig.VIRTUAL_CAST_ENABLED)
        val preferences = RearCastMode.preferences(compose.activity)
        val priorMode = preferences.getBoolean(RearCastMode.VIRTUAL, true)
        val scaleKey = Settings.Global.ANIMATOR_DURATION_SCALE
        val priorScale = Settings.Global.getString(context.contentResolver, scaleKey)
        try {
            preferences.edit().putBoolean(RearCastMode.VIRTUAL, false).commit()
            runDeviceShell("settings put global $scaleKey 0")
            compose.waitUntil(5_000) { Settings.Global.getFloat(context.contentResolver, scaleKey, -1f) == 0f }
            compose.onNodeWithTag("cast_settings_entry").performClick()
            compose.waitUntil(10_000) { route() == 10 && mergedTagExists("physical_dpi_entry") }
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { motionDurationScale.scaleFactor = 0f }
            compose.onNodeWithTag("rear_cast_mode_toggle").performClick()
            val measuredHeights = mutableListOf<Float>()
            for (frame in 0 until 3) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                measuredHeights += compose.onNodeWithTag("physical_settings_reveal", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot.height
                if (measuredHeights.last() <= .5f) break
            }
            assertFalse("scale zero removes the outgoing control without a lingering animation", mergedTagExists("physical_dpi_entry"))
            val diagnostic = "route=${route()}; virtual=${preferences.getBoolean(RearCastMode.VIRTUAL, true)}; " +
                "globalScale=${Settings.Global.getFloat(context.contentResolver, scaleKey, -1f)}; " +
                "composeScale=${motionDurationScale.scaleFactor}; " +
                "clockMs=${compose.mainClock.currentTime}; heights=$measuredHeights"
            instrumentation.addResults(Bundle().apply { putString("shared_interaction_scale_zero_probe", diagnostic) })
            assertEquals("scale zero collapses the measured reveal within bounded frames; $diagnostic", 0f,
                measuredHeights.last(), .5f)
        } finally {
            motionDurationScale.scaleFactor = 1f
            compose.mainClock.autoAdvance = true
            if (priorScale == null) runDeviceShell("settings delete global $scaleKey")
            else runDeviceShell("settings put global $scaleKey $priorScale")
            preferences.edit().putBoolean(RearCastMode.VIRTUAL, priorMode).commit()
            runCatching { if (route() == 10) compose.onNodeWithTag("cast_settings_back").performClick() }
        }
    }

    @Test
    fun numberDialogKeepsDraftAndErrorUntilDismissFinishes() {
        val dialog = modal()
        val saved = AtomicInteger(-1)
        compose.runOnIdle {
            dialog.number("motion-number", "Motion number", "Enter a value", 12, 1, 99,
                java.util.function.IntConsumer { saved.set(it) }, null)
        }
        waitForTag("number_input")
        compose.onNodeWithTag("number_input").performTextReplacement("0")
        compose.onNodeWithTag("modal_action_0").performClick()
        assertEquals("out-of-range input does not call the business save", -1, saved.get())
        val invalid = Bundle().also(dialog::saveState)
        assertEquals("motion-number", invalid.getString("native_number_kind"))
        assertEquals("0", invalid.getString("native_number_draft"))

        compose.onNodeWithTag("number_input").performTextReplacement("42")
        val beforeDismiss = Bundle().also(dialog::saveState)
        assertEquals("42", beforeDismiss.getString("native_number_draft"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { dialog.dismiss() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        captureWindowPng("number_dialog_exit_mid")
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        val afterDismiss = Bundle().also(dialog::saveState)
        assertFalse("closed dialogs are not saved as visible drafts", afterDismiss.containsKey("native_number_kind"))
        assertNotNull("the model remains composed until Miuix finishes the close", stateValue(dialog, "model"))
        assertEquals("42", stateValue(dialog, "draft"))
        assertEquals("motion-number", privateValue(dialog, "kind"))
        assertFalse("the exiting dialog title/content surface leaves accessibility", mergedTagExists("modal_message"))
        assertTrue("the outgoing dialog content remains composed for its exit frame", unmergedTagExists("modal_message"))
        assertFalse("the closing input is removed from accessibility", mergedTagExists("number_input"))

        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertNull("the model is released only after the exit callback", stateValue(dialog, "model"))
        assertNull("the number kind is released with the completed dialog", privateValue(dialog, "kind"))
        assertEquals("validation never submits a value during dismissal", -1, saved.get())
    }

    @Test
    fun reopeningAModalDuringItsExitWinsOverTheOldDismissCallback() {
        val dialog = modal()
        val oldAction = AtomicInteger(0)
        val newAction = AtomicInteger(0)
        compose.runOnIdle {
            dialog.show("First motion dialog", "Old dialog content", arrayOf("Run old", "Cancel"),
                arrayOf(Runnable { oldAction.incrementAndGet() }, null))
        }
        waitForTag("modal_message")
        compose.mainClock.autoAdvance = false
        val oldButton = compose.onNodeWithTag("modal_action_0")
        val heldPointInWindowRoot = oldButton.fetchSemanticsNode().boundsInRoot.center
        oldButton.performTouchInput { down(center) }
        compose.runOnIdle { dialog.dismiss() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        captureWindowPng("modal_exit_mid")
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        assertFalse("the logical dialog closes immediately", dialog.isVisible())
        assertNotNull("the old visual model remains during its exit", stateValue(dialog, "model"))
        assertFalse("closing actions are inaccessible", mergedTagExists("modal_action_0"))

        compose.runOnIdle {
            dialog.show("Replacement motion dialog", "New dialog content", arrayOf("Run new", "Cancel"),
                arrayOf(Runnable { newAction.incrementAndGet() }, null))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        releaseHeldPressAtWindowRoot("modal_message", heldPointInWindowRoot)
        compose.waitForIdle()
        assertEquals("release from the retired press cannot run the old callback", 0, oldAction.get())
        assertEquals("release from the retired press cannot activate the replacement callback", 0, newAction.get())
        compose.mainClock.advanceTimeBy(260)
        compose.waitForIdle()
        assertTrue("a new show interrupts the old exit", dialog.isVisible())
        compose.onNodeWithTag("modal_message").assertTextContains("New dialog content")
        compose.onNodeWithTag("modal_action_0").performClick()
        assertEquals("the stale old action never runs", 0, oldAction.get())
        assertEquals("the replacement action submits once", 1, newAction.get())
        compose.mainClock.advanceTimeBy(260)
        compose.waitForIdle()
    }

    @Test
    fun heldModalPressCannotCrossASameFrameSessionReplacement() {
        val dialog = modal()
        val oldAction = AtomicInteger(0)
        val newAction = AtomicInteger(0)
        compose.runOnIdle {
            dialog.show("Pressed old dialog", "Old session", arrayOf("Run", "Cancel"),
                arrayOf(Runnable { oldAction.incrementAndGet() }, null))
        }
        waitForTag("modal_action_0")
        compose.onNodeWithTag("modal_action_0").performTouchInput { down(center) }
        compose.runOnIdle {
            dialog.dismiss()
            dialog.show("Replacement dialog", "New session", arrayOf("Run", "Cancel"),
                arrayOf(Runnable { newAction.incrementAndGet() }, null))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("modal_action_0").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("a retired modal press cannot submit its old callback", 0, oldAction.get())
        assertEquals("a retired modal press cannot activate the replacement callback", 0, newAction.get())

        compose.onNodeWithTag("modal_message").assertTextContains("New session")
        compose.onNodeWithTag("modal_action_0").performClick()
        assertEquals("the new modal session remains usable", 1, newAction.get())
    }

    @Test
    fun updateDialogKeepsTheClosingSessionAndAcceptsANewShow() {
        val ui = updateDialog()
        val oldAction = AtomicInteger(0)
        val newAction = AtomicInteger(0)
        compose.runOnIdle { showUpdate(ui, "First update", "version-old", Runnable { oldAction.incrementAndGet() }) }
        waitForTag("update_dialog_current")
        val firstGeneration = (privateValue(ui, "generation") as Number).toLong()

        compose.mainClock.autoAdvance = false
        val oldButton = compose.onNodeWithTag("update_dialog_action_0")
        val heldPointInWindowRoot = oldButton.fetchSemanticsNode().boundsInRoot.center
        oldButton.performTouchInput { down(center) }
        compose.runOnIdle { ui.dismiss() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        captureWindowPng("update_dialog_exit_mid")
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        assertNotNull("update details remain in the outgoing window", stateValue(ui, "model"))
        assertFalse("the exiting update title/content surface leaves accessibility", mergedTagExists("update_dialog_current"))
        assertTrue("the outgoing update content remains composed for its exit frame", unmergedTagExists("update_dialog_current"))
        assertFalse("outgoing update actions cannot be activated", mergedTagExists("update_dialog_action_0"))

        compose.runOnIdle { showUpdate(ui, "Replacement update", "version-new", Runnable { newAction.incrementAndGet() }, progress = 73) }
        assertTrue("a new update show advances the close generation", (privateValue(ui, "generation") as Number).toLong() > firstGeneration)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        releaseHeldPressAtWindowRoot("update_dialog_current", heldPointInWindowRoot)
        compose.waitForIdle()
        assertEquals("release from the retired update press cannot run the old callback", 0, oldAction.get())
        assertEquals("release from the retired update press cannot activate the replacement callback", 0, newAction.get())
        compose.mainClock.advanceTimeBy(260)
        compose.waitForIdle()
        compose.onNodeWithTag("update_dialog_current").assertTextContains("version-new")
        compose.onNodeWithTag("update_dialog_action_0").performClick()
        assertEquals("the closing session's stale action stays blocked", 0, oldAction.get())
        assertEquals("the replacement update session remains actionable", 1, newAction.get())
        compose.mainClock.advanceTimeBy(260)
        compose.waitForIdle()
    }

    @Test
    fun heldUpdatePressCannotCrossASameFrameSessionReplacement() {
        val ui = updateDialog()
        val oldAction = AtomicInteger(0)
        val newAction = AtomicInteger(0)
        compose.runOnIdle { showUpdate(ui, "Pressed old update", "version-old", Runnable { oldAction.incrementAndGet() }) }
        waitForTag("update_dialog_action_0")
        compose.onNodeWithTag("update_dialog_action_0").performTouchInput { down(center) }
        compose.runOnIdle {
            ui.dismiss()
            showUpdate(ui, "Replacement update", "version-new", Runnable { newAction.incrementAndGet() }, progress = 73)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("update_dialog_action_0").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("a retired update press cannot submit its old callback", 0, oldAction.get())
        assertEquals("a retired update press cannot activate the replacement callback", 0, newAction.get())

        compose.onNodeWithTag("update_dialog_current").assertTextContains("version-new")
        compose.onNodeWithTag("update_dialog_action_0").performClick()
        assertEquals("the new update session remains usable", 1, newAction.get())
    }

    @Test
    fun pickerSelectionReordersByPackageKeyFiltersImmediatelyAndReturnsWithItsLiveList() {
        val preferences = compose.activity.getSharedPreferences("mrss_settings", 0)
        val preferenceKey = "notification_selected_apps"
        val priorSelection = preferences.getStringSet(preferenceKey, emptySet())!!.toSet()
        preferences.edit().putStringSet(preferenceKey, emptySet()).commit()
        val picker = openPicker()
        val priorApps = picker.apps
        val priorInclude = picker.includeSystem
        val priorSearch = picker.search
        try {
            val infos = priorApps.take(3).map { it.info }.toMutableList()
            while (infos.size < 3) infos += ApplicationInfo().apply { packageName = "motion.fixture.${infos.size}" }
            compose.runOnIdle {
                picker.search = ""
                picker.includeSystem = false
                picker.apps = infos.mapIndexed { index, info ->
                    AppPickerModel.App(info, listOf("Alpha", "Bravo", "Charlie")[index], system = false)
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("app_picker_list").performScrollToNode(hasTestTag("app_search"))
            val packageNames = picker.apps.map { it.info.packageName }
            assertEquals("the deterministic test set has three stable package keys", 3, packageNames.size)
            val movingPackage = packageNames[2]
            val rowTag = "picker_app_$movingPackage"
            compose.onNodeWithTag(rowTag).assertExists()
            val list = getPrivateLazyList(picker)
            val beforeScroll = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            val beforeTop = compose.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot.top

            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag(rowTag).performTouchInput { click() }
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            val middleTop = compose.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot.top
            captureComposePng("picker_selected_reorder_mid")
            compose.mainClock.advanceTimeBy(150)
            compose.waitForIdle()
            val finalTop = compose.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot.top
            assertTrue("selection sorting moves the keyed item upward", finalTop < beforeTop)
            assertTrue("the keyed item has a true intermediate placement", middleTop < beforeTop && middleTop > finalTop)
            assertEquals("selection reorder leaves list reading position intact", beforeScroll,
                list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)

            compose.onNodeWithTag("app_search").performTextReplacement("Bravo")
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals("the search query reaches the picker model in one Compose frame", "Bravo", picker.search)
            assertTrue("search results update immediately without waiting for item motion",
                mergedTagExists("picker_app_${packageNames[1]}"))
            assertFalse("nonmatching rows are removed immediately for search=${picker.search}", mergedTagExists(rowTag))

            compose.onNodeWithTag("app_search").performTextReplacement("")
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithTag(rowTag).performTouchInput { click() }
            compose.mainClock.advanceTimeBy(32)
            val currentList = getPrivateLazyList(picker)
            compose.onNodeWithTag("app_picker_back").performClick()
            assertEquals("back commits the picker parent route once", 1, route())
            compose.mainClock.advanceTimeBy(16)
            captureComposePng("picker_fast_return_mid")
            val controller = settingsController()
            val pickerField = SettingsController::class.java.getDeclaredField("picker").apply { isAccessible = true }
            assertNull("the live model is released on committed exit", pickerField.get(controller))
            val ui = SettingsController::class.java.getDeclaredField("composeUi").apply { isAccessible = true }
                .get(controller) as MiuixSettingsUi
            val snapshot = stateValue(ui, "pickerExitSnapshot") ?: error("the read-only picker snapshot remains during exit")
            val snapshotList = snapshot.javaClass.getDeclaredField("list").apply { isAccessible = true }.get(snapshot)
            assertTrue("the live and snapshot renderer keep the same LazyListState", currentList === snapshotList)
            assertEquals("the snapshot keeps the selected package set", picker.selected,
                snapshot.javaClass.getDeclaredField("selected").apply { isAccessible = true }.get(snapshot))
            assertTrue("the old icon executor is already shut down", executorShutdown(picker))
            compose.mainClock.advanceTimeBy(400)
            assertFalse("the read-only child is removed after its exit", mergedTagExists("navigation_motion_child"))
        } finally {
            compose.mainClock.autoAdvance = true
            preferences.edit().putStringSet(preferenceKey, priorSelection).commit()
            compose.runOnIdle {
                picker.apps = priorApps
                picker.includeSystem = priorInclude
                picker.search = priorSearch
            }
            leavePicker()
        }
    }

    @Test
    fun filteringImmediatelyPlacesAStillMovingKeyedRowAtItsFilteredSlot() {
        val preferences = compose.activity.getSharedPreferences("mrss_settings", 0)
        val preferenceKey = "notification_selected_apps"
        val priorSelection = preferences.getStringSet(preferenceKey, emptySet())!!.toSet()
        preferences.edit().putStringSet(preferenceKey, emptySet()).commit()
        val picker = openPicker()
        val priorApps = picker.apps
        val priorInclude = picker.includeSystem
        val priorSearch = picker.search
        try {
            val infos = priorApps.take(3).map { it.info }.toMutableList()
            while (infos.size < 3) infos += ApplicationInfo().apply { packageName = "motion.filter.fixture.${infos.size}" }
            compose.runOnIdle {
                picker.search = ""
                picker.includeSystem = false
                picker.apps = infos.mapIndexed { index, info ->
                    AppPickerModel.App(info, listOf("Alpha", "Bravo", "Charlie")[index], system = false)
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("app_picker_list").performScrollToNode(hasTestTag("app_search"))
            val packages = picker.apps.map { it.info.packageName }
            val retainedTag = "picker_app_${packages[1]}"
            val selectedTag = "picker_app_${packages[2]}"

            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("app_search").performTextReplacement("Bravo")
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals("the initial filtered fixture query reaches the model", "Bravo", picker.search)
            val filteredTargetTop = compose.onNodeWithTag(retainedTag).fetchSemanticsNode().boundsInRoot.top
            assertFalse("filtering removes nonmatching app rows in the same composition for search=${picker.search}",
                mergedTagExists(selectedTag))

            compose.onNodeWithTag("app_search").performTextReplacement("")
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(240)
            compose.waitForIdle()
            val beforeSelection = compose.onNodeWithTag(retainedTag).fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag(selectedTag).performTouchInput { click() }
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            val movingTop = compose.onNodeWithTag(retainedTag).fetchSemanticsNode().boundsInRoot.top
            assertTrue("the retained row is genuinely moving before filtering; baseline=$beforeSelection current=$movingTop",
                kotlin.math.abs(movingTop - beforeSelection) > 1f)

            compose.onNodeWithTag("app_search").performTextReplacement("a")
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals("same-count filtering still reaches the model", "a", picker.search)
            assertTrue("same-count filtering retains all keyed fixture rows",
                mergedTagExists(retainedTag) && mergedTagExists(selectedTag) && mergedTagExists("picker_app_${packages[0]}"))
            val sameCountFilterTop = compose.onNodeWithTag(selectedTag).fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            assertEquals("same-count filtering cancels rather than resumes the old placement",
                sameCountFilterTop, compose.onNodeWithTag(selectedTag).fetchSemanticsNode().boundsInRoot.top, .5f)

            val beforeSecondSelection = compose.onNodeWithTag(retainedTag).fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag(selectedTag).performTouchInput { click() }
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            val movingAgainTop = compose.onNodeWithTag(retainedTag).fetchSemanticsNode().boundsInRoot.top
            assertTrue("selection placement can resume after the same-count filter layout; " +
                "before=$beforeSecondSelection current=$movingAgainTop",
                kotlin.math.abs(movingAgainTop - beforeSecondSelection) > 1f)

            compose.onNodeWithTag("app_search").performTextReplacement("Bravo")
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals("the in-flight filter query reaches the model", "Bravo", picker.search)
            assertTrue("filtering retains exactly the matching keyed row", mergedTagExists(retainedTag))
            assertFalse("filtering removes the item driving the former reorder for search=${picker.search}",
                mergedTagExists(selectedTag))
            val immediatelyFilteredTop = compose.onNodeWithTag(retainedTag).fetchSemanticsNode().boundsInRoot.top
            assertEquals("the retained item occupies the filtered list slot immediately", filteredTargetTop, immediatelyFilteredTop, 1f)
            captureComposePng("picker_filter_during_reorder")

            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            val afterFilterTop = compose.onNodeWithTag(retainedTag).fetchSemanticsNode().boundsInRoot.top
            assertEquals("search does not inherit or wait for the old selection-placement animation",
                immediatelyFilteredTop, afterFilterTop, .5f)

            compose.onNodeWithTag("app_search").performTextReplacement("")
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val selectedTopBeforeNextMove = compose.onNodeWithTag(selectedTag).fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            assertEquals("clearing search stays immediate before a later selection", selectedTopBeforeNextMove,
                compose.onNodeWithTag(selectedTag).fetchSemanticsNode().boundsInRoot.top, .5f)

            compose.onNodeWithTag(selectedTag).performTouchInput { click() }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val selectedTopFirstFrame = compose.onNodeWithTag(selectedTag).fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            val selectedTopMidpoint = compose.onNodeWithTag(selectedTag).fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.advanceTimeBy(160)
            compose.waitForIdle()
            val selectedTopSettled = compose.onNodeWithTag(selectedTag).fetchSemanticsNode().boundsInRoot.top
            val totalMove = selectedTopSettled - selectedTopBeforeNextMove
            val moveDirection = kotlin.math.sign(totalMove)
            val firstFrameProgress = (selectedTopFirstFrame - selectedTopBeforeNextMove) * moveDirection
            val midpointProgress = (selectedTopMidpoint - selectedTopBeforeNextMove) * moveDirection
            val totalDistance = kotlin.math.abs(totalMove)
            instrumentation.addResults(Bundle().apply {
                putString("picker_post_filter_selection_probe",
                    "before=$selectedTopBeforeNextMove; first=$selectedTopFirstFrame; " +
                        "mid=$selectedTopMidpoint; settled=$selectedTopSettled")
            })
            assertTrue("selection restores a continuous keyed placement from its retained coordinate; " +
                "before=$selectedTopBeforeNextMove first=$selectedTopFirstFrame mid=$selectedTopMidpoint " +
                "settled=$selectedTopSettled", totalDistance > 1f &&
                firstFrameProgress < totalDistance * .75f &&
                midpointProgress > firstFrameProgress && totalDistance > midpointProgress)
        } finally {
            compose.mainClock.autoAdvance = true
            preferences.edit().putStringSet(preferenceKey, priorSelection).commit()
            compose.runOnIdle {
                picker.apps = priorApps
                picker.includeSystem = priorInclude
                picker.search = priorSearch
            }
            leavePicker()
        }
    }

    private fun openPicker(): AppPickerModel {
        compose.onNodeWithContentDescription(NativeStrings.get(compose.activity, "notifications_title")).performClick()
        compose.waitUntil(10_000) { route() == 1 && mergedTagExists("notifications_list") }
        compose.onNodeWithTag("notification_apps_entry").performClick()
        compose.waitUntil(10_000) { route() == 100 && mergedTagExists("app_picker_list") }
        compose.waitUntil(10_000) { pickerModel()?.let { !it.loading } == true }
        return pickerModel() ?: error("The app picker model was not created")
    }

    private fun leavePicker() {
        if (route() != 100) return
        runCatching { compose.onNodeWithTag("app_picker_back").performClick() }
        runCatching { compose.waitUntil(5_000) { route() != 100 } }
    }

    private fun mergedTagExists(tag: String): Boolean = tagExistsInTree(tag, useUnmergedTree = false)

    private fun unmergedTagExists(tag: String): Boolean = tagExistsInTree(tag, useUnmergedTree = true)

    private fun tagExistsInTree(tag: String, useUnmergedTree: Boolean): Boolean = runCatching {
        compose.onNodeWithTag(tag, useUnmergedTree = useUnmergedTree).assertExists()
        true
    }.getOrDefault(false)

    /** Release the original press through its stable Compose window root, not an exiting child node. */
    private fun releaseHeldPressAtWindowRoot(contentTag: String, pointInWindowRoot: Offset) {
        val dialogRoot = isRoot() and hasAnyDescendant(hasTestTag(contentTag))
        compose.onNode(dialogRoot, useUnmergedTree = true).performTouchInput {
            moveTo(pointInWindowRoot)
            up()
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(10_000) { mergedTagExists(tag) }
    }

    private fun showUpdate(ui: UpdateDialogUi, title: String, current: String, action: Runnable, progress: Int = -1) {
        ui.update(title, current, "Checking", "Details", false, progress, false,
            emptyArray(), emptyArray(), emptyArray(), "Added", "Improved", "Fixed", "No notes",
            arrayOf("Continue"), arrayOf(action))
    }

    private fun privateValue(owner: Any, fieldName: String): Any? = owner.javaClass.getDeclaredField(fieldName)
        .apply { isAccessible = true }.get(owner)

    private fun stateValue(owner: Any, fieldName: String): Any? {
        val field = owner.javaClass.getDeclaredField("${fieldName}\$delegate").apply { isAccessible = true }
        return (field.get(owner) as androidx.compose.runtime.MutableState<*>).value
    }

    private fun getPrivateLazyList(model: AppPickerModel): androidx.compose.foundation.lazy.LazyListState =
        AppPickerModel::class.java.getDeclaredField("list").apply { isAccessible = true }.get(model) as androidx.compose.foundation.lazy.LazyListState

    private fun executorShutdown(model: AppPickerModel): Boolean {
        val executor = AppPickerModel::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(model)
        return (executor as java.util.concurrent.ExecutorService).isShutdown
    }

    private fun captureComposePng(label: String) {
        compose.waitForIdle()
        val mainSettingsRoot = isRoot() and hasAnyDescendant(hasTestTag("main_pager"))
        val bitmap = compose.onNode(mainSettingsRoot, useUnmergedTree = true).captureToImage().asAndroidBitmap()
        try { addPngResult(label, bitmap) } finally { bitmap.recycle() }
    }

    private fun captureWindowPng(label: String) {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: error("UiAutomation.takeScreenshot returned no frame for $label")
        try { addPngResult(label, bitmap) } finally { bitmap.recycle() }
    }

    private fun addPngResult(label: String, bitmap: Bitmap) {
        val scaled = if (bitmap.width > 480)
            Bitmap.createScaledBitmap(bitmap, 480, (bitmap.height * 480f / bitmap.width).toInt(), true)
        else bitmap
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            instrumentation.addResults(Bundle().apply {
                putString("shared_interaction_png_$label", Base64.encodeToString(bytes, Base64.NO_WRAP))
                putString("shared_interaction_frame_$label", "${bitmap.width}x${bitmap.height}; displayed=${scaled.width}x${scaled.height}")
            })
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun colorDistance(first: Int, second: Int): Int = maxOf(
        kotlin.math.abs(((first shr 16) and 0xff) - ((second shr 16) and 0xff)),
        kotlin.math.abs(((first shr 8) and 0xff) - ((second shr 8) and 0xff)),
        kotlin.math.abs((first and 0xff) - (second and 0xff)),
    )

    private fun tapMainPager(point: Offset) {
        val pager = compose.onNodeWithTag("main_pager", useUnmergedTree = true)
        val bounds = pager.fetchSemanticsNode().boundsInRoot
        pager.performTouchInput {
            click(Offset(point.x - bounds.left, point.y - bounds.top))
        }
    }

    private fun runDeviceShell(command: String) {
        val pipe = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
    }
}
