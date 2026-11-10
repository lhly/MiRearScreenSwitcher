package top.zainzh.rearscreen

import android.os.Build
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** Shared contract keeps API-33 drawing classes out of the older Android path. */
internal interface NavigationMaterial {
    val samplesContent: Boolean get() = false
    @Composable fun Modifier.surface(shape: Shape, press: () -> Float, indicator: Boolean = false, compact: Boolean = false): Modifier
    fun Modifier.captureTabs(): Modifier = this
}

internal val LocalNavigationMaterial = staticCompositionLocalOf<NavigationMaterial> { SolidNavigationMaterial }

/** Optional, no-op-by-default event trace for the deterministic navigation interaction tests. */
internal object NavigationMotionProbe {
    @Volatile var recorder: ((String) -> Unit)? = null
    fun record(event: String) { recorder?.invoke(event) }
}

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
    pagerIsScrollInProgress: () -> Boolean,
    pagerTargetPage: () -> Int,
    selectedIndex: Int,
    tabsActive: Boolean,
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
    var settling by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val press = androidx.compose.animation.core.animateFloatAsState(if (pressed || dragging) 1f else 0f,
        spring(dampingRatio = .75f, stiffness = 450f), label = "navigation_press")
    val currentProgress by rememberUpdatedState(progress)
    val isPagerScrolling by rememberUpdatedState(pagerIsScrollInProgress)
    val currentPagerTarget by rememberUpdatedState(pagerTargetPage)
    val indicator = remember(labels.size) { Animatable(progress().coerceIn(0f, (labels.size - 1).toFloat())) }
    val shownProgress by remember {
        derivedStateOf {
            when {
                dragging -> dragProgress
                settling -> indicator.value.coerceIn(0f, (labels.size - 1).toFloat())
                else -> currentProgress().coerceIn(0f, (labels.size - 1).toFloat())
            }
        }
    }
    val latestShownProgress by rememberUpdatedState(shownProgress)
    var dockRequest by remember { mutableStateOf<NavigationDockRequest?>(null) }
    var dockGeneration by remember { mutableIntStateOf(0) }
    val value = { shownProgress }
    LaunchedEffect(dockRequest?.generation) {
        val request = dockRequest ?: return@LaunchedEffect
        val destination = request.target.roundToInt()
        if (isPagerScrolling() && currentPagerTarget() != destination) {
            indicator.snapTo(currentProgress().coerceIn(0f, (labels.size - 1).toFloat()))
            settling = false
            dockRequest = null
            return@LaunchedEffect
        }
        val interrupted = coroutineScope {
            val animation = async {
                indicator.snapTo(request.start)
                indicator.animateTo(request.target, spring(dampingRatio = 1f, stiffness = 900f),
                    initialVelocity = request.velocity)
            }
            val targetChanged = async {
                snapshotFlow { isPagerScrolling() to currentPagerTarget() }
                    .first { (scrolling, target) -> scrolling && target != destination }
            }
            val interruptedDuringSettle = select {
                animation.onAwait { false }
                targetChanged.onAwait { true }
            }
            if (interruptedDuringSettle) {
                animation.cancelAndJoin()
                true
            } else {
                val scrollEnded = async { snapshotFlow { isPagerScrolling() }.first { !it } }
                val interruptedAfterSettle = select {
                    targetChanged.onAwait { true }
                    scrollEnded.onAwait { false }
                }
                if (interruptedAfterSettle) {
                    scrollEnded.cancelAndJoin()
                } else {
                    targetChanged.cancelAndJoin()
                    val actualProgress = currentProgress().coerceIn(0f, (labels.size - 1).toFloat())
                    if (kotlin.math.abs(actualProgress - request.target) > .001f) {
                        indicator.animateTo(actualProgress, spring(dampingRatio = 1f, stiffness = 900f))
                    }
                }
                interruptedAfterSettle
            }
        }
        if (dockRequest?.generation == request.generation) {
            if (interrupted) indicator.snapTo(currentProgress().coerceIn(0f, (labels.size - 1).toFloat()))
            settling = false
            dockRequest = null
        }
    }
    val currentOnSelected by rememberUpdatedState(onSelected)
    val dragAnchor = remember { AtomicReference<NavigationDragAnchor?>(null) }
    val edge = with(density) { 4.dp.roundToPx().toFloat() }
    val tabWidth = ((width - edge * 2f) / labels.size).coerceAtLeast(0f)
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    fun indexAt(x: Float): Float = tabProgressAt(x, width.toFloat(), edge, labels.size, isRtl)

    @Composable fun Tabs(tinted: Boolean) {
        Row(Modifier.fillMaxWidth().padding(4.dp)
            .then(if (tinted || tabsActive) Modifier else Modifier.clearAndSetSemantics {}),
            verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                val color = if (tinted || (!material.samplesContent && index == selectedIndex)) AppPalette.accent() else AppPalette.text()
                Column(Modifier.weight(1f).then(if (tinted) Modifier else Modifier
                    .selectable(selected = index == selectedIndex, interactionSource = interactions, indication = null,
                        role = Role.Tab, onClick = {
                            NavigationMotionProbe.record("onSelected source=tab_click target=$index")
                            dockRequest = null; settling = false; currentOnSelected(index)
                        })
                    .clearAndSetSemantics {
                        contentDescription = descriptions[index]
                        role = Role.Tab
                        selected = index == selectedIndex
                        onClick {
                            NavigationMotionProbe.record("onSelected source=accessibility_click target=$index")
                            dockRequest = null; settling = false; currentOnSelected(index); true
                        }
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
        .pointerInput(dragAnchor) {
            // Observe the raw down in Initial without consuming it. The public drag detector below
            // remains responsible for pointer-type-specific slop, cancellation and pointer handoff.
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                NavigationMotionProbe.record("down id=${down.id} x=${down.position.x} y=${down.position.y} uptime=${down.uptimeMillis}")
                val pointerDowns = mutableMapOf(
                    down.id to NavigationDragAnchor(down.id, down.position, down.uptimeMillis)
                )
                var activePointer = down.id
                dragAnchor.set(pointerDowns.getValue(activePointer))
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { change ->
                            if (change.pressed && !change.previousPressed) {
                                pointerDowns[change.id] = NavigationDragAnchor(
                                    change.id, change.position, change.uptimeMillis
                                )
                            }
                        }
                        val activeChange = event.changes.firstOrNull { it.id == activePointer }
                        if (activeChange?.pressed != true) {
                            val replacement = event.changes.firstOrNull { it.pressed && it.id != activePointer }
                            if (replacement == null) break
                            activePointer = replacement.id
                            dragAnchor.set(pointerDowns[activePointer] ?: NavigationDragAnchor(
                                replacement.id, replacement.position, replacement.uptimeMillis
                            ))
                            NavigationMotionProbe.record("handoff from=${activeChange?.id} to=${activePointer} anchor=${dragAnchor.get()}")
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                } finally {
                    dragAnchor.set(null)
                }
            }
        }
        .pointerInput(labels.size, width, isRtl, dragAnchor) {
            val tracker = VelocityTracker()
            var activePointer: PointerId? = null
            var grabOffset = 0f
            var anchorApplied = false
            detectHorizontalDragGestures(
                onDragStart = { overSlopPosition ->
                    dockRequest = null
                    settling = false
                    dragging = true
                    dragProgress = latestShownProgress
                    activePointer = null
                    anchorApplied = false
                    tracker.resetTracking()
                    NavigationMotionProbe.record("dragStart overSlop=$overSlopPosition rawAnchor=${dragAnchor.get()} progress=$dragProgress")
                },
                onHorizontalDrag = { change, _ ->
                    val changedPointer = activePointer != change.id
                    if (changedPointer) {
                        activePointer = change.id
                        if (!anchorApplied) {
                            val initial = dragAnchor.get()?.takeIf { it.pointerId == change.id }
                            val initialX = initial?.position?.x ?: change.position.x
                            grabOffset = latestShownProgress - indexAt(initialX)
                            dragProgress = latestShownProgress
                            tracker.resetTracking()
                            if (initial != null) tracker.addPosition(initial.uptimeMillis, initial.position)
                            anchorApplied = true
                        } else {
                            // A replacement finger takes over the current visual position. Its
                            // absolute coordinate must not inherit the departed finger's offset.
                            grabOffset = dragProgress - indexAt(change.position.x)
                            tracker.resetTracking()
                        }
                    }
                    dragProgress = dragProgressWithGrabOffset(indexAt(change.position.x), grabOffset, labels.size)
                    tracker.addPosition(change.uptimeMillis, change.position)
                    NavigationMotionProbe.record("drag id=${change.id} x=${change.position.x} progress=$dragProgress")
                },
                onDragEnd = {
                    val target = dragProgress.roundToInt()
                    settling = true
                    val velocity = (tracker.calculateVelocity().x / tabWidth.coerceAtLeast(1f)) * if (isRtl) -1f else 1f
                    dockRequest = NavigationDockRequest(++dockGeneration, dragProgress, target.toFloat(), velocity)
                    NavigationMotionProbe.record("dragEnd target=$target progress=$dragProgress velocity=$velocity")
                    NavigationMotionProbe.record("onSelected source=drag target=$target")
                    currentOnSelected(target)
                    dragging = false
                    activePointer = null
                    anchorApplied = false
                },
                onDragCancel = {
                    NavigationMotionProbe.record("dragCancel progress=$dragProgress")
                    settling = true
                    dockRequest = NavigationDockRequest(++dockGeneration, dragProgress,
                        currentProgress().coerceIn(0f, (labels.size - 1).toFloat()), 0f)
                    dragging = false
                    activePointer = null
                    anchorApplied = false
                }
            )
        }) {
        Tabs(false)
        if (material.samplesContent) {
            Box(Modifier.matchParentSize().clearAndSetSemantics {}.then(with(material) { Modifier.captureTabs() })
                .then(with(material) { Modifier.surface(CircleShape, { press.value }) })) { Tabs(true) }
        }
        if (width > 0) {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.align(Alignment.TopStart).padding(4.dp)
                    .graphicsLayer {
                        translationX = value() * tabWidth * if (isRtl) -1f else 1f
                        val travel = if (dragging) dragProgress - selectedIndex else value() - selectedIndex
                        scaleX = 1f + .12f * press.value + .06f * kotlin.math.abs(travel).coerceAtMost(1f)
                        scaleY = 1f + .08f * press.value - .03f * kotlin.math.abs(travel).coerceAtMost(1f)
                    }
                    .width(with(density) { tabWidth.toDp() })
                    .fillMaxHeight()
                    .testTag("navigation_indicator")) {
                    Box(Modifier.matchParentSize()
                        .then(with(material) { Modifier.surface(CircleShape, { press.value }, indicator = true) })
                        .clearAndSetSemantics {})
                }
            }
        }
    }
}

private data class NavigationDockRequest(
    val generation: Int,
    val start: Float,
    val target: Float,
    val velocity: Float,
)

/** The initial position of the pointer currently owning a navigation drag. */
private data class NavigationDragAnchor(
    val pointerId: PointerId,
    val position: androidx.compose.ui.geometry.Offset,
    val uptimeMillis: Long,
)
