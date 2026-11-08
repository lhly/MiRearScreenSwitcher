package top.zainzh.rearscreen.platform.data

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import top.zainzh.rearscreen.ITaskService
import top.zainzh.rearscreen.TaskServiceManager
import java.io.Closeable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Production wiring; never accepts a path, command, or shell capability from a caller. */
object PlatformDataProviders {
    fun create(context: Context): PlatformDataProvider {
        val app = context.applicationContext
        val source = AndroidPlatformDataSource(app)
        val schedule = PlatformDataSchedule { interval, task ->
            val sampler = ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "platform-data-sampler").apply { isDaemon = true }
            }
            val future = sampler.scheduleWithFixedDelay(task, interval, interval, TimeUnit.MILLISECONDS)
            Closeable { future.cancel(false); sampler.shutdownNow() }
        }
        return SharedPlatformDataProvider(source, PlatformDataClock { SystemClock.elapsedRealtime() }, schedule)
    }
}

private class AndroidPlatformDataSource(private val context: Context) : PlatformDataSource {
    @Volatile private var battery: BatterySample? = null
    @Volatile private var remote: ITaskService? = null
    @Volatile private var batteryReceiver: BroadcastReceiver? = null
    @Volatile private var serviceConnection: ServiceConnection? = null

    override fun readBattery(): BatterySample? {
        battery?.let { return it }
        val sticky = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            ?: return null
        return batteryFrom(sticky)
    }

    override fun readCpuCounters(): CpuCounterSample? {
        val service = remote?.takeIf { it.asBinder().isBinderAlive } ?: return null
        return runCatching {
            val result = JSONObject(service.readSystemMetrics())
            if (result.optString("cpuStatus") != "available") null else {
                val line = result.optString("cpu")
                val sampledAt = result.optLong("sampledAt", -1L)
                if (line.isBlank() || line == "null" || sampledAt < 0) null else CpuCounterSample(line, sampledAt)
            }
        }.onFailure { Log.d(TAG, "CPU metrics source unavailable", it) }.getOrNull()
    }

    override fun readMemory(): MemorySample? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        if (!runCatching { manager.getMemoryInfo(info) }.isSuccess || info.totalMem <= 0 || info.availMem < 0) return null
        return MemorySample(info.availMem, info.totalMem, SystemClock.elapsedRealtime())
    }

    override fun startBatteryEvents(onChanged: () -> Unit): Closeable {
        if (batteryReceiver != null) return Closeable { }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                battery = batteryFrom(intent)
                onChanged()
            }
        }
        batteryReceiver = receiver
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (failure: RuntimeException) {
            batteryReceiver = null
            Log.w(TAG, "Battery event source unavailable", failure)
            return Closeable { }
        }
        return Closeable {
            if (batteryReceiver === receiver) {
                batteryReceiver = null
                battery = null
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    @Synchronized override fun startSystemMetrics() {
        if (serviceConnection != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = ITaskService.Stub.asInterface(binder) }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
            override fun onBindingDied(name: ComponentName) { remote = null }
            override fun onNullBinding(name: ComponentName) { remote = null }
        }
        serviceConnection = connection
        TaskServiceManager.acquire(context, connection)
    }

    @Synchronized override fun stopSystemMetrics() {
        val connection = serviceConnection ?: return
        serviceConnection = null
        remote = null
        TaskServiceManager.release(connection)
    }

    private fun batteryFrom(intent: Intent): BatterySample {
        val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        return BatterySample(rawLevel, scale, charging, SystemClock.elapsedRealtime())
    }

    private companion object { const val TAG = "PlatformDataProvider" }
}
