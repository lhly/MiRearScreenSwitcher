package top.zainzh.rearscreen.platform.mods

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import top.zainzh.rearscreen.AppTheme
import top.zainzh.rearscreen.NativeModuleRendererTestHostActivity

@RunWith(AndroidJUnit4::class)
class ModNativeGeneralUiTest {
    @get:Rule val compose = createAndroidComposeRule<NativeModuleRendererTestHostActivity>()

    @Test fun listConditionFormattingDeadlineAndRegisteredActionUseNativeRenderer() {
        val base = InstrumentationRegistry.getInstrumentation().context.assets.open("platform/mods/generic-counter.zip").use { ModPackageReader.read(it) }
        val scene = JSONObject("""
            {"scene":"rear","root":{"type":"layout.column","horizontalAlignment":"center","gap":8,"children":[
              {"type":"ui.icon","name":"trophy","label":"本场","tone":"success"},
              {"type":"ui.text","value":{"bind":"state.percent"},"format":"percent","tone":"primary"},
              {"type":"ui.list","items":{"bind":"state.history"},"maxItems":2,"visibleWhen":{"bind":"state.visible","equals":true}},
              {"type":"ui.text","value":"不显示","visibleWhen":{"bind":"state.visible","equals":false}},
              {"type":"ui.deadlineText","value":{"bind":"state.deadline"}},
              {"type":"ui.button","label":"开始","onPress":{"type":"registeredAction","ref":{"bind":"config.actionRef"}}}
            ]}}
        """.trimIndent())
        val pkg = base.copy(rearScene = scene)
        val actionId = "a".repeat(48)
        val config = JSONObject().put("actionRef", JSONObject().put("id", actionId).put("version", 1))
        val state = JSONObject().put("percent", 72).put("visible", true)
            .put("history", JSONArray().put("A +1").put("B +2").put("A +3"))
            .put("deadline", android.os.SystemClock.elapsedRealtime() + 60_000)
        var clicked: top.zainzh.rearscreen.platform.actions.RegisteredActionRef? = null
        compose.setContent {
            AppTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ModNativeScene(pkg, scene, state, config, JSONObject(), rear = true,
                    onEvent = {}, onSaveConfig = { _, done -> done(true, null) },
                    onRegisteredAction = { clicked = it })
            } }
        }
        compose.waitForIdle()
        captureRequestedScreen()
        compose.onNodeWithText("72%").assertIsDisplayed()
        compose.onNodeWithText("A +1").assertIsDisplayed()
        compose.onNodeWithText("B +2").assertIsDisplayed()
        compose.onAllNodesWithText("A +3").assertCountEquals(0)
        compose.onAllNodesWithText("不显示").assertCountEquals(0)
        val countdown = compose.onAllNodesWithText(":", substring = true).fetchSemanticsNodes().single()
        val countdownValue = countdown.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(countdownValue.matches(Regex("\\d+:\\d{2}")))
        compose.onNodeWithText("开始").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(actionId, clicked?.id); assertEquals(1, clicked?.version) }
    }

    @Test fun settingsActionPickerIsHostCallbackAndStagesSelectedOpaqueRef() {
        val base = InstrumentationRegistry.getInstrumentation().context.assets.open("platform/mods/generic-counter.zip").use { ModPackageReader.read(it) }
        val scene = JSONObject("""{"scene":"settings","root":{"type":"layout.column","children":[
          {"type":"settings.action","label":"开始动作","value":{"bind":"config.actionRef"}},
          {"type":"settings.save"}]}}""")
        val refSchema = JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string").put("maxLength", 48).put("pattern", "^[a-f0-9]{48}$"))
                .put("version", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 1).put("const", 1)))
            .put("required", JSONArray().put("id").put("version")).put("additionalProperties", false)
        val configSchema = JSONObject().put("type", "object").put("properties", JSONObject().put("actionRef", refSchema))
            .put("required", JSONArray()).put("additionalProperties", false)
        val pkg = base.copy(settingsScene = scene, configSchema = configSchema)
        var pickerBinding = ""
        val selectedActions = mutableStateMapOf<String, top.zainzh.rearscreen.platform.actions.RegisteredActionRef>()
        var saved: JSONObject? = null
        var recreateRenderer: (() -> Unit)? = null
        compose.setContent {
            var renderEpoch by remember { mutableIntStateOf(0) }
            recreateRenderer = { renderEpoch += 1 }
            AppTheme { key(renderEpoch) {
                ModNativeScene(pkg, scene, JSONObject(), JSONObject(), JSONObject(), rear = false,
                    onEvent = {}, registeredActionDrafts = selectedActions,
                    onSelectRegisteredAction = { binding, _ -> pickerBinding = binding; selectedActions[binding] = top.zainzh.rearscreen.platform.actions.RegisteredActionRef("a".repeat(48)) },
                    onClearRegisteredActionDrafts = { selectedActions.clear() },
                    onSaveConfig = { config, done -> saved = config; done(true, null) })
            } }
        }
        compose.onNodeWithText("开始动作").assertHasClickAction().performClick()
        compose.runOnIdle { recreateRenderer?.invoke() } // Recreate the renderer with only the caller-restored draft map.
        compose.waitForIdle()
        compose.onNodeWithText("已选择：${"a".repeat(48)}").assertIsDisplayed()
        compose.onNodeWithText("保存设置").performClick()
        compose.runOnIdle { assertEquals("config.actionRef", pickerBinding) }
        compose.runOnIdle { assertEquals("a".repeat(48), saved?.getJSONObject("actionRef")?.getString("id")); assertTrue(selectedActions.isEmpty()) }
    }

    @Test fun unselectedConfigActionIsDisabledWithSettingsHint() {
        val base = InstrumentationRegistry.getInstrumentation().context.assets.open("platform/mods/generic-counter.zip").use { ModPackageReader.read(it) }
        val scene = JSONObject("""{"scene":"rear","root":{"type":"layout.column","children":[
          {"type":"ui.button","label":"开始","onPress":{"type":"registeredAction","ref":{"bind":"config.actionRef"}}}]}}""")
        compose.setContent {
            AppTheme { ModNativeScene(base.copy(rearScene = scene), scene, JSONObject(), JSONObject(), JSONObject(), rear = true,
                onEvent = {}, onSaveConfig = { _, done -> done(true, null) }) }
        }
        compose.onNodeWithText("开始").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("请先在设置中选择已登记动作").assertIsDisplayed()
    }

    private fun captureRequestedScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = InstrumentationRegistry.getArguments().getString("modUiScreenshot") ?: return
        require(name.matches(Regex("^[a-z0-9-]{1,48}$")))
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("mod-ui-evidence"))
        require(directory.exists() || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { output ->
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
