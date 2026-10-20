package top.zainzh.rearscreen

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/** Per-App policy editor; never changes an already running cast. */
class DensityCompatibilityActivity : ComponentActivity() {
    private data class App(val packageName: String, val label: String)
    private val worker = Executors.newSingleThreadExecutor()
    private var apps by mutableStateOf(emptyList<App>())
    private var search by mutableStateOf("")
    private var loading by mutableStateOf(true)
    private var failed by mutableStateOf(false)
    private var revision by mutableIntStateOf(0)
    private lateinit var list: androidx.compose.foundation.lazy.LazyListState
    private lateinit var preferences: SharedPreferences
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { revision++ } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!BuildConfig.VIRTUAL_CAST_ENABLED) { finish(); return }
        search = state?.getString("density_search", "") ?: ""
        list = state.listState("density_list")
        preferences = RearDensityPreferences.preferences(this)
        preferences.registerOnSharedPreferenceChangeListener(listener)
        load()
        miuixContent {
            val scope = rememberCoroutineScope()
            val visible = remember(apps, search) {
                val query = search.trim().lowercase(Locale.ROOT)
                apps.filter { query.isEmpty() || it.label.lowercase(Locale.ROOT).contains(query) || it.packageName.lowercase(Locale.ROOT).contains(query) }
            }
            val selected = remember(apps, revision) { apps.associate { it.packageName to RearDensityPreferences.enabled(this, it.packageName) } }
            PageFrame(getString(R.string.rear_density_title), ::finish, "density_back") {
                LazyColumn(Modifier.fillMaxSize().testTag("density_app_list"), state = list,
                    contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("help") { Text(getString(R.string.rear_density_help), fontSize = 13.sp, color = AppPalette.secondary()) }
                    item("search") {
                        TextField(search, { search = it; scope.launch { list.scrollToItem(0) } },
                            label = getString(R.string.rear_density_search), singleLine = true,
                            textStyle = MiuixTheme.textStyles.main.copy(color = AppPalette.text()),
                            modifier = Modifier.fillMaxWidth().testTag("density_app_search"))
                    }
                    if (loading || failed || visible.isEmpty()) item("status") {
                        Text(getString(if (loading) R.string.rear_density_loading else if (failed) R.string.rear_density_failed else R.string.rear_density_empty),
                            fontSize = 13.sp, color = AppPalette.secondary())
                        if (failed) TextButton(getString(R.string.rear_density_retry), ::load, minHeight = 48.dp)
                    }
                    items(visible, key = { it.packageName }) { app ->
                        AppCard(Modifier.testTag("density_app_" + app.packageName)) {
                            AppToggle(app.label, app.packageName, selected[app.packageName] == true,
                                onChange = { RearDensityPreferences.setEnabled(this@DensityCompatibilityActivity, app.packageName, it) })
                        }
                    }
                }
            }
        }
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
        if (::list.isInitialized) out.saveList("density_list", list)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        if (::preferences.isInitialized) preferences.unregisterOnSharedPreferenceChangeListener(listener)
        worker.shutdownNow()
        super.onDestroy()
    }
}
