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
        modifier = Modifier.testTag("rear_cast_mode_toggle"),
        onChange = { RearCastMode.setVirtual(context, it) })
}

/** Java controllers publish plain modal state; only this renderer creates UI. */
class MiuixDialogUi(private val activity: ComponentActivity) {
    private data class Model(val title: String, val message: String, val labels: List<String>, val actions: List<Runnable?>,
        val number: Boolean = false, val min: Int = 1, val max: Int = Int.MAX_VALUE, val save: java.util.function.IntConsumer? = null, val pinned: Int = 0)
    private var model by mutableStateOf<Model?>(null)
    private var showing by mutableStateOf(false)
    private var generation = 0L
    private var draft by mutableStateOf("")
    private var error by mutableStateOf(false)
    private var kind: String? = null
    val view: View = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { AppTheme { Render() } }
    }
    fun show(title: String, message: String, labels: Array<String>, actions: Array<Runnable?>) {
        kind = null; generation++; model = Model(title, message, labels.toList(), actions.toList()); showing = true
    }
    fun showWithPinnedActions(title: String, message: String, labels: Array<String>, actions: Array<Runnable?>) {
        kind = null; generation++; model = Model(title, message, labels.toList(), actions.toList(), pinned = minOf(2, labels.size)); showing = true
    }
    fun number(kind: String, title: String, help: String, value: Int, min: Int, max: Int, save: java.util.function.IntConsumer, reset: Runnable?) {
        this.kind = kind; draft = value.toString(); error = false
        val labels = mutableListOf(NativeStrings.get(activity, "apply"), NativeStrings.get(activity, "cancel"))
        val actions = mutableListOf<Runnable?>(null, null)
        if (reset != null) { labels += NativeStrings.get(activity, "reset_dpi"); actions += reset }
        generation++; model = Model(title, help, labels, actions, true, min, max, save); showing = true
    }
    fun restoreDraft(value: String) { draft = value }
    fun updateMessage(title: String, message: String) { model?.takeIf { it.title == title }?.let { model = it.copy(message = message) } }
    fun saveState(bundle: Bundle) {
        if (showing && model?.number == true) { bundle.putString("native_number_kind", kind); bundle.putString("native_number_draft", draft) }
    }
    fun isShowing(title: String): Boolean = showing && model?.title == title
    fun isVisible(): Boolean = showing
    fun dismiss() { showing = false }
    @Composable private fun DialogAction(current: Model, index: Int, sessionGeneration: Long) {
        key(sessionGeneration, index) {
            TextButton(current.labels[index], {
                if (!showing || generation != sessionGeneration) return@TextButton
                if (current.number && index == 0) {
                    val value = draft.trim().toIntOrNull()
                    if (value == null || value !in current.min..current.max) error = true
                    else { dismiss(); current.save?.accept(value) }
                } else { dismiss(); current.actions[index]?.run() }
            }, Modifier.fillMaxWidth().testTag("modal_action_$index"), minHeight = 48.dp,
                enabled = showing && generation == sessionGeneration,
                colors = if (index == 0) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
        }
    }
    @Composable private fun DialogBody(current: Model, sessionGeneration: Long) {
        SelectionContainer { Text(current.message, fontSize = 14.sp, color = AppPalette.secondary(), modifier = Modifier.testTag("modal_message")) }
        if (current.number) {
            TextField(value = draft, onValueChange = {
                if (showing && generation == sessionGeneration) { draft = it; error = false }
            }, label = current.title,
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("number_input").semantics { if (error) error(current.message) },
                enabled = showing)
            if (error) Text(current.message, color = AppPalette.accent(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        for (index in current.pinned until current.labels.size) DialogAction(current, index, sessionGeneration)
    }
    @Composable private fun Render() {
        val current = model
        val renderGeneration = generation
        val focusManager = LocalFocusManager.current
        LaunchedEffect(showing) { if (!showing) focusManager.clearFocus(force = true) }
        WindowDialog(show = showing, title = current?.title,
            modifier = Modifier.inactiveInteractionIsolation(!showing),
            onDismissRequest = { if (showing && generation == renderGeneration) dismiss() },
            onDismissFinished = {
                if (!showing && generation == renderGeneration) { model = null; kind = null }
            }) {
            if (current != null) {
                val maximum = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .65f }
                if (current.pinned == 0) {
                    Column(Modifier.fillMaxWidth().heightIn(max = maximum).inactiveInteractionIsolation(!showing).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) { DialogBody(current, renderGeneration) }
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(max = maximum).inactiveInteractionIsolation(!showing), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) { DialogBody(current, renderGeneration) }
                        for (index in 0 until current.pinned) DialogAction(current, index, renderGeneration)
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
    private var showing by mutableStateOf(false)
    private var generation = 0L
    val view: View = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AppTheme {
                val current = model
                val renderGeneration = generation
                val focusManager = LocalFocusManager.current
                LaunchedEffect(showing) { if (!showing) focusManager.clearFocus(force = true) }
                WindowDialog(show = showing, title = current?.title,
                    modifier = Modifier.inactiveInteractionIsolation(!showing),
                    onDismissRequest = { if (showing && generation == renderGeneration) cancel.run() },
                    onDismissFinished = { if (!showing && generation == renderGeneration) model = null }) {
                    if (current != null) Column(Modifier.fillMaxWidth().heightIn(max = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .65f })
                        .inactiveInteractionIsolation(!showing)
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(current.current, fontSize = 14.sp, color = AppPalette.secondary(), modifier = Modifier.testTag("update_dialog_current"))
                        if (current.status.isNotEmpty()) Text(current.status, color = AppPalette.text(), modifier = Modifier.testTag("update_dialog_status").semantics { liveRegion = LiveRegionMode.Polite })
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
                            key(renderGeneration, index) {
                                TextButton(label, {
                                    if (showing && generation == renderGeneration) current.actions[index].run()
                                }, Modifier.fillMaxWidth().testTag("update_dialog_action_$index"), minHeight = 48.dp,
                                    enabled = showing && generation == renderGeneration,
                                    colors = if (index == 0 && current.labels.size > 1) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
                            }
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
        if (!showing) generation++
        model = Model(title, current, status, details, showReleaseNotes, notesAdded.toList(), notesImproved.toList(), notesFixed.toList(),
            notesAddedTitle, notesImprovedTitle, notesFixedTitle, notesEmpty, progress, indeterminate,
            labels.toList(), actions.toList())
        showing = true
    }
    fun dismiss() { showing = false }
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
