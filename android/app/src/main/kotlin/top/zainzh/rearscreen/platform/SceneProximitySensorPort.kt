package top.zainzh.rearscreen.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

/** Sensor-system boundary so the lease monitor and its near/far delivery can be tested together. */
internal interface SceneProximitySensorPort {
    val managerAvailable: Boolean
    fun discover(): List<RearProximitySensorCandidate>
    fun register(source: RearProximitySensorCandidate, onDistance: (Float) -> Unit): Boolean
    fun unregister()
}

internal fun interface SceneProximitySensorPortFactory {
    fun create(context: Context, callbackHandler: Handler): SceneProximitySensorPort
}

/** Production source: enumerate OEM sensor types and deliver events on the monitor's main handler. */
internal class AndroidSceneProximitySensorPort(
    context: Context,
    private val callbackHandler: Handler
) : SceneProximitySensorPort {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val listenerOwner = SceneSensorListenerOwner<Pair<Sensor, SensorEventListener>>(
        registerSource = { registration -> manager?.registerListener(registration.second, registration.first, SensorManager.SENSOR_DELAY_NORMAL, callbackHandler) ?: false },
        unregisterSource = { registration -> manager?.unregisterListener(registration.second) }
    )

    override val managerAvailable: Boolean get() = manager != null

    override fun discover(): List<RearProximitySensorCandidate> = manager
        ?.getSensorList(RearProximitySensorSelector.SENSOR_LIST_TYPE)
        .orEmpty()
        .map { sensor ->
            RearProximitySensorCandidate(
                type = sensor.type,
                name = sensor.name.orEmpty(),
                stringType = sensor.stringType.orEmpty(),
                wakeUp = sensor.isWakeUpSensor,
                maximumRange = sensor.maximumRange,
                platformHandle = sensor
            )
        }

    override fun register(source: RearProximitySensorCandidate, onDistance: (Float) -> Unit): Boolean {
        val sensor = source.platformHandle as? Sensor ?: return false
        if (manager == null) return false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor == sensor && event.values.isNotEmpty()) onDistance(event.values[0])
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        return listenerOwner.register(sensor to listener)
    }

    override fun unregister() = listenerOwner.unregister()
}

/** Serializes a potentially blocking source registration with disable/close cleanup. */
internal class SceneSensorListenerOwner<T>(
    private val registerSource: (T) -> Boolean,
    private val unregisterSource: (T) -> Unit
) {
    private val lock = Any()
    private var active: T? = null

    fun register(listener: T): Boolean = synchronized(lock) {
        releaseActiveLocked()
        val registered = registerSource(listener)
        if (registered) active = listener
        registered
    }

    fun unregister() = synchronized(lock) { releaseActiveLocked() }

    private fun releaseActiveLocked() {
        val previous = active ?: return
        active = null
        unregisterSource(previous)
    }
}
