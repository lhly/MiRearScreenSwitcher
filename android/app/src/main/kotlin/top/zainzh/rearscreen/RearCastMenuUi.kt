package top.zainzh.rearscreen

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/** Compact drawer inside the physical safe area; all input is dispatched locally. */
class RearCastMenuUi(activity: ComponentActivity, exit: Runnable, left: Runnable, right: Runnable, viewport: Runnable, dismiss: Runnable) {
    private var animationGeneration = 0
    private var controlsBusy by mutableStateOf(false)
    private var gestureY by mutableFloatStateOf(Float.NaN)
    private var fullViewport by mutableStateOf(false)
    private val content = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AppTheme {
                BoxWithConstraints(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = !controlsBusy, onClick = dismiss::run
                )) {
                    val density = LocalDensity.current
                    val inset = with(density) { 8.dp.roundToPx() }
                    val drawerHeight = with(density) { 56.dp.roundToPx() }
                    val availableHeight = with(density) { maxHeight.roundToPx() }
                    val drawerWidth = minOf(212.dp, (maxWidth - 16.dp).coerceAtLeast(48.dp))
                    AppCard(Modifier.align(Alignment.TopEnd).padding(end = 8.dp).width(drawerWidth)
                        .offset {
                            val center = gestureY.takeIf { it.isFinite() } ?: (availableHeight / 2f)
                            IntOffset(0, (center - drawerHeight / 2f).toInt()
                                .coerceIn(inset, (availableHeight - drawerHeight - inset).coerceAtLeast(inset)))
                        }.clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}
                        )) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            DrawerAction(R.drawable.ic_rear_cast_exit, R.string.rear_cast_menu_exit, controlsBusy, exit)
                            DrawerAction(R.drawable.ic_rear_cast_rotate_left, R.string.rear_cast_menu_left, controlsBusy, left)
                            DrawerAction(R.drawable.ic_rear_cast_rotate_right, R.string.rear_cast_menu_right, controlsBusy, right)
                            DrawerAction(if (fullViewport) R.drawable.ic_rear_cast_avoid_camera else R.drawable.ic_rear_cast_full,
                                if (fullViewport) R.string.rear_cast_menu_avoid_camera else R.string.rear_cast_menu_full, controlsBusy, viewport)
                        }
                    }
                }
            }
        }
    }
    val view: View = android.widget.FrameLayout(activity).apply {
        visibility = View.INVISIBLE
        clipChildren = true
        addView(content, android.widget.FrameLayout.LayoutParams(-1, -1))
    }
    @Composable private fun DrawerAction(icon: Int, label: Int, busy: Boolean, action: Runnable) {
        IconButton(onClick = action::run, modifier = Modifier.size(48.dp), enabled = !busy,
            minWidth = 48.dp, minHeight = 48.dp, cornerRadius = 16.dp,
            backgroundColor = colorResource(R.color.remrss_control)) {
            Icon(painterResource(icon), contentDescription = stringResource(label), modifier = Modifier.size(26.dp),
                tint = AppPalette.text().copy(alpha = if (busy) .4f else 1f))
        }
    }
    fun anchor(y: Float) { gestureY = y }
    fun setBusy(value: Boolean) { controlsBusy = value }
    fun updateViewportMode(value: Boolean) { fullViewport = value }
    fun show(open: Boolean) {
        val generation = ++animationGeneration
        val wasVisible = view.visibility == View.VISIBLE
        content.animate().cancel()
        val distance = minOf(view.width.toFloat(), 228 * view.resources.displayMetrics.density)
        val animate = (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()) && view.isAttachedToWindow
        if (!open) {
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try { view.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            if (animate && wasVisible && view.isShown) {
                content.animate().alpha(0f).translationX(distance).setDuration(125)
                    .setInterpolator(PathInterpolator(.23f, 1f, .32f, 1f))
                    .withEndAction { if (generation == animationGeneration) view.visibility = View.INVISIBLE }.start()
            } else { view.visibility = View.INVISIBLE; content.alpha = 0f }
            return
        }
        controlsBusy = false
        view.visibility = View.VISIBLE
        if (animate) {
            if (!wasVisible) { content.alpha = 0f; content.translationX = distance }
            content.animate().alpha(1f).translationX(0f).setDuration(180)
                .setInterpolator(PathInterpolator(.23f, 1f, .32f, 1f)).start()
        } else { content.alpha = 1f; content.translationX = 0f }
    }
}
