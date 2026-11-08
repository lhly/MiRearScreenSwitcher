package top.zainzh.rearscreen

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationStructureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun s(key: String) = NativeStrings.get(compose.activity, key)

    @Test fun fourFixedTabsNavigateToTheirOwnContentAndRestore() {
        val tabs = listOf("screen_title", "notifications_title", "nav_modules", "nav_settings")
        tabs.forEach { compose.onNodeWithContentDescription(s(it)).assertExists() }
        compose.onNodeWithContentDescription(s("nav_modules")).performClick()
        compose.onNodeWithTag("modules_tab").assertExists()
        compose.onNodeWithContentDescription(s("nav_settings")).performClick()
        compose.onNodeWithTag("settings_list").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription(s("nav_settings")).assertIsSelected()
        compose.onNodeWithTag("settings_list").assertExists()
    }

    @Test fun backscreenAndNotificationEntriesOpenIndependentSecondaryPages() {
        compose.onNodeWithTag("wake_settings_entry").performClick()
        compose.onNodeWithTag("wake_settings_back").assertExists().performClick()
        compose.onNodeWithContentDescription(s("notifications_title")).performClick()
        compose.onNodeWithText(s("privacy_section")).performClick()
        compose.onNodeWithTag("notification_privacy_back").assertExists().performClick()
        compose.onNodeWithText(s("notification_rules")).performClick()
        compose.onNodeWithTag("notification_rules_back").assertExists()
    }

    @Test fun legacySavedRoutesMigrateWithoutConfusingNewTabs() {
        val field = MainActivity::class.java.getDeclaredField("nativeUi").apply { isAccessible = true }
        val initial = field.get(compose.activity) as SettingsController
        compose.runOnIdle { initial.destroy() }
        val oldSettings = android.os.Bundle().apply { putInt("native_page", 2) }
        val settings = SettingsController(compose.activity, oldSettings, ReleaseUpdateChecker {
            ReleaseUpdateChecker.Response(404, "")
        })
        compose.runOnIdle {
            field.set(compose.activity, settings)
            compose.activity.setContentView(settings.getView())
        }
        compose.onNodeWithContentDescription(s("nav_settings")).assertIsSelected()
        val newState = android.os.Bundle().apply { putInt("native_route_version", 2); putInt("native_page", 2) }
        compose.runOnIdle { settings.destroy() }
        val modules = SettingsController(compose.activity, newState, ReleaseUpdateChecker {
            ReleaseUpdateChecker.Response(404, "")
        })
        compose.runOnIdle {
            field.set(compose.activity, modules)
            compose.activity.setContentView(modules.getView())
        }
        compose.onNodeWithContentDescription(s("nav_modules")).assertIsSelected()
    }

}
