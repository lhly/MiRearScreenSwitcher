package top.zainzh.rearscreen.platform.mods

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.AppTheme
import top.zainzh.rearscreen.NativeModuleRendererTestHostActivity
import java.io.ByteArrayInputStream

/** Generic two-column Row layout regression at the small landscape preview size. */
@RunWith(AndroidJUnit4::class)
class ModNativeRowLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<NativeModuleRendererTestHostActivity>()

    @Test fun nestedColumnsAndButtonsStayBoundedAndReachableAt347By212Dp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.context.assets.open("platform/mods/generic-counter.zip").use { ModPackageReader.read(it) }
        val scene = JSONObject("""
            {"scene":"rear","root":{"type":"layout.row","gap":8,"children":[
              {"type":"layout.column","gap":2,"children":[
                {"type":"ui.text","value":"Left","style":"caption"},
                {"type":"ui.number","value":{"bind":"state.left"},"style":"displayNumber"},
                {"type":"layout.row","gap":4,"children":[
                  {"type":"ui.button","label":"Left +1","onPress":{"type":"event","name":"left.increment"}},
                  {"type":"ui.button","label":"Left -1","onPress":{"type":"event","name":"left.decrement"}}]}]},
              {"type":"layout.column","gap":2,"children":[
                {"type":"ui.text","value":"Right","style":"caption"},
                {"type":"ui.number","value":{"bind":"state.right"},"style":"displayNumber"},
                {"type":"layout.row","gap":4,"children":[
                  {"type":"ui.button","label":"Right +1","onPress":{"type":"event","name":"right.increment"}},
                  {"type":"ui.button","label":"Right -1","onPress":{"type":"event","name":"right.decrement"}}]}]}
            ]}}
        """.trimIndent())
        val pkg = base.copy(rearScene = scene)
        val state = JSONObject().put("left", 11).put("right", 8)
        val events = mutableListOf<String>()

        compose.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(347.dp).height(212.dp).testTag("row_viewport")) {
                        ModNativeScene(pkg, scene, state, JSONObject(), JSONObject(), rear = true,
                            onEvent = { events += it }, onSaveConfig = { _, complete -> complete(true, null) })
                    }
                }
            }
        }

        compose.onNodeWithTag("row_viewport").assertIsDisplayed()
        compose.onNodeWithText("Left").assertIsDisplayed()
        compose.onNodeWithText("11").assertIsDisplayed()
        compose.onNodeWithText("Left +1").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("Left -1").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("Right").assertIsDisplayed()
        compose.onNodeWithText("8").assertIsDisplayed()
        compose.onNodeWithText("Right +1").assertHasClickAction().performClick()
        compose.onNodeWithText("Right -1").assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(listOf("left.increment", "left.decrement", "right.increment", "right.decrement"), events)
        }
    }
}
