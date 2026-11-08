package top.zainzh.rearscreen

import android.app.Activity
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import top.zainzh.rearscreen.modules.ModuleStore
import java.util.concurrent.Executors

/** User initiated, Shizuku checked launch gate for a native module rear panel. */
class NativeModuleLaunchActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var submitted = false
    @Volatile private var gateSucceeded = false
    private var moduleId = ""
    @Volatile private var startupToken: ModuleStartupHoldRegistry.Token? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed || submitted) return
            submitted = true
            Log.i(TAG, "gate binder connected module=$moduleId")
            worker.execute { launch(ITaskService.Stub.asInterface(binder)) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.i(TAG, "gate binder disconnected module=$moduleId")
            complete(getString(R.string.native_modules_rear_unavailable))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        moduleId = intent.getStringExtra("module_id").orEmpty()
        val enabled = moduleId.isNotBlank() && ModuleStore.isEnabled(this, moduleId)
        val keyguardLocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        Log.i(TAG, "gate create module=$moduleId enabled=$enabled restored=${savedInstanceState != null}")
        if (savedInstanceState?.getBoolean("launch_submitted") == true) {
            complete(getString(R.string.native_modules_rear_unavailable))
            return
        }
        if (!enabled) {
            complete(getString(R.string.native_modules_rear_unavailable))
            return
        }
        if (keyguardLocked) {
            complete(getString(R.string.native_modules_unlock))
            return
        }
        main.postDelayed({ complete(getString(R.string.native_modules_service_unavailable)) }, 7_000L)
        TaskServiceManager.acquire(this, connection)
    }

    private fun launch(service: ITaskService) {
        var failure = getString(R.string.native_modules_rear_unavailable)
        val startedAt = SystemClock.elapsedRealtime()
        try {
            RearAnimationManager.runDisplayAction {
                if (closed || !ModuleStore.isEnabled(this, moduleId)) return@runDisplayAction
                try {
                    val display = RearDisplay.requireId()
                    val foreground = service.getForegroundAppOnDisplay(display).orEmpty()
                    val own = RearModuleSession.owns(foreground, packageName)
                    val official = foreground.startsWith("com.xiaomi.subscreencenter:") ||
                        foreground.startsWith("com.xiaomi.mirror:")
                    val casting = JSONObject(service.getRearVirtualCastStatus()).optString("state") == "running"
                    val animating = RearAnimationManager.isAnimationPlaying()
                    Log.i(TAG, "gate check module=$moduleId display=$display owner=${ownerKind(foreground, own, official)} casting=$casting animation=$animating")
                    if (casting || animating || (!official && !own)) {
                        failure = getString(R.string.native_modules_rear_busy)
                        return@runDisplayAction
                    }
                    if (!ModuleStore.isEnabled(this, moduleId)) return@runDisplayAction

                    val token = ModuleStartupHoldRegistry.issue()
                    startupToken = token
                    Log.i(TAG, "gate startup-hold issued module=$moduleId nonce=${token.nonce} deadline=${token.deadlineElapsedMs}")
                    service.executeShellCommand("input -d $display keyevent KEYCODE_WAKEUP")
                    val component = ComponentName(this, ModuleRearActivity::class.java).flattenToString()
                    val started = service.executeShellCommand(
                        "am start --display $display -n ${RearAnimationDisplay.quote(component)} " +
                            "--ei display_id $display --es module_id ${RearAnimationDisplay.quote(moduleId)} " +
                            "--el ${ModuleStartupHoldRegistry.EXTRA_NONCE} ${token.nonce} " +
                            "--el ${ModuleStartupHoldRegistry.EXTRA_DEADLINE} ${token.deadlineElapsedMs}"
                    )
                    Log.i(TAG, "gate am-start module=$moduleId display=$display started=$started elapsed=${SystemClock.elapsedRealtime() - startedAt}ms")
                    if (!started) {
                        cancelStartupHold(token)
                        return@runDisplayAction
                    }
                    val awaitStartedAt = SystemClock.elapsedRealtime()
                    val foregroundReached = RearAnimationDisplay.awaitForeground(
                        service, component, packageName,
                        { !closed && ModuleStore.isEnabled(this, moduleId) &&
                            ModuleStartupHoldRegistry.state(token) == ModuleStartupHoldRegistry.State.ACTIVE },
                        { observation -> Log.i(TAG, "gate await-observation module=$moduleId $observation") }
                    )
                    val moduleLoaded = foregroundReached && awaitModuleLoaded(token, display)
                    val confirmedForeground = moduleLoaded && RearAnimationDisplay.awaitForeground(
                        service, component, packageName,
                        { !closed && ModuleStore.isEnabled(this, moduleId) &&
                            ModuleStartupHoldRegistry.isModuleLoaded(token, moduleId, display) }
                    )
                    Log.i(TAG, "gate await module=$moduleId foreground=$foregroundReached loaded=$moduleLoaded confirmed=$confirmedForeground elapsed=${SystemClock.elapsedRealtime() - awaitStartedAt}ms total=${SystemClock.elapsedRealtime() - startedAt}ms")
                    if (confirmedForeground) {
                        gateSucceeded = true
                        main.post { complete(null) }
                    } else {
                        cancelStartupHold(token)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "gate launch failed module=$moduleId exception=${e.javaClass.simpleName}")
                    startupToken?.let(::cancelStartupHold)
                    failure = e.message ?: failure
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "gate display-action failed module=$moduleId exception=${e.javaClass.simpleName}")
            startupToken?.let(::cancelStartupHold)
        }
        if (!closed) main.post { if (!isFinishing) complete(failure) }
    }

    private fun ownerKind(foreground: String, own: Boolean, official: Boolean): String = when {
        own -> "self"
        official -> "official"
        foreground.isBlank() -> "none"
        else -> "other"
    }

    private fun cancelStartupHold(token: ModuleStartupHoldRegistry.Token) {
        ModuleStartupHoldRegistry.release(token)
        sendBroadcast(ModuleStartupHoldRegistry.write(
            Intent(ModuleRearActivity.ACTION_STARTUP_HOLD_RELEASE).setPackage(packageName), token
        ).putExtra("module_id", moduleId))
    }

    private fun awaitModuleLoaded(token: ModuleStartupHoldRegistry.Token, display: Int): Boolean {
        val deadline = minOf(token.deadlineElapsedMs, SystemClock.elapsedRealtime() + MODULE_READY_WAIT_MS)
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            if (ModuleStartupHoldRegistry.isModuleLoaded(token, moduleId, display)) return true
            if (!ModuleStore.isEnabled(this, moduleId)) return false
            Thread.sleep(MODULE_READY_POLL_MS)
        }
        return ModuleStartupHoldRegistry.isModuleLoaded(token, moduleId, display)
    }

    private fun complete(error: String?) {
        if (closed || isFinishing) return
        closed = true
        if (error == null) {
            Log.i(TAG, "gate result module=$moduleId result=ok")
            setResult(RESULT_OK)
        } else {
            startupToken?.let(::cancelStartupHold)
            Log.i(TAG, "gate result module=$moduleId result=cancelled error=${error}")
            setResult(RESULT_CANCELED, Intent().putExtra("error", error))
        }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("launch_submitted", submitted)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closed = true
        main.removeCallbacksAndMessages(null)
        if (!gateSucceeded) startupToken?.let(::cancelStartupHold)
        TaskServiceManager.release(connection)
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "NativeModuleLaunch"
        const val MODULE_READY_WAIT_MS = 750L
        const val MODULE_READY_POLL_MS = 25L
    }
}
