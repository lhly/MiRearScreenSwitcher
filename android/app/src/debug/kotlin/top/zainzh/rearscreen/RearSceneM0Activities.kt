package top.zainzh.rearscreen

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import android.widget.FrameLayout
import android.view.ViewTreeObserver
import android.util.DisplayMetrics

/** Emulator-only M0 fixtures. They do not use or move an existing app task. */
abstract class RearSceneM0Activity : Activity() {
    private lateinit var label: TextView
    private var taps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    taps += 1
                    renderSnapshot()
                }
                true
            }
        }
        setContentView(FrameLayout(this).apply { addView(label, FrameLayout.LayoutParams(-1, -1)) })
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        renderSnapshot()
    }

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { renderSnapshot() }

    private fun renderSnapshot() {
        if (!::label.isInitialized) return
        val display = if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        val metrics = DisplayMetrics().also { display?.getRealMetrics(it) }
        val decor = window.decorView
        label.text = buildString {
            appendLine("rear scene M0")
            appendLine("displayId=${display?.displayId ?: -1}")
            appendLine("taskId=$taskId")
            appendLine("windowPx=${decor.width}x${decor.height}")
            appendLine("densityDpi=${resources.displayMetrics.densityDpi}")
            appendLine("physicalPx=${metrics.widthPixels}x${metrics.heightPixels}")
            append("inputUps=$taps")
        }
        snapshot = Snapshot(display?.displayId ?: -1, taskId, decor.width, decor.height,
            resources.displayMetrics.densityDpi, metrics.widthPixels, metrics.heightPixels, taps, isFinishing)
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        if (this is RearSceneM0PrimaryActivity) primaryDestroyCount++ else rearDestroyCount++
        super.onDestroy()
    }

    data class Snapshot(
        val displayId: Int, val taskId: Int, val windowWidthPx: Int, val windowHeightPx: Int,
        val densityDpi: Int, val displayWidthPx: Int, val displayHeightPx: Int,
        val inputUps: Int, val finishing: Boolean
    )

    companion object {
        @Volatile var primary: RearSceneM0Activity? = null
        @Volatile var rear: RearSceneM0Activity? = null
        @Volatile var primaryDestroyCount = 0
        @Volatile var rearDestroyCount = 0
        @Volatile var snapshot: Snapshot? = null
    }
}

class RearSceneM0PrimaryActivity : RearSceneM0Activity() {
    override fun onResume() { super.onResume(); primary = this }
    override fun onPause() { if (primary === this) primary = this; super.onPause() }
}

class RearSceneM0RearActivity : RearSceneM0Activity() {
    override fun onResume() { super.onResume(); rear = this }
    override fun onPause() { if (rear === this) rear = null; super.onPause() }
}
