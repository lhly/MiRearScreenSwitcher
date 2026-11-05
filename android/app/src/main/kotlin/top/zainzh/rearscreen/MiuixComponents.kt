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
    onChange: (Boolean) -> Unit) {
    AppRow(title, summary, Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        trailing = { Switch(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.clearAndSetSemantics { }) })
}

@Composable internal fun rememberVirtualRearCastMode(): Boolean {
    if (!BuildConfig.VIRTUAL_CAST_ENABLED) return false
    val context = LocalContext.current
    val preferences = remember(context) { RearCastMode.preferences(context) }
    var virtual by remember(context) { mutableStateOf(RearCastMode.useVirtual(context)) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == RearCastMode.VIRTUAL) virtual = RearCastMode.useVirtual(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return virtual
}

@Composable internal fun RearCastModeToggle(virtual: Boolean) {
    if (!BuildConfig.VIRTUAL_CAST_ENABLED) return
    val context = LocalContext.current
    val supported = android.os.Build.VERSION.SDK_INT >= 33
    AppToggle(stringResource(R.string.rear_cast_mode_title),
        if (supported) stringResource(if (virtual) R.string.rear_cast_mode_on else R.string.rear_cast_mode_off) + "\n" +
                stringResource(R.string.rear_cast_mode_next) else stringResource(R.string.rear_cast_unavailable), virtual, enabled = supported,
        onChange = { RearCastMode.setVirtual(context, it) })
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

/** Java controllers publish plain modal state; only this renderer creates UI. */
class MiuixDialogUi(private val activity: ComponentActivity) {
    private data class Model(val title: String, val message: String, val labels: List<String>, val actions: List<Runnable?>,
        val number: Boolean = false, val min: Int = 1, val max: Int = Int.MAX_VALUE, val save: java.util.function.IntConsumer? = null, val pinned: Int = 0)
    private var model by mutableStateOf<Model?>(null)
    private var draft by mutableStateOf("")
    private var error by mutableStateOf(false)
    private var kind: String? = null
    val view: View = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { AppTheme { Render() } }
    }
    fun show(title: String, message: String, labels: Array<String>, actions: Array<Runnable?>) {
        kind = null; model = Model(title, message, labels.toList(), actions.toList())
    }
    fun showWithPinnedActions(title: String, message: String, labels: Array<String>, actions: Array<Runnable?>) {
        kind = null; model = Model(title, message, labels.toList(), actions.toList(), pinned = minOf(2, labels.size))
    }
    fun number(kind: String, title: String, help: String, value: Int, min: Int, max: Int, save: java.util.function.IntConsumer, reset: Runnable?) {
        this.kind = kind; draft = value.toString(); error = false
        val labels = mutableListOf(NativeStrings.get(activity, "apply"), NativeStrings.get(activity, "cancel"))
        val actions = mutableListOf<Runnable?>(null, null)
        if (reset != null) { labels += NativeStrings.get(activity, "reset_dpi"); actions += reset }
        model = Model(title, help, labels, actions, true, min, max, save)
    }
    fun restoreDraft(value: String) { draft = value }
    fun updateMessage(title: String, message: String) { model?.takeIf { it.title == title }?.let { model = it.copy(message = message) } }
    fun saveState(bundle: Bundle) {
        if (model?.number == true) { bundle.putString("native_number_kind", kind); bundle.putString("native_number_draft", draft) }
    }
    fun isShowing(title: String): Boolean = model?.title == title
    fun isVisible(): Boolean = model != null
    fun dismiss() { model = null; kind = null }
    @Composable private fun DialogAction(current: Model, index: Int) {
        TextButton(current.labels[index], {
            if (current.number && index == 0) {
                val value = draft.trim().toIntOrNull()
                if (value == null || value !in current.min..current.max) error = true
                else { dismiss(); current.save?.accept(value) }
            } else { dismiss(); current.actions[index]?.run() }
        }, Modifier.fillMaxWidth(), minHeight = 48.dp,
            colors = if (index == 0) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
    }
    @Composable private fun DialogBody(current: Model) {
        SelectionContainer { Text(current.message, fontSize = 14.sp, color = AppPalette.secondary()) }
        if (current.number) {
            TextField(value = draft, onValueChange = { draft = it; error = false }, label = current.title,
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("number_input").semantics { if (error) error(current.message) })
            if (error) Text(current.message, color = AppPalette.accent(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        for (index in current.pinned until current.labels.size) DialogAction(current, index)
    }
    @Composable private fun Render() {
        val current = model
        WindowDialog(show = current != null, title = current?.title, onDismissRequest = ::dismiss) {
            if (current != null) {
                val maximum = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .65f }
                if (current.pinned == 0) {
                    Column(Modifier.fillMaxWidth().heightIn(max = maximum).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) { DialogBody(current) }
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(max = maximum), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) { DialogBody(current) }
                        for (index in 0 until current.pinned) DialogAction(current, index)
                    }
                }
            }
        }
    }
}

/** A stable Miuix window while transfer/verification state changes. */
class UpdateDialogUi(activity: ComponentActivity, private val cancel: Runnable) {
    private data class Model(val title: String, val current: String, val status: String, val details: String,
        val showReleaseNotes: Boolean,
        val notesAdded: List<String>, val notesImproved: List<String>, val notesFixed: List<String>,
        val notesAddedTitle: String, val notesImprovedTitle: String, val notesFixedTitle: String, val notesEmpty: String,
        val progress: Int, val indeterminate: Boolean, val labels: List<String>, val actions: List<Runnable>)
    private var model by mutableStateOf<Model?>(null)
    val view: View = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AppTheme {
                val current = model
                WindowDialog(show = current != null, title = current?.title, onDismissRequest = { cancel.run() }) {
                    if (current != null) Column(Modifier.fillMaxWidth().heightIn(max = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .65f })
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(current.current, fontSize = 14.sp, color = AppPalette.secondary())
                        if (current.status.isNotEmpty()) Text(current.status, color = AppPalette.text(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (current.progress >= 0) LinearProgressIndicator(progress = if (current.indeterminate) null else current.progress / 100f, modifier = Modifier.fillMaxWidth())
                        Text(current.details, fontSize = 14.sp, color = AppPalette.secondary())
                        if (current.showReleaseNotes) {
                            val hasNotes = current.notesAdded.isNotEmpty() || current.notesImproved.isNotEmpty() || current.notesFixed.isNotEmpty()
                            if (hasNotes) {
                                ReleaseNoteGroup(current.notesAddedTitle, current.notesAdded)
                                ReleaseNoteGroup(current.notesImprovedTitle, current.notesImproved)
                                ReleaseNoteGroup(current.notesFixedTitle, current.notesFixed)
                            } else {
                                Text(current.notesEmpty, fontSize = 14.sp, color = AppPalette.secondary(),
                                    modifier = Modifier.testTag("update_release_notes_empty"))
                            }
                        }
                        current.labels.forEachIndexed { index, label ->
                            TextButton(label, { current.actions[index].run() }, Modifier.fillMaxWidth(), minHeight = 48.dp,
                                colors = if (index == 0 && current.labels.size > 1) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
                        }
                    }
                }
            }
        }
    }
    fun update(title: String, current: String, status: String, details: String, showReleaseNotes: Boolean,
        progress: Int, indeterminate: Boolean,
        notesAdded: Array<String>, notesImproved: Array<String>, notesFixed: Array<String>,
        notesAddedTitle: String, notesImprovedTitle: String, notesFixedTitle: String, notesEmpty: String,
        labels: Array<String>, actions: Array<Runnable>) {
        model = Model(title, current, status, details, showReleaseNotes, notesAdded.toList(), notesImproved.toList(), notesFixed.toList(),
            notesAddedTitle, notesImprovedTitle, notesFixedTitle, notesEmpty, progress, indeterminate,
            labels.toList(), actions.toList())
    }
    fun dismiss() { model = null }
}

@Composable
private fun ReleaseNoteGroup(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 14.sp, color = AppPalette.text(), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
        items.forEach { item ->
            Text("•  $item", fontSize = 14.sp, color = AppPalette.secondary(),
                modifier = Modifier.fillMaxWidth().testTag("update_release_note_item"))
        }
    }
}
