package top.zainzh.rearscreen

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/** Data and lifecycle for the Miuix app picker; preferences remain compatible with services. */
class AppPickerModel(private val activity: MainActivity, saved: Bundle?, private val back: Runnable) {
    internal data class App(val info: ApplicationInfo, val name: String, val system: Boolean)
    private val preferences = activity.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE)
    private val executor = Executors.newFixedThreadPool(2)
    private val icons = LruCache<String, Drawable>(64)
    private var destroyed = false
    private var granted = queryGranted()
    internal var selected by mutableStateOf(preferences.getStringSet("notification_selected_apps", emptySet())!!.toSet())
        private set
    internal var includeSystem by mutableStateOf(saved?.getBoolean("native_include_system", false) ?: false)
    internal var search by mutableStateOf(saved?.getString("native_search", "") ?: "")
    internal var loading by mutableStateOf(false)
    internal var failure by mutableStateOf<String?>(null)
    internal var apps by mutableStateOf(emptyList<App>())
    private val list = saved.listState("picker_list")
    init { load() }
    fun onResume() { val next = queryGranted(); if (next != granted) { granted = next; load() } }
    fun destroy() { destroyed = true; executor.shutdownNow(); icons.evictAll() }
    @JvmName("snapshotForExit")
    internal fun snapshotForExit(): AppPickerExitSnapshot = AppPickerExitSnapshot(
        apps = apps.toList(),
        selected = selected.toSet(),
        includeSystem = includeSystem,
        granted = granted,
        search = search,
        loading = loading,
        failure = failure,
        list = list,
        loadedIcons = icons.snapshot().mapNotNull { (packageName, drawable) ->
            (drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()?.let { packageName to it }
        }.toMap(),
    )
    fun saveState(bundle: Bundle) {
        bundle.putBoolean("native_include_system", includeSystem); bundle.putString("native_search", search); bundle.saveList("picker_list", list)
    }
    private fun queryGranted() = activity.checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == PackageManager.PERMISSION_GRANTED
    internal fun load() {
        if (destroyed || loading) return
        loading = true
        executor.execute {
            val result = runCatching { activity.packageManager.getInstalledApplications(0).filter { it.packageName != activity.packageName }.map {
                App(it, activity.packageManager.getApplicationLabel(it).toString(), it.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                    it.packageName.startsWith("com.android.") || it.packageName.startsWith("com.google.android.") || it.packageName.startsWith("android"))
            } }
            activity.runOnUiThread { if (!destroyed) { loading = false; apps = result.getOrDefault(emptyList()); failure = result.exceptionOrNull()?.message } }
        }
    }
    internal val queryPackagesGranted: Boolean get() = granted
    internal val canInteract: Boolean get() = !destroyed
    internal fun renderState() = AppPickerRenderState(apps, selected, includeSystem, search, loading, failure, list)
    internal fun choose(packages: List<String>, enabled: Boolean) {
        selected = if (enabled) selected + packages else selected - packages.toSet()
        preferences.edit().putStringSet("notification_selected_apps", selected.toSet()).apply()
    }
    internal fun backFromContent() { back.run() }
    internal fun requestAccessFromContent() { activity.runAction("requestQueryAllPackagesPermission", null, null) }
    @Composable internal fun Content() { AppPickerSlot(this, null) }
    @Composable internal fun renderIcon(app: App) { AppIcon(app) }
    @Composable private fun AppIcon(app: App) {
        var bitmap by remember(app.info.packageName) { mutableStateOf(icons.get(app.info.packageName)?.toBitmap(80, 80)?.asImageBitmap()) }
        DisposableEffect(app.info.packageName) {
            if (destroyed || executor.isShutdown) return@DisposableEffect onDispose { }
            var attached = true
            val future: Future<*> = executor.submit {
                val icon = runCatching { activity.packageManager.getApplicationIcon(app.info).toBitmap(80, 80) }.getOrNull()
                activity.runOnUiThread { if (!destroyed && icon != null) {
                    icons.put(app.info.packageName, android.graphics.drawable.BitmapDrawable(activity.resources, icon))
                    if (attached) bitmap = icon.asImageBitmap()
                } }
            }
            onDispose { attached = false; future.cancel(true) }
        }
        val image = bitmap
        if (image != null) Image(image, contentDescription = null, modifier = Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
    }
}

internal data class AppPickerExitSnapshot(
    val apps: List<AppPickerModel.App>,
    val selected: Set<String>,
    val includeSystem: Boolean,
    val granted: Boolean,
    val search: String,
    val loading: Boolean,
    val failure: String?,
    val list: LazyListState,
    val loadedIcons: Map<String, ImageBitmap>,
)

internal data class AppPickerRenderState(
    val apps: List<AppPickerModel.App>,
    val selected: Set<String>,
    val includeSystem: Boolean,
    val search: String,
    val loading: Boolean,
    val failure: String?,
    val list: LazyListState,
)

@Composable internal fun AppPickerSlot(model: AppPickerModel?, snapshot: AppPickerExitSnapshot?) {
    val state = model?.renderState() ?: snapshot?.let {
        AppPickerRenderState(it.apps, it.selected, it.includeSystem, it.search, it.loading, it.failure, it.list)
    } ?: return
    val interactive = model?.canInteract == true
    val granted = model?.queryPackagesGranted ?: snapshot?.granted ?: false
    PickerContent(
        state = state,
        interactive = interactive,
        granted = granted,
        onBack = { model?.takeIf { it.canInteract }?.backFromContent() },
        onSearch = { query -> model?.takeIf { it.canInteract }?.let { it.search = query } },
        onIncludeSystem = { included -> model?.takeIf { it.canInteract }?.let { it.includeSystem = included } },
        onChoose = { packages, enabled -> model?.takeIf { it.canInteract }?.choose(packages, enabled) },
        onRefresh = { model?.takeIf { it.canInteract }?.load() },
        onRequestAccess = { model?.takeIf { it.canInteract }?.requestAccessFromContent() },
        icon = { app ->
            if (model != null && model.canInteract) model.renderIcon(app)
            else snapshot?.loadedIcons?.get(app.info.packageName)?.let {
                Image(it, contentDescription = null, modifier = Modifier.size(40.dp))
            } ?: Spacer(Modifier.size(40.dp))
        },
    )
}

@Composable internal fun AppPickerExitContent(snapshot: AppPickerExitSnapshot) {
    AppPickerSlot(null, snapshot)
}

private val pickerReorderEasing = CubicBezierEasing(.23f, 1f, .32f, 1f)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun PickerContent(
    state: AppPickerRenderState,
    interactive: Boolean,
    granted: Boolean,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onIncludeSystem: (Boolean) -> Unit,
    onChoose: (List<String>, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRequestAccess: () -> Unit,
    icon: @Composable (AppPickerModel.App) -> Unit,
) {
    val context = LocalContext.current
    fun s(key: String) = NativeStrings.get(context, key)
    val locale = LocalConfiguration.current.locales[0]
    val visible = remember(state.apps, state.search, state.includeSystem, state.selected, locale) {
        val query = state.search.trim().lowercase(Locale.ROOT)
        val collator = Collator.getInstance(locale)
        state.apps.filter { (state.includeSystem || !it.system) && (query.isEmpty() || it.name.lowercase(Locale.ROOT).contains(query) || it.info.packageName.lowercase(Locale.ROOT).contains(query)) }
            .sortedWith { a, b ->
                val picked = state.selected.contains(b.info.packageName).compareTo(state.selected.contains(a.info.packageName))
                if (picked != 0) picked else collator.compare(a.name, b.name)
            }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    var filterGeneration by remember { mutableIntStateOf(0) }
    var selectionAnimationGeneration by remember { mutableIntStateOf(-1) }
    var placementAnimationsSuppressed by remember { mutableStateOf(false) }
    var placementBarrierPhase by remember { mutableIntStateOf(2) }
    val expectedLayoutKeys = remember(visible) {
        buildList<Any> {
            add("title")
            add("filters")
            if (visible.isEmpty()) add("empty") else addAll(visible.map { it.info.packageName })
        }
    }
    val observedFilterGeneration = filterGeneration
    val observedPlacementSuppression = placementAnimationsSuppressed
    LaunchedEffect(selectionAnimationGeneration) {
        val generation = selectionAnimationGeneration
        if (generation >= 0) {
            delay(180)
            if (selectionAnimationGeneration == generation) selectionAnimationGeneration = -1
        }
    }
    fun choose(packages: List<String>, enabled: Boolean) {
        if (!interactive) return
        val next = if (enabled) state.selected + packages else state.selected - packages.toSet()
        if (next != state.selected) {
            if (placementBarrierPhase == 2 && !placementAnimationsSuppressed) {
                selectionAnimationGeneration = filterGeneration
            } else {
                selectionAnimationGeneration = -1
                placementAnimationsSuppressed = true
                placementBarrierPhase = 0
            }
        }
        onChoose(packages, enabled)
    }
    AppPageContent(s("notification_apps"), { keyboard?.hide(); if (interactive) onBack() }, "app_picker_back") { pageModifier, headerInset ->
        LazyColumn(pageModifier.testTag("app_picker_list").onPlaced {
            if (observedFilterGeneration == filterGeneration) {
                val layout = state.list.layoutInfo
                val hasExpectedLayout = layout.totalItemsCount == expectedLayoutKeys.size &&
                    layout.visibleItemsInfo.isNotEmpty() && layout.visibleItemsInfo.all { item ->
                        item.index in expectedLayoutKeys.indices && expectedLayoutKeys[item.index] == item.key
                    }
                if (hasExpectedLayout) when {
                    observedPlacementSuppression && placementBarrierPhase == 0 -> {
                        placementBarrierPhase = 1
                        placementAnimationsSuppressed = false
                    }
                    !observedPlacementSuppression && placementBarrierPhase == 1 -> placementBarrierPhase = 2
                }
            }
        }, state = state.list,
            contentPadding = PaddingValues(20.dp, headerInset + 12.dp, 20.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") {
                Text(s("apps_summary").replace("{0}", state.selected.size.toString()).replace("{1}", visible.size.toString()),
                    fontSize = 13.sp, color = AppPalette.secondary())
            }
            item("filters") {
                TextField(state.search, { if (interactive) {
                    filterGeneration++
                    selectionAnimationGeneration = -1
                    placementAnimationsSuppressed = true
                    placementBarrierPhase = 0
                    onSearch(it)
                } }, label = s("search_apps"), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("app_search").then(
                        if (interactive) Modifier else Modifier.focusProperties { canFocus = false }
                    ), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                AppToggle(s("system_apps"), null, state.includeSystem, enabled = interactive,
                    modifier = Modifier.testTag("picker_system_apps_toggle")) {
                    if (interactive) {
                        filterGeneration++
                        selectionAnimationGeneration = -1
                        placementAnimationsSuppressed = true
                        placementBarrierPhase = 0
                        onIncludeSystem(it)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(s("select_all"), { choose(visible.map { it.info.packageName }, true) }, minHeight = 48.dp, enabled = interactive)
                    TextButton(s("deselect_all"), { choose(visible.map { it.info.packageName }, false) }, minHeight = 48.dp, enabled = interactive)
                    TextButton(s("refresh"), { if (interactive) onRefresh() }, minHeight = 48.dp, enabled = interactive && !state.loading)
                }
                if (!granted) TextButton(s("apps_access_help"), { if (interactive) onRequestAccess() }, minHeight = 48.dp,
                    enabled = interactive, modifier = Modifier.testTag("apps_access_help"))
            }
            if (visible.isEmpty()) item("empty") { Text(state.failure ?: s(if (state.loading) "loading_apps" else "no_apps_found"), color = AppPalette.secondary()) }
            items(visible, key = { it.info.packageName }) { app ->
                val placementModifier = if (placementAnimationsSuppressed) Modifier else Modifier.animateItem(
                    fadeInSpec = null,
                    placementSpec = if (selectionAnimationGeneration == filterGeneration)
                        tween<IntOffset>(180, easing = pickerReorderEasing) else snap(),
                    fadeOutSpec = null,
                )
                AppCard(placementModifier.testTag("picker_app_${app.info.packageName}")) {
                    Row(Modifier.fillMaxWidth().toggleable(state.selected.contains(app.info.packageName), enabled = interactive,
                        role = Role.Checkbox, onValueChange = { choose(listOf(app.info.packageName), it) }).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        icon(app)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(app.name, fontSize = 16.sp, color = AppPalette.text())
                            Text(app.info.packageName, fontSize = 12.sp, color = AppPalette.secondary())
                        }
                        Checkbox(if (state.selected.contains(app.info.packageName)) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                            onClick = null, modifier = Modifier.clearAndSetSemantics { })
                    }
                }
            }
        }
    }
}
