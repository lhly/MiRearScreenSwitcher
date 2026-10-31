package top.zainzh.rearscreen

import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.blur.highlight.*
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The content capture excludes navigation overlays, avoiding recursive glass sampling. */
@RequiresApi(33)
@Composable
internal fun BlurredNavigationFrame(
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
    navigation: @Composable BoxScope.() -> Unit,
) {
    val background = AppPalette.background()
    val backdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
    val tabsBackdrop = rememberLayerBackdrop()
    val dark = isSystemInDarkTheme()
    val tilt = rememberDeviceTilt()
    val angle = remember(tilt) { derivedStateOf {
        val gravity = tilt.value
        if (gravity.gravityX * gravity.gravityX + gravity.gravityY * gravity.gravityY > .01f)
            (atan2(gravity.gravityY, gravity.gravityX) / (PI / 60)).roundToInt() * (PI / 60).toFloat()
        else (-PI / 2).toFloat()
    } }
    val material = remember(backdrop, tabsBackdrop, dark) { GlassNavigationMaterial(backdrop, tabsBackdrop, dark, angle) }
    CompositionLocalProvider(LocalNavigationMaterial provides material) {
        Box(modifier) { content(Modifier.layerBackdrop(backdrop)); navigation() }
    }
}

@RequiresApi(33)
private class GlassNavigationMaterial(
    private val backdrop: Backdrop,
    private val tabs: LayerBackdrop,
    private val dark: Boolean,
    private val angle: State<Float>,
) : NavigationMaterial {
    override val samplesContent = true
    private val combined = NavigationCombinedBackdrop(backdrop, tabs)
    override fun Modifier.captureTabs() = this.alpha(0f).layerBackdrop(tabs)

    @Composable override fun Modifier.surface(shape: Shape, press: () -> Float, indicator: Boolean, compact: Boolean): Modifier {
        val surface = AppPalette.navigationSurface()
        val tint = surface.copy(alpha = if (dark) .44f else .38f)
        return this.drawBackdrop(
            backdrop = if (indicator) combined else backdrop,
            shape = { shape },
            effects = {
                padding = maxOf(padding, if (compact) 20.dp.toPx() else 40.dp.toPx())
                if (indicator) {
                    val p = .12f + .88f * press()
                    navigationLens(10.dp.toPx() * p, 14.dp.toPx() * p, depthEffect = true, chromaticAberration = .3f * press())
                } else {
                    colorControls(saturation = 1.5f)
                    blur(4.dp.toPx(), 4.dp.toPx())
                    val depth = if (compact) 8.dp.toPx() else 24.dp.toPx()
                    navigationLens(depth, depth * (1f + .15f * press()))
                }
            },
            highlight = {
                Highlight(width = .8.dp, alpha = if (indicator) .35f + .65f * press() else .75f,
                    style = BloomStroke(color = Color.White.copy(alpha = .12f), innerBlurRadius = 2.dp,
                        primaryLight = LightSource(LightPosition(.5f + cos(angle.value), .7f + sin(angle.value), -.05f), Color.White, 1f),
                        secondaryLight = LightSource(LightPosition(.5f, .8f, -.5f), Color.White, .4f), dualPeak = true))
            },
            onDrawSurface = {
                if (indicator) {
                    drawRect((if (dark) Color.White else Color.Black).copy(alpha = .08f * (1f - press())))
                } else drawRect(tint)
            },
        ).innerShadow(shape) {
            radius = (if (compact) 3.dp else 6.dp).toPx()
            color = Color.Black
            alpha = (if (dark) .12f else .06f) + .05f * press()
            offset = androidx.compose.ui.geometry.Offset(0f, 1.dp.toPx())
        }
    }
}

/** Composite the emphasized tabs over the page before the indicator refracts them. */
@RequiresApi(33)
private class NavigationCombinedBackdrop(private val page: Backdrop, private val tabs: Backdrop) : Backdrop {
    override val isCoordinatesDependent = true
    override val offsetResidualX get() = page.offsetResidualX
    override val offsetResidualY get() = page.offsetResidualY
    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?, downscaleFactor: Int) {
        with(page) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        with(tabs) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}
