package top.zainzh.rearscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import top.zainzh.rearscreen.modules.ModuleStore
import top.zainzh.rearscreen.timer.StageTimerController
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Finite, user-visible sensor and display recovery lease for one running module session. */
class ModuleDisplayRecoveryService : Service(), SensorEventListener {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var moduleId = ""
    private var moduleVersion = 0
    private var timerGeneration = ""
    private var taskId = -1
    private var displayId = -1
    @Volatile private var sessionLease: ModuleDisplayLease? = null
    private var recoveryState: ModuleDisplayRecoveryState? = null
    @Volatile private var requestNonce = 0L
    private var sensorManager: SensorManager? = null
    private var rearSensor: Sensor? = null
    private var taskService: ITaskService? = null
    private var bound = false
    private var receiverRegistered = false
    private var proximityEnabled = true
    private var preferenceListenerRegistered = false
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PROXIMITY_PREF_KEY) main.post {
            proximityEnabled = readProximityPreference()
            updateSensorRegistration()
        }
    }
    private val configPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "config.$moduleId" && !validateSession()) main.post { stopLease() }
    }
    private var configPreferenceListenerRegistered = false
    private var displayListenerRegistered = false
    private var lastLoggedRearDisplayState = -1
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            val expected = sessionLease ?: return
            if (id == expected.displayId) {
                val state = displayManager.getDisplay(id)?.state ?: -1
                if (state != lastLoggedRearDisplayState) {
                    lastLoggedRearDisplayState = state
                    logPower("rear_display_state=$state")
                }
            }
            if (id == expected.displayId && displayManager.getDisplay(id)?.state == Display.STATE_OFF &&
                !covered && sleepConfirmedToken == -1L && lockedRecoveryToken == 0L &&
                ((mainDisplayIsOn() && !isKeyguardLocked()) || lockRecoveryCompleted)) {
                // A rear-only timeout/manual-off with no near or lock recovery cause revokes this lease.
                recoveryState?.manualRearOff()
                stopLease(expected, "rear_display_off")
            }
        }
    }
    @Volatile private var near: Boolean? = null
    @Volatile private var nearToken = 0L
    @Volatile private var presentationSequence = 0L
    @Volatile private var proximityObservation = 0L
    @Volatile private var observedNear: Boolean? = null
    @Volatile private var covered = false
    @Volatile private var sleepConfirmedToken = -1L
    private var pendingProximity: Runnable? = null
    private var ownTaskWasVisible = false
    private var lockedRecoveryToken = 0L
    private var lockRecoveryCompleted = false
    private var lockRecoveryWakeLock: PowerManager.WakeLock? = null
    @Volatile private var farActionWakeLock: PowerManager.WakeLock? = null
    private var userStoppedGeneration = ""
    private var destroyed = false

    private var taskConnection: ServiceConnection? = null

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                StageTimerController.ACTION_SESSION_CHANGED -> {
                    if (intent.getStringExtra("module_id") == moduleId) {
                        if (!validateSession()) stopLease()
                    }
                }
                ModuleStore.ACTION_CHANGED -> if (intent.getStringExtra("id") == moduleId) {
                    val current = ModuleStore.get(this@ModuleDisplayRecoveryService, moduleId)
                    if (current == null || !ModuleStore.isEnabled(this@ModuleDisplayRecoveryService, moduleId) ||
                        current.versionCode != moduleVersion) stopLease()
                }
                Intent.ACTION_SCREEN_OFF -> { logPower("screen_off_received"); onMainScreenOff() }
                Intent.ACTION_SCREEN_ON -> {
                    logPower("screen_on_received")
                    main.removeCallbacksAndMessages(SCREEN_OFF_TOKEN)
                    val pending = lockedRecoveryToken
                    if (pending != 0L) recoveryState?.consumeLockRecovery(pending)
                    lockedRecoveryToken = 0L
                    releaseLockRecoveryWakeLock()
                }
                ACTION_VISIBILITY -> {
                    if (matchesLeaseAction(intent)) {
                        val front = intent.getBooleanExtra(EXTRA_FRONT, false)
                        if (front) ownTaskWasVisible = true
                        else sessionLease?.let { expected -> worker.execute {
                            if (sessionLease !== expected || !validateSession(expected) || covered ||
                                isKeyguardLocked() || !mainDisplayIsOn() || RearAnimationManager.isAnimationPlaying()) return@execute
                            val foreground = runCatching { taskService?.getForegroundAppOnDisplay(expected.displayId) }.getOrNull() ?: return@execute
                            if (foreground != "$packageName:${expected.taskId}") main.post {
                                if (sessionLease === expected && !covered && !isKeyguardLocked() && mainDisplayIsOn()) {
                                    ownTaskWasVisible = false
                                    val pending = lockedRecoveryToken
                                    if (pending != 0L) recoveryState?.consumeLockRecovery(pending)
                                    lockedRecoveryToken = 0L
                                    stopLease(expected)
                                }
                            }
                        } }
                    }
                }
                ACTION_ACTIVITY_FRONT -> {
                    if (matchesLeaseAction(intent)) {
                        logPower("activity_front_received front=${intent.getBooleanExtra(EXTRA_FRONT, false)}")
                        if (intent.getBooleanExtra(EXTRA_FRONT, false)) ownTaskWasVisible = true
                        else {
                            val expected = sessionLease
                            val pendingLockToken = lockedRecoveryToken
                            val preserveLockRecovery = expected != null && recoveryState?.shouldRetainPendingLockExit(
                                expected, pendingLockToken, mainDisplayIsOn(), isKeyguardLocked()) == true
                            if (preserveLockRecovery) {
                                logPower("activity_front_false_preserved_for_lock token=$pendingLockToken", expected)
                            } else {
                                ownTaskWasVisible = false
                                lockedRecoveryToken = 0L
                                stopLease(expected, "activity_front_false")
                            }
                        }
                    }
                }
                ACTION_STOP -> if (matchesLeaseAction(intent)) {
                    userStoppedGeneration = timerGeneration
                    stopTimerAndExit()
                }
                ACTION_CANCEL -> if (matchesLeaseAction(intent)) stopLease()
                ACTION_ANIMATION_RESTORED -> {
                    if (matchesLeaseAction(intent)) {
                        val animationId = intent.getLongExtra(EXTRA_ANIMATION_ID, -1L)
                        sessionLease?.let { expected ->
                            if (animationId == lastAnimationId && validateSession(expected))
                                recoverExistingTask(expected, null, false, fromAnimation = true, blackCoverRecovery = covered)
                        }
                    }
                }
                ACTION_ANIMATION_CHANGED -> lastAnimationId = RearAnimationManager.getGeneration()
                ACTION_SENSOR_PREF_CHANGED -> {
                    proximityEnabled = readProximityPreference()
                    updateSensorRegistration()
                }
                ACTION_MANUAL_REAR_OFF -> {
                    logPower("manual_rear_off_received")
                    if (!covered && sleepConfirmedToken == -1L && lockedRecoveryToken == 0L && !rearDisplayIsOn() &&
                    ((mainDisplayIsOn() && !isKeyguardLocked()) || lockRecoveryCompleted)) {
                    recoveryState?.manualRearOff()
                    stopLease(reason = "manual_rear_off")
                    }
                }
            }
        }
    }

    private var lastAnimationId = -1L

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (matchesLeaseAction(intent)) stopTimerAndExit()
            else if (sessionLease == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL) {
            if (matchesLeaseAction(intent)) stopLease()
            else if (sessionLease == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val requestedId = intent?.getStringExtra(EXTRA_MODULE_ID).orEmpty()
        val requestedGeneration = intent?.getStringExtra(EXTRA_GENERATION).orEmpty()
        val requestedVersion = intent?.getIntExtra(EXTRA_VERSION, 0) ?: 0
        val requestedTask = intent?.getIntExtra(EXTRA_TASK_ID, -1) ?: -1
        val requestedNonce = intent?.getLongExtra(EXTRA_NONCE, -1L) ?: -1L
        if (requestedId.isBlank() || requestedGeneration.isBlank() || requestedTask <= 0 || requestedNonce <= 0L) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val requestedDisplay = runCatching { RearDisplay.requireId() }.getOrDefault(-1)
        val requestedLease = leaseKey(requestedId, requestedVersion, requestedGeneration, requestedTask, requestedDisplay, requestedNonce)
        // A late START from an older request must never replace the currently requested display lease.
        if (activeLease != requestedLease || activeNonce != requestedNonce || activeTaskId != requestedTask) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val existing = sessionLease
        if (existing != null && existing.moduleId == requestedId && existing.versionCode == requestedVersion &&
            existing.timerGeneration == requestedGeneration && existing.taskId == requestedTask &&
            existing.displayId == requestedDisplay && existing.nonce == requestedNonce) {
            if (validateSession(existing)) foregroundNotification() else stopLease(existing)
            return START_NOT_STICKY
        }
        if (existing != null) {
            releaseLeaseResources(existing)
        }
        destroyed = false
        requestNonce = requestedNonce
        nearToken++
        near = null
        covered = false
        sleepConfirmedToken = -1L
        ownTaskWasVisible = false
        lockedRecoveryToken = 0L
        lockRecoveryCompleted = false
        userStoppedGeneration = ""
        moduleId = requestedId
        timerGeneration = requestedGeneration
        moduleVersion = requestedVersion
        taskId = requestedTask
        displayId = requestedDisplay
        val identity = ModuleDisplayLease(moduleId, moduleVersion, timerGeneration, taskId, displayId, requestNonce)
        sessionLease = identity
        recoveryState = ModuleDisplayRecoveryState(identity)
        ownTaskWasVisible = RearModuleSession.isVisible(identity.taskId)
        if (!foregroundNotification()) {
            stopLease(identity)
            return START_NOT_STICKY
        }
        if (!validateSession()) {
            stopLease()
            return START_NOT_STICKY
        }
        val lease = leaseKey(moduleId, moduleVersion, timerGeneration, taskId, displayId, requestNonce)
        activeLease = lease
        activeTaskId = taskId
        activeModuleId = moduleId
        activeGeneration = timerGeneration
        RearScreenKeeperService.suspendForModuleLease(lease)
        startMonitoring()
        lastAnimationId = RearAnimationManager.getGeneration()
        bindTaskService(identity)
        bound = true
        return START_NOT_STICKY
    }

    private fun bindTaskService(identity: ModuleDisplayLease) {
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (sessionLease !== identity) { TaskServiceManager.release(connection); return }
                taskService = ITaskService.Stub.asInterface(binder)
                worker.execute {
                    val owns = currentForegroundIsOwn(identity)
                    main.post {
                        if (sessionLease !== identity) return@post
                        if (!validateSession(identity)) stopLease(identity)
                        else {
                            if (owns) ownTaskWasVisible = true
                            refreshNotification()
                        }
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (sessionLease === identity) { taskService = null; stopLease(identity) }
            }
        }
        taskConnection = connection
        TaskServiceManager.acquire(this, connection)
    }

    private fun startMonitoring() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(StageTimerController.ACTION_SESSION_CHANGED)
                addAction(ModuleStore.ACTION_CHANGED)
                addAction(ACTION_VISIBILITY)
                addAction(ACTION_ACTIVITY_FRONT)
                addAction(ACTION_STOP)
                addAction(ACTION_CANCEL)
                addAction(ACTION_ANIMATION_CHANGED)
                addAction(ACTION_ANIMATION_RESTORED)
                addAction(ACTION_SENSOR_PREF_CHANGED)
                addAction(ACTION_MANUAL_REAR_OFF)
            }
            ContextCompat.registerReceiver(this, events, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        if (!preferenceListenerRegistered) {
            getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferenceListener)
            preferenceListenerRegistered = true
        }
        if (!configPreferenceListenerRegistered) {
            getSharedPreferences("native_stage_timer", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(configPreferenceListener)
            configPreferenceListenerRegistered = true
        }
        if (!displayListenerRegistered) {
            displayManager.registerDisplayListener(displayListener, main)
            displayListenerRegistered = true
        }
        proximityEnabled = readProximityPreference()
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        rearSensor = sensorManager?.getSensorList(Sensor.TYPE_ALL)?.firstOrNull { sensor ->
            sensor.name.contains("Proximity", true) && sensor.name.contains("Back", true) &&
                sensor.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE && sensor.isWakeUpSensor
        } ?: sensorManager?.getSensorList(Sensor.TYPE_ALL)?.firstOrNull { sensor ->
            sensor.name.contains("Proximity", true) && sensor.name.contains("Back", true)
        }
        updateSensorRegistration()
    }

    private fun updateSensorRegistration() {
        val manager = sensorManager ?: return
        val expected = sessionLease
        val shouldResolveCover = !proximityEnabled && covered
        manager.unregisterListener(this)
        near = null
        pendingProximity?.let(main::removeCallbacks)
        pendingProximity = null
        if (shouldResolveCover && expected != null) {
            worker.execute { recoveryActions(expected)?.stableFar() }
        }
        if (!proximityEnabled) releaseFarActionWakeLock()
        if (proximityEnabled && rearSensor != null && validateSession()) {
            manager.registerListener(this, rearSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor != rearSensor || !proximityEnabled || event.values.isEmpty()) return
        val sampleNear = event.values[0] < event.sensor.maximumRange
        val observedAt = SystemClock.elapsedRealtime()
        observedNear = sampleNear
        if (sampleNear) releaseFarActionWakeLock() else if (covered) acquireFarActionWakeLock()
        val observation = ++proximityObservation
        pendingProximity?.let(main::removeCallbacks)
        val callback = Runnable {
            if (SystemClock.elapsedRealtime() - observedAt < DEBOUNCE_MS) return@Runnable
            if (observation != proximityObservation || observedNear != sampleNear || !validateSession()) {
                if (!sampleNear) releaseFarActionWakeLock()
                return@Runnable
            }
            if (near == sampleNear) {
                if (!sampleNear) releaseFarActionWakeLock()
                return@Runnable
            }
            near = sampleNear
            logRearProximityEdge(sampleNear)
            if (sampleNear) handleNear(observation) else handleFar()
        }
        pendingProximity = callback
        main.postDelayed(callback, DEBOUNCE_MS)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleNear(observation: Long) {
        val expected = sessionLease ?: return
        worker.execute {
            if (observation != proximityObservation || observedNear != true || !validateSession(expected)) return@execute
            val actions = recoveryActions(expected, observation) ?: return@execute
            actions.stableNear()
        }
    }

    private fun handleFar() {
        val expected = sessionLease
        if (expected == null) { releaseFarActionWakeLock(); return }
        worker.execute {
            try {
                val action = recoveryActions(expected)?.stableFar() ?: ModuleDisplayRecoveryState.FarAction.IGNORE
                val token = lockedRecoveryToken
                if (action == ModuleDisplayRecoveryState.FarAction.RESTORE_VISIBLE_UI &&
                    !currentForegroundIsOwn(expected) && recoveryState?.lockRecoveryValid(token) == true &&
                    (!mainDisplayIsOn() || isKeyguardLocked())) {
                    recoverExistingTask(expected, null, false)
                } else if (action == ModuleDisplayRecoveryState.FarAction.RESTORE_VISIBLE_UI &&
                    currentForegroundIsOwn(expected) && rearDisplayIsOn() && recoveryState?.lockRecoveryValid(token) == true) {
                    recoveryState?.consumeLockRecovery(token)
                    if (lockedRecoveryToken == token) lockedRecoveryToken = 0L
                }
            }
            finally { releaseFarActionWakeLock() }
        }
    }

    private fun recoveryActions(expected: ModuleDisplayLease, nearObservation: Long? = null): ModuleDisplayRecoveryActions? {
        val state = recoveryState ?: return null
        if (sessionLease !== expected) return null
        return ModuleDisplayRecoveryActions(state, object : ModuleDisplayRecoveryActions.Port {
            override fun ownerEligible(cover: ModuleDisplayRecoveryState.Cover?): Boolean =
                sessionLease === expected && validateSession(expected) && validateOwner(expected, allowCovered = cover != null)

            override fun coverRear(cover: ModuleDisplayRecoveryState.Cover): Boolean {
                if (sessionLease !== expected || nearObservation == null ||
                    nearObservation != proximityObservation || observedNear != true ||
                    !validateOwner(expected, allowCovered = true) || !currentForegroundIsOwn(expected)) return false
                nearToken = cover.serial
                covered = true
                presentationSequence++
                lockedRecoveryToken = 0L
                sleepConfirmedToken = -1L
                announceBlackCover(expected, true)
                return true
            }

            override fun onUncovered(cover: ModuleDisplayRecoveryState.Cover) {
                if (sessionLease === expected && nearToken == cover.serial) {
                    covered = false
                    presentationSequence++
                }
            }

            override fun rearDisplayIsOn() = this@ModuleDisplayRecoveryService.rearDisplayIsOn()
            override fun ownerEligibleForVisibleRestore(cover: ModuleDisplayRecoveryState.Cover): Boolean =
                sessionLease === expected && recoveryState?.isCurrent(cover) == false &&
                    validateOwnerForRecovery(expected) && currentForegroundIsOwn(expected)
            override fun wakeRearOnce(): Boolean {
                val remote = taskService ?: return false
                var woke = false
                RearAnimationManager.runDisplayAction {
                    if (sessionLease === expected && validateOwnerForRecovery(expected))
                        woke = remote.executeShellCommand("input -d ${expected.displayId} keyevent KEYCODE_WAKEUP")
                }
                return woke
            }
            override fun wakeRearOnce(cover: ModuleDisplayRecoveryState.Cover): Boolean {
                val remote = taskService ?: return false
                var woke = false
                RearAnimationManager.runDisplayAction {
                    if (sessionLease === expected && recoveryState?.isCurrent(cover) == true &&
                        nearToken == cover.serial && sleepConfirmedToken == cover.serial &&
                        validateOwnerForRecovery(expected)) {
                        woke = remote.executeShellCommand("input -d ${expected.displayId} keyevent KEYCODE_WAKEUP")
                    }
                }
                return woke
            }
            override fun focusExistingTask(): Boolean {
                val remote = taskService ?: return false
                var focused = false
                RearAnimationManager.runDisplayAction {
                    if (sessionLease !== expected || !validateOwnerForRecovery(expected)) return@runDisplayAction
                    if (!remote.executeShellCommand("am task focus ${expected.taskId}") ||
                        sessionLease !== expected || !validateOwnerForRecovery(expected)) return@runDisplayAction
                    focused = remote.getForegroundAppOnDisplay(expected.displayId) == "$packageName:${expected.taskId}"
                }
                return focused
            }
            override fun ownerEligibleForAnimationRestore(coveredBlack: Boolean): Boolean =
                sessionLease === expected && recoveryState?.isActive() == true && recoveryState?.isCovered() == coveredBlack &&
                    validateOwner(expected, allowCovered = coveredBlack)
            override fun wakeRearOnce(coveredBlack: Boolean): Boolean {
                val remote = taskService ?: return false
                var woke = false
                RearAnimationManager.runDisplayAction {
                    if (sessionLease === expected && recoveryState?.isCovered() == coveredBlack &&
                        validateOwner(expected, allowCovered = coveredBlack)) {
                        woke = remote.executeShellCommand("input -d ${expected.displayId} keyevent KEYCODE_WAKEUP")
                    }
                }
                return woke
            }
            override fun focusExistingTask(coveredBlack: Boolean): Boolean {
                val remote = taskService ?: return false
                var focused = false
                RearAnimationManager.runDisplayAction {
                    if (sessionLease === expected && recoveryState?.isCovered() == coveredBlack &&
                        validateOwner(expected, allowCovered = coveredBlack) && remote.executeShellCommand("am task focus ${expected.taskId}") &&
                        sessionLease === expected && recoveryState?.isCovered() == coveredBlack && validateSession(expected)) {
                        focused = remote.getForegroundAppOnDisplay(expected.displayId) == "$packageName:${expected.taskId}"
                    }
                }
                return focused
            }
            override fun showAndRefresh() {
                showAndRefresh(false)
            }
            override fun showAndRefresh(coveredBlack: Boolean) {
                val expectedState = recoveryState ?: return
                val expectedPresentation = presentationSequence
                val expectedAnimation = RearAnimationManager.getGeneration()
                worker.execute {
                    if (sessionLease !== expected || recoveryState !== expectedState || covered != coveredBlack ||
                        expectedState.isCovered() != coveredBlack || presentationSequence != expectedPresentation ||
                        !validateOwner(expected, allowCovered = coveredBlack) || !currentForegroundIsOwn(expected)) return@execute
                    main.post {
                        if (sessionLease === expected && recoveryState === expectedState && validateSession(expected) &&
                            covered == coveredBlack && expectedState.isCovered() == coveredBlack &&
                            presentationSequence == expectedPresentation && RearModuleSession.isVisible(expected.taskId) &&
                            expectedAnimation == RearAnimationManager.getGeneration() && !RearAnimationManager.isAnimationPlaying()) {
                            ownTaskWasVisible = true
                            sleepConfirmedToken = -1L
                            near = false
                            announceBlackCover(expected, coveredBlack)
                        }
                    }
                }
            }
        })
    }

    private fun mainDisplayMatches(expectedOn: Boolean) = mainDisplayIsOn() == expectedOn

    private fun onMainScreenOff() {
        val expected = sessionLease ?: return
                val sessionValid = validateSession(expected)
                if (!sessionValid || (near == true && !covered)) { logPower("screen_off_rejected valid=$sessionValid near=$near covered=$covered"); return }
        val token = recoveryState?.screenOff(ownTaskWasVisible, covered) ?: return
        lockedRecoveryToken = token
        logPower("screen_off_token_created token=$token")
        acquireLockRecoveryWakeLock()
        main.removeCallbacksAndMessages(SCREEN_OFF_TOKEN)
        main.postAtTime({
            logPower("screen_off_delay_elapsed token=$token")
            if (token == lockedRecoveryToken && validateSession(expected) && ownTaskWasVisible &&
                ((near != true && !covered) || covered)) {
                recoverExistingTask(expected, null, false, blackCoverRecovery = covered)
            } else logPower("screen_off_delay_rejected token=$token tokenCurrent=${token == lockedRecoveryToken} valid=${validateSession(expected)} visible=$ownTaskWasVisible near=$near")
        }, SCREEN_OFF_TOKEN, SystemClock.uptimeMillis() + SCREEN_OFF_DELAY_MS)
    }

    private fun recoverExistingTask(expected: ModuleDisplayLease, cover: ModuleDisplayRecoveryState.Cover?, fromFarSensor: Boolean, fromAnimation: Boolean = false, blackCoverRecovery: Boolean = false) {
        val sequence = cover?.serial ?: nearToken
        val recoveryToken = lockedRecoveryToken
        logPower("recovery_queued token=$recoveryToken far=$fromFarSensor animation=$fromAnimation")
        worker.execute {
            val lock = shortActionLock()
            try {
                lock.acquire(ACTION_WAKELOCK_TIMEOUT_MS)
                RearAnimationManager.runDisplayAction {
                    logPower("recovery_attempt token=$recoveryToken far=$fromFarSensor animation=$fromAnimation")
                    if (!recoveryStillValid(expected, sequence, recoveryToken, fromFarSensor, fromAnimation, cover, blackCoverRecovery) || !recoveryOwnerValid(expected, blackCoverRecovery)) { logPower("recovery_aborted_stage=prewake token=$recoveryToken"); return@runDisplayAction }
                    val remote = taskService ?: return@runDisplayAction
                    if (!remote.executeShellCommand("input -d ${expected.displayId} keyevent KEYCODE_WAKEUP")) { logPower("recovery_aborted_stage=wake token=$recoveryToken"); return@runDisplayAction }
                    if (!recoveryStillValid(expected, sequence, recoveryToken, fromFarSensor, fromAnimation, cover, blackCoverRecovery) || !recoveryOwnerValid(expected, blackCoverRecovery)) { logPower("recovery_aborted_stage=postwake token=$recoveryToken"); return@runDisplayAction }
                    if (!remote.executeShellCommand("am task focus ${expected.taskId}")) { logPower("recovery_aborted_stage=focus token=$recoveryToken"); return@runDisplayAction }
                    if (!recoveryStillValid(expected, sequence, recoveryToken, fromFarSensor, fromAnimation, cover, blackCoverRecovery) || !validateSession(expected)) { logPower("recovery_aborted_stage=postfocus token=$recoveryToken"); return@runDisplayAction }
                    if (runCatching { remote.getForegroundAppOnDisplay(expected.displayId) }.getOrNull() != "$packageName:${expected.taskId}") { logPower("recovery_aborted_stage=foreground token=$recoveryToken"); return@runDisplayAction }
                    logPower("recovery_remote_success token=$recoveryToken")
                    main.post {
                        if (recoveryStillValid(expected, sequence, recoveryToken, fromFarSensor, fromAnimation, cover, blackCoverRecovery) && validateSession(expected)) {
                            if (blackCoverRecovery) announceBlackCover(expected, true) else announceVisibility(expected, true)
                            ownTaskWasVisible = true
                            if (fromFarSensor) { near = false; sleepConfirmedToken = -1L }
                            if (!fromFarSensor && !fromAnimation) {
                                recoveryState?.consumeLockRecovery(recoveryToken)
                                lockedRecoveryToken = 0L
                                lockRecoveryCompleted = true
                                logPower("lock_recovery_consumed token=$recoveryToken")
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // A stale or failed one-shot recovery is abandoned.
            } finally {
                if (lock.isHeld) lock.release()
                if (!fromFarSensor && !fromAnimation) main.post { releaseLockRecoveryWakeLock() }
            }
        }
    }

    private fun recoveryStillValid(expected: ModuleDisplayLease, sequence: Long, recoveryToken: Long,
                                   fromFarSensor: Boolean, fromAnimation: Boolean, cover: ModuleDisplayRecoveryState.Cover?,
                                   blackCoverRecovery: Boolean = false) =
        sessionLease === expected && validateSession(expected) &&
            (if (blackCoverRecovery) covered && recoveryState?.isCovered() == true &&
                (fromAnimation || recoveryState?.validBlackCoverLockRecovery(expected, recoveryToken) == true) else !covered) &&
            (fromFarSensor && cover != null && cover.serial == sequence && sleepConfirmedToken == sequence && recoveryState?.isActive() == true ||
                fromAnimation && !RearAnimationManager.isAnimationPlaying() && lastAnimationId == RearAnimationManager.getGeneration() ||
                !fromFarSensor && !fromAnimation && recoveryState?.lockRecoveryValid(recoveryToken) == true)

    private fun recoveryOwnerValid(expected: ModuleDisplayLease, blackCoverRecovery: Boolean) =
        validateOwner(expected, allowCovered = blackCoverRecovery)

    private fun shortActionLock(): PowerManager.WakeLock {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReMRSS:ModuleDisplayRecovery").apply { setReferenceCounted(false) }
    }

    private fun acquireLockRecoveryWakeLock() {
        releaseLockRecoveryWakeLock()
        val lock = shortActionLock()
        lock.acquire(ACTION_WAKELOCK_TIMEOUT_MS)
        lockRecoveryWakeLock = lock
    }

    private fun acquireFarActionWakeLock() {
        if (farActionWakeLock?.isHeld == true) return
        val lock = shortActionLock()
        try {
            lock.acquire(ACTION_WAKELOCK_TIMEOUT_MS)
            farActionWakeLock = lock
        } catch (_: Exception) { runCatching { if (lock.isHeld) lock.release() } }
    }

    private fun releaseFarActionWakeLock() {
        farActionWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        farActionWakeLock = null
    }

    private fun releaseLockRecoveryWakeLock() {
        lockRecoveryWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        lockRecoveryWakeLock = null
    }

    private fun validateSession(): Boolean {
        return validateSession(sessionLease)
    }

    private fun validateSession(expected: ModuleDisplayLease?): Boolean {
        if (expected == null || sessionLease !== expected || expected.nonce != requestNonce ||
            activeLease != leaseKey(expected.moduleId, expected.versionCode, expected.timerGeneration,
                expected.taskId, expected.displayId, expected.nonce) ||
            userStoppedGeneration == expected.timerGeneration || !ModuleStore.isEnabled(this, expected.moduleId)) return false
        val descriptor = ModuleStore.get(this, expected.moduleId) ?: return false
        if (descriptor.versionCode != expected.versionCode || expected.displayId <= 0 || expected.taskId <= 0) return false
        if (!StageTimerController.config(this, expected.moduleId).optBoolean("keepVisible", false)) return false
        return StageTimerController.recoverySession(this, expected.moduleId)?.optString("generation") == expected.timerGeneration
    }

    private fun matchesLeaseAction(intent: Intent): Boolean {
        val current = sessionLease ?: return false
        return RecoveryLeaseIntents.matches(intent, current)
    }

    private fun validateOwnerForRecovery(expected: ModuleDisplayLease): Boolean = validateOwner(expected, allowCovered = false)

    private fun validateOwnerForSleep(expected: ModuleDisplayLease, cover: ModuleDisplayRecoveryState.Cover): Boolean =
        sessionLease === expected && recoveryState?.isCurrent(cover) == true && validateOwner(expected, allowCovered = true)

    private fun validateOwner(expected: ModuleDisplayLease, allowCovered: Boolean): Boolean {
        if (!validateSession(expected) || (!allowCovered && covered) || RearAnimationManager.isAnimationPlaying()) return false
        if (RearScreenKeeperService.isPhysicalCastRunning()) return false
        val remote = taskService ?: return false
        return try {
            if (!remote.isTaskOnDisplay(expected.taskId, expected.displayId)) return false
            val virtual = JSONObject(remote.getRearVirtualCastStatus()).optString("state") == "running"
            if (virtual) return false
            val owner = remote.getForegroundAppOnDisplay(expected.displayId).orEmpty()
            owner == "$packageName:${expected.taskId}" || isOfficialRear(owner)
        } catch (_: Exception) { false }
    }

    private fun currentForegroundIsOwn(expected: ModuleDisplayLease): Boolean = try {
        val foreground = taskService?.getForegroundAppOnDisplay(expected.displayId).orEmpty()
        foreground == "$packageName:${expected.taskId}"
    } catch (_: Exception) { false }

    private fun isOfficialRear(owner: String) = owner.startsWith("com.xiaomi.subscreencenter:") || owner.startsWith("com.xiaomi.mirror:")
    private fun isKeyguardLocked() = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
    private fun mainDisplayIsOn() = getSystemService(android.hardware.display.DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
    private fun rearDisplayIsOn() = getSystemService(android.hardware.display.DisplayManager::class.java)
        ?.getDisplay(displayId)?.state == Display.STATE_ON

    private fun logPower(event: String, lease: ModuleDisplayLease? = sessionLease) {
        Log.i(POWER_DIAGNOSTIC_TAG, "event=$event elapsed=${SystemClock.elapsedRealtime()} nonce=${lease?.nonce ?: 0L} mainOn=${mainDisplayIsOn()} rearOn=${rearDisplayIsOn()} keyguard=${isKeyguardLocked()} covered=$covered ownVisible=$ownTaskWasVisible lockToken=$lockedRecoveryToken lockCompleted=$lockRecoveryCompleted")
    }

    private fun logRearProximityEdge(isNear: Boolean) {
        val lease = sessionLease
        Log.i(POWER_DIAGNOSTIC_TAG, "event=rear_proximity_edge elapsed=${SystemClock.elapsedRealtime()} task=${lease?.taskId ?: taskId} nonce=${lease?.nonce ?: 0L} near=$isNear covered=$covered")
    }

    private fun announceVisibility(expected: ModuleDisplayLease, front: Boolean) {
        sendBroadcast(leaseIntent(ACTION_VISIBILITY, expected).putExtra(EXTRA_FRONT, front))
    }

    private fun announceBlackCover(expected: ModuleDisplayLease, black: Boolean) {
        sendBroadcast(leaseIntent(ACTION_VISIBILITY, expected)
            .putExtra(EXTRA_FRONT, true).putExtra(EXTRA_BLACK_COVER, black))
    }

    private fun revokeRecovery() {
        ownTaskWasVisible = false
        lockedRecoveryToken = 0L
        nearToken++
        sessionLease?.let { announceVisibility(it, false) }
    }

    private fun stopTimerAndExit() {
        val expected = sessionLease ?: return
        val remote = taskService
        worker.execute {
            val lock = shortActionLock()
            try {
                lock.acquire(ACTION_WAKELOCK_TIMEOUT_MS)
                RearAnimationManager.runDisplayAction {
                    if (sessionLease === expected && !covered && !RearAnimationManager.isAnimationPlaying() &&
                        validateOwnerForRecovery(expected) && currentForegroundIsOwn(expected) && taskService === remote) {
                        runCatching { remote?.executeShellCommand(sleepRearOnceCommand(expected.displayId)) }
                    }
                }
            } catch (_: Exception) {
                // Stopping the timer remains authoritative; only the optional rear sleep is skipped.
            } finally { if (lock.isHeld) lock.release() }
            main.post {
                if (sessionLease !== expected) return@post
                val stopped = StageTimerController.stopIfGeneration(
                    this, expected.moduleId, expected.timerGeneration, expected.versionCode)
                if (stopped) sendBroadcast(leaseIntent(ACTION_CLOSE_ACTIVITY, expected))
                stopLease(expected)
            }
        }
    }

    private fun stopLease(expected: ModuleDisplayLease? = sessionLease, reason: String = "unspecified") {
        if (destroyed || expected == null || sessionLease !== expected) return
        logPower("stop_lease reason=$reason", expected)
        destroyed = true
        releaseLeaseResources(expected)
        val expectedKey = leaseKey(expected.moduleId, expected.versionCode, expected.timerGeneration,
            expected.taskId, expected.displayId, expected.nonce)
        if (activeLease == null || activeLease == expectedKey) stopSelf()
    }

    private fun releaseLeaseResources(expected: ModuleDisplayLease) {
        if (sessionLease !== expected) return
        sessionLease = null
        recoveryState?.invalidate()
        recoveryState = null
        pendingProximity?.let(main::removeCallbacks)
        main.removeCallbacksAndMessages(SCREEN_OFF_TOKEN)
        releaseLockRecoveryWakeLock()
        releaseFarActionWakeLock()
        sensorManager?.unregisterListener(this)
        if (receiverRegistered) runCatching { unregisterReceiver(events) }
        receiverRegistered = false
        if (bound) {
            taskConnection?.let { TaskServiceManager.release(it) }
            bound = false
        }
        taskConnection = null
        taskService = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (preferenceListenerRegistered) {
            getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferenceListener)
            preferenceListenerRegistered = false
        }
        if (configPreferenceListenerRegistered) {
            getSharedPreferences("native_stage_timer", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(configPreferenceListener)
            configPreferenceListenerRegistered = false
        }
        if (displayListenerRegistered) {
            displayManager.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        val lease = leaseKey(expected.moduleId, expected.versionCode, expected.timerGeneration,
            expected.taskId, expected.displayId, expected.nonce)
        if (activeLease == lease) {
            activeLease = null
            activeTaskId = -1
            activeModuleId = ""
            activeGeneration = ""
            activeVersion = 0
            activeDisplayId = -1
            activeNonce = 0L
        }
        RearScreenKeeperService.resumeAfterModuleLease(lease)
        sendBroadcast(revokedLeaseVisibilityIntent(expected))
    }

    private fun readProximityPreference() = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        .getBoolean("flutter.proximity_sensor_enabled", true)

    private fun foregroundNotification(): Boolean = runCatching {
        val open = PendingIntent.getActivity(this, 5021,
            Intent(this, ModuleSettingsActivity::class.java).putExtra("module_id", moduleId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, ModuleDisplayRecoveryService::class.java).setAction(ACTION_STOP)
            .putExtra(EXTRA_MODULE_ID, moduleId).putExtra(EXTRA_GENERATION, timerGeneration)
            .putExtra(EXTRA_VERSION, moduleVersion).putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_DISPLAY, displayId).putExtra(EXTRA_NONCE, requestNonce)
            .setData(android.net.Uri.parse("mrss://module-stop/${android.net.Uri.encode(moduleId)}/$moduleVersion/${android.net.Uri.encode(timerGeneration)}/$taskId/$displayId/$requestNonce"))
        val stop = PendingIntent.getService(this, leaseKey(moduleId, moduleVersion, timerGeneration, taskId, displayId, requestNonce).hashCode(),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.module_recovery_notification_title))
            .setContentText(getString(R.string.module_recovery_notification_text))
            .setContentIntent(open).setOngoing(true)
            .addAction(0, getString(R.string.module_recovery_stop), stop)
            .setCategory(Notification.CATEGORY_SERVICE).build()
        startForeground(NOTIFICATION_ID, notification)
        true
    }.getOrDefault(false)

    private fun refreshNotification() { foregroundNotification() }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.module_recovery_notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        logPower("service_destroy")
        if (!destroyed) stopLease()
        worker.shutdownNow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_VISIBILITY = "top.zainzh.rearscreen.modules.RECOVERY_VISIBILITY"
        const val ACTION_ACTIVITY_FRONT = "top.zainzh.rearscreen.modules.RECOVERY_ACTIVITY_FRONT"
        private const val POWER_DIAGNOSTIC_TAG = "ModulePowerDiag"
        const val ACTION_LEASE_CHANGED = "top.zainzh.rearscreen.modules.RECOVERY_LEASE_CHANGED"
        const val ACTION_STOP = "top.zainzh.rearscreen.modules.RECOVERY_STOP"
        const val ACTION_CANCEL = "top.zainzh.rearscreen.modules.RECOVERY_CANCEL"
        const val ACTION_CLOSE_ACTIVITY = "top.zainzh.rearscreen.modules.RECOVERY_CLOSE_ACTIVITY"
        const val ACTION_ANIMATION_RESTORED = "top.zainzh.rearscreen.modules.RECOVERY_ANIMATION_RESTORED"
        const val ACTION_ANIMATION_CHANGED = "top.zainzh.rearscreen.modules.RECOVERY_ANIMATION_CHANGED"
        const val ACTION_SENSOR_PREF_CHANGED = "top.zainzh.rearscreen.modules.RECOVERY_SENSOR_PREF_CHANGED"
        const val ACTION_MANUAL_REAR_OFF = "top.zainzh.rearscreen.modules.RECOVERY_MANUAL_REAR_OFF"
        const val SUB_SCREEN_OFF = "miui.intent.action.SUB_SCREEN_OFF"
        const val EXTRA_MODULE_ID = "module_id"
        const val EXTRA_GENERATION = "generation"
        const val EXTRA_FRONT = "front"
        const val EXTRA_BLACK_COVER = "black_cover"
        const val EXTRA_ANIMATION_ID = "animation_id"
        private const val EXTRA_VERSION = "module_version"
        private const val EXTRA_TASK_ID = "rear_task_id"
        private const val EXTRA_DISPLAY = "display_id"
        const val EXTRA_NONCE = "display_nonce"
        private const val PROXIMITY_PREF_KEY = "flutter.proximity_sensor_enabled"
        private const val CHANNEL = "module_display_recovery"
        private const val NOTIFICATION_ID = 4103
        private const val DEBOUNCE_MS = 700L
        private const val SCREEN_OFF_DELAY_MS = 350L
        private const val ACTION_WAKELOCK_TIMEOUT_MS = 2500L
        private val SCREEN_OFF_TOKEN = Any()
        @Volatile private var activeLease: String? = null
        @Volatile private var activeTaskId: Int = -1
        @Volatile private var activeModuleId: String = ""
        @Volatile private var activeGeneration: String = ""
        @Volatile private var activeVersion: Int = 0
        @Volatile private var activeDisplayId: Int = -1
        @Volatile private var activeNonce: Long = 0L
        private val nonceCounter = AtomicLong(SystemClock.elapsedRealtime().coerceAtLeast(1L))
        @Volatile private var instance: ModuleDisplayRecoveryService? = null

        private fun leaseKey(id: String, version: Int, generation: String, task: Int, display: Int, nonce: Long) =
            "$id:$version:$generation:$task:$display:$nonce"
        internal fun leaseIntent(action: String, lease: ModuleDisplayLease) =
            RecoveryLeaseIntents.create(action, lease, BuildConfig.APPLICATION_ID)
        internal fun revokedLeaseVisibilityIntent(lease: ModuleDisplayLease) =
            leaseIntent(ACTION_VISIBILITY, lease).putExtra(EXTRA_FRONT, false).putExtra(EXTRA_BLACK_COVER, false)
        @JvmStatic fun hasActiveLease() = activeLease != null
        @JvmStatic fun activeLeaseId(): String? = activeLease
        @JvmStatic fun isLeaseCurrent(lease: String) = activeLease == lease
        @androidx.annotation.MainThread
        @JvmStatic fun stopRecoveryForPriorityTakeover(context: Context) {
            // Keeper.onStartCommand must see the lease revoked before starting cast policy/FGS.
            val requested = activeLease
            val pending = if (requested != null) ModuleDisplayLease(activeModuleId, activeVersion,
                activeGeneration, activeTaskId, activeDisplayId, activeNonce) else null
            instance?.let { service -> service.sessionLease?.let { service.stopLease(it, "priority_takeover") } }
            // A request may exist before the recovery service has received onStartCommand.
            if (requested != null && activeLease == requested && pending != null) {
                activeLease = null
                activeTaskId = -1; activeModuleId = ""; activeGeneration = ""
                activeVersion = 0; activeDisplayId = -1; activeNonce = 0L
                RearScreenKeeperService.resumeAfterModuleLease(requested)
                context.sendBroadcast(revokedLeaseVisibilityIntent(pending))
            }
            context.stopService(Intent(context, ModuleDisplayRecoveryService::class.java))
        }
        @JvmStatic fun isActiveModuleTask(task: Int, animationId: Long) =
            task > 0 && activeLease != null && activeTaskId == task && RearAnimationManager.isIdle(animationId)
        @JvmStatic fun isModuleAnimationTask(taskId: Int) = taskId > 0 && (RearModuleSession.isAnimationTask(taskId) || activeTaskId == taskId)

        @JvmStatic fun animationRestoreDisposition(taskId: Int, animationId: Long): AnimationRestoreDisposition {
            val capture = RearModuleSession.animationCapture(animationId)
            if (capture != null && capture.taskId != taskId) return AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK
            val identity = if (capture != null) capture.identity else {
                RearModuleSession.identityFor(taskId)
                    ?: if (activeTaskId == taskId && activeLease != null) ModuleRearAnimationTaskIdentity(
                        taskId, activeModuleId, activeVersion, activeGeneration) else null
            }
            val isModuleTask = identity != null || (capture == null && taskId > 0 && activeTaskId == taskId)
            val context = RearModuleSession.appContext()
            val descriptor = identity?.let { context?.let { app -> ModuleStore.get(app, it.moduleId) } }
            val timerState = identity?.let { context?.let { app -> StageTimerController.state(app, it.moduleId) } }
            val service = instance
            val expected = service?.sessionLease
            val recoveryLeaseValid = identity != null && expected != null && expected.taskId == taskId &&
                expected.moduleId == identity.moduleId && expected.versionCode == identity.versionCode &&
                expected.timerGeneration == identity.generation && service.validateSession(expected) && isLeaseRequested(expected)
            return ModuleRearAnimationRestorePolicy.animationRestoreRoute(
                isModuleTask = isModuleTask,
                identityMatches = identity != null && identity.taskId == taskId && identity.moduleId.isNotBlank() &&
                    identity.generation.isNotBlank(),
                moduleEnabled = descriptor != null && context?.let { ModuleStore.isEnabled(it, identity!!.moduleId) } == true,
                versionMatches = descriptor != null && identity?.versionCode == descriptor.versionCode,
                generationMatches = timerState != null && identity?.generation == timerState.optString("generation"),
                status = timerState?.optString("status").orEmpty(),
                recoveryLeaseValid = recoveryLeaseValid
            )
        }

        /** Explicitly separates a live recovery lease, a still-valid timer page and stale tasks. */
        @JvmStatic fun restoreAnimation(remote: ITaskService, taskId: Int, animationId: Long): AnimationRestoreDisposition {
            val route = animationRestoreDisposition(taskId, animationId)
            if (route != AnimationRestoreDisposition.RECOVERY) return route
            if (taskId <= 0 || !RearAnimationManager.isIdle(animationId)) return AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK
            val service = instance
            val expected = service?.sessionLease
            val owner = service ?: return AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK
            val lease = expected ?: return AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK
            val state = owner.recoveryState ?: return AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK
            val actions = ModuleDisplayRecoveryActions(state, object : ModuleDisplayRecoveryActions.Port {
                override fun ownerEligible(cover: ModuleDisplayRecoveryState.Cover?): Boolean = try {
                    owner.sessionLease === lease && owner.validateSession(lease) && !state.isCovered() &&
                        RearAnimationManager.isIdle(animationId) && !RearAnimationManager.isAnimationPlaying() &&
                        !RearScreenKeeperService.isPhysicalCastRunning() && remote.isTaskOnDisplay(taskId, lease.displayId) &&
                        JSONObject(remote.getRearVirtualCastStatus()).optString("state") != "running" &&
                        (remote.getForegroundAppOnDisplay(lease.displayId).orEmpty().let {
                            it == "${owner.packageName}:$taskId" || it.startsWith("${owner.packageName}:") || owner.isOfficialRear(it)
                        })
                } catch (_: Exception) { false }
                override fun ownerEligibleForAnimationRestore(coveredBlack: Boolean): Boolean = try {
                    owner.sessionLease === lease && owner.validateSession(lease) && state.isCovered() == coveredBlack &&
                        RearAnimationManager.isIdle(animationId) && !RearAnimationManager.isAnimationPlaying() &&
                        !RearScreenKeeperService.isPhysicalCastRunning() && remote.isTaskOnDisplay(taskId, lease.displayId) &&
                        JSONObject(remote.getRearVirtualCastStatus()).optString("state") != "running" &&
                        remote.getForegroundAppOnDisplay(lease.displayId).orEmpty().let {
                            it == "${owner.packageName}:$taskId" || owner.isOfficialRear(it)
                        }
                } catch (_: Exception) { false }
                override fun coverRear(cover: ModuleDisplayRecoveryState.Cover) = true
                override fun onUncovered(cover: ModuleDisplayRecoveryState.Cover) = Unit
                override fun rearDisplayIsOn() = owner.rearDisplayIsOn()
                override fun wakeRearOnce(): Boolean {
                    var woke = false
                    RearAnimationManager.runDisplayAction {
                        if (ownerEligible()) woke = remote.executeShellCommand("input -d ${lease.displayId} keyevent KEYCODE_WAKEUP")
                    }
                    return woke
                }
                override fun wakeRearOnce(coveredBlack: Boolean): Boolean {
                    var woke = false
                    RearAnimationManager.runDisplayAction {
                        if (ownerEligibleForAnimationRestore(coveredBlack))
                            woke = remote.executeShellCommand("input -d ${lease.displayId} keyevent KEYCODE_WAKEUP")
                    }
                    return woke
                }
                override fun focusExistingTask(): Boolean = try {
                    var focused = false
                    RearAnimationManager.runDisplayAction {
                        if (ownerEligible() && remote.executeShellCommand("am task focus $taskId") && ownerEligible()) {
                            focused = remote.getForegroundAppOnDisplay(lease.displayId) == "${owner.packageName}:$taskId"
                        }
                    }
                    focused
                } catch (_: Exception) { false }
                override fun focusExistingTask(coveredBlack: Boolean): Boolean = try {
                    var focused = false
                    RearAnimationManager.runDisplayAction {
                        if (ownerEligibleForAnimationRestore(coveredBlack) && remote.executeShellCommand("am task focus $taskId") &&
                            ownerEligibleForAnimationRestore(coveredBlack)) {
                            focused = remote.getForegroundAppOnDisplay(lease.displayId) == "${owner.packageName}:$taskId"
                        }
                    }
                    focused
                } catch (_: Exception) { false }
                override fun showAndRefresh() {
                    showAndRefresh(false)
                }
                override fun showAndRefresh(coveredBlack: Boolean) {
                    service.main.post {
                        if (owner.sessionLease === lease && owner.validateSession(lease) && state.isCovered() == coveredBlack &&
                            RearAnimationManager.isIdle(animationId)) {
                            owner.ownTaskWasVisible = true
                            owner.announceBlackCover(lease, coveredBlack)
                        }
                    }
                }
            })
            runCatching { actions.restoreAnimation() }
            return AnimationRestoreDisposition.RECOVERY
        }

        /** Called only from a visible, user-initiated settings/rear Activity path. */
        @JvmStatic fun currentLeaseForSession(moduleId: String, taskId: Int): ModuleDisplayLease? {
            val service = instance ?: return null
            val lease = service.sessionLease ?: return null
            return lease.takeIf { it.moduleId == moduleId && it.taskId == taskId && service.validateSession(it) }
        }

        @JvmStatic fun isLeaseCurrent(lease: ModuleDisplayLease): Boolean {
            val service = instance ?: return false
            return service.sessionLease == lease && activeLease == leaseKey(
                lease.moduleId, lease.versionCode, lease.timerGeneration, lease.taskId, lease.displayId, lease.nonce)
        }

        @JvmStatic fun isLeaseRequested(lease: ModuleDisplayLease): Boolean = activeLease == leaseKey(
            lease.moduleId, lease.versionCode, lease.timerGeneration, lease.taskId, lease.displayId, lease.nonce)

        @JvmStatic fun isLeaseCovered(lease: ModuleDisplayLease): Boolean =
            instance?.let { it.sessionLease == lease && it.recoveryState?.isCovered() == true } ?: false

        @JvmStatic fun startForSession(
            context: Context, moduleId: String, taskId: Int, forceNewDisplay: Boolean = false
        ): ModuleDisplayLease? {
            val descriptor = ModuleStore.get(context, moduleId) ?: return null
            if (!ModuleStore.isEnabled(context, moduleId) || taskId <= 0 ||
                !StageTimerController.config(context, moduleId).optBoolean("keepVisible", false)) return null
            val session = StageTimerController.recoverySession(context, moduleId) ?: return null
            val generation = session.optString("generation")
            if (generation.isBlank()) return null
            val displayId = runCatching { RearDisplay.requireId() }.getOrDefault(-1)
            if (displayId <= 0) return null
            val currentLease = activeLease
            if (currentLease != null && activeModuleId == moduleId && activeVersion == descriptor.versionCode &&
                activeGeneration == generation && activeTaskId == taskId && activeDisplayId == displayId && !forceNewDisplay) {
                return ModuleDisplayLease(moduleId, descriptor.versionCode, generation, taskId, displayId, activeNonce)
            }
            val nonce = nonceCounter.incrementAndGet().coerceAtLeast(1L)
            val lease = leaseKey(moduleId, descriptor.versionCode, generation, taskId, displayId, nonce)
            activeLease = lease
            activeTaskId = taskId
            activeModuleId = moduleId
            activeGeneration = generation
            activeVersion = descriptor.versionCode
            activeDisplayId = displayId
            activeNonce = nonce
            return try {
                val launch = Intent(context, ModuleDisplayRecoveryService::class.java)
                    .putExtra(EXTRA_MODULE_ID, moduleId).putExtra(EXTRA_VERSION, descriptor.versionCode)
                    .putExtra(EXTRA_GENERATION, generation).putExtra(EXTRA_TASK_ID, taskId)
                    .putExtra(EXTRA_NONCE, nonce)
                ContextCompat.startForegroundService(context, launch)
                val identity = ModuleDisplayLease(moduleId, descriptor.versionCode, generation, taskId, displayId, nonce)
                context.sendBroadcast(leaseIntent(ACTION_LEASE_CHANGED, identity))
                identity
            } catch (_: Exception) {
                if (activeLease == lease) activeLease = null
                if (activeTaskId == taskId && activeGeneration == generation && activeNonce == nonce) {
                    activeTaskId = -1; activeModuleId = ""; activeGeneration = ""
                    activeVersion = 0; activeDisplayId = -1; activeNonce = 0L
                }
                RearScreenKeeperService.resumeAfterModuleLease(lease)
                null
            }
        }

        @JvmStatic fun stopForModule(context: Context, lease: ModuleDisplayLease, owner: android.app.Activity) {
            if (!isLeaseRequested(lease) || !RearModuleSession.isOwner(lease.taskId, owner)) return
            context.sendBroadcast(leaseIntent(ACTION_CANCEL, lease))
        }

        @JvmStatic fun notifyRearFront(context: Context, lease: ModuleDisplayLease, front: Boolean, owner: android.app.Activity) {
            if (!isLeaseCurrent(lease) || !RearModuleSession.isOwner(lease.taskId, owner)) return
            context.sendBroadcast(leaseIntent(ACTION_ACTIVITY_FRONT, lease).putExtra(EXTRA_FRONT, front))
        }

        @JvmStatic fun notifySensorPreferenceChanged(context: Context) {
            context.sendBroadcast(Intent(ACTION_SENSOR_PREF_CHANGED).setPackage(context.packageName))
        }

        @JvmStatic fun notifyAnimationRestored(context: Context, lease: ModuleDisplayLease, animationId: Long) {
            if (!isLeaseCurrent(lease)) return
            context.sendBroadcast(leaseIntent(ACTION_ANIMATION_RESTORED, lease)
                .putExtra(EXTRA_ANIMATION_ID, animationId))
        }

        @JvmStatic fun notifyAnimationChanged(context: Context) {
            context.sendBroadcast(Intent(ACTION_ANIMATION_CHANGED).setPackage(context.packageName))
        }

        @JvmStatic fun notifyManualRearOff(context: Context) {
            if (activeLease != null) context.sendBroadcast(Intent(ACTION_MANUAL_REAR_OFF).setPackage(context.packageName))
        }

        /** The actual sleep is a single display-targeted key event; its exit status is not confirmation. */
        @JvmStatic fun sleepRearOnceCommand(displayId: Int) = "input -d $displayId keyevent KEYCODE_SLEEP"
    }
}
