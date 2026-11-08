package top.zainzh.rearscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.modules.ModuleDescriptor

@RunWith(AndroidJUnit4::class)
class NativeModuleRendererTest {
    @get:Rule val compose = createAndroidComposeRule<NativeModuleRendererTestHostActivity>()

    @Test fun rowEditorsAreReachableAndActionEnabledConditionsAreEnforced() {
        val descriptor = ModuleDescriptor("renderer_test", 1, "1", "stage_timer",
            JSONObject().put("presets", JSONArray()))
        val nodes = JSONArray()
            .put(JSONObject().put("id", "row").put("type", "row").put("children", JSONArray()
                .put(JSONObject().put("id", "title").put("type", "textInput")
                    .put("text", "Title").put("bind", "config.title"))
                .put(JSONObject().put("id", "rounds").put("type", "number")
                    .put("text", "Rounds").put("bind", "config.rounds"))))
            .put(JSONObject().put("id", "start").put("type", "button")
                .put("text", "Start").put("action", "start"))
            .put(JSONObject().put("id", "pause").put("type", "button")
                .put("text", "Pause").put("action", "pause"))
            .put(JSONObject().put("id", "stop").put("type", "button")
                .put("text", "Stop").put("action", "stop").put("enabledWhen", "active"))
        val config = JSONObject().put("title", "Focus").put("rounds", 2)
            .put("keepVisible", false).put("stages", JSONArray())
        val state = JSONObject().put("enabled", true).put("idle", true).put("active", false)
        var pauseCount = 0

        compose.setContent {
            AppTheme {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NativeModuleNodes(nodes, descriptor, config, state, false, { },
                        { if (it == "pause") pauseCount++ }, actionsEnabled = false)
                }
            }
        }

        compose.onNodeWithTag("module_node_title").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("module_node_rounds").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("module_node_start").assertIsNotEnabled()
        compose.onNodeWithTag("module_node_stop").assertIsNotEnabled()
        compose.onNodeWithTag("module_node_pause").assertIsEnabled()
            .performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, pauseCount) }
    }
}
