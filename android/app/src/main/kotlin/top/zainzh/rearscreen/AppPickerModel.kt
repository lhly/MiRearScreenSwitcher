package top.zainzh.rearscreen

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors
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
    private fun choose(packages: List<String>, enabled: Boolean) {
        selected = if (enabled) selected + packages else selected - packages.toSet()
        preferences.edit().putStringSet("notification_selected_apps", selected.toSet()).apply()
    }
    @Composable internal fun Content() {
        val context = LocalContext.current
        fun s(key: String) = NativeStrings.get(context, key)
        val locale = LocalConfiguration.current.locales[0]
        val visible = remember(apps, search, includeSystem, selected, locale) {
            val query = search.trim().lowercase(Locale.ROOT)
            val collator = Collator.getInstance(locale)
            apps.filter { (includeSystem || !it.system) && (query.isEmpty() || it.name.lowercase(Locale.ROOT).contains(query) || it.info.packageName.lowercase(Locale.ROOT).contains(query)) }
                .sortedWith { a, b -> val picked = selected.contains(b.info.packageName).compareTo(selected.contains(a.info.packageName)); if (picked != 0) picked else collator.compare(a.name, b.name) }
        }
        val keyboard = LocalSoftwareKeyboardController.current
        LazyColumn(Modifier.fillMaxSize().testTag("app_picker_list"), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") {
                TextButton(s("back"), { keyboard?.hide(); back.run() }, minHeight = 48.dp)
                Text(s("notification_apps"), fontSize = 25.sp, color = AppPalette.text(), modifier = Modifier.semantics { heading() })
                Text(s("apps_summary").replace("{0}", selected.size.toString()).replace("{1}", visible.size.toString()), fontSize = 13.sp, color = AppPalette.secondary())
            }
            item("filters") {
                TextField(search, { search = it }, label = s("search_apps"), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("app_search"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                AppToggle(s("system_apps"), null, includeSystem) { includeSystem = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(s("select_all"), { choose(visible.map { it.info.packageName }, true) }, minHeight = 48.dp)
                    TextButton(s("deselect_all"), { choose(visible.map { it.info.packageName }, false) }, minHeight = 48.dp)
                    TextButton(s("refresh"), ::load, minHeight = 48.dp, enabled = !loading)
                }
                if (!granted) TextButton(s("apps_access_help"), { activity.runAction("requestQueryAllPackagesPermission", null, null) }, minHeight = 48.dp)
            }
            if (visible.isEmpty()) item("empty") { Text(failure ?: s(if (loading) "loading_apps" else "no_apps_found"), color = AppPalette.secondary()) }
            items(visible, key = { it.info.packageName }) { app ->
                AppCard {
                    Row(Modifier.fillMaxWidth().toggleable(selected.contains(app.info.packageName), role = Role.Checkbox,
                        onValueChange = { choose(listOf(app.info.packageName), it) }).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AppIcon(app)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(app.name, fontSize = 16.sp, color = AppPalette.text())
                            Text(app.info.packageName, fontSize = 12.sp, color = AppPalette.secondary())
                        }
                        Checkbox(if (selected.contains(app.info.packageName)) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                            onClick = null, modifier = Modifier.clearAndSetSemantics { })
                    }
                }
            }
        }
    }
    @Composable private fun AppIcon(app: App) {
        var bitmap by remember(app.info.packageName) { mutableStateOf(icons.get(app.info.packageName)?.toBitmap(80, 80)?.asImageBitmap()) }
        DisposableEffect(app.info.packageName) {
            var attached = true
            val future = executor.submit {
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
