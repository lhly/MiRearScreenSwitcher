package top.zainzh.rearscreen

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import rikka.shizuku.Shizuku

class PermissionChecksActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var states by mutableStateOf(emptyMap<PermissionChecks.Item, PermissionChecks.State>())
    private lateinit var list: androidx.compose.foundation.lazy.LazyListState
    private var resumed = false
    private val tick = object : Runnable {
        override fun run() { if (resumed) { refresh(); handler.postDelayed(this, 2500) } }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> handler.post { if (resumed) refresh() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = savedInstanceState.listState("permissions_scroll")
        refresh()
        miuixContent {
            PageFrame(getString(R.string.ui_permissions_title), ::finish, "permissions_back") {
                LazyColumn(Modifier.fillMaxSize().testTag("permissions_scroll"), state = list,
                    contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(getString(R.string.ui_permissions_help), fontSize = 13.sp, color = AppPalette.secondary()) }
                    PermissionChecks.Item.entries.forEach { item ->
                        if (item == PermissionChecks.Item.BATTERY) item { AppSection(getString(R.string.ui_permissions_background_title)) }
                        item(item.name) {
                            val state = states[item] ?: PermissionChecks.State.UNKNOWN
                            AppCard {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(getString(item.title), fontSize = 16.sp, color = AppPalette.text(), fontWeight = FontWeight.Medium,
                                        modifier = Modifier.semantics { heading() })
                                    Text(getString(item.help), fontSize = 13.sp, color = AppPalette.secondary())
                                    Text(getString(PermissionChecks.statusLabel(item, state)), fontSize = 13.sp, color = AppPalette.secondary(),
                                        modifier = Modifier.testTag("permission_status_" + item.name).semantics { liveRegion = LiveRegionMode.Polite })
                                    if (state != PermissionChecks.State.GRANTED && state != PermissionChecks.State.NOT_REQUIRED) {
                                        TextButton(getString(PermissionChecks.actionLabel(item, state)), { openPermission(item) }, minHeight = 48.dp,
                                            modifier = Modifier.testTag("permission_action_" + item.name))
                                    }
                                }
                            }
                        }
                    }
                    item { Text(getString(R.string.ui_permissions_background_note), fontSize = 13.sp, color = AppPalette.secondary()) }
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }
    private fun refresh() { states = PermissionChecks.Item.entries.associateWith { PermissionChecks.read(this, it) } }
    private fun openPermission(item: PermissionChecks.Item) {
        if (item == PermissionChecks.Item.SHIZUKU) try {
            if (PermissionChecks.read(this, item) == PermissionChecks.State.DENIED && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(701); return
            }
        } catch (_: RuntimeException) { }
        if (!PermissionChecks.openSettings(this, item)) Toast.makeText(this, R.string.ui_permission_settings_unavailable, Toast.LENGTH_LONG).show()
    }
    override fun onResume() { super.onResume(); resumed = true; handler.removeCallbacks(tick); handler.post(tick) }
    override fun onPause() { resumed = false; handler.removeCallbacks(tick); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.saveList("permissions_scroll", list); super.onSaveInstanceState(outState) }
    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(permissionResult); handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
