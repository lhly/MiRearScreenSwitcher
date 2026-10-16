package com.tgwgroup.MiRearScreenSwitcher

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class HomeComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun s(key: String) = NativeStrings.get(compose.activity, key)
    private fun switch(key: String) = compose.onNode(hasText(s(key)) and isToggleable())
    private fun tab(key: String) = compose.onNodeWithContentDescription(s(key))
    private fun swipe(left: Boolean) {
        compose.onNodeWithTag("main_pager").performTouchInput {
            if (left) swipeLeft(startX = width * .7f, endX = width * .2f)
            else swipeRight(startX = width * .2f, endX = width * .7f)
        }
        compose.waitForIdle()
    }

    @Test fun swipeVisitsAllTabsAndStopsAtEdges() {
        swipe(true); tab("notifications_title").assertIsSelected()
        swipe(true); tab("about").assertIsSelected()
        swipe(true); tab("about").assertIsSelected()
        swipe(false); tab("notifications_title").assertIsSelected()
        swipe(false); tab("screen_title").assertIsSelected()
        swipe(false); tab("screen_title").assertIsSelected()
    }

    @Test fun clickingTabsMovesThroughIntermediateFramesAndCanReverse() {
        compose.mainClock.autoAdvance = false
        try {
            tab("about").performClick()
            compose.mainClock.advanceTimeBy(80)
            val range = compose.onNodeWithTag("main_pager").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue("Tab clicks must move through intermediate positions", range.value() > 0f && range.value() < range.maxValue())
            tab("screen_title").performClick()
            compose.mainClock.advanceTimeBy(400)
            tab("screen_title").assertIsSelected()
            tab("about").performClick(); compose.mainClock.advanceTimeBy(64)
            tab("notifications_title").performClick(); compose.mainClock.advanceTimeBy(64)
            tab("screen_title").performClick(); compose.mainClock.advanceTimeBy(400)
            tab("screen_title").assertIsSelected()
            tab("about").performClick(); compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("main_pager").performTouchInput {
                down(Offset(width * .5f, 10f)); advanceEventTime(80); up()
            }
            compose.mainClock.advanceTimeBy(400)
            tab("screen_title").assertIsSelected()
            compose.runOnIdle {
                val saved = android.os.Bundle()
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(compose.activity, saved)
                assertEquals("Interrupted animation must save the actual visible page", 0, saved.getInt("native_page"))
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun nativePageScrollSurvivesSwipesAndActivityRecreation() {
        fun scroll(view: View): ScrollView? {
            if (view is ScrollView && view.tag == "native_scroll_1") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) scroll(view.getChildAt(i))?.let { return it }
            return null
        }
        tab("notifications_title").performClick()
        var position = 0
        compose.runOnIdle {
            val native = checkNotNull(scroll(compose.activity.window.decorView))
            native.scrollTo(0, 400); position = native.scrollY; assertTrue(position > 0)
        }
        swipe(true); tab("about").assertIsSelected()
        swipe(false); tab("notifications_title").assertIsSelected()
        compose.runOnIdle { assertEquals(position, checkNotNull(scroll(compose.activity.window.decorView)).scrollY) }
        compose.activityRule.scenario.recreate()
        tab("notifications_title").assertIsSelected()
        compose.runOnIdle { assertEquals(position, checkNotNull(scroll(compose.activity.window.decorView)).scrollY) }
    }

    @Test fun systemDisabledAnimationsStillChangesTabs() {
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val previous = Settings.Global.getFloat(compose.activity.contentResolver, key, 1f)
        fun scale(value: Float) {
            val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("settings put global $key $value")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
        }
        try {
            scale(0f)
            tab("about").performClick(); tab("about").assertIsSelected()
            tab("screen_title").performClick(); tab("screen_title").assertIsSelected()
        } finally { scale(previous) }
    }

    private fun scrollSwitch(key: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("home_list").performScrollToNode(hasText(s(key)))
        return aboveNavigation(switch(key))
    }

    private fun aboveNavigation(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        val navTop = compose.onNodeWithContentDescription(s("screen_title")).fetchSemanticsNode().boundsInRoot.top
        val bottom = node.fetchSemanticsNode().boundsInRoot.bottom
        if (bottom > navTop - 16f) compose.onNodeWithTag("home_list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, bottom - navTop + 16f) }
        return node
    }

    @Test fun homeSwitchPersistsAndRestoresWithActivity() {
        val prefs = NativePreferences(compose.activity)
        val before = prefs.getBoolean("proximity_sensor_enabled")
        try {
            scrollSwitch("proximity_sensor").assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(!before, prefs.getBoolean("proximity_sensor_enabled"))
                assertEquals(!before, compose.activity.getSharedPreferences("FlutterSharedPreferences", 0).getBoolean("flutter.proximity_sensor_enabled", before))
            }
            compose.activityRule.scenario.recreate()
            scrollSwitch("proximity_sensor").let { if (before) it.assertIsOff() else it.assertIsOn() }
            switch("proximity_sensor").performClick()
            compose.runOnIdle { assertEquals(before, prefs.getBoolean("proximity_sensor_enabled")) }
        } finally { compose.runOnIdle { prefs.putBoolean("proximity_sensor_enabled", before) } }
    }

    @Test fun wakeModesStayExclusiveAndChargingControlsRemainAvailable() {
        val prefs = NativePreferences(compose.activity)
        val oldKeep = prefs.getBoolean("keep_screen_on_enabled")
        val oldAlways = prefs.getBoolean("always_wakeup_enabled")
        try {
            if (oldAlways) scrollSwitch("always_wake_up").performClick()
            if (!prefs.getBoolean("keep_screen_on_enabled")) scrollSwitch("keep_screen_on").performClick()
            scrollSwitch("always_wake_up").performClick()
            scrollSwitch("always_wake_up").assertIsOn()
            scrollSwitch("keep_screen_on").assertIsOff()
            compose.runOnIdle { assertFalse(prefs.getBoolean("keep_screen_on_enabled")); assertTrue(prefs.getBoolean("always_wakeup_enabled")) }
            scrollSwitch("keep_screen_on").performClick()
            switch("keep_screen_on").assertIsOn()
            scrollSwitch("always_wake_up").assertIsOff()
            scrollSwitch("charging_animation").assertIsEnabled()
            scrollSwitch("charging_always_on").assertIsEnabled()
        } finally {
            compose.runOnIdle {
                prefs.putBoolean("keep_screen_on_enabled", oldKeep); prefs.putBoolean("always_wakeup_enabled", oldAlways)
                compose.activity.runAction("setKeepScreenOnEnabled", mapOf("enabled" to oldKeep), null)
                compose.activity.runAction("setAlwaysWakeUpEnabled", mapOf("enabled" to oldAlways), null)
            }
        }
    }

    @Test fun homeScrollSurvivesRecreationAndNavigation() {
        scrollSwitch("charging_always_on").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        switch("charging_always_on").assertIsDisplayed()
        compose.onNodeWithContentDescription(s("notifications_title")).performClick()
        compose.onNodeWithContentDescription(s("screen_title")).performClick()
        switch("charging_always_on").assertIsDisplayed()
    }

    @Test fun unavailableShizukuDoesNotCommitRotation() {
        org.junit.Assume.assumeFalse(rikka.shizuku.Shizuku.pingBinder())
        val ninety = compose.onNodeWithContentDescription(s("rotation_caption") + " 90°")
        ninety.performScrollTo()
        aboveNavigation(ninety).performClick()
        compose.waitUntil(5000) { !ninety.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        ninety.assertIsNotSelected()
        compose.onNodeWithContentDescription(s("rotation_caption") + " 0°").assertIsSelected()
    }
}
