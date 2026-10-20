package top.zainzh.rearscreen

import android.os.Bundle
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text

/** Debug-only target: does not set any OEM flags or periodically wake the rear display. */
class FixtureInputActivity : ComponentActivity() {
    @JvmField var ups = 0
    @JvmField var moves = 0
    @JvmField var cancels = 0
    @JvmField var doubleTaps = 0
    @JvmField var maxPointers = 0
    @JvmField var genericEvents = 0
    @JvmField var scales = 0
    @JvmField var scaleFactor = 1f
    @JvmField var focused = false
    private var revision by mutableIntStateOf(0)
    private lateinit var gestures: GestureDetector
    private lateinit var scaleGesture: ScaleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setTurnScreenOn(true); setShowWhenLocked(true)
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { doubleTaps++; return true }
        })
        scaleGesture = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scales++; scaleFactor *= detector.scaleFactor; return true
            }
        })
        miuixContent {
            PageFrame("背屏输入验证", ::finish, "input_probe_back") {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppCard {
                        @Suppress("UNUSED_EXPRESSION") revision
                        AppRow("触摸统计", "抬起 $ups · 移动 $moves · 双击 $doubleTaps · 最多 $maxPointers 指 · 缩放 $scales")
                    }
                    Text("取消 $cancels · 辅助事件 $genericEvents · 焦点 $focused", color = AppPalette.secondary())
                }
            }
        }
    }

    @Synchronized fun resetCounters() {
        ups = 0; moves = 0; cancels = 0; doubleTaps = 0; maxPointers = 0; genericEvents = 0
        scales = 0; scaleFactor = 1f
        revision++
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        maxPointers = maxOf(maxPointers, event.pointerCount)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> ups++
            MotionEvent.ACTION_MOVE -> moves++
            MotionEvent.ACTION_CANCEL -> cancels++
        }
        gestures.onTouchEvent(event)
        scaleGesture.onTouchEvent(event)
        revision++
        // Keep a complete stream even when the pointer leaves a particular composable.
        super.dispatchTouchEvent(event)
        return true
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        genericEvents++; revision++
        return super.dispatchGenericMotionEvent(event)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus; revision++
    }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    companion object { @JvmField @Volatile var instance: FixtureInputActivity? = null }
}
