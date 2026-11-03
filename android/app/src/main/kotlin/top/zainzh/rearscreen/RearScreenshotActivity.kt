package top.zainzh.rearscreen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.concurrent.Executors

/** Settings and preview share the same template parser and compositor as real exports. */
open class RearScreenshotActivity : ComponentActivity() {
    // Overridden only by the debug preview fixture; capture always uses the real Build.DEVICE.
    protected open fun templateDevice(): String = android.os.Build.DEVICE
    private fun supported() = RearShellRepository.resourceId(templateDevice(), "BK") != 0L
    private fun savedOptions() = RearScreenshotPreferences.readForDevice(this, templateDevice())
    private val worker = Executors.newSingleThreadExecutor()
    private var revision by mutableIntStateOf(0)
    private var preview by mutableStateOf<Bitmap?>(null)
    private var loading by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var picker by mutableStateOf<String?>(null)
    private var request = 0
    private lateinit var list: androidx.compose.foundation.lazy.LazyListState

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        list = state.listState("rear_screenshot_list")
        picker = state?.getString("rear_screenshot_picker")
        if (savedOptions().shell && supported()) loadPreview()
        miuixContent {
            val options = remember(revision) { savedOptions() }
            PageFrame(getString(R.string.rear_screenshot_title), ::finish, "screenshot_back") { pageModifier, headerInset ->
                LazyColumn(state = list, modifier = pageModifier.testTag("screenshot_scroll"),
                    contentPadding = PaddingValues(20.dp, headerInset + 8.dp, 20.dp, 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("switch") {
                        AppCard {
                            AppToggle(getString(R.string.rear_screenshot_shell),
                                getString(if (supported()) R.string.rear_screenshot_shell_help else R.string.rear_screenshot_unsupported),
                                options.shell, enabled = supported(), onChange = {
                                    RearScreenshotPreferences.preferences(this@RearScreenshotActivity).edit().putBoolean("shell", it).apply()
                                    revision++
                                    if (it) loadPreview() else { request++; preview = null; loading = false; failed = false }
                                })
                        }
                    }
                    if (options.shell && supported()) {
                        item("options") {
                            AppCard {
                                AppRow(getString(R.string.rear_screenshot_style), styleLabel(options.style),
                                    Modifier.testTag("screenshot_style"), action = { picker = "style" })
                                AppDivider()
                                AppRow(getString(R.string.rear_screenshot_color), colorLabel(options.color),
                                    Modifier.testTag("screenshot_color"), action = { picker = "color" })
                                AppDivider()
                                AppToggle(getString(R.string.rear_screenshot_keep_original), getString(R.string.rear_screenshot_keep_original_help),
                                    options.keepOriginal, onChange = {
                                        RearScreenshotPreferences.preferences(this@RearScreenshotActivity).edit().putBoolean("keep_original", it).apply()
                                        revision++
                                    })
                            }
                        }
                        item("preview") {
                            AppCard {
                                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(getString(R.string.rear_screenshot_preview), fontSize = 16.sp, color = AppPalette.text(),
                                        modifier = Modifier.align(Alignment.Start).semantics { heading() })
                                    if (loading) {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        Text(getString(R.string.rear_screenshot_preparing), fontSize = 13.sp, color = AppPalette.secondary(),
                                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                    } else if (failed) {
                                        Text(getString(R.string.rear_screenshot_preview_failed), fontSize = 13.sp, color = AppPalette.secondary(),
                                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                        TextButton(getString(R.string.rear_screenshot_retry), ::loadPreview, minHeight = 48.dp)
                                    }
                                    preview?.let {
                                        Image(it.asImageBitmap(), getString(R.string.rear_screenshot_preview_description, colorLabel(options.color), styleLabel(options.style)),
                                            Modifier.fillMaxWidth().height(260.dp).testTag("screenshot_preview"))
                                    }
                                }
                            }
                        }
                        item("offline") {
                            Text(getString(R.string.rear_screenshot_download_help), fontSize = 13.sp, color = AppPalette.secondary())
                        }
                    }
                    item("save") {
                        Text(getString(R.string.rear_screenshot_save_help), fontSize = 13.sp, color = AppPalette.secondary())
                    }
                }
            }
            WindowDialog(show = picker != null, title = getString(if (picker == "color") R.string.rear_screenshot_color else R.string.rear_screenshot_style),
                onDismissRequest = { picker = null }) {
                Column(Modifier.fillMaxWidth().heightIn(max = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .6f })
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val values = if (picker == "color") RearShellCatalog.colors(templateDevice()).toList() else listOf("full", "half")
                    values.forEach { value ->
                        val selected = value == if (picker == "color") options.color else options.style
                        TextButton(if (picker == "color") colorLabel(value) else styleLabel(value), {
                            val key = picker ?: return@TextButton
                            picker = null
                            RearScreenshotPreferences.preferences(this@RearScreenshotActivity).edit().putString(key, value).apply()
                            revision++; loadPreview()
                        }, Modifier.fillMaxWidth().semantics { this.selected = selected }, minHeight = 48.dp,
                            colors = if (selected) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
                    }
                }
            }
        }
    }
    private fun styleLabel(style: String) = getString(if (style == "half") R.string.rear_screenshot_half else R.string.rear_screenshot_full)
    private fun colorLabel(color: String) = getString(when (color) {
        "WH" -> R.string.rear_screenshot_white; "PU" -> R.string.rear_screenshot_purple
        "LG" -> R.string.rear_screenshot_green; "PK" -> R.string.rear_screenshot_pink
        "DB" -> R.string.rear_screenshot_deep_blue; "RD" -> R.string.rear_screenshot_red
        "CY" -> R.string.rear_screenshot_transparent_red; else -> R.string.rear_screenshot_black
    })
    private fun loadPreview() {
        val token = ++request
        val options = savedOptions()
        loading = true; failed = false; preview = null
        worker.execute {
            val result = runCatching {
                RearShellRepository.loadForDevice(applicationContext, templateDevice(), options.style, options.color).use { asset ->
                    val g = asset.geometry
                    val sample = Bitmap.createBitmap(g.width.toInt(), g.height.toInt(), Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(sample)
                        canvas.drawColor(android.graphics.Color.rgb(36, 107, 223))
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE; textSize = 68f; textAlign = Paint.Align.CENTER
                        }
                        // Label the unobscured side of the physical rear display.
                        canvas.drawText("ReMRSS", sample.width * .65f, sample.height * .55f, paint)
                        val composed = RearScreenshotPipeline.compose(sample, 0, asset)
                        try { Bitmap.createScaledBitmap(composed, (600f * composed.width / composed.height).toInt(), 600, true) }
                        finally { composed.recycle() }
                    } finally { sample.recycle() }
                }
            }
            result.exceptionOrNull()?.let { android.util.Log.w("RearScreenshot", "Frame preview unavailable", it) }
            runOnUiThread {
                if (!isDestroyed && !isFinishing && token == request) {
                    preview = result.getOrNull(); failed = result.isFailure; loading = false
                } else result.getOrNull()?.recycle()
            }
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.saveList("rear_screenshot_list", list); out.putString("rear_screenshot_picker", picker)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() { request++; worker.shutdownNow(); super.onDestroy() }
}
