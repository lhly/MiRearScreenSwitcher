package top.zainzh.rearscreen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Content, chapter navigation and attribution are bundled resources and work offline. */
class TutorialActivity : ComponentActivity() {
    private lateinit var list: androidx.compose.foundation.lazy.LazyListState
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = savedInstanceState.listState("tutorial_scroll")
        miuixContent {
            val scope = rememberCoroutineScope()
            PageFrame(NativeStrings.get(this, "tutorial"), ::finish, "tutorial_back") {
                LazyColumn(Modifier.fillMaxSize().testTag("tutorial_scroll"), state = list,
                    contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item("intro") { Text(getString(R.string.tutorial_intro), fontSize = 14.sp, color = AppPalette.secondary()) }
                    item("contents") {
                        AppSection(getString(R.string.tutorial_contents))
                        AppCard { sections.forEachIndexed { index, section ->
                            if (index > 0) AppDivider()
                            AppRow(getString(titles[index]), null, modifier = Modifier.testTag("tutorial_jump_$section"),
                                action = { scope.launch { list.animateScrollToItem(index + 2) } })
                        } }
                    }
                    sections.forEachIndexed { index, section ->
                        item(section) {
                            AppCard(Modifier.testTag("tutorial_section_$section")) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("${index + 1}. ${getString(titles[index])}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                        color = AppPalette.text(), modifier = Modifier.semantics { heading() })
                                    getString(steps[index]).replace("{scheme}", BuildConfig.URI_SCHEME).split('\n').forEachIndexed { number, step ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("${number + 1}.", fontSize = 14.sp, color = AppPalette.accent())
                                            Text(step, fontSize = 14.sp, color = AppPalette.secondary(), modifier = Modifier.weight(1f))
                                        }
                                    }
                                    if (index == 0) TextButton(NativeStrings.get(this@TutorialActivity, "open_shizuku"), {
                                        if (!PermissionChecks.openSettings(this@TutorialActivity, PermissionChecks.Item.SHIZUKU)) openError()
                                    }, Modifier.testTag("tutorial_shizuku"), minHeight = 48.dp)
                                    if (index == 1) TextButton(getString(R.string.tutorial_open_permissions), {
                                        startActivity(Intent(this@TutorialActivity, PermissionChecksActivity::class.java))
                                    }, Modifier.testTag("tutorial_permissions"), minHeight = 48.dp)
                                }
                            }
                        }
                    }
                    item("source") {
                        AppCard(Modifier.testTag("tutorial_source")) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(getString(R.string.tutorial_source_title), fontSize = 16.sp, color = AppPalette.text(), fontWeight = FontWeight.Medium)
                                Text(getString(R.string.tutorial_source_help), fontSize = 13.sp, color = AppPalette.secondary())
                                TextButton(getString(R.string.tutorial_view_original), ::openOriginal, Modifier.testTag("tutorial_original"), minHeight = 48.dp)
                            }
                        }
                    }
                    item("top") { TextButton(getString(R.string.tutorial_back_to_top), { scope.launch { list.animateScrollToItem(0) } },
                        Modifier.testTag("tutorial_top"), minHeight = 48.dp) }
                }
            }
        }
    }
    private fun openOriginal() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ProjectInfo.TUTORIAL_SOURCE))) }
        catch (_: android.content.ActivityNotFoundException) { openError() }
        catch (_: SecurityException) { openError() }
    }
    private fun openError() { Toast.makeText(this, NativeStrings.get(this, "open_failed"), Toast.LENGTH_LONG).show() }
    override fun onSaveInstanceState(outState: Bundle) { outState.saveList("tutorial_scroll", list); super.onSaveInstanceState(outState) }
    companion object {
        val sections = listOf("setup", "permissions", "tiles", "capture", "display", "notifications", "background", "troubleshooting")
        private val titles = listOf(R.string.tutorial_setup_title, R.string.tutorial_permissions_title, R.string.tutorial_tiles_title,
            R.string.tutorial_capture_title, R.string.tutorial_display_title, R.string.tutorial_notifications_title, R.string.tutorial_background_title, R.string.tutorial_troubleshooting_title)
        private val steps = listOf(R.string.tutorial_setup_steps, R.string.tutorial_permissions_steps, R.string.tutorial_tiles_steps,
            R.string.tutorial_capture_steps, R.string.tutorial_display_steps, R.string.tutorial_notifications_steps, R.string.tutorial_background_steps, R.string.tutorial_troubleshooting_steps)
    }
}
