package top.zainzh.rearscreen

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs

/** A lifecycle-owned Compose surface. Java remains the source of truth for settings/actions. */
class MiuixHomeUi(
    private val activity: MainActivity,
    private val notifications: ViewGroup,
    private val about: ViewGroup,
    private val picker: ViewGroup,
    savedState: Bundle?,
    private val actions: Actions,
) {
    interface Actions {
        fun navigate(target: Int)
        fun settled(target: Int)
        fun connect()
        fun details()
        fun dpi()
        fun rotation(target: Int)
        fun toggle(key: String, method: String, enabled: Boolean)
        fun tutorial()
        fun automation()
    }
    private data class HomeState(
        val status: String = "check_shizuku", val summary: String = "connection_help",
        val action: String = "open_shizuku", val dpi: Int = 0, val rotation: Int = 0,
        val busy: Boolean = false, val values: Map<String, Boolean> = emptyMap(),
    )
    private data class SafeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0, val ime: Int = 0)
    private var state by mutableStateOf(HomeState())
    private var uriConflict by mutableStateOf(false)
    private var navigationHeight by mutableIntStateOf(0)
    private var page by mutableIntStateOf(savedState?.getInt("native_page", 0) ?: 0)
    private var navigationRequest by mutableIntStateOf(0)
    private var insets by mutableStateOf(SafeInsets())
    private val listState = LazyListState(savedState?.getInt("compose_home_index", 0) ?: 0, savedState?.getInt("compose_home_offset", 0) ?: 0)
    private val view = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val dark = isSystemInDarkTheme()
            val pager = rememberPagerState(initialPage = if (page == 3) 1 else page, pageCount = { 3 })
            LaunchedEffect(navigationRequest) {
                val target = page
                if (target == 3) pager.scrollToPage(1)
                else {
                    if (Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) pager.scrollToPage(target)
                    else pager.animateScrollToPage(target, animationSpec = tween(250, easing = pageEasing))
                }
            }
            LaunchedEffect(pager) {
                snapshotFlow { if (pager.isScrollInProgress) null else pager.settledPage }
                    .filterNotNull().collect { if (page != 3) actions.settled(it) }
            }
            MiuixTheme(colors = if (dark) darkColorScheme(primary = DarkAccent, onPrimary = Color(0xFF0E213D)) else lightColorScheme(primary = Accent)) {
                // No blur class is loaded by the pre-33 fallback.
                if (Build.VERSION.SDK_INT >= 33) {
                    GlassHomeFrame(dark, content = { SurfaceContent(it, dark, pager) }, navigation = { Navigation(it, dark, pager) })
                } else {
                    Box(Modifier.fillMaxSize().background(background(dark))) {
                        SurfaceContent(Modifier, dark, pager)
                        Navigation(Modifier.background(if (dark) Color(0xFF242427) else Color.White), dark, pager)
                    }
                }
            }
        }
    }
    fun getView(): View = view
    fun updateUriConflict(conflict: Boolean) { uriConflict = conflict }
    fun showPage(target: Int) { page = target; navigationRequest++ }
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int, ime: Int) { insets = SafeInsets(left, top, right, bottom, ime) }
    fun updateHome(status: String, summary: String, action: String, dpi: Int, rotation: Int, busy: Boolean, values: Map<String, Boolean>) {
        state = HomeState(status, summary, action, dpi, rotation, busy, values.toMap())
    }
    fun saveState(bundle: Bundle) {
        bundle.putInt("compose_home_index", listState.firstVisibleItemIndex)
        bundle.putInt("compose_home_offset", listState.firstVisibleItemScrollOffset)
    }
    private fun s(key: String) = NativeStrings.get(activity, key)
    private fun background(dark: Boolean) = if (dark) Color(0xFF101013) else Color(0xFFF3F3F5)
    private fun foreground(dark: Boolean) = if (dark) Color(0xFFF4F4F5) else Color(0xFF19191C)
    private fun secondary(dark: Boolean) = if (dark) Color(0xFFACACB4) else Color(0xFF686870)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun SurfaceContent(modifier: Modifier, dark: Boolean, pager: PagerState) {
        val d = LocalDensity.current
        val safe = with(d) { PaddingValues(start = insets.left.toDp(), top = insets.top.toDp(), end = insets.right.toDp(), bottom = maxOf(insets.bottom, insets.ime).toDp()) }
        Box(modifier.fillMaxSize().background(background(dark)).padding(safe).clipToBounds(), contentAlignment = Alignment.TopCenter) {
            HorizontalPager(state = pager, beyondViewportPageCount = 1, userScrollEnabled = page != 3,
                modifier = Modifier.fillMaxSize().testTag("main_pager").semantics { if (page == 3) hideFromAccessibility() }) { index ->
                if (index == 0) {
                    // Keep keyboard/accessibility focus above the floating navigation surface.
                    val reserve = navigationHeight + with(d) { 24.dp.toPx() }
                    val focusScroll = remember(reserve) { object : BringIntoViewSpec {
                        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                            val visibleEnd = (containerSize - reserve).coerceAtLeast(size)
                            return when { offset < 0f -> offset; offset + size > visibleEnd -> offset + size - visibleEnd; else -> 0f }
                        }
                    } }
                    CompositionLocalProvider(LocalBringIntoViewSpec provides focusScroll) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Home(dark, Modifier.widthIn(max = 680.dp).fillMaxSize().semantics { if (pager.currentPage != 0 || page == 3) hideFromAccessibility() })
                        }
                    }
                }
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    AndroidView(factory = { if (index == 1) notifications else about },
                        update = { it.importantForAccessibility = if (pager.currentPage == index && page != 3) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS },
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxSize())
                }
            }
            if (page == 3) Box(Modifier.fillMaxSize().background(background(dark)), contentAlignment = Alignment.TopCenter) {
                AndroidView(factory = { picker }, modifier = Modifier.widthIn(max = 680.dp).fillMaxSize())
            }
        }
    }

    @Composable private fun BoxScope.Navigation(modifier: Modifier, dark: Boolean, pager: PagerState) {
        if (page == 3 || insets.ime > insets.bottom) return
        val bottom = with(LocalDensity.current) { insets.bottom.toDp() }
        val shape = RoundedCornerShape(32.dp)
        Box(Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = bottom + 12.dp).widthIn(max = 340.dp).fillMaxWidth()
            .onSizeChanged { navigationHeight = it.height }.shadow(6.dp, shape, clip = false).clip(shape).then(modifier).border(0.8.dp, if (dark) Color.White.copy(alpha = .12f) else Color.White.copy(alpha = .8f), shape)) {
            NavigationBar(color = Color.Transparent, showDivider = false, defaultWindowInsetsPadding = false, modifier = Modifier.padding(6.dp)) {
                Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Canvas(Modifier.matchParentSize()) {
                        val progress = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 2f)
                        val tabWidth = size.width / 3
                        drawRoundRect(if (dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .8f),
                            topLeft = Offset(tabWidth * progress, 0f), size = Size(tabWidth, size.height), cornerRadius = CornerRadius(26.dp.toPx()))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        val labels = listOf("nav_screen", "nav_notifications", "about")
                        labels.forEachIndexed { index, key ->
                            val selected = pager.currentPage == index
                            val color = if (selected) accent(dark) else secondary(dark)
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(26.dp))
                                .selectable(selected, role = Role.Tab, onClick = { actions.navigate(index) })
                                .clearAndSetSemantics {
                                    contentDescription = s(if (index == 0) "screen_title" else if (index == 1) "notifications_title" else "about")
                                    role = Role.Tab
                                    this.selected = selected
                                    onClick { actions.navigate(index); true }
                                }
                                .heightIn(min = 60.dp).padding(vertical = 7.dp, horizontal = 3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(listOf("screen", "bell", "info")[index], color, Modifier.size(23.dp), tintProvider = {
                                    val emphasis = (1f - abs(pager.currentPage + pager.currentPageOffsetFraction - index)).coerceIn(0f, 1f)
                                    lerp(secondary(dark), accent(dark), emphasis)
                                })
                                Text(s(key), fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = color, modifier = Modifier.clearAndSetSemantics { })
                            }
                        }
                    }
                }
            }
        }
    }

    private val pageEasing = CubicBezierEasing(.77f, 0f, .175f, 1f)

    @Composable private fun Home(dark: Boolean, modifier: Modifier) {
        LazyColumn(modifier.testTag("home_list"), state = listState, contentPadding = PaddingValues(20.dp, 22.dp, 20.dp, maxOf(126.dp, with(LocalDensity.current) { navigationHeight.toDp() } + 32.dp)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") {
                Column(Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                    Text("ReMRSS", fontSize = 12.sp, color = secondary(dark), fontWeight = FontWeight.Medium)
                    Text(s("screen_title"), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = foreground(dark), modifier = Modifier.padding(top = 7.dp).semantics { heading() })
                    Text(s("screen_subtitle"), fontSize = 13.sp, color = secondary(dark), modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (uriConflict) item("uri_conflict") {
                val tint = if (dark) Color(0xFFFFCB80) else Color(0xFF804600)
                Column(Modifier.fillMaxWidth().testTag("uri_conflict_warning").clip(RoundedCornerShape(24.dp))
                    .background(if (dark) Color(0xFF3A2C1B) else Color(0xFFFFF3DF)).padding(18.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s("uri_conflict_title"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = tint,
                        modifier = Modifier.semantics { heading() })
                    Text(s("uri_conflict_help").replace("{0}", BuildConfig.URI_SCHEME + "://"), fontSize = 13.sp, color = tint)
                    TextButton(s("uri_title"), actions::automation, minHeight = 48.dp)
                }
            }
            item("connection") {
                Group {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = if (dark) .18f else .09f)), contentAlignment = Alignment.Center) { Icon("screen", accent(dark), Modifier.size(24.dp)) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("SHIZUKU", fontSize = 10.sp, color = secondary(dark), fontWeight = FontWeight.SemiBold)
                                Text(s(state.status), fontSize = 19.sp, color = foreground(dark), fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                        }
                        Text(s(state.summary), fontSize = 13.sp, color = secondary(dark))
                        BoxWithConstraints {
                            if (maxWidth < 260.dp || LocalDensity.current.fontScale > 1.3f) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ConnectButton(Modifier.fillMaxWidth())
                                    DetailsButton(Modifier.fillMaxWidth())
                                }
                            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ConnectButton(Modifier.weight(1f))
                                DetailsButton(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            item("display") {
                Section("screen_size", dark)
                Group {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy, onClick = actions::dpi).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile("density", dark)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Label("dpi_caption", dark)
                            Description("dpi_help", dark)
                        }
                        Text(if (state.dpi > 0) "${state.dpi}" else "—", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = accent(dark))
                        Text("›", fontSize = 22.sp, color = secondary(dark), modifier = Modifier.clearAndSetSemantics { })
                    }
                    Divider(dark)
                    Row(Modifier.padding(18.dp, 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile("rotate", dark)
                        Label("rotation_caption", dark, Modifier.weight(1f))
                        Text("${state.rotation * 90}°", fontSize = 14.sp, color = secondary(dark))
                    }
                    Row(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(4) { index ->
                            Box(Modifier.weight(1f).clearAndSetSemantics {
                                contentDescription = s("rotation_caption") + " ${index * 90}°"
                                selected = state.rotation == index
                                role = Role.Button
                                if (state.busy) disabled() else onClick { actions.rotation(index); true }
                            }) {
                                TextButton("${index * 90}°", onClick = { actions.rotation(index) }, enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth(), minWidth = 0.dp, minHeight = 48.dp,
                                    insideMargin = PaddingValues(2.dp), cornerRadius = 16.dp,
                                    colors = if (state.rotation == index) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
                            }
                        }
                    }
                }
            }
            item("wake") {
                Section("display_wake", dark)
                Group {
                    ToggleRow("eye", "proximity_sensor", "proximity_help", "proximity_sensor_enabled", "setProximitySensorEnabled", dark)
                    Divider(dark)
                    ToggleRow("sun", "keep_screen_on", "keep_help", "keep_screen_on_enabled", "setKeepScreenOnEnabled", dark)
                    Divider(dark)
                    ToggleRow("sun", "always_wake_up", "always_help", "always_wakeup_enabled", "setAlwaysWakeUpEnabled", dark)
                }
                Text(s("warning_burn_in"), fontSize = 12.sp, color = secondary(dark), modifier = Modifier.padding(14.dp, 10.dp))
            }
            item("charging") {
                Section("charging_section", dark)
                Group {
                    ToggleRow("charge", "charging_animation", "charging_help", "charging_animation_enabled", "toggleChargingService", dark)
                    Divider(dark)
                    ToggleRow("sun", "charging_always_on", "charging_always_help", "charging_always_on_enabled", "setChargingAlwaysOnEnabled", dark)
                }
            }
            item("tiles") {
                Section("quick_tiles", dark)
                Group {
                    Row(Modifier.fillMaxWidth().clickable(onClick = actions::tutorial).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile("apps", dark)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Label("quick_tiles", dark); Description("quick_tiles_help", dark) }
                        Text("›", fontSize = 22.sp, color = secondary(dark), modifier = Modifier.clearAndSetSemantics { })
                    }
                }
            }
        }
    }
    @Composable private fun Group(content: @Composable ColumnScope.() -> Unit) {
        Card(modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(26.dp), clip = false), cornerRadius = 26.dp, insideMargin = PaddingValues(0.dp), content = content)
    }
    @Composable private fun ConnectButton(modifier: Modifier) {
        TextButton(s(state.action), actions::connect, modifier, minHeight = 48.dp, cornerRadius = 16.dp, insideMargin = PaddingValues(10.dp), colors = ButtonDefaults.textButtonColorsPrimary())
    }
    @Composable private fun DetailsButton(modifier: Modifier) {
        TextButton(s("connection_details"), actions::details, modifier, minHeight = 48.dp, cornerRadius = 16.dp, insideMargin = PaddingValues(10.dp))
    }
    @Composable private fun Section(key: String, dark: Boolean) {
        Text(s(key), fontSize = 13.sp, color = secondary(dark), fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp).semantics { heading() })
    }
    @Composable private fun Label(key: String, dark: Boolean, modifier: Modifier = Modifier) {
        Text(s(key), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = foreground(dark), modifier = modifier)
    }
    @Composable private fun Description(key: String, dark: Boolean) { Text(s(key), fontSize = 12.sp, color = secondary(dark)) }
    @Composable private fun ToggleRow(icon: String, title: String, description: String, key: String, method: String, dark: Boolean) {
        val checked = state.values[key] ?: false
        Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = { actions.toggle(key, method, it) })
            .padding(18.dp, 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, dark)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Label(title, dark); Description(description, dark) }
            // The entire row is one accessible switch; Miuix owns the visual thumb/spring.
            Switch(checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
        }
    }
    @Composable private fun Divider(dark: Boolean) {
        Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(0.5.dp).background(if (dark) Color.White.copy(alpha = .07f) else Color.Black.copy(alpha = .045f)))
    }
    @Composable private fun IconTile(name: String, dark: Boolean) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(if (dark) Color(0xFF303039) else Color(0xFFF2F4F9)), contentAlignment = Alignment.Center) { Icon(name, secondary(dark), Modifier.size(20.dp)) }
    }
    private fun accent(dark: Boolean) = if (dark) DarkAccent else Accent
    companion object { private val Accent = Color(0xFF246BDF); private val DarkAccent = Color(0xFF82B2FF) }
}

