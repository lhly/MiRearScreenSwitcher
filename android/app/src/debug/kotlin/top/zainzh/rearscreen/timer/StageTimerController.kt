package top.zainzh.rearscreen.timer

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.R
import top.zainzh.rearscreen.ModuleSettingsActivity
import top.zainzh.rearscreen.modules.ModuleStore
import java.util.UUID

/** Single monotonic timer session and one alarm at the next stage boundary. */
object StageTimerController {
    private const val PREFS = "native_stage_timer"
    private const val CHANNEL = "stage_timer_boundaries"
    private const val NOTICE = 4101
    private const val ACTION = "top.zainzh.rearscreen.modules.STAGE_TIMER_BOUNDARY"
    const val ACTION_SESSION_CHANGED = "top.zainzh.rearscreen.modules.TIMER_SESSION_CHANGED"
    private var restoredScheduler = false
    private val fallbackBoot = java.security.SecureRandom().nextInt(Int.MAX_VALUE)
    private data class Record(
        val moduleId: String,
        val versionCode: Int,
        val engine: StageTimerEngine,
        val generation: String,
        var announced: Int
    )
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun now() = SystemClock.elapsedRealtime()
    private fun boot(c: Context) = runCatching {
        Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(fallbackBoot).coerceAtLeast(0)
    private fun allowed(c: Context, id: String) = ModuleStore.get(c, id) != null && ModuleStore.isEnabled(c, id)
    fun defaults(c: Context, id: String): JSONObject {
        val raw = ModuleStore.get(c, id)?.document?.optJSONObject("defaultConfig")?.toString() ?: "{}"
        return JSONObject(raw)
    }

    fun config(c: Context, id: String): JSONObject {
        val descriptor = ModuleStore.get(c, id)
        val currentVersion = descriptor?.versionCode ?: 0
        val saved = prefs(c).getString("config.$id", null)
        if (saved != null) {
            val result = runCatching { JSONObject(saved) }.getOrDefault(defaults(c, id))
            return migrateConfig(c, id, descriptor, currentVersion, result)
        }

        val result = defaults(c, id)
        val presetId = result.optString("preset")
        val presets = ModuleStore.get(c, id)?.document?.optJSONArray("presets")
        val preset = (0 until (presets?.length() ?: 0))
            .mapNotNull { index -> presets?.getJSONObject(index) }
            .firstOrNull { it.optString("id") == presetId }
        if (preset != null) {
            val currentLocale = java.util.Locale.getDefault()
            val traditional = currentLocale.script.equals("Hant", true) || currentLocale.country in setOf("TW", "HK", "MO")
            val locale = if (currentLocale.language != "zh") "en" else if (traditional) "zh-TW" else "zh-CN"
            val stages = preset.getJSONArray("stages")
            result.put("stages", JSONArray().apply {
                for (index in 0 until stages.length()) {
                    val stage = stages.getJSONObject(index)
                    val names = stage.optJSONObject("name")
                    val name = names?.optString(locale).orEmpty().ifBlank { names?.optString("en").orEmpty() }
                    put(JSONObject().put("name", name).put("durationSeconds", stage.optInt("durationSeconds")))
                }
            })
            result.put("rounds", preset.optInt("rounds", result.optInt("rounds", 1)))
        }
        return migrateConfig(c, id, descriptor, currentVersion, result)
    }

    fun saveConfig(c: Context, id: String, value: JSONObject) {
        val version = ModuleStore.get(c, id)?.versionCode ?: 0
        prefs(c).edit().putString("config.$id", value.toString()).putInt("configVersion.$id", version).apply()
    }

    /** Update only fields whose editable contract disappeared in a newer module version. */
    private fun migrateConfig(c: Context, id: String, descriptor: top.zainzh.rearscreen.modules.ModuleDescriptor?,
                              currentVersion: Int, config: JSONObject): JSONObject {
        if (descriptor == null || currentVersion <= 0) return config
        val preferences = prefs(c)
        val storedVersion = preferences.getInt("configVersion.$id", 0)
        if (storedVersion < currentVersion && config.has("keepVisible") &&
            !exposesBinding(descriptor.document, "config.keepVisible")) {
            val defaultValue = descriptor.document.optJSONObject("defaultConfig")
                ?.takeIf { it.has("keepVisible") }
                ?.optBoolean("keepVisible")
            if (defaultValue != null) config.put("keepVisible", defaultValue)
        }
        if (storedVersion != currentVersion) {
            preferences.edit().putString("config.$id", config.toString())
                .putInt("configVersion.$id", currentVersion).apply()
        }
        return config
    }

    private fun exposesBinding(document: JSONObject, binding: String): Boolean {
        fun contains(nodes: JSONArray?): Boolean {
            if (nodes == null) return false
            for (index in 0 until nodes.length()) {
                val node = nodes.optJSONObject(index) ?: continue
                if (node.optString("bind") == binding || contains(node.optJSONArray("children"))) return true
            }
            return false
        }
        return contains(document.optJSONArray("settingsUi")) || contains(document.optJSONArray("rearUi"))
    }

    private fun plan(c: Context, id: String, json: JSONObject = config(c, id)): StageTimerPlan {
        val stages = json.getJSONArray("stages")
        val planStages = (0 until stages.length()).map { index ->
            val stage = stages.getJSONObject(index)
            StageTimerPlan.Stage(stage.getString("name"), stage.getLong("durationSeconds") * 1000L)
        }
        return StageTimerPlan(json.optString("title", "Timer"), planStages, json.optInt("rounds", 1))
    }
    fun validateConfig(c: Context, id: String, json: JSONObject = config(c, id)) {
        require(allowed(c, id)) { "模块未启用或不可用" }
        plan(c, id, json)
    }
    @Synchronized fun start(c: Context, id: String) {
        if (!allowed(c, id)) return

        // Build and start the replacement before touching the current session. Invalid edits
        // must never cancel a valid running timer or its alarm.
        val newPlan = plan(c, id)
        val old = read(c)
        if (old?.moduleId == id && state(c, id).optBoolean("active")) return
        val descriptor = ModuleStore.get(c, id) ?: return
        val engine = StageTimerEngine(newPlan)
        engine.start(now(), boot(c))

        if (old != null) cancel(c)
        val record = Record(id, descriptor.versionCode, engine, UUID.randomUUID().toString(), 0)
        save(c, record)
        schedule(c, record)
        broadcastSessionChange(c, id)
    }
    @Synchronized fun pause(c: Context, id: String) = command(c, id) { it.pause(now(), boot(c)) }

    @Synchronized fun resume(c: Context, id: String) {
        if (allowed(c, id)) command(c, id) { it.resume(now(), boot(c)) }
    }
    @Synchronized fun stop(c: Context, id: String) = command(c, id) { it.stop(now(), boot(c)) }

    @Synchronized fun stopIfGeneration(c: Context, id: String, generation: String, versionCode: Int): Boolean {
        val record = read(c) ?: return false
        if (record.moduleId != id || record.generation != generation || record.versionCode != versionCode || !allowed(c, id))
            return false
        command(c, id) { it.stop(now(), boot(c)) }
        return true
    }

    @Synchronized fun clear(c: Context, id: String) {
        val record = read(c)
        if (record?.moduleId == id) {
            cancel(c)
            prefs(c).edit().remove("session").apply()
        }
    }

    @Synchronized fun onModuleChanged(c: Context, id: String, operation: String) {
        if (operation in setOf("disable", "update", "remove")) {
            // read() validates the persisted session against the module's current
            // installed version and enabled state, and only discards stale records.
            // A delayed update/disable/remove broadcast must not cancel a newer,
            // valid session started after that change.
            read(c)
        }
        if (operation == "remove" && ModuleStore.get(c, id) == null) {
            prefs(c).edit().remove("config.$id").apply()
        }
    }

    private fun command(c: Context, id: String, change: (StageTimerEngine) -> Unit) {
        val record = read(c) ?: return
        if (record.moduleId != id || !allowed(c, id)) return
        change(record.engine)
        record.announced = record.engine.snapshot(now(), boot(c)).completedStages
        save(c, record)
        cancelAlarm(c)
        schedule(c, record)
        broadcastSessionChange(c, id)
    }

    @Synchronized fun state(c: Context, id: String): JSONObject {
        val found = read(c)
        val record = found?.takeIf { it.moduleId == id && allowed(c, id) }
        val plan = record?.engine?.plan ?: runCatching { plan(c, id) }.getOrNull()
        val snapshot = record?.engine?.snapshot(now(), boot(c))
        val status = snapshot?.status ?: StageTimerEngine.Status.IDLE
        val index = (snapshot?.stageIndex ?: 0).coerceIn(0, (plan?.stages?.size ?: 1) - 1)
        val remainingMs = snapshot?.stageRemainingMs ?: (plan?.stages?.getOrNull(index)?.durationMs ?: 0)
        val seconds = (remainingMs + 999) / 1000
        val locale = java.util.Locale.getDefault()
        val remainingText = if (seconds >= 3600) {
            String.format(locale, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        } else {
            String.format(locale, "%02d:%02d", seconds / 60, seconds % 60)
        }
        val stageCount = plan?.stages?.size ?: 1
        val progress = if (snapshot == null || plan == null || plan.totalDurationMs == 0L) 0.0
            else 1.0 - snapshot.totalRemainingMs.toDouble() / plan.totalDurationMs
        return JSONObject()
            .put("enabled", allowed(c, id))
            .put("active", status == StageTimerEngine.Status.RUNNING || status == StageTimerEngine.Status.PAUSED)
            .put("running", status == StageTimerEngine.Status.RUNNING)
            .put("paused", status == StageTimerEngine.Status.PAUSED)
            .put("finished", status == StageTimerEngine.Status.FINISHED)
            .put("idle", status == StageTimerEngine.Status.IDLE)
            .put("stageName", plan?.stages?.getOrNull(index)?.name ?: "")
            .put("remainingText", remainingText)
            .put("position", "${(snapshot?.roundIndex ?: 0) + 1}/${plan?.rounds ?: 1} · ${index + 1}/$stageCount")
            .put("progress", progress)
            .put("stageIndex", index)
            .put("rounds", plan?.rounds ?: 1)
            .put("status", status.name)
            .put("generation", record?.generation ?: "")
            .put("versionCode", record?.versionCode ?: 0)
    }

    /** Immutable identity used by the finite display-recovery session. */
    @Synchronized fun recoverySession(c: Context, id: String): JSONObject? {
        val record = read(c) ?: return null
        if (record.moduleId != id || !allowed(c, id)) return null
        val snapshot = record.engine.snapshot(now(), boot(c))
        val descriptor = ModuleStore.get(c, id) ?: return null
        if (snapshot.status != StageTimerEngine.Status.RUNNING || descriptor.versionCode != record.versionCode) return null
        return JSONObject().put("moduleId", id).put("versionCode", record.versionCode)
            .put("generation", record.generation)
    }
    @Synchronized fun pulse(c: Context, id: String) {
        if (!restoredScheduler) {
            restoredScheduler = true
            prefs(c).edit().remove("deadline").apply()
        }
        val record = read(c) ?: return
        if (record.moduleId != id || !allowed(c, id)) {
            if (record.moduleId == id) stop(c, id)
            return
        }
        val snapshot = record.engine.snapshot(now(), boot(c))
        if (snapshot.completedStages > record.announced) {
            record.announced = snapshot.completedStages
            save(c, record)
            notifyBoundary(c, id, record.engine.plan, snapshot)
            broadcastSessionChange(c, id)
        }
        schedule(c, record)
    }

    @Synchronized fun alarm(c: Context, id: String, generation: String) {
        val record = read(c) ?: return
        if (record.moduleId != id || record.generation != generation || !allowed(c, id)) return
        prefs(c).edit().remove("deadline").apply()
        pulse(c, id)
    }

    @Synchronized fun reschedule(c: Context, id: String) {
        prefs(c).edit().remove("deadline").apply()
        pulse(c, id)
    }

    @Synchronized fun handleSystemStateChange(c: Context) {
        val record = read(c) ?: return
        if (!allowed(c, record.moduleId)) {
            onModuleChanged(c, record.moduleId, "disable")
            return
        }
        val snapshot = record.engine.snapshot(now(), boot(c))
        if (snapshot.status == StageTimerEngine.Status.INTERRUPTED) {
            save(c, record)
            cancelAlarm(c)
            NotificationManagerCompat.from(c).cancel(NOTICE)
            return
        }
        prefs(c).edit().remove("deadline").apply()
        schedule(c, record)
    }

    fun exactAllowed(c: Context) = Build.VERSION.SDK_INT < 31 ||
        c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun notificationsAllowed(c: Context): Boolean {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = c.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun broadcastSessionChange(c: Context, id: String) {
        c.sendBroadcast(Intent(ACTION_SESSION_CHANGED).setPackage(c.packageName).putExtra("module_id", id))
    }
    private fun pending(c: Context, id: String?, generation: String?): PendingIntent {
        val intent = Intent(c, StageTimerAlarmReceiver::class.java)
            .setAction(ACTION)
            .putExtra("module_id", id)
            .putExtra("generation", generation)
        return PendingIntent.getBroadcast(c, 4100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(c: Context, record: Record) {
        val snapshot = record.engine.snapshot(now(), boot(c))
        val descriptor = ModuleStore.get(c, record.moduleId)
        val validSession = allowed(c, record.moduleId) && descriptor?.versionCode == record.versionCode
        if (!validSession || snapshot.status != StageTimerEngine.Status.RUNNING) {
            cancelAlarm(c)
            return
        }
        if (prefs(c).getLong("deadline", -1) == snapshot.boundaryElapsedMs) return

        val alarmManager = c.getSystemService(AlarmManager::class.java)
        val pendingIntent = pending(c, record.moduleId, record.generation)
        val deadline = snapshot.boundaryElapsedMs.coerceAtLeast(now() + 1)
        try {
            if (exactAllowed(c)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pendingIntent)
            }
        } catch (_: SecurityException) {
            // Permission can be revoked between the capability check and alarm submission.
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pendingIntent)
        }
        prefs(c).edit().putLong("deadline", snapshot.boundaryElapsedMs).apply()
    }

    private fun cancelAlarm(c: Context) {
        val existing = PendingIntent.getBroadcast(c, 4100,
            Intent(c, StageTimerAlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (existing != null) {
            c.getSystemService(AlarmManager::class.java).cancel(existing)
            existing.cancel()
        }
        val preferences = prefs(c)
        if (preferences.contains("deadline")) preferences.edit().remove("deadline").apply()
    }

    private fun cancel(c: Context) {
        cancelAlarm(c)
        NotificationManagerCompat.from(c).cancel(NOTICE)
    }

    private fun notifyBoundary(c: Context, id: String, plan: StageTimerPlan, snapshot: StageTimerEngine.Snapshot) {
        val manager = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                c.getString(R.string.timer_channel_name), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val openSettings = PendingIntent.getActivity(c, 4102,
            Intent(c, ModuleSettingsActivity::class.java).putExtra("module_id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val message = if (snapshot.status == StageTimerEngine.Status.FINISHED) {
            c.getString(R.string.timer_runtime_finished)
        } else {
            plan.stages[snapshot.stageIndex].name
        }
        val notification = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(plan.title)
            .setContentText(message)
            .setContentIntent(openSettings)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setTimeoutAfter(60_000)
            .build()
        runCatching { manager.notify(NOTICE, notification) }
    }

    private fun encode(plan: StageTimerPlan) = JSONObject()
        .put("title", plan.title)
        .put("rounds", plan.rounds)
        .put("stages", JSONArray().apply {
            plan.stages.forEach { stage ->
                put(JSONObject().put("name", stage.name).put("durationSeconds", stage.durationMs / 1000))
            }
        })

    private fun save(c: Context, record: Record) {
        val saved = record.engine.exportState()
        val state = JSONObject()
            .put("status", saved.status.name)
            .put("elapsed", saved.elapsedBeforeAnchorMs)
            .put("anchor", saved.anchorElapsedMs)
            .put("boot", saved.bootCount)
            .put("observed", saved.lastObservedElapsedMs)
        val session = JSONObject()
            .put("moduleId", record.moduleId)
            .put("versionCode", record.versionCode)
            .put("generation", record.generation)
            .put("plan", encode(record.engine.plan))
            .put("state", state)
            .put("announced", record.announced)
        prefs(c).edit().putString("session", session.toString()).commit()
    }

    private fun read(c: Context): Record? {
        val raw = prefs(c).getString("session", null) ?: return null
        return runCatching {
            val session = JSONObject(raw)
            val id = session.getString("moduleId")
            val descriptor = ModuleStore.get(c, id) ?: error("module missing")
            require(descriptor.versionCode == session.getInt("versionCode") && ModuleStore.isEnabled(c, id))
            val planJson = session.getJSONObject("plan")
            val stages = planJson.getJSONArray("stages")
            val plan = StageTimerPlan(planJson.getString("title"), (0 until stages.length()).map { index ->
                val stage = stages.getJSONObject(index)
                StageTimerPlan.Stage(stage.getString("name"), stage.getLong("durationSeconds") * 1000L)
            }, planJson.getInt("rounds"))
            val savedJson = session.getJSONObject("state")
            val saved = StageTimerEngine.SavedState(
                StageTimerEngine.Status.valueOf(savedJson.getString("status")),
                savedJson.getLong("elapsed"),
                savedJson.getLong("anchor"),
                savedJson.getInt("boot"),
                savedJson.getLong("observed")
            )
            val engine = StageTimerEngine.restore(plan, saved, now(), boot(c))
            Record(id, descriptor.versionCode, engine, session.getString("generation"), session.optInt("announced"))
        }.getOrElse {
            cancel(c)
            prefs(c).edit().remove("session").apply()
            null
        }
    }
}

class StageTimerAlarmReceiver: android.content.BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){val module=intent.getStringExtra("module_id")?:return;val id=intent.getStringExtra("generation");if(id==null)StageTimerController.reschedule(context,module)else StageTimerController.alarm(context,module,id)}}

class ModuleRuntimeReceiver:android.content.BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val id=intent.getStringExtra("id")?:return
        val operation=intent.getStringExtra("operation")?:return
        StageTimerController.onModuleChanged(context,id,operation)
    }
}

class StageTimerSystemReceiver:android.content.BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        when(intent.action){
            Intent.ACTION_BOOT_COMPLETED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> StageTimerController.handleSystemStateChange(context)
        }
    }
}
