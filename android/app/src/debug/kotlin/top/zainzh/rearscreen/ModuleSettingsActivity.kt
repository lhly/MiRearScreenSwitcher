package top.zainzh.rearscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.modules.ModuleDescriptor
import top.zainzh.rearscreen.modules.ModuleStore
import top.zainzh.rearscreen.timer.StageTimerController
import top.yukonga.miuix.kmp.basic.Text

/** Module-owned JSON drives this page; all executable actions remain host controlled. */
class ModuleSettingsActivity : ComponentActivity() {
    private var pendingRearAction: String? = null
    private var pendingRearVersion = 0
    private val rearLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val action = pendingRearAction
        val expectedVersion = pendingRearVersion
        Log.i("ModuleSettings", "rear-result module=${if(::descriptor.isInitialized) descriptor.id else "unknown"} action=${action.orEmpty()} expectedVersion=$expectedVersion resultCode=${result.resultCode}")
        pendingRearAction = null
        pendingRearVersion = 0
        if (result.resultCode != RESULT_OK) {
            error = result.data?.getStringExtra("error") ?: getString(R.string.native_modules_rear_unavailable)
        } else if (action != null) {
            runCatching {
                val current = ModuleStore.get(this, descriptor.id)
                if (current == null || !ModuleStore.isEnabled(this, descriptor.id) || current.versionCode != expectedVersion) {
                    throw IllegalStateException(getString(R.string.native_modules_rear_unavailable))
                }
                when (action) {
                    "start" -> StageTimerController.start(this, descriptor.id)
                    "resume" -> StageTimerController.resume(this, descriptor.id)
                }
                state = StageTimerController.state(this, descriptor.id)
                if (state.optBoolean("running")) {
                    val keepVisible = StageTimerController.config(this, descriptor.id).optBoolean("keepVisible", false)
                    val lease = ModuleDisplayRecoveryService.startForSession(
                        this, descriptor.id, RearModuleSession.taskId(), forceNewDisplay = true)
                    if (keepVisible && lease == null)
                        throw IllegalStateException(getString(R.string.native_modules_rear_unavailable))
                }
            }.onFailure { error = it.message ?: getString(R.string.native_modules_action_failed) }
        }
    }
    private var permissionRevision by mutableIntStateOf(0)
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->permissionRevision++;if(!granted)error=getString(R.string.native_modules_notifications_disabled)}
    private lateinit var descriptor: ModuleDescriptor
    private var config by mutableStateOf(JSONObject())
    private var state by mutableStateOf(JSONObject())
    private var error by mutableStateOf("")
    private var invalidFields by mutableStateOf<Set<String>>(emptySet())
    private var activityVisible = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var displayListenerRegistered=false
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener=object:DisplayManager.DisplayListener{
        override fun onDisplayAdded(displayId:Int)=updateVisibility()
        override fun onDisplayRemoved(displayId:Int)=updateVisibility()
        override fun onDisplayChanged(displayId:Int)=updateVisibility()
    }
    private fun canRefresh()=activityVisible&&!isFinishing&&window.decorView.windowVisibility==android.view.View.VISIBLE&&
        windowManager.defaultDisplay.state==android.view.Display.STATE_ON
    private fun updateVisibility(){
        handler.removeCallbacks(tick)
        if(canRefresh())refreshSession()
    }
    private fun refreshSession(){
        if(!::descriptor.isInitialized||isFinishing)return
        state=StageTimerController.state(this,descriptor.id)
        handler.removeCallbacks(tick)
        if(canRefresh()&&state.optBoolean("running"))handler.post(tick)
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!canRefresh()) return
            if (!state.optBoolean("running")) return
            StageTimerController.pulse(this@ModuleSettingsActivity, descriptor.id)
            state = StageTimerController.state(this@ModuleSettingsActivity, descriptor.id)
            if(state.optBoolean("running"))handler.postDelayed(this,1000)
        }
    }
    private var registered = false
    private val moduleChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val changedId=if(intent?.action==StageTimerController.ACTION_SESSION_CHANGED)intent?.getStringExtra("module_id")else intent?.getStringExtra("id")
            if (changedId != descriptor.id) return
            if(intent?.action==StageTimerController.ACTION_SESSION_CHANGED){refreshSession();return}
            val operation = intent?.getStringExtra("operation")
            if (operation in setOf("disable", "remove", "update")) {
                val current = ModuleStore.get(this@ModuleSettingsActivity, descriptor.id)
                if (current == null || !ModuleStore.isEnabled(this@ModuleSettingsActivity, descriptor.id) ||
                    current.versionCode != descriptor.versionCode) {
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("module_id").orEmpty()
        descriptor = ModuleStore.get(this, id) ?: run { finish(); return }
        if (!ModuleStore.isEnabled(this, id)) { finish(); return }
        pendingRearAction = savedInstanceState?.getString("pending_rear_action")
        pendingRearVersion = savedInstanceState?.getInt("pending_rear_version") ?: 0
        config = StageTimerController.config(this, id)
        state = StageTimerController.state(this, id)
        miuixContent {
            val locale = resources.configuration.locales[0].toLanguageTag()
            PageFrame(descriptor.label(locale), ::finish, "module_settings_back") { modifier, header ->
                LazyColumn(modifier, contentPadding = PaddingValues(20.dp, header + 12.dp, 20.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (descriptor.id == "stage_timer") item(key = "cover_black_note") {
                        AppCard {
                            Text(getString(R.string.native_modules_cover_black_note),
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                fontSize = 13.sp, color = AppPalette.secondary())
                        }
                    }
                    item(key = "module_content") {
                        androidx.compose.foundation.layout.Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            NativeModuleNodes(descriptor.document.optJSONArray("settingsUi") ?: JSONArray(), descriptor,
                                config, state, false, ::updateConfig, ::performAction, ::updateValidity, invalidFields.isEmpty())
                            if (error.isNotEmpty()) Text(error, Modifier.padding(horizontal = 12.dp), fontSize = 13.sp, color = AppPalette.accent())
                        }
                    }
                    item(key = "alarm_note") {
                        AppSection(getString(R.string.native_modules_runtime))
                        AppCard {
                            androidx.compose.foundation.layout.Column {
                                AppRow(getString(R.string.native_modules_exact_alarm), exactAlarmSummary(),
                                    enabled=Build.VERSION.SDK_INT>=31&&!StageTimerController.exactAllowed(this@ModuleSettingsActivity),
                                    action=if(Build.VERSION.SDK_INT>=31&&!StageTimerController.exactAllowed(this@ModuleSettingsActivity))::requestExactAlarm else null)
                                AppDivider()
                                AppRow(getString(R.string.native_modules_notifications), notificationSummary(), action=::requestNotifications)
                            }
                        }
                        Text(getString(R.string.timer_alarm_caveat), Modifier.padding(horizontal = 6.dp), fontSize = 13.sp, color = AppPalette.secondary())
                    }
                }
            }
        }
    }

    private fun updateConfig(next: JSONObject) {
        config = JSONObject(next.toString())
        val currentFields=mutableSetOf<String>()
        fun collect(nodes:JSONArray){for(index in 0 until nodes.length()){
            val node=nodes.getJSONObject(index)
            when(node.optString("type")){
                "number","textInput"->currentFields+=node.optString("id")
                "stageList"->{val id=node.optString("id");currentFields+=id;val count=config.optJSONArray("stages")?.length()?:0;repeat(count){currentFields+="$id.name.$it";currentFields+="$id.duration.$it"}}
            }
            node.optJSONArray("children")?.let(::collect)
        }}
        collect(descriptor.document.optJSONArray("settingsUi")?:JSONArray())
        invalidFields=invalidFields.intersect(currentFields)
        StageTimerController.saveConfig(this, descriptor.id, config)
    }

    private fun updateValidity(id:String,valid:Boolean){
        val updated=if(valid)invalidFields-id else invalidFields+id
        if(updated!=invalidFields)invalidFields=updated
    }

    private fun performAction(action: String) {
        error = ""
        if (pendingRearAction != null && action in setOf("start", "resume", "show")) {
            error = getString(R.string.native_modules_working)
            return
        }
        if(invalidFields.isNotEmpty()&&action in setOf("start","save")){error=getString(R.string.native_modules_fix_invalid_fields);return}
        if (!ModuleStore.isEnabled(this, descriptor.id)) { finish(); return }
        runCatching {
            when (action) {
                "start" -> {
                    StageTimerController.validateConfig(this, descriptor.id, config)
                    StageTimerController.saveConfig(this, descriptor.id, config)
                    launchRearAction(action)
                    return
                }
                "pause" -> StageTimerController.pause(this, descriptor.id)
                "resume" -> {
                    launchRearAction(action)
                    return
                }
                "stop" -> StageTimerController.stop(this, descriptor.id)
                "show" -> launchRearAction(action)
                "close" -> finish()
                "save" -> StageTimerController.saveConfig(this, descriptor.id, config)
            }
            state = StageTimerController.state(this, descriptor.id)
        }.onFailure { error = it.message ?: getString(R.string.native_modules_action_failed) }
    }

    private fun launchRearAction(action: String) {
        pendingRearAction = action
        pendingRearVersion = descriptor.versionCode
        try {
            rearLauncher.launch(Intent(this, NativeModuleLaunchActivity::class.java).putExtra("module_id", descriptor.id))
        } catch (failure: Exception) {
            pendingRearAction = null
            pendingRearVersion = 0
            throw failure
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_rear_action", pendingRearAction)
        outState.putInt("pending_rear_version", pendingRearVersion)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        val filter=IntentFilter().apply{
            addAction(ModuleStore.ACTION_CHANGED)
            addAction(StageTimerController.ACTION_SESSION_CHANGED)
        }
        ContextCompat.registerReceiver(this, moduleChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        displays.registerDisplayListener(displayListener,handler)
        displayListenerRegistered=true
    }
    override fun onResume() {
        super.onResume()
        val current = ModuleStore.get(this, descriptor.id)
        if (current == null || !ModuleStore.isEnabled(this, descriptor.id) ||
            current.versionCode != descriptor.versionCode) {
            finish()
            return
        }
        permissionRevision++
        StageTimerController.handleSystemStateChange(this)
        activityVisible = true
        updateVisibility()
    }
    override fun onPause() { activityVisible = false; handler.removeCallbacks(tick); super.onPause() }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);updateVisibility()}
    override fun onStop() { if (registered) { unregisterReceiver(moduleChanges); registered = false };if(displayListenerRegistered){displays.unregisterDisplayListener(displayListener);displayListenerRegistered=false};super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun exactAlarmSummary():String{
        permissionRevision
        return if(StageTimerController.exactAllowed(this))getString(R.string.native_modules_exact_ready)
        else getString(R.string.native_modules_exact_fallback)
    }
    private fun notificationSummary():String{
        permissionRevision
        return if(StageTimerController.notificationsAllowed(this))getString(R.string.native_modules_notifications_ready)
        else getString(R.string.native_modules_notifications_disabled)
    }
    private fun requestExactAlarm(){
        if(Build.VERSION.SDK_INT<31)return
        runCatching{startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.fromParts("package",packageName,null)))}
            .onFailure{error=getString(R.string.native_modules_permission_page_unavailable)}
    }
    private fun requestNotifications(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        runCatching{startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))}
            .onFailure{error=getString(R.string.native_modules_permission_page_unavailable)}
    }
}