/** Small consistent stroke icons without bundling an unused icon font/library. */
@Composable private fun Icon(name: String, baseTint: Color, modifier: Modifier, tintProvider: (() -> Color)? = null) {
    Canvas(modifier) {
        val tint = tintProvider?.invoke() ?: baseTint
        val scale = size.width / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x * scale, y * scale), Offset(x2 * scale, y2 * scale), 1.7f * scale, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val stroke = Stroke(1.7f * scale)
        when (name) {
            "screen" -> { drawRoundRect(tint, Offset(5f*scale,2f*scale), Size(14f*scale,20f*scale), androidx.compose.ui.geometry.CornerRadius(3f*scale), style=stroke); drawRoundRect(tint,Offset(8f*scale,5f*scale),Size(8f*scale,7f*scale), androidx.compose.ui.geometry.CornerRadius(2f*scale),style=stroke);line(10f,19f,14f,19f) }
            "bell" -> { drawArc(tint,180f,180f,false,Offset(6f*scale,4f*scale),Size(12f*scale,12f*scale),style=stroke);line(6f,10f,5f,17f);line(18f,10f,19f,17f);line(5f,17f,19f,17f);drawArc(tint,0f,180f,false,Offset(10f*scale,18f*scale),Size(4f*scale,3f*scale),style=stroke) }
            "info" -> { drawCircle(tint,9f*scale,style=stroke);line(12f,11f,12f,17f);drawCircle(tint,1f*scale,Offset(12f*scale,7f*scale)) }
            "sun" -> { drawCircle(tint,4f*scale,style=stroke);repeat(8){ val a=it*Math.PI/4;line(12f+8f*kotlin.math.cos(a).toFloat(),12f+8f*kotlin.math.sin(a).toFloat(),12f+10f*kotlin.math.cos(a).toFloat(),12f+10f*kotlin.math.sin(a).toFloat()) } }
            "eye" -> { drawOval(tint,Offset(2f*scale,6f*scale),Size(20f*scale,12f*scale),style=stroke);drawCircle(tint,3f*scale,style=stroke) }
            "charge" -> { line(14f,2f,6f,13f);line(6f,13f,12f,13f);line(12f,13f,10f,22f);line(10f,22f,18f,10f);line(18f,10f,12f,10f);line(12f,10f,14f,2f) }
            "rotate" -> { drawArc(tint,35f,285f,false,Offset(3f*scale,3f*scale),Size(18f*scale,18f*scale),style=stroke);line(20f,4f,20f,10f);line(20f,10f,14f,10f) }
            else -> { for (x in listOf(4f,14f)) for(y in listOf(4f,14f)) drawRoundRect(tint,Offset(x*scale,y*scale),Size(6f*scale,6f*scale),androidx.compose.ui.geometry.CornerRadius(scale),style=stroke) }
        }
    }
}
