package top.zainzh.rearscreen.timer

import android.content.Context
import org.json.JSONObject

/** Release boundary for the unshipped .remodule stage-timer experiment. */
object StageTimerController {
    const val ACTION_SESSION_CHANGED = "top.zainzh.rearscreen.modules.TIMER_SESSION_CHANGED"

    fun defaults(c: Context, id: String) = JSONObject()
    fun config(c: Context, id: String) = JSONObject()
    fun saveConfig(c: Context, id: String, value: JSONObject) = Unit
    fun validateConfig(c: Context, id: String, json: JSONObject = config(c, id)) {
        throw UnsupportedOperationException("E_UNSUPPORTED: legacy stage_timer is debug-only")
    }
    fun start(c: Context, id: String) = Unit
    fun pause(c: Context, id: String) = Unit
    fun resume(c: Context, id: String) = Unit
    fun stop(c: Context, id: String) = Unit
    fun stopIfGeneration(c: Context, id: String, generation: String, versionCode: Int) = false
    fun clear(c: Context, id: String) = Unit
    fun onModuleChanged(c: Context, id: String, operation: String) = Unit
    fun state(c: Context, id: String) = JSONObject()
        .put("enabled", false).put("active", false).put("running", false).put("paused", false)
        .put("finished", false).put("idle", true).put("status", "UNSUPPORTED")
        .put("generation", "").put("versionCode", 0)
    fun recoverySession(c: Context, id: String): JSONObject? = null
    fun pulse(c: Context, id: String) = Unit
    fun alarm(c: Context, id: String, generation: String) = Unit
    fun reschedule(c: Context, id: String) = Unit
    fun handleSystemStateChange(c: Context) = Unit
    fun exactAllowed(c: Context) = false
    fun notificationsAllowed(c: Context) = false
}
