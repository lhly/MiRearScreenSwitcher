package top.zainzh.rearscreen

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private object DensityBulkOperation {
    var running by mutableStateOf(false)
}

/** Per-App policy editor; never changes an already running cast. */
class DensityCompatibilityActivity : ComponentActivity() {
    private data class App(val packageName: String, val label: String)
    private var bulkError by mutableStateOf(false)
    private var bulkResult by mutableStateOf<String?>(null)
    private var apps by mutableStateOf(emptyList<App>())
    private var search by mutableStateOf("")
    private var loading by mutableStateOf(true)
    private var failed by mutableStateOf(false)
    private var revision by mutableIntStateOf(0)
    private var editingPackage by mutableStateOf<String?>(null)
    private var draft by mutableStateOf("")
    private var invalid by mutableStateOf(false)
    private lateinit var list: androidx.compose.foundation.lazy.LazyListState
    private lateinit var preferences: SharedPreferences
    private val preferenceRefreshQueued = AtomicBoolean(false)
    private val refreshPreferences = Runnable {
        preferenceRefreshQueued.set(false)
        if (!isDestroyed && !isFinishing) revision++
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key.startsWith("enabled:") || key.startsWith("dpi:"))
            && preferenceRefreshQueued.compareAndSet(false, true)) mainHandler.post(refreshPreferences)
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "density-compat-worker").apply { isDaemon = true }
        }
        val mainHandler = Handler(Looper.getMainLooper())
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) { finish(); return }
        search = state?.getString("density_search", "") ?: ""
        editingPackage = state?.getString("density_editing_package")
        draft = state?.getString("density_dpi_draft", "") ?: ""
        invalid = state?.getBoolean("density_dpi_invalid", false) ?: false
        list = state.listState("density_list")
        preferences = RearDensityPreferences.preferences(this)
        preferences.registerOnSharedPreferenceChangeListener(listener)
        load()
        miuixContent {
            val scope = rememberCoroutineScope()
            val keyboard = LocalSoftwareKeyboardController.current
            val compactEditor = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                && WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val visible = remember(apps, search) {
                val query = search.trim().lowercase(Locale.ROOT)
                apps.filter { query.isEmpty() || it.label.lowercase(Locale.ROOT).contains(query) || it.packageName.lowercase(Locale.ROOT).contains(query) }
            }
            val selected = remember(apps, revision) { apps.associate { it.packageName to RearDensityPreferences.enabled(this, it.packageName) } }
            val dpiValues = remember(apps, revision) { apps.associate { it.packageName to RearDensityPreferences.layoutDensity(this, it.packageName) } }
            val groups = remember(visible, selected) {
                val (enabled, disabled) = visible.partition { selected[it.packageName] == true }
                listOf(true to enabled, false to disabled)
            }
            PageFrame(getString(R.string.rear_density_title), ::finish, "density_back") { pageModifier, headerInset ->
                LazyColumn(pageModifier.testTag("density_app_list"), state = list,
                    contentPadding = PaddingValues(20.dp, headerInset + 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("help") { Text(getString(R.string.rear_density_help), fontSize = 13.sp, color = AppPalette.secondary()) }
                    item("search") {
                        TextField(search, { search = it; scope.launch { list.scrollToItem(0) } },
                            label = getString(R.string.rear_density_search), singleLine = true,
                            textStyle = MiuixTheme.textStyles.main.copy(color = AppPalette.text()),
                            modifier = Modifier.fillMaxWidth().testTag("density_app_search"))
                    }
                    item("bulk_controls") {
                        val canChangeAll = !loading && !failed && apps.isNotEmpty() && !DensityBulkOperation.running
                        BulkControlSection(
                            appCount = apps.size,
                            enabled = canChangeAll,
                            running = DensityBulkOperation.running,
                            error = bulkError,
                            result = bulkResult,
                            apply = ::applyBulk,
                        )
                    }
                    if (loading || failed || visible.isEmpty()) item("status") {
                        Text(getString(if (loading) R.string.rear_density_loading else if (failed) R.string.rear_density_failed else R.string.rear_density_empty),
                            fontSize = 13.sp, color = AppPalette.secondary())
                        if (failed) TextButton(getString(R.string.rear_density_retry), ::load, minHeight = 48.dp)
                    }
                    groups.forEach { (enabled, groupApps) ->
                        if (groupApps.isNotEmpty()) {
                            item(if (enabled) "enabled_group" else "disabled_group") {
                                Box(Modifier.testTag(if (enabled) "density_enabled_group" else "density_disabled_group")) {
                                    AppSection(getString(if (enabled) R.string.rear_density_group_enabled else R.string.rear_density_group_disabled,
                                        groupApps.size))
                                }
                            }
                            items(groupApps, key = { it.packageName }) { app ->
                                AppCard(Modifier.testTag("density_app_" + app.packageName)) {
                                    AppToggle(app.label, app.packageName, selected[app.packageName] == true,
                                        onChange = { RearDensityPreferences.setEnabled(this@DensityCompatibilityActivity, app.packageName, it) })
                                    if (selected[app.packageName] == true) {
                                        AppDivider()
                                        val dpi = dpiValues[app.packageName] ?: 0
                                        AppRow(getString(R.string.rear_density_layout_title),
                                            getString(if (dpi == 0) R.string.rear_density_layout_default else R.string.rear_density_layout_value,
                                                if (dpi == 0) VirtualCastRequest.DEFAULT_DENSITY_DPI else dpi),
                                            Modifier.testTag("density_dpi_" + app.packageName), action = {
                                                editingPackage = app.packageName
                                                draft = (if (dpi == 0) VirtualCastRequest.DEFAULT_DENSITY_DPI else dpi).toString()
                                                invalid = false
                                            })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            WindowDialog(show = editingPackage != null, title = if (compactEditor) null else getString(R.string.rear_density_layout_title),
                onDismissRequest = { editingPackage = null; keyboard?.hide() }) {
                Column(Modifier.fillMaxWidth()
                    .heightIn(max = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * if (compactEditor) 1f else .65f })
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!compactEditor) {
                        Text(apps.find { it.packageName == editingPackage }?.label ?: editingPackage.orEmpty(), color = AppPalette.text())
                        Text(getString(R.string.rear_density_layout_help), fontSize = 13.sp, color = AppPalette.secondary())
                    }
                    TextField(draft, { draft = it; invalid = false }, label = getString(R.string.rear_density_layout_title),
                        textStyle = MiuixTheme.textStyles.main.copy(color = AppPalette.text()),
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (saveDraft()) keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().testTag("density_dpi_input"))
                    if (invalid) Text(getString(R.string.rear_density_layout_error), color = AppPalette.accent())
                    TextButton(getString(R.string.rear_density_layout_save), {
                        if (saveDraft()) keyboard?.hide()
                    }, Modifier.fillMaxWidth().testTag("density_dpi_save"), minHeight = 48.dp,
                        colors = ButtonDefaults.textButtonColorsPrimary())
                    TextButton(getString(R.string.rear_density_layout_reset), {
                        editingPackage?.let { RearDensityPreferences.setLayoutDensity(this@DensityCompatibilityActivity, it, 0) }
                        editingPackage = null; keyboard?.hide()
                    }, Modifier.fillMaxWidth().testTag("density_dpi_reset"), minHeight = 48.dp)
                    TextButton(getString(R.string.rear_density_layout_cancel), { editingPackage = null; keyboard?.hide() },
                        Modifier.fillMaxWidth(), minHeight = 48.dp)
                }
            }
        }
    }

    @Composable
    private fun BulkControlSection(
        appCount: Int,
        enabled: Boolean,
        running: Boolean,
        error: Boolean,
        result: String?,
        apply: (Boolean) -> Unit,
    ) {
        val actionColors = ButtonDefaults.textButtonColors().copy(
            color = Color.Transparent,
            disabledColor = Color.Transparent,
            textColor = AppPalette.accent(),
            disabledTextColor = AppPalette.secondary().copy(alpha = 0.5f),
        )
        Column(
            Modifier.fillMaxWidth().testTag("density_bulk_controls"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    getString(R.string.rear_density_bulk_summary, appCount),
                    Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppPalette.text(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BulkActionText(
                        getString(R.string.rear_density_bulk_enable),
                        "density_bulk_enable",
                        enabled,
                        actionColors,
                    ) { apply(true) }
                    BulkActionText(
                        getString(R.string.rear_density_bulk_disable),
                        "density_bulk_disable",
                        enabled,
                        actionColors,
                    ) { apply(false) }
                }
            }

            when {
                running -> Text(
                    getString(R.string.rear_density_bulk_running, appCount),
                    fontSize = 12.sp,
                    color = AppPalette.secondary(),
                    modifier = Modifier.testTag("density_bulk_status").semantics { liveRegion = LiveRegionMode.Polite },
                )
                error -> Text(
                    getString(R.string.rear_density_bulk_error),
                    fontSize = 12.sp,
                    color = AppPalette.accent(),
                    modifier = Modifier.testTag("density_bulk_status").semantics { liveRegion = LiveRegionMode.Polite },
                )
                result != null -> Text(
                    result,
                    fontSize = 12.sp,
                    color = AppPalette.secondary(),
                    modifier = Modifier.testTag("density_bulk_status").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    @Composable
    private fun BulkActionText(
        label: String,
        tag: String,
        enabled: Boolean,
        colors: top.yukonga.miuix.kmp.basic.TextButtonColors,
        onClick: () -> Unit,
    ) {
        TextButton(
            label,
            onClick,
            modifier = Modifier.testTag(tag),
            enabled = enabled,
            minWidth = 48.dp,
            minHeight = 48.dp,
            colors = colors,
            insideMargin = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            textStyle = MiuixTheme.textStyles.main.copy(fontSize = 13.sp),
        )
    }

    private fun applyBulk(enabled: Boolean) {
        if (loading || failed || apps.isEmpty() || DensityBulkOperation.running) return
        val targets = apps.map { it.packageName }.distinct()
        if (targets.isEmpty()) return
        DensityBulkOperation.running = true
        bulkError = false
        bulkResult = null
        val application = applicationContext
        worker.execute {
            val result = runCatching { RearDensityPreferences.setAllEnabled(application, targets, enabled) }
            mainHandler.post {
                DensityBulkOperation.running = false
                if (isDestroyed || isFinishing) return@post
                if (result.isSuccess) {
                    bulkResult = getString(R.string.rear_density_bulk_done, targets.size)
                } else {
                    bulkError = true
                }
            }
        }
    }
    private fun saveDraft(): Boolean {
        val value = draft.trim().toIntOrNull()
        if (value == null || value !in 72..640) { invalid = true; return false }
        val pkg = editingPackage ?: return false
        RearDensityPreferences.setLayoutDensity(this, pkg, value)
        editingPackage = null
        return true
    }
    private fun load() {
        loading = true; failed = false
        worker.execute {
            val result = runCatching {
                val collator = Collator.getInstance(resources.configuration.locales[0])
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != packageName }
                    .map { App(it.activityInfo.packageName, packageManager.getApplicationLabel(it.activityInfo.applicationInfo).toString()) }
                    .sortedWith { a, b -> collator.compare(a.label, b.label) }
            }
            runOnUiThread { if (!isDestroyed && !isFinishing) {
                apps = result.getOrDefault(emptyList()); failed = result.isFailure; loading = false
            } }
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("density_search", search)
        out.putString("density_editing_package", editingPackage)
        out.putString("density_dpi_draft", draft)
        out.putBoolean("density_dpi_invalid", invalid)
        if (::list.isInitialized) out.saveList("density_list", list)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        if (::preferences.isInitialized) preferences.unregisterOnSharedPreferenceChangeListener(listener)
        mainHandler.removeCallbacks(refreshPreferences)
        super.onDestroy()
    }
}
