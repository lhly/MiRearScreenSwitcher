package top.zainzh.rearscreen

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import top.zainzh.rearscreen.modules.ModuleDescriptor
import top.zainzh.rearscreen.modules.ModuleStore

/** All settings destinations share the same Miuix theme and reusable components. */
class MiuixSettingsUi(
    private val activity: MainActivity,
    savedState: Bundle?,
    private val actions: Actions,
) {
    interface Actions {
        fun navigate(target: Int)
        fun settled(target: Int)
        fun privilege()
        fun connect()
        fun details()
        fun dpi()
        fun rotation(target: Int)
        fun toggle(key: String, method: String, enabled: Boolean)
        fun tutorial()
        fun apps()
        fun duration()
        fun action(method: String)
        fun updates()
        fun proxy(enabled: Boolean)
        fun permissions()
        fun automation()
        fun moduleImport()
        fun moduleSettings(id: String)
        fun moduleRear(id: String)
        fun moduleRemove(id: String, onComplete: (Boolean) -> Unit)
    }
    private data class HomeState(
        val status: String = "check_shizuku", val summary: String = "connection_help",
        val action: String = "open_shizuku", val dpi: Int = 0, val rotation: Int = -1,
        val busy: Boolean = false, val values: Map<String, Boolean> = emptyMap(),
    )
    private data class SafeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0, val ime: Int = 0)
    private var state by mutableStateOf(HomeState())
    private data class SettingsState(val selected: Int = 0, val listener: Boolean = false, val posting: Boolean = true,
        val duration: Int = 8, val updates: String = "", val checking: Boolean = false, val proxy: Boolean = false)
    private var settings by mutableStateOf(SettingsState())
    private var picker by mutableStateOf<AppPickerModel?>(null)
    private val notificationsList = savedState.listState("notifications_list")
    private val modulesList = savedState.listState("modules_list")
    private val settingsList = savedState.listState("settings_list")
    private val aboutList = savedState.listState("about_list")
    private var uriConflict by mutableStateOf(false)
    private var navigationHeight by mutableIntStateOf(0)
    private var page by mutableIntStateOf(savedState?.getInt("native_page", 0)?.let {
        if (savedState.getInt("native_route_version", 0) >= 2) it
        else when (it) { 2 -> 3; 3 -> 100; else -> it }
    } ?: 0)
    private var subpage by mutableIntStateOf(savedState?.getInt("native_subpage", 0) ?: 0)
    private var selectedModuleId by mutableStateOf(savedState?.getString("native_module_id").orEmpty())
    private var navigationRequest by mutableIntStateOf(0)
    private var insets by mutableStateOf(SafeInsets())
    private val listState = LazyListState(savedState?.getInt("compose_home_index", 0) ?: 0, savedState?.getInt("compose_home_offset", 0) ?: 0)
    private val view = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val dark = isSystemInDarkTheme()
            val pager = rememberPagerState(initialPage = if (page == 100) 1 else page, pageCount = { 4 })
            LaunchedEffect(navigationRequest) {
                val target = page
                val tab = when (target) { 100, in 20..21 -> 1; in 10..13 -> 0; 30 -> 2; 40 -> 3; else -> target.coerceIn(0, 3) }
                if (Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) pager.scrollToPage(tab)
                else pager.animateScrollToPage(tab, animationSpec = tween(250, easing = pageEasing))
            }
            LaunchedEffect(pager) {
                snapshotFlow { if (pager.isScrollInProgress) null else pager.settledPage }
                    .filterNotNull().collect { if (page != 100 && page < 10) actions.settled(it) }
            }
            AppTheme {
                AppNavigationFrame(Modifier.fillMaxSize(), content = { SurfaceContent(it, dark, pager) }) {
                    Navigation(pager)
                }
            }
        }
    }
    fun getView(): View = view
    fun showPicker(model: AppPickerModel?) { picker = model }
    fun updateSettings(selected: Int, listener: Boolean, posting: Boolean, duration: Int, updates: String, checking: Boolean, proxy: Boolean) {
        settings = SettingsState(selected, listener, posting, duration, updates, checking, proxy)
    }
    fun updateUriConflict(conflict: Boolean) { uriConflict = conflict }
    fun showPage(target: Int) { page = target; navigationRequest++ }
    fun isShowingModuleDetails(id: String): Boolean = page == 30 && selectedModuleId == id
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int, ime: Int) { insets = SafeInsets(left, top, right, bottom, ime) }
    fun updateHome(status: String, summary: String, action: String, dpi: Int, rotation: Int, busy: Boolean, values: Map<String, Boolean>) {
        state = HomeState(status, summary, action, dpi, rotation, busy, values.toMap())
    }
    fun saveState(bundle: Bundle) {
        bundle.saveList("notifications_list", notificationsList)
        bundle.saveList("modules_list", modulesList)
        bundle.saveList("settings_list", settingsList)
        bundle.saveList("about_list", aboutList)
        bundle.putInt("compose_home_index", listState.firstVisibleItemIndex)
        bundle.putInt("compose_home_offset", listState.firstVisibleItemScrollOffset)
        bundle.putInt("native_subpage", subpage)
        bundle.putInt("native_route_version", 2)
        bundle.putString("native_module_id", selectedModuleId)
    }
    private fun s(key: String) = NativeStrings.get(activity, key)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun SurfaceContent(modifier: Modifier, dark: Boolean, pager: PagerState) {
        val d = LocalDensity.current
        val safe = with(d) { PaddingValues(start = insets.left.toDp(), top = insets.top.toDp(), end = insets.right.toDp(), bottom = maxOf(insets.bottom, insets.ime).toDp()) }
        Box(modifier.fillMaxSize().background(AppPalette.background()).padding(safe).clipToBounds(), contentAlignment = Alignment.TopCenter) {
            HorizontalPager(state = pager, beyondViewportPageCount = 1, userScrollEnabled = page == 0 || page in 1..3,
                modifier = Modifier.fillMaxSize().testTag("main_pager").semantics { if (page >= 10 || page == 100) hideFromAccessibility() }) { index ->
                val reserve = navigationHeight + with(d) { 24.dp.toPx() }
                val focusScroll = remember(reserve) { object : BringIntoViewSpec {
                    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                        val visibleEnd = (containerSize - reserve).coerceAtLeast(size)
                        return when { offset < 0f -> offset; offset + size > visibleEnd -> offset + size - visibleEnd; else -> 0f }
                    }
                } }
                CompositionLocalProvider(LocalBringIntoViewSpec provides focusScroll) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        val pageModifier = Modifier.widthIn(max = 680.dp).fillMaxSize().semantics { if (pager.currentPage != index || page >= 10 || page == 100) hideFromAccessibility() }
                        if (pager.currentPage == index && page < 10) {
                            when (index) {
                                0 -> Home(dark, pageModifier)
                                1 -> Notifications(pageModifier)
                                2 -> Modules(pageModifier)
                                3 -> Settings(pageModifier)
                            }
                        }
                    }
                }
            }
            if (page == 100) Box(Modifier.fillMaxSize().background(AppPalette.background()), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 680.dp).fillMaxSize()) { picker?.Content() }
            }
            if (page in 10..13) BackSubpage(page)
            if (page in 20..21) NotificationsSubpage(page)
            if (page == 30) ModuleDetails(selectedModuleId)
            if (page == 40) AboutDetails()

        }
    }

    @Composable private fun BoxScope.Navigation(pager: PagerState) {
        if (page >= 10 || page == 100 || insets.ime > insets.bottom) return
        val bottom = with(LocalDensity.current) { insets.bottom.toDp() }
        Box(Modifier.align(Alignment.BottomCenter)
            .padding(start = 20.dp, end = 20.dp, bottom = bottom + 12.dp)
            .widthIn(max = 360.dp).fillMaxWidth()) {
            AppBottomNavigation(
                progress = { pager.currentPage + pager.currentPageOffsetFraction },
                selectedIndex = pager.currentPage,
                labels = listOf(s("nav_screen"), s("nav_notifications"), s("nav_modules"), s("nav_settings")),
                descriptions = listOf(s("screen_title"), s("notifications_title"), s("nav_modules"), s("nav_settings")),
                onSelected = actions::navigate,
                modifier = Modifier.fillMaxWidth().onSizeChanged { navigationHeight = it.height },
            ) { index, color -> Icon(listOf("screen", "bell", "apps", "settings")[index], color, Modifier.size(23.dp)) }
        }
    }

    private val pageEasing = CubicBezierEasing(.77f, 0f, .175f, 1f)

    @Composable private fun Home(dark: Boolean, modifier: Modifier) {
        val virtualRearCast = rememberVirtualRearCastMode()
        LazyColumn(modifier.testTag("home_list"), state = listState,
            contentPadding = PaddingValues(20.dp, 22.dp, 20.dp,
                maxOf(126.dp, with(LocalDensity.current) { navigationHeight.toDp() } + 32.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") { PageTitle(s("screen_title"), s("screen_subtitle")) }
            if (uriConflict) item("uri_conflict") {
                AppCard { AppRow(s("uri_conflict_title"), s("uri_conflict_help").replace("{0}", BuildConfig.URI_SCHEME + "://"), action = actions::automation) }
            }
            item("connection") {
                ConnectionStatusCard(
                    dark = dark,
                    status = state.status,
                    summary = state.summary,
                    action = if (state.status == "connection_ready") "refresh" else state.action,
                )
            }
            item("cast") { EntryCard("nav_cast", s(if (virtualRearCast) "nav_cast_virtual" else "nav_cast_direct"), "cast_settings_entry") { actions.navigate(10) } }
            item("wake") {
                val screenOn = s(if (state.values["keep_screen_on_enabled"] == true) "status_on" else "status_off")
                val coveredWake = s(if (state.values["always_wakeup_enabled"] == true) "status_on" else "status_off")
                val proximity = s(if (state.values["proximity_sensor_enabled"] == true) "status_on" else "status_off")
                EntryCard("nav_wake", s("nav_wake_state").replace("{0}", screenOn).replace("{1}", coveredWake).replace("{2}", proximity), "wake_settings_entry") { actions.navigate(11) }
            }
            item("charging") {
                val animation = s(if (state.values["charging_animation_enabled"] == true) "status_on" else "status_off")
                val always = s(if (state.values["charging_always_on_enabled"] == true) "status_on" else "status_off")
                EntryCard("nav_charging", s("nav_charging_state").replace("{0}", animation).replace("{1}", always), "charging_settings_entry") { actions.navigate(12) }
            }
            item("screenshots") { EntryCard("nav_screenshots", activity.getString(R.string.rear_screenshot_entry_help), "screenshot_settings_entry") {
                activity.startActivity(android.content.Intent(activity, RearScreenshotActivity::class.java))
            } }
        }
    }

    @Composable private fun EntryCard(title: String, help: String, tag: String, action: () -> Unit) {
        AppCard { AppRow(s(title), s(help), Modifier.testTag(tag), action = action) }
    }

    @Composable private fun ConnectionTitle(dark: Boolean, status: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                .background(AppPalette.accent().copy(alpha = if (dark) .18f else .09f)).clearAndSetSemantics { },
                contentAlignment = Alignment.Center) {
                Icon("screen", AppPalette.accent(), Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (state.values["privilege_root"] == true) "ROOT" else "SHIZUKU",
                    fontSize = 10.sp, color = AppPalette.secondary(), fontWeight = FontWeight.SemiBold)
                Text(s(status), fontSize = 19.sp, color = AppPalette.text(), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            }
        }
    }
    @Composable private fun PrivilegeSourceButton() {
        TextButton(activity.getString(R.string.privilege_source_value, if (state.values["privilege_root"] == true) "Root" else "Shizuku"),
            actions::privilege, minHeight = 48.dp,
            enabled = !state.busy && state.status != "privilege_switching",
            modifier = Modifier.fillMaxWidth().testTag("privilege_source"))
    }
    @Composable private fun ConnectionStatusCard(dark: Boolean, status: String, summary: String, action: String) {
        AppCard(Modifier.testTag("connection_status_card")) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
                val contentWidth = maxWidth
                if (contentWidth >= 520.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ConnectionTitle(dark, status)
                            Text(s(summary), fontSize = 13.sp, color = AppPalette.secondary())
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrivilegeSourceButton()
                            ConnectButton(Modifier.fillMaxWidth(), action)
                            DetailsButton(Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConnectionTitle(dark, status)
                        Text(s(summary), fontSize = 13.sp, color = AppPalette.secondary())
                        PrivilegeSourceButton()
                        if (contentWidth < 260.dp || LocalDensity.current.fontScale > 1.3f) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ConnectButton(Modifier.fillMaxWidth(), action)
                                DetailsButton(Modifier.fillMaxWidth())
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ConnectButton(Modifier.weight(1f), action)
                                DetailsButton(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun BackSubpage(route: Int) {
        val (title, backTag) = when (route) {
            10 -> s("nav_cast") to "cast_settings_back"
            11 -> s("nav_wake") to "wake_settings_back"
            12 -> s("nav_charging") to "charging_settings_back"
            else -> s("nav_screenshots") to "screenshot_settings_back"
        }
        PageFrame(title, { actions.navigate(0) }, backTag) { modifier, header ->
            LazyColumn(modifier, contentPadding = PaddingValues(20.dp, header + 12.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (route) {
                    10 -> item("cast_options") {
                        val virtual = rememberVirtualRearCastMode()
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppCard {
                                RearCastModeToggle(virtual)
                                AppSettingsReveal(virtual) {
                                    AppDivider()
                                    AppRow(activity.getString(R.string.rear_density_title), activity.getString(R.string.rear_density_summary),
                                        modifier = Modifier.testTag("density_compatibility_entry"), action = {
                                            activity.startActivity(android.content.Intent().setClassName(activity, "top.zainzh.rearscreen.DensityCompatibilityActivity"))
                                        })
                                    AppDivider()
                                    RearCameraGapBatteryToggle(virtual)
                                    AppDivider()
                                    RearCameraStatusToggle(virtual)
                                }
                            }
                            if (!virtual) AppCard {
                                AppRow(s("screen_size"), s("dpi_help"), Modifier.testTag("physical_dpi_entry"), action = actions::dpi,
                                    trailing = { Text(if (state.dpi > 0) state.dpi.toString() else "—", color = AppPalette.accent()) })
                                AppDivider()
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    repeat(4) { index ->
                                        TextButton("${index * 90}°", onClick = { actions.rotation(index) }, enabled = !state.busy,
                                            modifier = Modifier.weight(1f), minHeight = 48.dp)
                                    }
                                }
                            }
                        }
                    }
                    11 -> item("wake_options") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppCard {
                                SettingsToggle("proximity_sensor", "proximity_help", "proximity_sensor_enabled", "setProximitySensorEnabled")
                                AppDivider()
                                SettingsToggle("keep_screen_on", "keep_help", "keep_screen_on_enabled", "setKeepScreenOnEnabled")
                                AppDivider()
                                SettingsToggle("always_wake_up", "always_help", "always_wakeup_enabled", "setAlwaysWakeUpEnabled")
                            }
                            Text(s("warning_burn_in"), Modifier.padding(14.dp), fontSize = 12.sp, color = AppPalette.secondary())
                        }
                    }
                    12 -> item("charging_options") {
                        AppCard {
                            SettingsToggle("charging_animation", "charging_help", "charging_animation_enabled", "toggleChargingService")
                            AppDivider()
                            SettingsToggle("charging_always_on", "charging_always_help", "charging_always_on_enabled", "setChargingAlwaysOnEnabled")
                        }
                    }
                    13 -> item("screenshot_options") {
                        AppCard { AppRow(activity.getString(R.string.rear_screenshot_title), activity.getString(R.string.rear_screenshot_entry_help),
                            action = { activity.startActivity(android.content.Intent(activity, RearScreenshotActivity::class.java)) }) }
                    }
                }
            }
        }
    }

    @Composable private fun Modules(modifier: Modifier) {
        var modules by remember { mutableStateOf<List<ModuleDescriptor>>(emptyList()) }
        var enabled by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
        var message by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var initialLoaded by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        fun refresh() {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val all = ModuleStore.list(activity)
                    all to all.associate { it.id to ModuleStore.isEnabled(activity, it.id) }
                }
                modules = result.first
                enabled = result.second
                initialLoaded = true
            }
        }
        if (BuildConfig.DEBUG) DisposableEffect(Unit) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) { refresh() }
            }
            androidx.core.content.ContextCompat.registerReceiver(activity, receiver,
                android.content.IntentFilter(ModuleStore.ACTION_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            refresh()
            onDispose { runCatching { activity.unregisterReceiver(receiver) } }
        } else LaunchedEffect(Unit) { initialLoaded = true }
        if (!initialLoaded) {
            Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PageTitle(s("nav_modules"), s("native_modules_trust"))
                AppCard { AppRow(activity.getString(R.string.native_modules_loading), null) }
            }
        } else LazyColumn(modifier.testTag("modules_tab"), state = modulesList, contentPadding = PaddingValues(20.dp, 22.dp, 20.dp,
            maxOf(126.dp, with(LocalDensity.current) { navigationHeight.toDp() } + 32.dp)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") { PageTitle(s("nav_modules"), s("native_modules_trust")) }
            item("zip_mods") { AppCard { AppRow(activity.getString(R.string.zip_mods_title), activity.getString(R.string.zip_mods_summary),
                Modifier.testTag("zip_mods_entry"), action = {
                    activity.startActivity(Intent(activity, top.zainzh.rearscreen.platform.mods.ModSettingsActivity::class.java))
                }) } }
            if (BuildConfig.DEBUG) {
                item("legacy_debug_heading") { Text(activity.getString(R.string.native_modules_debug_only), Modifier.padding(horizontal = 12.dp), color = AppPalette.secondary()) }
                item("import") { AppCard { AppRow(s("native_modules_import"), s("native_modules_import_help"), enabled = !busy, action = actions::moduleImport) } }
                if (message.isNotBlank()) item("message") { Text(message, Modifier.padding(horizontal = 12.dp), color = AppPalette.secondary()) }
                if (modules.isEmpty()) item("empty") { AppCard { AppRow(s("native_modules_empty"), s("native_modules_empty_help")) } }
                items(modules, key = { it.id }) { descriptor ->
                    val isEnabled = enabled[descriptor.id] == true
                    AppCard(Modifier.testTag("native_module_${descriptor.id}")) {
                        AppRow(descriptor.label(activity.resources.configuration.locales[0].toLanguageTag()),
                            descriptor.description(activity.resources.configuration.locales[0].toLanguageTag()) + " · " + descriptor.versionName,
                            action = { selectedModuleId = descriptor.id; actions.navigate(30) }, trailing = {
                                Switch(isEnabled, onCheckedChange = { value ->
                                    scope.launch {
                                        busy = true
                                        val result = withContext(Dispatchers.IO) { runCatching { ModuleStore.setEnabled(activity, descriptor.id, value) } }
                                        message = result.exceptionOrNull()?.message.orEmpty()
                                        busy = false
                                        refresh()
                                    }
                                }, enabled = !busy)
                            })
                    }
                }
            }
        }
    }

    @Composable private fun ModuleDetails(id: String) {
        var descriptor by remember(id) { mutableStateOf<ModuleDescriptor?>(null) }
        var enabled by remember(id) { mutableStateOf(false) }
        var loading by remember(id) { mutableStateOf(true) }
        var busy by remember(id) { mutableStateOf(false) }
        var removePrompt by remember(id) { mutableStateOf(false) }
        var removing by remember(id) { mutableStateOf(false) }
        var removeFailed by remember(id) { mutableStateOf(false) }
        var moduleMessage by remember(id) { mutableStateOf("") }
        var moduleLookupGeneration by remember(id) { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(id) {
            val generation = ++moduleLookupGeneration
            val result = withContext(Dispatchers.IO) {
                val module = ModuleStore.get(activity, id)
                module to (module != null && ModuleStore.isEnabled(activity, id))
            }
            if (generation == moduleLookupGeneration) {
                descriptor = result.first
                enabled = result.second
                loading = false
                if (result.first == null) actions.navigate(2)
            }
        }
        DisposableEffect(id, scope) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    if (intent?.action != ModuleStore.ACTION_CHANGED || intent.getStringExtra("id") != id) return
                    val generation = ++moduleLookupGeneration
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val module = ModuleStore.get(activity, id)
                            module to (module != null && ModuleStore.isEnabled(activity, id))
                        }
                        withContext(Dispatchers.Main.immediate) {
                            if (generation != moduleLookupGeneration) return@withContext
                            loading = false
                            val latestDescriptor = result.first
                            val loadedVersion = descriptor?.versionCode
                            if (latestDescriptor == null ||
                                (loadedVersion != null && latestDescriptor.versionCode != loadedVersion)
                            ) {
                                actions.navigate(2)
                            } else {
                                descriptor = latestDescriptor
                                enabled = result.second
                            }
                        }
                    }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(activity, receiver,
                android.content.IntentFilter(ModuleStore.ACTION_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            onDispose { runCatching { activity.unregisterReceiver(receiver) } }
        }
        if (loading || descriptor == null) {
            return
        }
        val currentDescriptor = descriptor ?: return
        val locale = activity.resources.configuration.locales[0].toLanguageTag()
        val moduleTitle = currentDescriptor.label(locale)
        val controlsEnabled = enabled && !busy && !removing
        val disabledReason = s(if (busy || removing) "native_modules_working" else "native_modules_enable_required")
        val destructiveColors = ButtonDefaults.textButtonColorsPrimary().copy(
            color = AppPalette.destructive(),
            disabledColor = AppPalette.destructive().copy(alpha = .32f),
            textColor = AppPalette.onDestructive(),
            disabledTextColor = AppPalette.onDestructive().copy(alpha = .6f),
        )
        PageFrame(currentDescriptor.label(activity.resources.configuration.locales[0].toLanguageTag()), { actions.navigate(2) }, "module_detail_back") { modifier, header ->
            LazyColumn(
                modifier.testTag("module_detail_list"),
                contentPadding = PaddingValues(20.dp, header + 12.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("module_status") {
                    AppCard {
                        AppToggle(
                            s("native_modules_enable_module"),
                            null,
                            checked = enabled,
                            enabled = !busy && !removing,
                            modifier = Modifier.testTag("module_detail_enabled"),
                        ) { value ->
                            if (busy || removing) return@AppToggle
                            scope.launch {
                                busy = true
                                moduleMessage = ""
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { ModuleStore.setEnabled(activity, id, value) }
                                }
                                if (result.isSuccess) {
                                    enabled = value
                                } else {
                                    moduleMessage = result.exceptionOrNull()?.message
                                        ?: s("native_modules_action_failed")
                                }
                                busy = false
                            }
                        }
                        if (!enabled) {
                            AppDivider()
                            Text(
                                s("native_modules_disabled_detail_help"),
                                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)
                                    .testTag("module_detail_disabled_hint"),
                                fontSize = 13.sp,
                                color = AppPalette.secondary(),
                            )
                        }
                        if (moduleMessage.isNotBlank()) {
                            AppDivider()
                            Text(
                                moduleMessage,
                                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                fontSize = 13.sp,
                                color = AppPalette.accent(),
                            )
                        }
                    }
                }
                item("summary") {
                    AppCard {
                        AppRow(
                            currentDescriptor.description(locale),
                            currentDescriptor.versionName,
                        )
                    }
                }
                item("actions") {
                    AppCard {
                        AppRow(
                            s("native_modules_settings"),
                            if (controlsEnabled) null else disabledReason,
                            Modifier.testTag("module_detail_settings").graphicsLayer(alpha = if (controlsEnabled) 1f else .55f),
                            enabled = controlsEnabled,
                            action = { if (controlsEnabled) actions.moduleSettings(id) },
                            trailing = if (controlsEnabled) null else ({ }),
                        )
                        AppDivider()
                        AppRow(
                            s("native_modules_rear"),
                            if (controlsEnabled) s("native_modules_rear_help") else disabledReason,
                            Modifier.testTag("module_detail_rear").graphicsLayer(alpha = if (controlsEnabled) 1f else .55f),
                            enabled = controlsEnabled,
                            action = { if (controlsEnabled) actions.moduleRear(id) },
                            trailing = if (controlsEnabled) null else ({ }),
                        )
                    }
                }
                item("uninstall") {
                    TextButton(
                        s("native_modules_uninstall"),
                        onClick = { if (!busy && !removing) { removeFailed = false; removePrompt = true } },
                        modifier = Modifier.fillMaxWidth().testTag("module_detail_uninstall"),
                        enabled = !busy && !removing,
                        minHeight = 52.dp,
                        cornerRadius = 18.dp,
                        colors = destructiveColors,
                    )
                }
            }
        }
        WindowDialog(
            show = removePrompt,
            title = s("native_modules_uninstall_confirm_title"),
            onDismissRequest = { if (!removing) removePrompt = false },
        ) {
            Column(
                Modifier.fillMaxWidth().testTag("module_uninstall_dialog"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    activity.getString(R.string.native_modules_uninstall_confirm_message, moduleTitle),
                    Modifier.testTag("module_uninstall_module_name"),
                    fontSize = 14.sp,
                    color = AppPalette.secondary(),
                )
                if (removeFailed) {
                    Text(s("native_modules_action_failed"), color = AppPalette.accent())
                }
                TextButton(
                    s("cancel"),
                    onClick = { if (!removing) removePrompt = false },
                    modifier = Modifier.fillMaxWidth().testTag("module_uninstall_cancel"),
                    enabled = !removing,
                    minHeight = 48.dp,
                )
                TextButton(
                    s("native_modules_uninstall"),
                    onClick = {
                        if (busy || removing) return@TextButton
                        removing = true
                        removeFailed = false
                        actions.moduleRemove(id) { success ->
                            removing = false
                            if (success) {
                                removePrompt = false
                            } else {
                                removeFailed = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("module_uninstall_confirm"),
                    enabled = !busy && !removing,
                    minHeight = 48.dp,
                    colors = destructiveColors,
                )
            }
        }
    }

    @Composable private fun Notifications(modifier: Modifier) {
        LazyColumn(modifier.testTag("notifications_list"), state = notificationsList,
            contentPadding = PaddingValues(20.dp, 22.dp, 20.dp, maxOf(126.dp, with(LocalDensity.current) { navigationHeight.toDp() } + 32.dp)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") { PageTitle(s("notifications_title"), s("notifications_subtitle")) }
            item("delivery") {
                AppCard {
                    SettingsToggle("notification_service", "notification_help", "notification_service_enabled", "toggleNotificationService")
                    AppDivider()
                    AppRow(s("notification_apps"), s("selected_count").replace("{0}", settings.selected.toString()), action = actions::apps)
                    AppDivider()
                    AppRow(s("notification_access"), s("notification_access_help"), action = { actions.action("openNotificationListenerSettings") },
                        trailing = { Text(s(if (settings.listener) "access_granted" else "access_pending"), color = AppPalette.accent(), fontSize = 13.sp, modifier = Modifier.widthIn(max = 100.dp)) })
                    if (!settings.posting) {
                        AppDivider()
                        AppRow(s("notification_permission"), s("notification_permission_help"), action = { actions.action("requestNotificationPermission") })
                    }
                }
            }
            item("privacy") { AppCard { AppRow(s("privacy_section"), null, action = { actions.navigate(20) }) } }
            item("rules") { AppCard { AppRow(s("notification_rules"), s("duration_help"), action = { actions.navigate(21) }) } }
        }
    }

    @Composable private fun NotificationsSubpage(route: Int) {
        val privacy = route == 20
        val title = s(if (privacy) "privacy_section" else "notification_rules")
        PageFrame(title, { actions.navigate(1) }, if (privacy) "notification_privacy_back" else "notification_rules_back") { modifier, header ->
            LazyColumn(modifier.testTag(if (privacy) "notification_privacy_list" else "notification_rules_list"),
                contentPadding = PaddingValues(20.dp, header + 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    AppCard {
                        if (privacy) {
                            SettingsToggle("hide_notification_title", "hide_title_help", "notification_privacy_hide_title", "setNotificationPrivacyHideTitle")
                            AppDivider()
                            SettingsToggle("hide_notification_content", "hide_content_help", "notification_privacy_hide_content", "setNotificationPrivacyHideContent")
                            AppDivider()
                            SettingsToggle("notification_dark_mode", "dark_notification_help", "notification_dark_mode", "setNotificationDarkMode")
                        } else {
                            SettingsToggle("follow_system_dnd", "dnd_help", "notification_follow_dnd_mode", "setFollowDndMode")
                            AppDivider()
                            SettingsToggle("only_when_locked", "upside_help", "notification_only_when_locked", "setOnlyWhenLocked")
                            AppDivider()
                            AppRow(s("auto_destroy_time"), s("duration_help"), action = actions::duration,
                                trailing = { Text(s("seconds_value").replace("{0}", settings.duration.toString()), fontSize = 15.sp, color = AppPalette.accent(), modifier = Modifier.widthIn(max = 100.dp)) })
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Settings(modifier: Modifier) {
        LazyColumn(modifier.testTag("settings_list"), state = settingsList,
            contentPadding = PaddingValues(20.dp, 22.dp, 20.dp, maxOf(126.dp, with(LocalDensity.current) { navigationHeight.toDp() } + 32.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") { PageTitle(s("nav_settings")) }
            item("runtime") {
                AppSection(s("settings_runtime"))
                AppCard {
                    AppRow(activity.getString(R.string.ui_permissions_title), activity.getString(R.string.ui_permissions_menu_help),
                        modifier = Modifier.testTag("permission_checks_menu"), action = actions::permissions)
                    AppDivider()
                    AppRow(s("diagnostics"), null, action = actions::details)
                }
            }
            item("usage") {
                AppSection(s("settings_usage"))
                AppCard {
                    AppRow(s("tutorial"), activity.getString(R.string.tutorial_entry_help), action = actions::tutorial)
                    AppDivider()
                    AppRow(s("uri_title"), s("uri_help").replace("mrss://", BuildConfig.URI_SCHEME + "://"), action = actions::automation)
                }
            }
            item("support") {
                AppSection(s("settings_support"))
                AppCard {
                    AppRow(s("check_updates"), settings.updates, enabled = !settings.checking, action = actions::updates,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    AppDivider()
                    AppToggle(s("update_proxy"), s("update_proxy_help"), settings.proxy, !settings.checking,
                        onChange = actions::proxy)
                    AppDivider()
                    AppRow(s("about"), s("nav_about_help"), action = { actions.navigate(40) })
                }
            }
            if (BuildConfig.DEBUG) item("debug_tools") {
                AppCard {
                    AppRow(activity.getString(R.string.rear_info_title), activity.getString(R.string.rear_info_summary),
                        action = { activity.startActivity(android.content.Intent().setClassName(activity, "top.zainzh.rearscreen.RearInfoSettingsActivity")) })
                }
            }
        }
    }

    @Composable private fun AboutDetails() {
        PageFrame(s("about"), { actions.navigate(3) }, "about_details_back") { modifier, header ->
            LazyColumn(modifier.testTag("about_list"), state = aboutList,
                contentPadding = PaddingValues(20.dp, header + 12.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item("brand") {
                    Row(Modifier.padding(4.dp, 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_app_logo),
                            contentDescription = null, modifier = Modifier.size(64.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("ReMRSS", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppPalette.text())
                            Text(s("version_label").replace("{0}", BuildConfig.VERSION_NAME), fontSize = 14.sp, color = AppPalette.secondary())
                        }
                    }
                }
                item("project") {
                    AppCard {
                        AppRow(s("project_repository"), "GitHub · lhly/MiRearScreenSwitcher\n" +
                            s("project_maintainer").replace("{0}", ProjectInfo.MAINTAINER), action = { actions.action("openProject") })
                        AppDivider()
                        AppRow(s("issue_feedback"), "GitHub Issues", action = { actions.action("openFeedback") })
                        AppDivider()
                        AppRow(s("author_anti"), null, action = { actions.action("openOriginalProject") })
                    }
                }
            }
        }
    }

    @Composable private fun PageTitle(title: String, subtitle: String? = null) {
        Column(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AppPalette.text(), modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = AppPalette.secondary())
        }
    }
    @Composable private fun SettingsToggle(title: String, help: String, key: String, method: String) {
        AppToggle(s(title), s(help), state.values[key] ?: false) { actions.toggle(key, method, it) }
    }

    @Composable private fun Group(content: @Composable ColumnScope.() -> Unit) {
        AppCard(content = content)
    }
    @Composable private fun ConnectButton(modifier: Modifier, action: String = state.action) {
        TextButton(s(action), actions::connect, modifier, enabled = !state.busy && state.status !in setOf("privilege_root_authorizing", "connection_binding", "privilege_switching"), minHeight = 48.dp, cornerRadius = 16.dp, insideMargin = PaddingValues(10.dp), colors = ButtonDefaults.textButtonColorsPrimary())
    }
    @Composable private fun DetailsButton(modifier: Modifier) {
        TextButton(s("connection_details"), actions::details, modifier, minHeight = 48.dp, cornerRadius = 16.dp, insideMargin = PaddingValues(10.dp))
    }
    @Composable private fun Section(key: String, dark: Boolean) {
        Text(s(key), fontSize = 13.sp, color = AppPalette.secondary(), fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp).semantics { heading() })
    }
    @Composable private fun Label(key: String, dark: Boolean, modifier: Modifier = Modifier) {
        Text(s(key), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppPalette.text(), modifier = modifier)
    }
    @Composable private fun Description(key: String, dark: Boolean) { Text(s(key), fontSize = 12.sp, color = AppPalette.secondary()) }
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
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(if (dark) Color(0xFF303039) else Color(0xFFF2F4F9)), contentAlignment = Alignment.Center) { Icon(name, AppPalette.secondary(), Modifier.size(20.dp)) }
    }
}

/** Small consistent stroke icons without bundling an unused icon font/library. */
@Composable private fun Icon(name: String, baseTint: Color, modifier: Modifier, tintProvider: (() -> Color)? = null) {
    val background = AppPalette.background()
    Canvas(modifier) {
        val tint = tintProvider?.invoke() ?: baseTint
        val scale = size.width / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x * scale, y * scale), Offset(x2 * scale, y2 * scale), 1.7f * scale, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val stroke = Stroke(1.7f * scale)
        when (name) {
            "screen" -> { drawRoundRect(tint, Offset(5f*scale,2f*scale), Size(14f*scale,20f*scale), androidx.compose.ui.geometry.CornerRadius(3f*scale), style=stroke); drawRoundRect(tint,Offset(8f*scale,5f*scale),Size(8f*scale,7f*scale), androidx.compose.ui.geometry.CornerRadius(2f*scale),style=stroke);line(10f,19f,14f,19f) }
            "bell" -> { drawArc(tint,180f,180f,false,Offset(6f*scale,4f*scale),Size(12f*scale,12f*scale),style=stroke);line(6f,10f,5f,17f);line(18f,10f,19f,17f);line(5f,17f,19f,17f);drawArc(tint,0f,180f,false,Offset(10f*scale,18f*scale),Size(4f*scale,3f*scale),style=stroke) }
            "info" -> { drawCircle(tint,9f*scale,style=stroke);line(12f,11f,12f,17f);drawCircle(tint,1f*scale,Offset(12f*scale,7f*scale)) }
            "settings" -> {
                drawCircle(tint, 7f * scale, style = stroke)
                repeat(8) { index ->
                    val angle = index * Math.PI / 4
                    val inner = 7.5f
                    val outer = 10.5f
                    line(12f + inner * kotlin.math.cos(angle).toFloat(), 12f + inner * kotlin.math.sin(angle).toFloat(),
                        12f + outer * kotlin.math.cos(angle).toFloat(), 12f + outer * kotlin.math.sin(angle).toFloat())
                }
                drawCircle(background, 2.7f * scale)
                drawCircle(tint, 2.7f * scale, style = stroke)
            }
            "sun" -> { drawCircle(tint,4f*scale,style=stroke);repeat(8){ val a=it*Math.PI/4;line(12f+8f*kotlin.math.cos(a).toFloat(),12f+8f*kotlin.math.sin(a).toFloat(),12f+10f*kotlin.math.cos(a).toFloat(),12f+10f*kotlin.math.sin(a).toFloat()) } }
            "eye" -> { drawOval(tint,Offset(2f*scale,6f*scale),Size(20f*scale,12f*scale),style=stroke);drawCircle(tint,3f*scale,style=stroke) }
            "charge" -> { line(14f,2f,6f,13f);line(6f,13f,12f,13f);line(12f,13f,10f,22f);line(10f,22f,18f,10f);line(18f,10f,12f,10f);line(12f,10f,14f,2f) }
            "rotate" -> { drawArc(tint,35f,285f,false,Offset(3f*scale,3f*scale),Size(18f*scale,18f*scale),style=stroke);line(20f,4f,20f,10f);line(20f,10f,14f,10f) }
            else -> { for (x in listOf(4f,14f)) for(y in listOf(4f,14f)) drawRoundRect(tint,Offset(x*scale,y*scale),Size(6f*scale,6f*scale),androidx.compose.ui.geometry.CornerRadius(scale),style=stroke) }
        }
    }
}
