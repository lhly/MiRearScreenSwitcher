package top.zainzh.rearscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import top.zainzh.rearscreen.modules.ModuleDescriptor
import top.zainzh.rearscreen.modules.ModuleStore
import top.zainzh.rearscreen.timer.StageTimerController

/** Module rear layout is the same declared node tree used by its settings page. */
class ModuleRearActivity : ComponentActivity() {
    private var descriptor: ModuleDescriptor? = null
    private var state by mutableStateOf(JSONObject())
    private var configInvalid=mutableSetOf<String>()
    private var renderError by mutableStateOf("")
    private var activityVisible = false
    private var recoveryHidden = false
    private var blackCovered by mutableStateOf(false)
    private var displayLease: ModuleDisplayLease? = null
    private var startupHoldToken: ModuleStartupHoldRegistry.Token? = null
    private var startupHoldActive = false
    private val startupHoldPolicy = ModuleStartupHoldPolicy()
    private var startupHoldExpiry: Runnable? = null
    private var moduleContentInstalled = false
    private var pendingLaunchIntentTimeout: Runnable? = null
    private var receiverRegistered = false
    private var displayListenerRegistered = false
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateVisibility()
        override fun onDisplayRemoved(displayId: Int) = updateVisibility()
        override fun onDisplayChanged(displayId: Int) { if (displayId == ownDisplay()?.displayId) updateVisibility() }
    }
    private val ticker = object : Runnable {
        override fun run() {
            val module = descriptor ?: return
            if (!canRefresh()) { if (blackCovered) updateKeepScreenOn() else clearKeepScreenOn(); return }
            if (!state.optBoolean("running")) { clearKeepScreenOn(); return }
            StageTimerController.pulse(this@ModuleRearActivity, module.id)
            state = StageTimerController.state(this@ModuleRearActivity, module.id)
            updateKeepScreenOn()
            if(state.optBoolean("running"))handler.postDelayed(this,1000)
        }
    }
    private val moduleChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val module = descriptor ?: return
            if (intent?.action == ACTION_STARTUP_HOLD_RELEASE) {
                val token = ModuleStartupHoldRegistry.read(intent)
                if (intent.getStringExtra("module_id") == module.id && token != null && token == startupHoldToken) {
                    Log.i(TAG,"rear startup-hold cancelled module=${module.id} nonce=${token.nonce}")
                    releaseStartupHold()
                }
                return
            }
            val changedId = intent?.let(ModuleRearReceiverRouting::moduleIdFor)
            if (changedId != module.id) return
            if(intent?.action==ModuleDisplayRecoveryService.ACTION_VISIBILITY){
                val lease=displayLease
                if(lease!=null&&RearModuleSession.isOwner(taskId,this@ModuleRearActivity)&&RecoveryLeaseIntents.matches(intent,lease)){
                    blackCovered=intent.getBooleanExtra(ModuleDisplayRecoveryService.EXTRA_BLACK_COVER,false)
                    recoveryHidden=!intent.getBooleanExtra(ModuleDisplayRecoveryService.EXTRA_FRONT,false) && !blackCovered
                    if(blackCovered){handler.removeCallbacks(ticker);updateKeepScreenOn()}
                    else if(recoveryHidden){handler.removeCallbacks(ticker);clearKeepScreenOn()}else updateVisibility()
                }
                return
            }
            if(intent?.action==ModuleDisplayRecoveryService.ACTION_LEASE_CHANGED){
                val currentState=StageTimerController.state(this@ModuleRearActivity,module.id)
                val incomingLease=RecoveryLeaseIntents.read(intent)?:return
                val lease=ModuleRearReceiverRouting.acceptedLeaseChanged(
                    intent, module.id, module.versionCode, currentState.optString("generation"), taskId,
                    ownDisplay()?.displayId?:-1, currentState.optBoolean("running"),
                    RearModuleSession.isOwner(taskId,this@ModuleRearActivity),
                    ModuleDisplayRecoveryService.isLeaseRequested(incomingLease)
                )?:return
                state=currentState
                displayLease=lease
                RearModuleSession.updateIdentity(taskId, this@ModuleRearActivity, module.id, module.versionCode, lease.timerGeneration)
                blackCovered=ModuleDisplayRecoveryService.isLeaseCovered(lease)
                recoveryHidden=!ModuleDisplayRecoveryService.isLeaseCurrent(lease)
                updateStartupHoldForSession(leaseRequested = true)
                updateVisibility()
                return
            }
            if(intent?.action==ModuleDisplayRecoveryService.ACTION_CLOSE_ACTIVITY){
                displayLease?.let{if(RearModuleSession.isOwner(taskId,this@ModuleRearActivity)&&RecoveryLeaseIntents.matches(intent,it))finish()}
                return
            }
            if(intent?.action==StageTimerController.ACTION_SESSION_CHANGED){
                val updated=StageTimerController.state(this@ModuleRearActivity,module.id)
                if(ModuleRearAnimationRestorePolicy.shouldFinishAfterSessionChange(
                        intent, module.id, displayLease?.timerGeneration,
                        updated.optString("generation"), updated.optString("status"))) {
                    Log.i(TAG, "rear-finish stale timer task task=$taskId leaseGeneration=${displayLease?.timerGeneration} stateGeneration=${updated.optString("generation")} status=${updated.optString("status")}")
                    finish()
                    return
                }
                refreshSession(updated)
                return
            }
            if (intent?.getStringExtra("operation") in setOf("disable", "remove", "update")) {
                val current = ModuleStore.get(this@ModuleRearActivity, module.id)
                if (current == null || !ModuleStore.isEnabled(this@ModuleRearActivity, module.id) ||
                    current.versionCode != module.versionCode) {
                    finish()
                }
            }
        }
    }

    private fun ownDisplay(): Display? = if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
    private fun canRefresh() = activityVisible && !recoveryHidden && !blackCovered && !isFinishing &&
        window.decorView.windowVisibility == android.view.View.VISIBLE &&
        !RearAnimationManager.isAnimationPlaying() &&
        ownDisplay()?.let { it.isValid && it.state == Display.STATE_ON } == true
    private fun updateVisibility() {
        handler.removeCallbacks(ticker)
        if (blackCovered) updateKeepScreenOn() else if (canRefresh()) refreshSession() else clearKeepScreenOn()
    }
    private fun refreshSession(){
        val module=descriptor?:return
        refreshSession(StageTimerController.state(this,module.id))
    }
    private fun refreshSession(updated:JSONObject){
        val module=descriptor?:return
        if (ModuleRearAnimationRestorePolicy.shouldFinishStaleTimerTask(
                displayLease?.timerGeneration, updated.optString("generation"), updated.optString("status"))) {
            Log.i(TAG, "rear-finish stale timer task task=$taskId leaseGeneration=${displayLease?.timerGeneration} stateGeneration=${updated.optString("generation")} status=${updated.optString("status")}")
            finish()
            return
        }
        if (blackCovered) {
            when (ModuleRearCoverSessionPolicy.onSessionUpdate(true, displayLease?.timerGeneration,
                updated.optString("generation"), updated.optBoolean("running"),
                displayLease?.let(ModuleDisplayRecoveryService::isLeaseCurrent) == true)) {
                ModuleRearCoverSessionPolicy.Update.FINISH_STALE_SESSION -> { finish(); return }
                ModuleRearCoverSessionPolicy.Update.SHOW_FINAL_STATE -> { blackCovered=false; recoveryHidden=false }
                ModuleRearCoverSessionPolicy.Update.KEEP_BLACK -> Unit
            }
        }
        state=updated
        handler.removeCallbacks(ticker)
        if (blackCovered) updateKeepScreenOn()
        else if(canRefresh()&&state.optBoolean("running"))handler.post(ticker)else clearKeepScreenOn()
        updateStartupHoldForSession(leaseRequested = displayLease?.let(ModuleDisplayRecoveryService::isLeaseRequested) == true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG,"rear-create hasModule=${intent.getStringExtra("module_id").orEmpty().isNotBlank()} hasDisplay=${intent.hasExtra("display_id")} hasToken=${ModuleStartupHoldRegistry.read(intent)!=null} restored=${savedInstanceState!=null} task=$taskId")
        if (ModuleRearLaunchIntentPolicy.onCreate(intent) == ModuleRearLaunchIntentPolicy.CreateDisposition.WAIT_FOR_NEW_INTENT) {
            awaitExplicitLaunchIntent()
            return
        }
        if(!loadModuleIntent(intent))return
        initializeLoadedModule(savedInstanceState)
    }

    private fun initializeLoadedModule(savedInstanceState: Bundle?) {
        restoreStartupHold(savedInstanceState)
        val id = descriptor!!.id
        state = StageTimerController.state(this, id)
        displayLease = savedLease(savedInstanceState) ?: ModuleDisplayRecoveryService.currentLeaseForSession(id, taskId)
        recoveryHidden = if (state.optBoolean("running") && StageTimerController.config(this,id).optBoolean("keepVisible",false))
            displayLease?.let { !ModuleDisplayRecoveryService.isLeaseCurrent(it) } ?: true else false
        blackCovered=displayLease?.let(ModuleDisplayRecoveryService::isLeaseCovered)==true
        RearModuleSession.attach(taskId, this, id, descriptor!!.versionCode,
            displayLease?.timerGeneration ?: state.optString("generation"))
        registerModuleReceiver()
        miuixContent {
            if (blackCovered) {
                Box(Modifier.fillMaxSize().background(Color.Black)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .clearAndSetSemantics {})
            } else {
            val current = descriptor ?: return@miuixContent
            val locale = resources.configuration.locales[0].toLanguageTag()
            LazyColumn(
                Modifier.fillMaxSize().background(AppPalette.background()).safeDrawingPadding(),
                contentPadding = PaddingValues(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item("module-rear-root") {
                    androidx.compose.foundation.layout.Column(
                        Modifier.widthIn(max = 680.dp).fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        NativeModuleNodes(current.document.optJSONArray("rearUi") ?: JSONArray(), current,
                            StageTimerController.config(this@ModuleRearActivity, current.id), state, true,
                            ::updateConfig,
                            ::performAction, ::updateValidity, configInvalid.isEmpty())
                        if(renderError.isNotEmpty())top.yukonga.miuix.kmp.basic.Text(renderError,color=AppPalette.accent())
                    }
                }
            }
            }
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
        moduleContentInstalled = true
        markStartupLaunchLoaded()
    }

    private fun awaitExplicitLaunchIntent() {
        Log.i(TAG,"rear-create awaiting-explicit-intent task=$taskId")
        val timeout = Runnable {
            pendingLaunchIntentTimeout = null
            if (ModuleRearLaunchIntentPolicy.shouldFinishPendingCreate(moduleContentInstalled) && descriptor == null && !isFinishing) {
                Log.i(TAG,"rear-create explicit-intent-timeout task=$taskId")
                finish()
            }
        }
        pendingLaunchIntentTimeout = timeout
        handler.postDelayed(timeout, PENDING_LAUNCH_INTENT_TIMEOUT_MS)
    }

    private fun markStartupLaunchLoaded() {
        val token = ModuleStartupHoldRegistry.read(intent) ?: return
        val module = descriptor ?: return
        val displayId = ownDisplay()?.displayId ?: -1
        val accepted = startupHoldPolicy.shouldReapply(token, SystemClock.elapsedRealtime()) &&
            ModuleStartupHoldRegistry.markModuleLoaded(token, module.id, displayId)
        Log.i(TAG,"rear-launch-ready module=${module.id} display=$displayId nonce=${token.nonce} accepted=$accepted")
    }

    private fun loadModuleIntent(launch:Intent):Boolean{
        val id=launch.getStringExtra("module_id").orEmpty()
        val expectedDisplay=launch.getIntExtra("display_id",-1)
        val loaded=ModuleStore.get(this,id)
        val currentRear=runCatching{RearDisplay.requireId()}.getOrDefault(-1)
        val enabled=loaded!=null&&ModuleStore.isEnabled(this,id)
        val actualDisplay=ownDisplay()?.displayId?:-1
        val valid=loaded!=null&&enabled&&expectedDisplay>0&&expectedDisplay==currentRear&&actualDisplay==expectedDisplay
        Log.i(TAG,"rear-load module=$id known=${loaded!=null} enabled=$enabled expectedDisplay=$expectedDisplay currentRear=$currentRear actualDisplay=$actualDisplay version=${loaded?.versionCode?:-1} task=$taskId accepted=$valid")
        if(!valid){ModuleStartupHoldRegistry.read(launch)?.let(ModuleStartupHoldRegistry::release);releaseStartupHold();finish();return false}
        descriptor=loaded
        return true
    }

    private fun restoreStartupHold(saved: Bundle?) {
        val restored = if (saved?.containsKey("startup_hold_nonce") == true) {
            ModuleStartupHoldRegistry.Token(saved.getLong("startup_hold_nonce"), saved.getLong("startup_hold_deadline"))
        } else ModuleStartupHoldRegistry.read(intent)
        startupHoldToken = restored
        startupHoldActive = startupHoldPolicy.shouldAcquire(
            restored, SystemClock.elapsedRealtime(), explicitGate = restored != null,
            moduleValid = descriptor != null,
            displayMatches = ownDisplay()?.displayId == intent.getIntExtra("display_id", -1)
        )
        if (startupHoldActive) {
            Log.i(TAG,"rear startup-hold active module=${descriptor?.id} nonce=${restored?.nonce} deadline=${restored?.deadlineElapsedMs}")
            applyStartupHold()
        } else if (restored != null) {
            Log.i(TAG,"rear startup-hold rejected module=${descriptor?.id} nonce=${restored.nonce} state=${ModuleStartupHoldRegistry.state(restored)}")
        }
    }

    private fun applyStartupHold() {
        val token = startupHoldToken ?: return
        if (!startupHoldPolicy.shouldReapply(token, SystemClock.elapsedRealtime())) {
            releaseStartupHold()
            return
        }
        startupHoldActive = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startupHoldExpiry?.let(handler::removeCallbacks)
        val callback = Runnable {
            if (startupHoldToken == token && ModuleStartupHoldRegistry.state(token) != ModuleStartupHoldRegistry.State.ACTIVE) {
                Log.i(TAG,"rear startup-hold expired module=${descriptor?.id} nonce=${token.nonce}")
                releaseStartupHold()
            }
        }
        startupHoldExpiry = callback
        handler.postDelayed(callback, (token.deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
    }

    private fun updateStartupHoldForSession(leaseRequested: Boolean) {
        val module = descriptor ?: return
        val token = startupHoldToken ?: return
        val running = StageTimerController.state(this, module.id).optBoolean("running")
        val keepVisible = StageTimerController.config(this, module.id).optBoolean("keepVisible", false)
        if (startupHoldPolicy.shouldRelease(token, SystemClock.elapsedRealtime(), running, keepVisible, leaseRequested)) {
            Log.i(TAG,"rear startup-hold handed-off module=${module.id} nonce=${token.nonce} keepVisible=$keepVisible lease=$leaseRequested")
            releaseStartupHold()
        }
    }

    private fun releaseStartupHold() {
        val token = startupHoldToken
        startupHoldExpiry?.let(handler::removeCallbacks)
        startupHoldExpiry = null
        if (token != null) ModuleStartupHoldRegistry.release(token)
        startupHoldActive = false
        updateKeepScreenOn()
    }

    private fun savedLease(saved: Bundle?): ModuleDisplayLease? {
        if (saved == null || !saved.containsKey("recovery_nonce")) return null
        val lease = ModuleDisplayLease(
            saved.getString("recovery_module").orEmpty(), saved.getInt("recovery_version", -1),
            saved.getString("recovery_generation").orEmpty(), saved.getInt("recovery_task", -1),
            saved.getInt("recovery_display", -1), saved.getLong("recovery_nonce", -1L))
        return lease.takeIf { ModuleDisplayRecoveryService.isLeaseRequested(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        displayLease?.let { lease ->
            outState.putString("recovery_module", lease.moduleId)
            outState.putInt("recovery_version", lease.versionCode)
            outState.putString("recovery_generation", lease.timerGeneration)
            outState.putInt("recovery_task", lease.taskId)
            outState.putInt("recovery_display", lease.displayId)
            outState.putLong("recovery_nonce", lease.nonce)
        }
        startupHoldToken?.let { token ->
            outState.putLong("startup_hold_nonce", token.nonce)
            outState.putLong("startup_hold_deadline", token.deadlineElapsedMs)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG,"rear-new-intent hasModule=${intent.getStringExtra("module_id").orEmpty().isNotBlank()} hasDisplay=${intent.hasExtra("display_id")} hasToken=${ModuleStartupHoldRegistry.read(intent)!=null} task=$taskId initialized=$moduleContentInstalled")
        pendingLaunchIntentTimeout?.let(handler::removeCallbacks)
        pendingLaunchIntentTimeout = null
        val previous=descriptor?.id
        if (!moduleContentInstalled) {
            if (ModuleRearLaunchIntentPolicy.onNewIntent(intent) == ModuleRearLaunchIntentPolicy.NewIntentDisposition.REJECT_MISSING_EXTRAS) {
                Log.i(TAG,"rear-new-intent rejected reason=missing-extras task=$taskId")
                finish()
                return
            }
            if (!loadModuleIntent(intent)) return
            initializeLoadedModule(null)
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) reconcileResumedState()
            return
        }
        if(!loadModuleIntent(intent))return
        restoreStartupHold(null)
        val module=descriptor?:return
        state=StageTimerController.state(this,module.id)
        if(previous!=module.id)recreate() else {
            markStartupLaunchLoaded()
            if(state.optBoolean("running")) {
            displayLease=ModuleDisplayRecoveryService.startForSession(this,module.id,taskId,forceNewDisplay=true)
            recoveryHidden=false
            blackCovered=false
            updateStartupHoldForSession(leaseRequested = displayLease?.let(ModuleDisplayRecoveryService::isLeaseRequested) == true)
            updateVisibility()
            }
        }
    }

    private fun performAction(action: String) {
        val module = descriptor ?: return
        if (!ModuleStore.isEnabled(this, module.id)) { finish(); return }
        renderError=""
        if(configInvalid.isNotEmpty()&&action in setOf("start","save")){renderError=getString(R.string.native_modules_fix_invalid_fields);return}
        val actionGeneration=state.optString("generation")
        val priorLease=displayLease
        runCatching {
            when (action) {
                "start" -> StageTimerController.start(this, module.id)
                "pause" -> StageTimerController.pause(this, module.id)
                "resume" -> StageTimerController.resume(this, module.id)
                "stop" -> StageTimerController.stop(this, module.id)
                "close" -> { releaseStartupHold(); finish() }
                "save" -> Unit
            }
            state = StageTimerController.state(this, module.id)
            if(action in setOf("start","resume")&&state.optBoolean("running")) {
                val keepVisible=StageTimerController.config(this,module.id).optBoolean("keepVisible",false)
                displayLease=ModuleDisplayRecoveryService.startForSession(this,module.id,taskId,forceNewDisplay=true)
                if(keepVisible&&displayLease==null)throw IllegalStateException(getString(R.string.native_modules_rear_unavailable))
                recoveryHidden=false
                blackCovered=false
                updateStartupHoldForSession(leaseRequested = displayLease?.let(ModuleDisplayRecoveryService::isLeaseRequested) == true)
            }
            if(action in setOf("pause","stop"))releaseStartupHold()
            if(action=="stop")priorLease?.let{ModuleDisplayRecoveryService.stopForModule(this,it,this)}
        }.onFailure{renderError=it.message?:getString(R.string.native_modules_action_failed)}
    }

    private fun updateConfig(next:JSONObject){val module=descriptor?:return;StageTimerController.saveConfig(this,module.id,next);val fields=mutableSetOf<String>();fun collect(nodes:JSONArray){for(i in 0 until nodes.length()){val node=nodes.getJSONObject(i);when(node.optString("type")){"number","textInput"->fields.add(node.optString("id"));"stageList"->{val id=node.optString("id");fields.add(id);repeat(next.optJSONArray("stages")?.length()?:0){fields.add("$id.name.$it");fields.add("$id.duration.$it")}}};node.optJSONArray("children")?.let(::collect)}};collect(module.document.optJSONArray("rearUi")?:JSONArray());configInvalid.retainAll(fields)}
    private fun updateValidity(id:String,valid:Boolean){val changed=if(valid)configInvalid.remove(id)else configInvalid.add(id);if(changed)state=JSONObject(state.toString())}

    private fun updateKeepScreenOn() {
        val module = descriptor ?: return
        val keep = startupHoldActive || (activityVisible && !isFinishing && !RearAnimationManager.isAnimationPlaying() &&
            state.optBoolean("running") && ModuleStore.isEnabled(this, module.id) &&
            StageTimerController.config(this, module.id).optBoolean("keepVisible", false) &&
            displayLease?.let { ModuleDisplayRecoveryService.isLeaseCurrent(it) } == true && (blackCovered || !recoveryHidden))
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else clearKeepScreenOn()
    }
    private fun clearKeepScreenOn() {
        if (!startupHoldActive) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    internal fun animationChanged() {
        releaseStartupHold()
        handler.removeCallbacks(ticker)
        if (RearAnimationManager.isAnimationPlaying()) clearKeepScreenOn() else updateVisibility()
    }
    override fun onStart() {
        super.onStart()
        displays.registerDisplayListener(displayListener, handler)
        displayListenerRegistered = true
    }
    private fun registerModuleReceiver(){
        if(receiverRegistered)return
        val filter=IntentFilter().apply{
            addAction(ACTION_STARTUP_HOLD_RELEASE)
            addAction(ModuleStore.ACTION_CHANGED)
            addAction(StageTimerController.ACTION_SESSION_CHANGED)
            addAction(ModuleDisplayRecoveryService.ACTION_VISIBILITY)
            addAction(ModuleDisplayRecoveryService.ACTION_LEASE_CHANGED)
            addAction(ModuleDisplayRecoveryService.ACTION_CLOSE_ACTIVITY)
        }
        ContextCompat.registerReceiver(this, moduleChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }
    override fun onResume() {
        super.onResume()
        reconcileResumedState()
    }

    private fun reconcileResumedState() {
        if (!moduleContentInstalled) return
        val module = descriptor ?: return
        val current = ModuleStore.get(this, module.id)
        if (current == null || !ModuleStore.isEnabled(this, module.id) ||
            current.versionCode != module.versionCode || !RearModuleSession.isOwner(taskId,this)) {
            finish()
            return
        }
        activityVisible = true
        RearModuleSession.setVisible(taskId,this,true)
        val resumedState = StageTimerController.state(this,module.id)
        if (ModuleRearAnimationRestorePolicy.shouldFinishStaleTimerTask(
                displayLease?.timerGeneration, resumedState.optString("generation"), resumedState.optString("status"))) {
            Log.i(TAG, "rear-finish stale timer task on-resume task=$taskId leaseGeneration=${displayLease?.timerGeneration} stateGeneration=${resumedState.optString("generation")} status=${resumedState.optString("status")}")
            finish()
            return
        }
        state = resumedState
        startupHoldToken?.let { token ->
            if (startupHoldPolicy.shouldReapply(token, SystemClock.elapsedRealtime())) applyStartupHold()
            else releaseStartupHold()
        }
        recoveryHidden=if(state.optBoolean("running")&&StageTimerController.config(this,module.id).optBoolean("keepVisible",false))
            displayLease?.let{!ModuleDisplayRecoveryService.isLeaseCurrent(it)} ?: true else false
        blackCovered=displayLease?.let(ModuleDisplayRecoveryService::isLeaseCovered)==true
        updateVisibility()
        displayLease?.let{if(ModuleDisplayRecoveryService.isLeaseCurrent(it))ModuleDisplayRecoveryService.notifyRearFront(this,it,true,this)}
    }
    override fun onPause() {
        activityVisible = false
        RearModuleSession.setVisible(taskId,this,false)
        handler.removeCallbacks(ticker)
        clearKeepScreenOn()
        super.onPause()
    }
    override fun onUserLeaveHint() {
        val startupToken = startupHoldToken
        val startupStillValid = startupHoldActive && startupToken != null &&
            startupHoldPolicy.shouldReapply(startupToken, SystemClock.elapsedRealtime())
        // Some rear-display launchers report a transient sleep pause as userLeaving. Keep only
        // this bounded bootstrap lease; its absolute deadline remains the hard upper bound.
        if (!startupStillValid) releaseStartupHold()
        val module=descriptor
        if(module!=null&&ownDisplay()?.state==Display.STATE_ON&&!RearAnimationManager.isAnimationPlaying())
            displayLease?.let{
                Log.i(POWER_DIAGNOSTIC_TAG, "event=actor_leave elapsed=${SystemClock.elapsedRealtime()} mainOn=${getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON} rearState=${ownDisplay()?.state} keyguard=${getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true} nonce=${it.nonce} covered=${ModuleDisplayRecoveryService.isLeaseCovered(it)}")
                ModuleDisplayRecoveryService.notifyRearFront(this,it,false,this)
            }
        super.onUserLeaveHint()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); updateVisibility() }
    override fun onStop() {
        if (displayListenerRegistered) { displays.unregisterDisplayListener(displayListener); displayListenerRegistered = false }
        super.onStop()
    }
    override fun onDestroy() {
        displayLease?.let { Log.i(POWER_DIAGNOSTIC_TAG, "event=actor_destroy elapsed=${SystemClock.elapsedRealtime()} finishing=$isFinishing changingConfig=$isChangingConfigurations nonce=${it.nonce}") }
        activityVisible = false
        handler.removeCallbacksAndMessages(null)
        if (!isChangingConfigurations) releaseStartupHold()
        clearKeepScreenOn()
        if(receiverRegistered){unregisterReceiver(moduleChanges);receiverRegistered=false}
        if(isFinishing)displayLease?.let{ModuleDisplayRecoveryService.stopForModule(this,it,this)}
        RearModuleSession.detach(taskId,this)
        super.onDestroy()
    }

    companion object {
        private const val POWER_DIAGNOSTIC_TAG = "ModulePowerDiag"
        internal const val ACTION_STARTUP_HOLD_RELEASE = "top.zainzh.rearscreen.action.STARTUP_HOLD_RELEASE"
        private const val PENDING_LAUNCH_INTENT_TIMEOUT_MS = 1_500L
        private const val TAG = "ModuleRear"
    }
}

/** Kept separate from RearInfoSession so their task ownership remains independent. */
internal object RearModuleSession {
    @Volatile private var taskId = -1
    @Volatile private var activity: ModuleRearActivity? = null
    @Volatile private var visible = false
    @Volatile private var applicationContext: Context? = null
    private val identityHistory = ModuleRearAnimationTaskHistory()
    fun attach(id: Int, owner: ModuleRearActivity, moduleId: String, versionCode: Int, generation: String) {
        taskId = id
        identityHistory.attach(ModuleRearAnimationTaskIdentity(id, moduleId, versionCode, generation))
        applicationContext = owner.applicationContext
        activity = owner
        visible = false
    }
    fun taskId() = taskId
    fun isVisible(id: Int) = taskId == id && visible
    fun isOwner(id: Int, owner: android.app.Activity) = taskId == id && activity === owner
    fun setVisible(id: Int, owner: ModuleRearActivity, value: Boolean) { if(taskId==id&&activity===owner)visible=value }
    fun isAnimationTask(id: Int) = id > 0 && identityFor(id) != null
    fun identityFor(id: Int): ModuleRearAnimationTaskIdentity? = identityHistory.identityFor(id)
    fun updateIdentity(id: Int, owner: ModuleRearActivity, moduleId: String, versionCode: Int, generation: String) {
        if (taskId != id || activity !== owner) return
        val updated = ModuleRearAnimationRestorePolicy.acceptedLeaseIdentity(
            identityHistory.identityFor(id), id, moduleId, versionCode, generation) ?: return
        identityHistory.update(updated)
    }
    fun captureAnimationTask(animationId: Long, id: Int) = identityHistory.capture(animationId, id)
    fun animationCapture(animationId: Long): ModuleRearAnimationTaskCapture? = identityHistory.captureFor(animationId)
    fun appContext(): Context? = applicationContext
    fun consumeAnimationTask(id: Int) { /* Retain identity so duplicate or delayed callbacks cannot fall through to legacy restore. */ }
    fun detach(id: Int, owner: ModuleRearActivity) {
        if (taskId == id && activity === owner) {
            taskId = -1
            visible = false
            activity = null
        }
    }
    fun owns(foreground: String, packageName: String) = taskId > 0 && "$packageName:$taskId" == foreground
    fun animationChanged() {
        activity?.let { owner ->
            ModuleDisplayRecoveryService.notifyAnimationChanged(owner)
            owner.runOnUiThread { owner.animationChanged() }
        }
    }
}
