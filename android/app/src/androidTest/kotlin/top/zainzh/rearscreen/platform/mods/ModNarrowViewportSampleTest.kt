package top.zainzh.rearscreen.platform.mods

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.AppTheme
import top.zainzh.rearscreen.NativeModuleRendererTestHostActivity
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Renderer-only layout/event checks; the exported frames are for root's manual final glyph review. */
@RunWith(AndroidJUnit4::class)
class ModNarrowViewportSampleTest {
    @get:Rule val compose = createAndroidComposeRule<NativeModuleRendererTestHostActivity>()

    private data class LocalMode(val densityFontScale: Float, val configurationFontScale: Float, val nightMode: Int)

    @Test fun scoreboardLayoutAndEventContractAcrossThemesAndFontScales() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.context.assets.open("platform/mods/remrss-scoreboard-v4.zip")
            .use { ModPackageReader.read(it) }
        assertTrue("fixture must be the frozen scoreboard v4", pkg.id == "remrss-scoreboard" && pkg.version == "4.0.0")
        val scene = requireNotNull(pkg.rearScene)
        val state = JSONObject().put("scoreA", 2).put("scoreB", 0).put("winner", "none")
            .put("history", JSONArray())
        val config = JSONObject().put("teamA", "红队").put("teamB", "蓝队")
            .put("targetScore", 11).put("winBy", 2)
        val events = mutableListOf<String>()
        val violations = mutableListOf<String>()
        val evidence = linkedMapOf<String, String>()
        val geometry = mutableListOf<String>()
        val localModes = mutableMapOf<String, LocalMode>()
        val backgroundSamples = mutableMapOf<String, Int>()
        var setViewportMode: ((Float, Boolean) -> Unit)? = null

        compose.setContent {
            val baseDensity = LocalDensity.current
            val baseConfiguration = LocalConfiguration.current
            val baseContext = LocalContext.current
            var requestedScale by remember { mutableFloatStateOf(1f) }
            var requestedDark by remember { mutableStateOf(false) }
            setViewportMode = { scale, dark -> requestedScale = scale; requestedDark = dark }
            val simulatedConfiguration = remember(baseConfiguration, requestedScale, requestedDark) {
                Configuration(baseConfiguration).apply {
                    this.fontScale = requestedScale
                    val nightMode = if (requestedDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                }
            }
            val simulatedContext = remember(baseContext, simulatedConfiguration) {
                baseContext.createConfigurationContext(simulatedConfiguration)
            }
            CompositionLocalProvider(
                LocalConfiguration provides simulatedConfiguration,
                LocalContext provides simulatedContext,
                LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density, requestedScale),
            ) {
                AppTheme {
                    val activeConfig = LocalConfiguration.current
                    val activeDensity = LocalDensity.current
                    val modeKey = modeKey(requestedDark, requestedScale)
                    SideEffect {
                        localModes[modeKey] = LocalMode(activeDensity.fontScale, activeConfig.fontScale,
                            activeConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(210.dp).height(180.dp).testTag("scoreboard_viewport")) {
                            ModNativeScene(pkg, scene, state, config, JSONObject(), rear = true,
                                onEvent = { events += it },
                                onSaveConfig = { _, complete -> complete(true, null) })
                        }
                    }
                }
            }
        }

        val density = compose.activity.resources.displayMetrics.density
        val inner = 16f
        listOf(
            Triple("Light", 1f, false), Triple("Light", 1.3f, false),
            Triple("Dark", 1f, true), Triple("Dark", 1.3f, true),
        ).forEach { (theme, scale, dark) ->
            val key = modeKey(dark, scale)
            compose.runOnIdle { setViewportMode?.invoke(scale, dark) }
            compose.waitForIdle()

            // Capture each synthetic frame before any assertion, so a failing layout still leaves evidence.
            val png = runCatching {
                compose.onNodeWithTag("scoreboard_viewport").captureToImage().asAndroidBitmap().let { bitmap ->
                    backgroundSamples[key] = bitmap.getPixel(1, 1)
                    ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG compression failed" }
                        output.toByteArray()
                    }
                }
            }.onFailure { violations += "$theme ${scale}x frame capture failed: ${it.message}" }.getOrNull()
            if (png != null) evidence["scoreboard${theme}Font${scaleKey(scale)}Png"] = Base64.encodeToString(png, Base64.NO_WRAP)

