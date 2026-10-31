package top.zainzh.rearscreen

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** Shared contract keeps API-33 drawing classes out of the older Android path. */
internal interface NavigationMaterial {
    val samplesContent: Boolean get() = false
    @Composable fun Modifier.surface(shape: Shape, press: () -> Float, indicator: Boolean = false, compact: Boolean = false): Modifier
    fun Modifier.captureTabs(): Modifier = this
}

internal val LocalNavigationMaterial = staticCompositionLocalOf<NavigationMaterial> { SolidNavigationMaterial }

private object SolidNavigationMaterial : NavigationMaterial {
    @Composable override fun Modifier.surface(shape: Shape, press: () -> Float, indicator: Boolean, compact: Boolean): Modifier {
        val dark = isSystemInDarkTheme()
        val base = AppPalette.navigationSurface()
        val tint = if (indicator) AppPalette.accent().copy(alpha = .14f) else base
        return this.background(tint, shape).border(.75.dp,
            if (dark) Color.White.copy(alpha = .13f) else Color.Black.copy(alpha = .06f), shape)
    }
}

@Composable internal fun AppNavigationFrame(
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
    navigation: @Composable BoxScope.() -> Unit,
) {
    if (Build.VERSION.SDK_INT >= 33) {
        BlurredNavigationFrame(modifier, content, navigation)
    } else {
        CompositionLocalProvider(LocalNavigationMaterial provides SolidNavigationMaterial) {
            Box(modifier) { content(Modifier); navigation() }
        }
    }
}

/** Observes presses without consuming the click, drag, or accessibility action. */
internal class NavigationPress {
    var down by mutableStateOf(false)
    val modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down = true
            try {
                while (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) { }
            } finally { down = false }
        }
    }
}

/** Decorative affordances live inside captured cards, so never sample their own layer. */
@Composable internal fun AppNavigationChevron(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val base = if (dark) Color.White.copy(alpha = .09f) else AppPalette.background().copy(alpha = .8f)
    Box(modifier.size(28.dp).background(Brush.linearGradient(listOf(base, base.copy(alpha = if (dark) .03f else .3f))), CircleShape)
        .border(.5.dp, if (dark) Color.White.copy(alpha = .1f) else Color.White.copy(alpha = .9f), CircleShape)
        .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_navigation_forward), null, Modifier.size(16.dp), tint = AppPalette.secondary())
    }
}

@Composable internal fun AppBottomNavigation(
    progress: () -> Float,
    selectedIndex: Int,
    labels: List<String>,
    descriptions: List<String>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Int, Color) -> Unit,
) {
    val material = LocalNavigationMaterial.current
    val density = LocalDensity.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val press = animateFloatAsState(if (pressed || dragging) 1f else 0f,
        spring(dampingRatio = .75f, stiffness = 450f), label = "navigation_press")
    var releasedTarget by remember { mutableStateOf<Int?>(null) }
    val visualProgress = animateFloatAsState(
        (if (dragging) dragProgress else releasedTarget?.toFloat() ?: progress()).coerceIn(0f, (labels.size - 1).toFloat()),
        spring(dampingRatio = .9f, stiffness = 900f), label = "navigation_position")
    val value = { visualProgress.value }
    LaunchedEffect(releasedTarget) {
        val target = releasedTarget ?: return@LaunchedEffect
        snapshotFlow { progress() }.first { kotlin.math.abs(it - target) < .001f }
        releasedTarget = null
    }
    val currentOnSelected by rememberUpdatedState(onSelected)
    val edge = with(density) { 4.dp.roundToPx().toFloat() }
    val tabWidth = ((width - edge * 2f) / labels.size).coerceAtLeast(0f)
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    fun indexAt(x: Float): Float = (((if (isRtl) width - x else x) - edge) / tabWidth.coerceAtLeast(1f) - .5f)
        .coerceIn(0f, (labels.size - 1).toFloat())

    @Composable fun Tabs(tinted: Boolean) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                val color = if (tinted || (!material.samplesContent && index == selectedIndex)) AppPalette.accent() else AppPalette.text()
                Column(Modifier.weight(1f).then(if (tinted) Modifier else Modifier
                    .selectable(selected = index == selectedIndex, interactionSource = interactions, indication = null,
                        role = Role.Tab, onClick = { releasedTarget = null; currentOnSelected(index) })
                    .clearAndSetSemantics {
                        contentDescription = descriptions[index]
                        role = Role.Tab
                        selected = index == selectedIndex
                        onClick { releasedTarget = null; currentOnSelected(index); true }
                    })
                    .heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 8.dp)
                    .graphicsLayer {
                        val scale = 1f + .12f * press.value
                        scaleX = scale; scaleY = scale
                    }, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                    icon(index, color)
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }

    Box(modifier.testTag("main_navigation").selectableGroup()
        .onSizeChanged { width = it.width }
        .shadow(8.dp, CircleShape, clip = false)
        .then(with(material) { Modifier.surface(CircleShape, { press.value }) })
        .pointerInput(labels.size, width, isRtl) {
            detectHorizontalDragGestures(
                onDragStart = { releasedTarget = null; dragging = true; dragProgress = indexAt(it.x) },
                onHorizontalDrag = { change, _ -> dragProgress = indexAt(change.position.x) },
                onDragEnd = {
                    val target = dragProgress.roundToInt()
                    releasedTarget = target
                    currentOnSelected(target)
                    dragging = false
                },
                onDragCancel = { dragging = false },
            )
        }) {
        Tabs(false)
        if (material.samplesContent) {
            Box(Modifier.matchParentSize().clearAndSetSemantics {}.then(with(material) { Modifier.captureTabs() })
                .then(with(material) { Modifier.surface(CircleShape, { press.value }) })) { Tabs(true) }
        }
        if (width > 0) {
            Box(Modifier.matchParentSize().clearAndSetSemantics {}) {
                Box(Modifier.align(Alignment.TopStart).padding(4.dp)
                    .graphicsLayer {
                        translationX = value() * tabWidth * if (isRtl) -1f else 1f
                        val travel = if (dragging) dragProgress - selectedIndex else value() - selectedIndex
                        scaleX = 1f + .12f * press.value + .06f * kotlin.math.abs(travel).coerceAtMost(1f)
                        scaleY = 1f + .08f * press.value - .03f * kotlin.math.abs(travel).coerceAtMost(1f)
                    }
                    .width(with(density) { tabWidth.toDp() })
                    .fillMaxHeight()
                    .then(with(material) { Modifier.surface(CircleShape, { press.value }, indicator = true) })
                    .clearAndSetSemantics {})
            }
        }
    }
}
