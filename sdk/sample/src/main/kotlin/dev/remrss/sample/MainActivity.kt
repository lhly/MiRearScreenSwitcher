package dev.remrss.sample

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.remrss.platform.sdk.RearDataField
import dev.remrss.platform.sdk.RearDataSnapshot
import dev.remrss.platform.sdk.RearDataValue
import java.io.Closeable

class MainActivity : Activity() {
    private lateinit var scoreView: TextView
    private lateinit var statusView: TextView
    private lateinit var keepVisibleButton: Button
    private lateinit var apiView: TextView
    private val sample get() = application as SampleApplication
    private var scoreSubscription: java.io.Closeable? = null
    private var statusSubscription: java.io.Closeable? = null
    private var dataSubscription: Closeable? = null
    private var activityStarted = false
    private var batteryObservationRequested = false
    private var dataObservationEpoch = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
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
        layout.addView(Button(this).apply {
            text = "Check rear status"
            setOnClickListener { queryRearState() }
        })
        layout.addView(Button(this).apply {
            text = "Choose battery data access"
            setOnClickListener {
                sample.client.requestDataAuthorization(this@MainActivity, setOf(
                    RearDataField.BATTERY_LEVEL_PERCENT, RearDataField.BATTERY_CHARGING
                )) { result -> runOnUiThread { renderStatus(if (result.isSuccess) "Battery access screen opened" else "Data consent failed: ${result.exceptionOrNull()?.message}") } }
            }
        })
        layout.addView(Button(this).apply {
            text = "Read battery data once"
            setOnClickListener {
                sample.client.queryData(setOf(RearDataField.BATTERY_LEVEL_PERCENT, RearDataField.BATTERY_CHARGING)) { result ->
                    runOnUiThread { apiView.text = result.fold(::formatBattery, { "Battery query failed: ${it.message}" }) }
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Observe battery while this screen is open"
            setOnClickListener {
                if (batteryObservationRequested) return@setOnClickListener
                batteryObservationRequested = true
                val requestEpoch = ++dataObservationEpoch
                sample.client.observeData(setOf(RearDataField.BATTERY_LEVEL_PERCENT, RearDataField.BATTERY_CHARGING),
                    intervalMs = 5_000L, onSnapshot = { snapshot ->
                        runOnUiThread {
                            if (activityStarted && batteryObservationRequested && requestEpoch == dataObservationEpoch) {
                                apiView.text = formatBattery(snapshot)
                            }
                        }
                    }) { result -> runOnUiThread {
                        result.fold(onSuccess = { subscription ->
                            if (activityStarted && batteryObservationRequested && requestEpoch == dataObservationEpoch) {
                                dataSubscription = subscription
                            } else {
                                subscription.close()
                            }
                        }, onFailure = {
                            if (requestEpoch == dataObservationEpoch) {
                                batteryObservationRequested = false
                                renderStatus("Data observation failed: ${it.message}")
                            }
                        })
                    } }
            }
        })
        apiView = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(12, 12, 12, 12) }
        layout.addView(apiView, LinearLayout.LayoutParams(-1, -2))
        layout.addView(TextView(this).apply {
            text = "遮挡时使用纯黑画面并暂停刷新；这是遮挡保护，不是真正熄屏，仍可能耗电。"
            textSize = 13f
            gravity = Gravity.CENTER
        })
        statusView = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(12, 16, 12, 0) }
        layout.addView(statusView, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { clipToPadding = false; addView(layout) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
            insets
        }
        setContentView(scroll)
        render()
        refreshKeepVisibleLabel()
        renderStatus(sample.lastStatus)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (::statusView.isInitialized) renderStatus(sample.lastStatus)
        sample.connect { result ->
            runOnUiThread { renderStatus(if (result.isSuccess) sample.lastStatus else "Connection failed: ${result.exceptionOrNull()?.message}") }
        }
    }
    override fun onStart() {
        super.onStart()
        activityStarted = true
        scoreSubscription = sample.observeScore { value -> runOnUiThread { if (::scoreView.isInitialized) scoreView.text = value.toString() } }
        statusSubscription = sample.observeStatus { value -> runOnUiThread { renderStatus(value) } }
    }
    override fun onStop() {
        activityStarted = false
        batteryObservationRequested = false
        dataObservationEpoch++
        scoreSubscription?.close()
        scoreSubscription = null
        statusSubscription?.close()
        statusSubscription = null
        dataSubscription?.close()
        dataSubscription = null
        super.onStop()
    }
    private fun render() { if (::scoreView.isInitialized) scoreView.text = sample.score().toString() }
    private fun renderStatus(value: String) { if (::statusView.isInitialized) statusView.text = value }
    private fun configureSystemBars() {
        window.statusBarColor = android.graphics.Color.rgb(250, 250, 250)
        window.navigationBarColor = android.graphics.Color.rgb(250, 250, 250)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    private fun refreshKeepVisibleLabel() {
        if (::keepVisibleButton.isInitialized) {
            keepVisibleButton.text = if (sample.keepVisibleForNextScene) "Keep rear screen on · On" else "Keep rear screen on · Off (default)"
        }
    }

    private fun queryRearState() {
        sample.client.queryCapabilities { capabilities ->
            sample.client.queryRearInfo { info -> runOnUiThread {
                val caps = capabilities.getOrNull()
                val rear = info.getOrNull()
                apiView.text = buildString {
                    append("Contract: ${caps?.contract ?: rear?.contract ?: "unavailable"}\n")
                    append("Device available: ${rear?.deviceAvailable ?: caps?.deviceAvailable ?: "unknown"}; backend connected: ${rear?.backendConnected ?: caps?.backendConnected ?: "unknown"}\n")
                    append(rear?.panel?.let { "Rear display ${it.displayId}: ${it.logicalWidthPx}×${it.logicalHeightPx}px, ${it.densityDpi}dpi, ${it.state}" }
                        ?: "Rear panel details unavailable")
                }
                capabilities.exceptionOrNull()?.let { renderStatus("Capability query failed: ${it.message}") }
                info.exceptionOrNull()?.let { renderStatus("Panel query failed: ${it.message}") }
            } }
        }
    }

    private fun formatBattery(snapshot: RearDataSnapshot): String = buildString {
        append("Battery · ")
        append(snapshot.fields[RearDataField.BATTERY_LEVEL_PERCENT].displayValue("level"))
        append(" · ")
        append(snapshot.fields[RearDataField.BATTERY_CHARGING].displayValue("charging"))
    }

    private fun RearDataValue?.displayValue(label: String): String = when (this) {
        is RearDataValue.Number -> "$label ${value.toInt()} ${unit}"
        is RearDataValue.Flag -> "$label ${if (value) "yes" else "no"}"
        is RearDataValue.Unavailable -> "$label unavailable ($reason)"
        null -> "$label unavailable"
    }
}
