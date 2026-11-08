package top.zainzh.rearscreen

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.*
import top.yukonga.miuix.kmp.window.WindowDialog

/** Shared visual contract for every application settings page. */
internal object AppPalette {
    @Composable fun background() = colorResource(R.color.remrss_background)
    @Composable fun text() = colorResource(R.color.remrss_foreground)
    @Composable fun secondary() = colorResource(R.color.remrss_secondary)
    @Composable fun accent() = colorResource(R.color.remrss_primary)
    @Composable fun destructive() = colorResource(R.color.remrss_destructive)
    @Composable fun onDestructive() = colorResource(R.color.remrss_on_destructive)
    @Composable fun navigationSurface() = colorResource(R.color.remrss_navigation_surface)
}

@Composable internal fun AppTheme(content: @Composable () -> Unit) {
    MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme(primary = AppPalette.accent(), onPrimary = Color(0xFF0E213D))
        else lightColorScheme(primary = AppPalette.accent()), content = content)
}

internal fun ComponentActivity.miuixContent(content: @Composable () -> Unit) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
    }
    setContentView(ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { AppTheme { content() } }
    })
}

@Composable internal fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(26.dp), clip = false),
        cornerRadius = 26.dp, insideMargin = PaddingValues(0.dp), content = content)
}

@Composable internal fun AppSection(title: String) {
    Text(title, fontSize = 13.sp, color = AppPalette.secondary(), fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp).semantics { heading() })
}

@Composable internal fun AppDivider() {
    Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(.5.dp)
        .background(AppPalette.secondary().copy(alpha = .12f)))
}

// Match the existing drawer's ease-out curve. Compose applies the system duration scale.
private val settingsRevealEasing = CubicBezierEasing(.23f, 1f, .32f, 1f)
private const val SETTINGS_ENTER_MS = 200
private const val SETTINGS_EXIT_MS = 150

@Composable internal fun AppSettingsReveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible = visible,
        enter = expandVertically(tween(SETTINGS_ENTER_MS, easing = settingsRevealEasing), expandFrom = Alignment.Top) +
                fadeIn(tween(SETTINGS_ENTER_MS, easing = settingsRevealEasing)),
        exit = shrinkVertically(tween(SETTINGS_EXIT_MS, easing = settingsRevealEasing), shrinkTowards = Alignment.Top) +
                fadeOut(tween(SETTINGS_EXIT_MS, easing = settingsRevealEasing))) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable internal fun AppRow(title: String, summary: String?, modifier: Modifier = Modifier,
    enabled: Boolean = true, action: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().then(if (action != null) Modifier.clickable(enabled = enabled, onClick = action) else Modifier)
        .heightIn(min = 64.dp).padding(18.dp, 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppPalette.text())
            if (!summary.isNullOrEmpty()) Text(summary, fontSize = 13.sp, color = AppPalette.secondary())
        }
        if (trailing != null) trailing()
        else if (action != null) AppNavigationChevron()
    }
}

@Composable internal fun AppToggle(title: String, summary: String?, checked: Boolean, enabled: Boolean = true,
    modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    AppRow(title, summary, modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        trailing = { Switch(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.clearAndSetSemantics { }) })
}

@Composable internal fun AppBackButton(back: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.ui_back)
    val feedback = remember { NavigationPress() }
    val press = animateFloatAsState(if (feedback.down) 1f else 0f,
        spring(dampingRatio = .7f, stiffness = 450f), label = "back_press")
    val material = LocalNavigationMaterial.current
    IconButton(onClick = back, modifier = modifier.size(48.dp).then(feedback.modifier)
        .graphicsLayer { scaleX = 1f + .06f * press.value; scaleY = scaleX }
        .shadow(4.dp, CircleShape, clip = false)
        .then(with(material) { Modifier.surface(CircleShape, { press.value }, compact = true) }).clearAndSetSemantics {
        contentDescription = label
        role = Role.Button
        onClick { back(); true }
    }, minWidth = 48.dp, minHeight = 48.dp,
        backgroundColor = Color.Transparent) {
        Icon(painterResource(R.drawable.ic_navigation_back), contentDescription = null,
            modifier = Modifier.size(26.dp), tint = AppPalette.text())
    }
}

@Composable internal fun AppPageHeader(title: String, back: () -> Unit, backTag: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppBackButton(back, Modifier.testTag(backTag))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppPalette.text(),
            modifier = Modifier.weight(1f).semantics { heading() })
    }
}

@Composable internal fun PageFrame(title: String, back: () -> Unit, backTag: String,
    content: @Composable (Modifier, Dp) -> Unit) {
    Box(Modifier.fillMaxSize().background(AppPalette.background()).safeDrawingPadding().imePadding()) {
        AppPageContent(title, back, backTag, content = content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun AppPageContent(title: String, back: () -> Unit, backTag: String,
    modifier: Modifier = Modifier, content: @Composable (Modifier, Dp) -> Unit) {
    val density = LocalDensity.current
    val background = AppPalette.background()
    val host = LocalView.current
    var insetsReady by remember(host) { mutableStateOf(host.isLaidOut && host.rootWindowInsets != null) }
    DisposableEffect(host) {
        val observer = host.viewTreeObserver
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (host.rootWindowInsets != null) {
                    insetsReady = true
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                }
                return true
            }
        }
        if (!insetsReady) { observer.addOnPreDrawListener(listener); host.requestApplyInsets() }
        onDispose { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
    }
    // A short restored list must not be clamped against the pre-insets viewport.
    if (!insetsReady) return
    AppNavigationFrame(modifier.fillMaxSize(), content = { capture ->
        SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
            val header = subcompose("header") {
                Box(Modifier.fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures {} }
                    .background(Brush.verticalGradient(listOf(background, background.copy(alpha = .92f), background.copy(alpha = .0f)))),
                    contentAlignment = Alignment.TopCenter) {
                    AppPageHeader(title, back, backTag, Modifier.widthIn(max = 680.dp)
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp))
                }
            }.single().measure(constraints.copy(minHeight = 0))
            val headerInset = with(density) { header.height.toDp() }
            val body = subcompose("body") {
                val focusScroll = remember(headerInset, density) { object : BringIntoViewSpec {
                    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                        val top = with(density) { headerInset.toPx() }
                        return when {
                            offset < top -> offset - top
                            offset + size > containerSize -> offset + size - containerSize
                            else -> 0f
                        }
                    }
                } }
                CompositionLocalProvider(LocalBringIntoViewSpec provides focusScroll) {
                    Box(capture.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        content(Modifier.widthIn(max = 680.dp).fillMaxSize(), headerInset)
                    }
                }
            }.single().measure(constraints)
            layout(constraints.maxWidth, constraints.maxHeight) {
                body.placeRelative(0, 0)
                header.placeRelative(0, 0)
            }
        }
    }, navigation = {})
}

internal fun Bundle?.listState(key: String) = LazyListState(this?.getInt(key + "_index", 0) ?: 0, this?.getInt(key + "_offset", 0) ?: 0)
internal fun Bundle.saveList(key: String, state: LazyListState) {
    putInt(key + "_index", state.firstVisibleItemIndex); putInt(key + "_offset", state.firstVisibleItemScrollOffset)
}
