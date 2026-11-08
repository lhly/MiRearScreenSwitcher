package dev.remrss.sample

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var scoreView: TextView
    private lateinit var statusView: TextView
    private lateinit var keepVisibleButton: Button
    private val sample get() = application as SampleApplication
    private var scoreSubscription: java.io.Closeable? = null
    private var statusSubscription: java.io.Closeable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scoreView = TextView(this).apply { textSize = 30f; gravity = Gravity.CENTER }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24) }
        layout.addView(TextView(this).apply { text = "Scoreboard · Main screen"; textSize = 20f; gravity = Gravity.CENTER })
        layout.addView(scoreView, LinearLayout.LayoutParams(-1, 120))
        layout.addView(Button(this).apply { text = "+1 on main"; setOnClickListener { sample.addPoint() } })
        keepVisibleButton = Button(this).apply {
            setOnClickListener {
                sample.keepVisibleForNextScene = !sample.keepVisibleForNextScene
                refreshKeepVisibleLabel()
            }
        }
        layout.addView(keepVisibleButton)
        layout.addView(Button(this).apply {
            text = "Open rear scene"
            setOnClickListener { sample.open(this@MainActivity, { renderStatus(it) }, sample.keepVisibleForNextScene) }
        })
        layout.addView(TextView(this).apply {
            text = "遮挡时使用纯黑画面并暂停刷新；这是遮挡保护，不是真正熄屏，仍可能耗电。"
            textSize = 13f
            gravity = Gravity.CENTER
        })
        statusView = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(12, 16, 12, 0) }
        layout.addView(statusView, LinearLayout.LayoutParams(-1, -2))
        setContentView(layout)
        render()
        refreshKeepVisibleLabel()
        renderStatus(sample.lastStatus)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (::statusView.isInitialized) renderStatus(sample.lastStatus)
        if (!sample.client.isConnected) sample.client.connect { result ->
            runOnUiThread { renderStatus(if (result.isSuccess) sample.lastStatus else "Connection failed: ${result.exceptionOrNull()?.message}") }
        }
    }
    override fun onStart() {
        super.onStart()
        scoreSubscription = sample.observeScore { value -> runOnUiThread { if (::scoreView.isInitialized) scoreView.text = value.toString() } }
        statusSubscription = sample.observeStatus { value -> runOnUiThread { renderStatus(value) } }
    }
    override fun onStop() {
        scoreSubscription?.close()
        scoreSubscription = null
        statusSubscription?.close()
        statusSubscription = null
        super.onStop()
    }
    private fun render() { if (::scoreView.isInitialized) scoreView.text = sample.score().toString() }
    private fun renderStatus(value: String) { if (::statusView.isInitialized) statusView.text = value }
    private fun refreshKeepVisibleLabel() {
        if (::keepVisibleButton.isInitialized) {
            keepVisibleButton.text = if (sample.keepVisibleForNextScene) "Keep rear screen on · On" else "Keep rear screen on · Off"
        }
    }
}
