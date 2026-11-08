package top.zainzh.rearscreen.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal sealed interface SceneDisplaySignal {
    data object PrimaryScreenOff : SceneDisplaySignal
    data object PrimaryScreenOn : SceneDisplaySignal
    data class RearDisplayPower(val on: Boolean) : SceneDisplaySignal
    data class RearProximity(val near: Boolean) : SceneDisplaySignal
}

internal fun interface SceneDisplayLifecycleMonitor {
    fun start(displayId: Int, onSignal: (SceneDisplaySignal) -> Unit): Closeable
}

/** Registers rear-only event sources for the lifetime of one Scene lease. It never polls. */
internal class AndroidSceneDisplayLifecycleMonitor(
    private val context: Context,
    private val proximityPortFactory: SceneProximitySensorPortFactory = SceneProximitySensorPortFactory { app, handler ->
        AndroidSceneProximitySensorPort(app, handler)
    }
) : SceneDisplayLifecycleMonitor {
    override fun start(displayId: Int, onSignal: (SceneDisplaySignal) -> Unit): Closeable {
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val displayManager = app.getSystemService(DisplayManager::class.java)
        val proximityPort = proximityPortFactory.create(app, handler)
        val prefs = app.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val closed = AtomicBoolean(false)
        var receiverRegistered = false
        var displayListenerRegistered = false
        var sensorRegistered = false
        var nearState: Boolean? = null
        var observation = 0L
        val sourceLifecycleLock = Any()
        val registrationEpoch = AtomicLong(0L)
        val validCallbackEpoch = AtomicLong(0L)
        var pendingEdge: Runnable? = null
        lateinit var updateSensor: () -> Unit
        // Existing devices may expose the real rear source as an OEM-private type. Keep the
        // established name-based selector across TYPE_ALL, but exclude factory streams and never
        // substitute the front/default proximity sensor.
        val candidates = proximityPort.discover()
        val rearSensor = RearProximitySensorSelector.selectIndex(candidates)?.let(candidates::get)

        fun safeSensorName(name: String): String = name.replace(Regex("[^A-Za-z0-9 _.-]"), "?").take(48)
        fun sensorDescription(): String = rearSensor?.let { sensor ->
            // Sensor names are platform metadata, not session/user content. Bound and normalize
            // them so OEM-specific names remain useful without allowing noisy log payloads.
            val name = safeSensorName(sensor.name)
            "selected=rear_named name=$name type=${sensor.type} stringType=${sensor.stringType.take(48)} wakeup=${sensor.wakeUp} maxRange=${sensor.maximumRange}"
        } ?: "selected=none manager=${proximityPort.managerAvailable} rearCandidates=${candidates.filter {
            it.name.contains("Proximity", true) && it.name.contains("Back", true) && !it.stringType.contains(".factory", true)
        }.take(4).joinToString("|") { safeSensorName(it.name) + ":type=" + it.stringType.take(48) + ":wakeup=" + it.wakeUp }}"

        fun send(signal: SceneDisplaySignal) {
            if (!closed.get()) onSignal(signal)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> send(SceneDisplaySignal.PrimaryScreenOff)
                    Intent.ACTION_SCREEN_ON -> send(SceneDisplaySignal.PrimaryScreenOn)
                }
            }
        }
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) {
                if (id == displayId) send(SceneDisplaySignal.RearDisplayPower(false))
            }
            override fun onDisplayChanged(id: Int) {
                if (id == displayId) {
                    val state = displayManager?.getDisplay(id)?.state
                    send(SceneDisplaySignal.RearDisplayPower(state == Display.STATE_ON))
                }
            }
        }
        fun onProximityDistance(epoch: Long, distance: Float) {
            if (closed.get() || rearSensor == null || validCallbackEpoch.get() != epoch) return
            val sampleNear = distance < rearSensor.maximumRange
            val edge = ++observation
            pendingEdge?.let(handler::removeCallbacks)
            val observedAt = SystemClock.elapsedRealtime()
            val task = Runnable {
                if (closed.get() || validCallbackEpoch.get() != epoch || edge != observation || SystemClock.elapsedRealtime() - observedAt < SENSOR_DEBOUNCE_MS) return@Runnable
                if (nearState == sampleNear) return@Runnable
                nearState = sampleNear
                Log.i(TAG, "edge=${if (sampleNear) "near" else "far"} source=rear_proximity")
                send(SceneDisplaySignal.RearProximity(sampleNear))
            }
            pendingEdge = task
            handler.postDelayed(task, SENSOR_DEBOUNCE_MS)
        }
        val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PROXIMITY_PREF) handler.post { updateSensor() }
        }
        updateSensor = sensorUpdate@{
            synchronized(sourceLifecycleLock) {
                if (closed.get()) return@sensorUpdate
                val epoch = registrationEpoch.incrementAndGet()
                validCallbackEpoch.set(0L)
                if (!proximityPort.managerAvailable || rearSensor == null) {
                    Log.w(TAG, "source_unavailable ${sensorDescription()}")
                    return@sensorUpdate
                }
                val wasRegistered = sensorRegistered
                proximityPort.unregister()
                sensorRegistered = false
                pendingEdge?.let(handler::removeCallbacks)
                pendingEdge = null
                observation++
                val enabled = prefs.getBoolean(PROXIMITY_PREF, true)
                if (enabled && !closed.get()) {
                    // Set the epoch before register: some sources can deliver immediately. A later
                    // disable/close invalidates this epoch before waiting for serialized unregister.
                    validCallbackEpoch.set(epoch)
                    sensorRegistered = try {
                        proximityPort.register(rearSensor) { distance -> onProximityDistance(epoch, distance) }
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "register_failed source=rear_proximity error=${error.javaClass.simpleName}")
                        false
                    }
                    if (!sensorRegistered || closed.get()) {
                        validCallbackEpoch.compareAndSet(epoch, 0L)
                        if (sensorRegistered) proximityPort.unregister()
                        sensorRegistered = false
                    }
                    Log.i(TAG, "source_state enabled=true registered=$sensorRegistered replacedRegistration=$wasRegistered epoch=$epoch debounceMs=$SENSOR_DEBOUNCE_MS ${sensorDescription()}")
                } else if (nearState == true) {
                    nearState = false
                    Log.i(TAG, "source_state enabled=false registered=false reason=preference")
                    send(SceneDisplaySignal.RearProximity(false))
                } else {
                    Log.i(TAG, "source_state enabled=false registered=false reason=preference replacedRegistration=$wasRegistered ${sensorDescription()}")
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        receiverRegistered = true
        displayManager?.registerDisplayListener(displayListener, handler)
        displayListenerRegistered = displayManager != null
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        updateSensor()
        send(SceneDisplaySignal.RearDisplayPower(displayManager?.getDisplay(displayId)?.state == Display.STATE_ON))
        Log.i(TAG, "lease_monitor_started ${sensorDescription()} preferenceEnabled=${prefs.getBoolean(PROXIMITY_PREF, true)} registered=$sensorRegistered")

        return Closeable {
            if (!closed.compareAndSet(false, true)) return@Closeable
            validCallbackEpoch.set(0L)
            registrationEpoch.incrementAndGet()
            synchronized(sourceLifecycleLock) {
                val registeredBeforeClose = sensorRegistered
                pendingEdge?.let(handler::removeCallbacks)
                if (receiverRegistered) runCatching { app.unregisterReceiver(receiver) }
                if (displayListenerRegistered) runCatching { displayManager?.unregisterDisplayListener(displayListener) }
                runCatching { prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
                runCatching { proximityPort.unregister() }
                sensorRegistered = false
                Log.i(TAG, "lease_monitor_closed registeredBeforeClose=$registeredBeforeClose")
            }
        }
    }

    private companion object {
        const val TAG = "RearSceneProximity"
        const val PROXIMITY_PREF = "flutter.proximity_sensor_enabled"
        const val SENSOR_DEBOUNCE_MS = 300L
    }
}