            val localMode = localModes[key]
            if (localMode == null) {
                violations += "$theme ${scale}x CompositionLocal mode was not observed"
            } else {
                if (kotlin.math.abs(localMode.densityFontScale - scale) > .001f ||
                    kotlin.math.abs(localMode.configurationFontScale - scale) > .001f) {
                    violations += "$theme ${scale}x fontScale mismatch: density=${localMode.densityFontScale}, configuration=${localMode.configurationFontScale}"
                }
                val expectedNight = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                if (localMode.nightMode != expectedNight) violations += "$theme ${scale}x night mode mismatch: ${localMode.nightMode}"
            }

            val frameBounds = runCatching {
                compose.onNodeWithTag("scoreboard_viewport").let { it.getUnclippedBoundsInRoot() to it.getBoundsInRoot() }
            }.getOrNull()
            if (frameBounds == null) {
                violations += "$theme ${scale}x frame bounds unavailable"
            } else {
                val (frameFull, frameClipped) = frameBounds
                val frameWidth = frameFull.right.value - frameFull.left.value
                val frameHeight = frameFull.bottom.value - frameFull.top.value
                if (kotlin.math.abs(frameWidth - 210f) > .75f) violations += "$theme ${scale}x outer frame width=${frameWidth}dp, expected 210dp"
                if (kotlin.math.abs(frameHeight - 180f) > .75f) violations += "$theme ${scale}x outer frame height=${frameHeight}dp, expected 180dp"
                if (!sameRect(frameFull, frameClipped)) violations += "$theme ${scale}x frame is clipped: full=$frameFull clipped=$frameClipped"
                geometry += "$theme ${scale}x frame=$frameFull clipped=$frameClipped; Node safe content=178x148dp"

                fun inspect(text: String, occurrence: Int = 0, button: Boolean = false) {
                    val mergedNodes = compose.onAllNodesWithText(text).fetchSemanticsNodes()
                    val expectedCount = if (text == "−" || text == "+") 2 else 1
                    if (mergedNodes.size != expectedCount) {
                        violations += "$theme ${scale}x '$text' semantic count=${mergedNodes.size}, expected=$expectedCount"
                        return
                    }
                    val merged = if (button) compose.onAllNodesWithText(text)[occurrence] else null
                    val textNode = compose.onAllNodesWithText(text, useUnmergedTree = true).let { nodes ->
                        val textMatches = nodes.fetchSemanticsNodes()
                        if (textMatches.size != expectedCount) {
                            violations += "$theme ${scale}x unmerged '$text' count=${textMatches.size}, expected=$expectedCount"
                            return
                        }
                        nodes[occurrence]
                    }
                    runCatching { (merged ?: textNode).assertIsDisplayed() }
                        .onFailure { violations += "$theme ${scale}x '$text' is not displayed: ${it.message}" }
                    if (button) runCatching { requireNotNull(merged).assertHasClickAction() }
                        .onFailure { violations += "$theme ${scale}x '$text' has no click action: ${it.message}" }

                    val layoutBounds = runCatching { (merged ?: textNode).getUnclippedBoundsInRoot() }.getOrNull()
                    val clippedBounds = runCatching { (merged ?: textNode).getBoundsInRoot() }.getOrNull()
                    if (layoutBounds == null || clippedBounds == null) {
                        violations += "$theme ${scale}x '$text' bounds unavailable"
                    } else {
                        val left = layoutBounds.left.value
                        val top = layoutBounds.top.value
                        val right = layoutBounds.right.value
                        val bottom = layoutBounds.bottom.value
                        val within = left >= frameFull.left.value + inner - .5f && top >= frameFull.top.value + inner - .5f &&
                            right <= frameFull.right.value - inner + .5f && bottom <= frameFull.bottom.value - inner + .5f
                        if (!within) violations += "$theme ${scale}x '$text' full bounds leave Node safe frame: $layoutBounds"
                        if (!sameRect(layoutBounds, clippedBounds)) violations += "$theme ${scale}x '$text' clipping mismatch: full=$layoutBounds clipped=$clippedBounds"
                        val width = right - left
                        val height = bottom - top
                        if (width <= 0f || height < if (button) 48f else 1f) {
                            violations += "$theme ${scale}x '$text' bounds have unusable size ${width}x${height}dp"
                        }
                        geometry += "$theme ${scale}x '$text' bounds=$layoutBounds clipped=$clippedBounds"
                    }

                    val textMetrics = runCatching { readTextLayout(textNode) }.getOrNull()
                    if (textMetrics == null) {
                        violations += "$theme ${scale}x '$text' TextLayoutResult unavailable"
                    } else {
                        textMetrics.problems.forEach { violations += "$theme ${scale}x '$text' $it" }
                        geometry += "$theme ${scale}x '$text' text ${textMetrics.diagnostic}"
                    }
                }

                inspect("红队")
                inspect("2")
                inspect("蓝队")
                inspect("0")
                inspect("−", occurrence = 0, button = true)
                inspect("−", occurrence = 1, button = true)
                inspect("+", occurrence = 0, button = true)
                inspect("+", occurrence = 1, button = true)
                inspect("撤销", button = true)

                val expectedEvents = listOf("score.a.minus", "score.a.plus", "score.b.minus", "score.b.plus", "game.undo")
                val buttons = listOf(
                    "−" to 0, "+" to 0, "−" to 1, "+" to 1, "撤销" to 0,
                )
                buttons.forEach { (label, index) ->
                    runCatching {
                        val interaction = if (label == "撤销") compose.onNodeWithText(label)
                            else compose.onAllNodesWithText(label)[index]
                        interaction.performTouchInput { click(center) }
                    }
                        .onFailure { violations += "$theme ${scale}x '$label' touch failed: ${it.message}" }
                }
                compose.runOnIdle {
                    if (events.toList() != expectedEvents) violations += "$theme ${scale}x callback order=${events.toList()}, expected=$expectedEvents"
                    events.clear()
                }
            }
        }

        for (scale in listOf(1f, 1.3f)) {
            val light = backgroundSamples[modeKey(false, scale)]
            val dark = backgroundSamples[modeKey(true, scale)]
            if (light == null || dark == null || light == dark) {
                violations += "${scale}x palette background did not distinguish light and dark: light=$light dark=$dark"
            }
            if (light != null && dark != null) {
                geometry += "${scale}x palette background light=#${light.toUInt().toString(16)} dark=#${dark.toUInt().toString(16)}"
            }
        }

        // Layout/event assertions are automatic; these synthetic PNGs remain for manual glyph-clipping review.
        // Paragraph, line, and selection geometry are diagnostics because they do not encode the draw clip.
        instrumentation.addResults(Bundle().apply {
            evidence.forEach { (key, value) -> putString(key, value) }
            putString("scoreboardLayoutBounds", geometry.joinToString("; "))
        })
        assertTrue(violations.joinToString("\n").ifEmpty { "scoreboard narrow viewport passed" }, violations.isEmpty())
    }

    private fun modeKey(dark: Boolean, scale: Float) = "${if (dark) "Dark" else "Light"}-${scaleKey(scale)}"
    private fun scaleKey(scale: Float) = if (scale == 1f) "100" else "130"

    private fun sameRect(a: androidx.compose.ui.unit.DpRect, b: androidx.compose.ui.unit.DpRect, epsilon: Float = .5f): Boolean =
        kotlin.math.abs(a.left.value - b.left.value) <= epsilon &&
            kotlin.math.abs(a.top.value - b.top.value) <= epsilon &&
            kotlin.math.abs(a.right.value - b.right.value) <= epsilon &&
            kotlin.math.abs(a.bottom.value - b.bottom.value) <= epsilon

    private fun readTextLayout(node: SemanticsNodeInteraction): TextLayoutMetrics {
        var metrics: TextLayoutMetrics? = null
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            if (action(results) && results.isNotEmpty()) {
                val result = results.first()
                val textLength = result.layoutInput.text.length
                val lineIndices = 0 until result.lineCount
                val lineDetails = lineIndices.joinToString("|") { line ->
                    "L$line ${result.getLineStart(line)}..${result.getLineEnd(line)} visibleEnd=${result.getLineEnd(line, visibleEnd = true)} " +
                        "ellipsis=${result.isLineEllipsized(line)} xy=${result.getLineLeft(line)},${result.getLineTop(line)}..${result.getLineRight(line)},${result.getLineBottom(line)} " +
                        "baseline=${result.getLineBaseline(line)}"
                }
                val lastVisibleEnd = if (result.lineCount == 0) 0 else result.getLineEnd(result.lineCount - 1, visibleEnd = true)
                val selection = result.getPathForRange(0, textLength).getBounds()
                val widthExcess = result.multiParagraph.width - result.size.width
                val heightExcess = result.multiParagraph.height - result.size.height
                val lineRangesCoverText = result.lineCount == 1 && result.getLineStart(0) == 0 &&
                    result.getLineEnd(0, visibleEnd = true) == textLength
                val ellipsized = lineIndices.any(result::isLineEllipsized)
                val lineBoundsFit = lineIndices.all { line ->
                    result.getLineLeft(line) > -MAX_TEXT_QUANTIZATION_PX &&
                        result.getLineTop(line) > -MAX_TEXT_QUANTIZATION_PX &&
                        result.getLineRight(line) - result.size.width < MAX_TEXT_QUANTIZATION_PX &&
                        result.getLineBottom(line) - result.size.height < MAX_TEXT_QUANTIZATION_PX
                }
                val selectionFits = selection.left > -MAX_TEXT_QUANTIZATION_PX && selection.top > -MAX_TEXT_QUANTIZATION_PX &&
                    selection.right - result.size.width < MAX_TEXT_QUANTIZATION_PX &&
                    selection.bottom - result.size.height < MAX_TEXT_QUANTIZATION_PX
                val selectionHasArea = selection.width > 0f && selection.height > 0f
                val problems = buildList {
                    if (result.lineCount != 1) add("expected one complete line, got ${result.lineCount}")
                    if (ellipsized) add("text line is ellipsized")
                    if (!lineRangesCoverText || lastVisibleEnd != textLength) add("visible line range $lastVisibleEnd/$textLength does not cover the full text")
                    if (result.multiParagraph.didExceedMaxLines) add("paragraph exceeded maxLines")
                }
                val constraints = result.layoutInput.constraints
                val diagnostic = "lineCount=${result.lineCount}, visualOverflow=${result.hasVisualOverflow}, " +
                    "didOverflowWidth=${result.didOverflowWidth}, didOverflowHeight=${result.didOverflowHeight}, " +
                    "didExceedMaxLines=${result.multiParagraph.didExceedMaxLines}, paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}px, " +
                    "excess=${widthExcess}x${heightExcess}px, " +
                    "layout=${result.size.width}x${result.size.height}px, constraints=${constraints.minWidth}..${constraints.maxWidth}x${constraints.minHeight}..${constraints.maxHeight}px, " +
                    "lines=[$lineDetails], fullTextSelectionBounds=$selection lineWithinTextSizeUnder1px=$lineBoundsFit " +
                    "selectionWithinTextSizeUnder1px=$selectionFits selectionHasArea=$selectionHasArea, " +
                    "softWrap=${result.layoutInput.softWrap}, maxLines=${result.layoutInput.maxLines}, overflow=${result.layoutInput.overflow}"
                metrics = TextLayoutMetrics(
                    lineCount = result.lineCount,
                    textLength = textLength,
                    lastVisibleEnd = lastVisibleEnd,
                    ellipsized = ellipsized,
                    problems = problems,
                    diagnostic = diagnostic,
                )
            }
        }
        return requireNotNull(metrics) { "GetTextLayoutResult returned no result" }
    }

    private companion object {
        // Diagnostic threshold only; paragraph/line/selection geometry does not model the actual draw clip.
        const val MAX_TEXT_QUANTIZATION_PX = 1f
    }

    private data class TextLayoutMetrics(
        val lineCount: Int,
        val textLength: Int,
        val lastVisibleEnd: Int,
        val ellipsized: Boolean,
        val problems: List<String>,
        val diagnostic: String,
    )
}
