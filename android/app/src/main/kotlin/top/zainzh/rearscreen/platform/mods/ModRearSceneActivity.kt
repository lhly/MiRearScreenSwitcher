package top.zainzh.rearscreen.platform.mods

import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.zainzh.rearscreen.AppPalette
import top.zainzh.rearscreen.miuixContent
import top.zainzh.rearscreen.platform.*
import kotlin.math.roundToInt

/** Ticket-routed, package-independent host shell for a verified Mod rear scene. */
class ModRearSceneActivity : ComponentActivity() {
    private var pkg by mutableStateOf<VerifiedModPackage?>(null)
    private var model by mutableStateOf<ModPersistedModel?>(null)
    private var message by mutableStateOf("正在加载 Mod…")
    private var error by mutableStateOf("")
    private var hostData by mutableStateOf(JSONObject())
    private var cameraSafeInsets by mutableStateOf(CameraSafeInsets.Zero)
    private var cutoutReported by mutableStateOf<Boolean?>(null)
    private var launch: SceneLaunchContext? = null
    private var launchTicket: String? = null
    private var readyController: ModSceneReadyController? = null
    private var sceneReady by mutableStateOf(false)
    private var effectRequest by mutableStateOf<ModRearOpenEffect?>(null)
    private var moduleSlotActive = false
    private var sessionId: String? = null
    private var generation: Long = 0
    private var viewportRevision: Long = 0
    private var windowReported = false
    private var coreClosed = false
    private var instanceToken = 0L
    private var effectConsumer: ModHostEffectRouter.Consumer? = null
    private var layoutListener: View.OnLayoutChangeListener? = null
    private var dataSubscription: Closeable? = null
    private val hostDataEpoch = ModDataSubscriptionEpoch()
    private var windowActuallyVisible by mutableStateOf(false)
    private var coordinatorVisible by mutableStateOf(false)
    private lateinit var windowLifecycleGate: ModSceneWindowLifecycleGate
    private val decorAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { reportActualWindowVisibility() }
        override fun onViewDetachedFromWindow(view: View) { reportActualWindowVisibility() }
    }
    private val core by lazy { RearCapabilityCore.get(this) }
    private val broker by lazy { ModBrokerProvider.get(this) }
    private val observer = object : SceneObserver {
        override fun onSceneEvent(sessionId: String, generation: Long, event: SceneEvent) {
            runOnUiThread {
                if (!acceptSceneEvent(sessionId, generation)) return@runOnUiThread
                when (event) {
                    is SceneEvent.Ready -> {
                        // READY proves the measured window, not that Core has made
                        // the rear display visible. Visibility arrives separately.
                        sceneReady = true
                        message = ""
                        reconcileScenePresentation()
                    }
                    is SceneEvent.ViewportChanged -> Unit
                    is SceneEvent.VisibilityChanged,
                    is SceneEvent.Suspended,
                    SceneEvent.Restored -> reconcileScenePresentation()
                    SceneEvent.BackendLost -> {
                        reconcileScenePresentation()
                    }
                    is SceneEvent.Closed -> {
                        ModSceneReadyController.markClosed(launchTicket)
                        coreClosed = true
                        sceneReady = false
                        finishAndRemoveTask()
                    }
                }
            }
        }
    }
    private val brokerListener = object : ModBrokerListener {
        override fun onReady(modId: String, model: ModPersistedModel) {
            if (!isCurrentSceneInstance() || !moduleSlotActive) return
            this@ModRearSceneActivity.model = model; message = ""
        }
        override fun onModelCommitted(modId: String, eventId: String?, model: ModPersistedModel) {
            if (!isCurrentSceneInstance() || !moduleSlotActive) return
            this@ModRearSceneActivity.model = model
        }
        override fun onError(modId: String, code: String, message: String) {
            if (!isCurrentSceneInstance() || !moduleSlotActive) return
            if (code in setOf("E_PACKAGE_REPLACED", "E_MOD_REVOKED", "E_MOD_UNINSTALLED")) finishAndRemoveTask()
            else error = "$code：$message"
        }
        override fun onEffectRequested(modId: String, eventId: String) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        val ticket = intent.getStringExtra("remrss_scene_ticket")
        val route = ticket?.let { core.resolveSceneLaunchForHostActivity(it, componentName) }
        if (route == null) {
            // A ticket which no longer resolves cannot own a live READY controller.
            // Drop only this opaque ticket entry; do not infer or close another owner.
            ModSceneReadyController.markClosed(ticket)
            finishAndRemoveTask()
            return
        }
        launch = route
        launchTicket = ticket
        val restoredSession = savedInstanceState?.getString("scene_session")
        val restoredGeneration = savedInstanceState?.getLong("scene_generation")
        // The verified launch ticket is authoritative. Saved state may carry viewport data,
        // but cannot override the current Core owner/session/generation identity.
        sessionId = route.sessionId
        generation = route.generation
        viewportRevision = savedInstanceState?.getLong("viewport_revision") ?: 0L
        windowLifecycleGate = ModSceneWindowLifecycleGate(sessionId!!, generation, ::onWindowLifecycleStateChanged)
        val owner = route.owner
        val principal = owner.principal as? SessionPrincipal.SignedMod ?: run {
            core.displaySessions.closeOwnerSessions(owner, SceneCloseReason.OWNER_REVOKED)
            ModSceneReadyController.markClosed(ticket)
            finishAndRemoveTask()
            return
        }
        val installed = ModPackageStore.installed(this, principal.modId)
        val verified = ModPackageStore.find(this, principal.modId)
        if (installed == null || !installed.enabled || !ModPackageStore.isAuthorTrusted(this, principal.authorFingerprint) ||
            verified == null || verified.authorFingerprint != principal.authorFingerprint || installed.logicalSessionId != principal.logicalSessionId) {
            core.displaySessions.closeOwnerSessions(owner, SceneCloseReason.OWNER_REVOKED)
            ModSceneReadyController.markClosed(ticket)
            finishAndRemoveTask()
            return
        }
        // Do not publish a process-scoped READY controller until this exact signed
        // Mod route has passed the current installation, enabled, and trust checks.
        readyController = ModSceneReadyController.forLaunch(route, requireNotNull(ticket), core.displaySessions)
        if (readyController == null) { finishAndRemoveTask(); return }
        registerSceneInstance()
        pkg = verified
        model = ModPackageStore.model(this, principal.modId)
        if (restoredSession == route.sessionId && restoredGeneration == route.generation) {
            core.displaySessions.attachSceneObserver(owner, route.sessionId, route.generation, observer)
        }
        miuixContent {
            val activePackage = pkg
            val current = model
            ModSceneVisibilitySurface(visible = isSceneContentVisible()) {
                Column(Modifier.fillMaxSize().padding(
                    start = cameraSafeInsets.left + 16.dp,
                    top = cameraSafeInsets.top + 16.dp,
                    end = cameraSafeInsets.right + 16.dp,
                    bottom = cameraSafeInsets.bottom + 16.dp
                ), verticalArrangement = Arrangement.Center) {
                    if (cutoutReported == false) Text("ROM 未提供可核实的屏幕开孔安全区", color = AppPalette.secondary())
                    if (error.isNotEmpty()) Text(error, color = AppPalette.destructive())
                    else if (activePackage == null || current == null) Text(message, color = AppPalette.secondary())
                    else if(activePackage.rearScene==null) Text("此 Mod 未声明背屏场景",color=AppPalette.secondary())
                    else ModNativeScene(activePackage, activePackage.rearScene, current.state, current.config, hostData, true,
                        onBack = { finish() },
                        onEvent = { type -> broker.submitEvent(activePackage.id, type) },
                        onSaveConfig = { config, complete -> broker.saveConfig(activePackage.id, config, mainExecutor, complete) })
                }
            }
            val pendingEffect = effectRequest
            WindowDialog(show = isSceneContentVisible() && pendingEffect != null, title = "Mod 请求打开背屏场景", onDismissRequest = { effectRequest = null }) {
                pendingEffect?.let { effect ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("此 Effect 已在模型保存后发出。你确认后，宿主才会请求背屏服务。")
                        TextButton("取消", { effectRequest = null }, minHeight = 48.dp)
                        TextButton("确认打开", { effectRequest = null; requestEffect(effect) }, minHeight = 48.dp)
                    }
                }
            }
        }
        layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view.width > 0 && view.height > 0 && !windowReported) reportMeasuredWindow(ticket)
        }.also { window.decorView.addOnLayoutChangeListener(it) }
        window.decorView.addOnAttachStateChangeListener(decorAttachListener)
        reportActualWindowVisibility()
        window.decorView.post { if (!windowReported && window.decorView.width > 0 && window.decorView.height > 0) reportMeasuredWindow(ticket) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("scene_session", sessionId)
        outState.putLong("scene_generation", generation)
        outState.putLong("viewport_revision", viewportRevision)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val route = launch ?: return
        runCatching { core.displaySessions.attachSceneObserver(route.owner, route.sessionId, generation, observer) }
        reportActualWindowVisibility()
    }

    override fun onStart() {
        super.onStart()
        if (!::windowLifecycleGate.isInitialized) return
        windowLifecycleGate.onActivityStarted(true)
        reportActualWindowVisibility()
        launchTicket?.takeIf { !windowReported && window.decorView.width > 0 && window.decorView.height > 0 }?.let(::reportMeasuredWindow)
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        // Stop this Activity's observer/data work promptly. The Core lease stays
        // open and is re-authorized only after fresh Core visibility on return.
        readyController?.setActive(instanceToken, false)
        if (::windowLifecycleGate.isInitialized) {
            windowLifecycleGate.onActivityStarted(false)
            reportActualWindowVisibility()
        }
        super.onStop()
    }

    @Suppress("UNUSED_PARAMETER")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Input focus can move to the main display while this rear window remains
        // visible; recheck its real window visibility, but do not treat focus loss
        // as a cover or lease end.
        reportActualWindowVisibility()
    }

    override fun onDestroy() {
        val id = (launch?.owner?.principal as? SessionPrincipal.SignedMod)?.modId
        val isCurrentInstance = isCurrentSceneInstance()
        readyController?.detach(instanceToken)
        layoutListener?.let(window.decorView::removeOnLayoutChangeListener)
        window.decorView.removeOnAttachStateChangeListener(decorAttachListener)
        stopHostData()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (id != null) effectConsumer?.let { ModHostEffectRouter.consumers.remove(id, it) }
        if (isCurrentInstance && isFinishing && launch != null && !coreClosed) {
            val route = launch!!
            core.displaySessions.closeScene(route.owner, route.sessionId)
            ModSceneReadyController.markClosed(launchTicket)
        }
        sessionId?.let { activeSceneInstances.remove(it, instanceToken) }
        if (moduleSlotActive && id != null) broker.closeObserver(id, brokerListener)
        moduleSlotActive = false
        super.onDestroy()
    }

    private fun startHostData() {
        if (dataSubscription != null) return
        if (!isCurrentSceneInstance() || !windowPolicy().showSceneContent || isFinishing || isDestroyed) return
        val route = launch ?: return
        if (route.owner.principal !is SessionPrincipal.SignedMod) return
        val active = pkg ?: return
        val fields = ModHostDataSnapshot.fieldsFor(active)
        if (fields.isEmpty()) return
        val generation = hostDataEpoch.start()
        val subscription = ModHostDataSnapshot.observe(core, route.owner, fields) { snapshot ->
            runOnUiThread {
                if (hostDataEpoch.isCurrent(generation) && isCurrentSceneInstance() && windowPolicy().showSceneContent && !isFinishing && !isDestroyed) hostData = snapshot
            }
        }
        if (subscription == null) hostDataEpoch.stop() else dataSubscription = subscription
    }

    private fun stopHostData() {
        hostDataEpoch.stop()
        dataSubscription?.close()
        dataSubscription = null
        hostData = JSONObject()
    }

    private fun requestEffect(effect: ModRearOpenEffect) {
        val route = launch ?: return
        val region = if (effect.region == "FULL") RearRegion.FULL else RearRegion.CAMERA_SAFE
        val requirements = route.request.requiredCapabilities.toMutableSet()
        if (region == RearRegion.FULL) requirements += CapabilityRequirement("rear.region", 1)
        if (effect.keepVisible) requirements += CapabilityRequirement("rear.scene.keep_visible", 1)
        val request = route.request.copy(
            region = region,
            keepVisible = effect.keepVisible,
            requiredCapabilities = requirements
        )
        val token = core.issueSceneActivationAfterUserConfirmation(route.owner, request)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = core.displaySessions.openScene(route.owner, request, token, observer)
            if (result is SceneOpenResult.Rejected) runOnUiThread { error = "无法打开背屏：${result.code}" }
        }
    }

    private fun reportMeasuredWindow(ticket: String) {
        if (!isCurrentSceneInstance() || windowReported) return
        val route = launch ?: return
        val controller = readyController ?: return
        val epoch = windowLifecycleGate.currentWindowRequestEpoch()
        controller.submit(
            instance = instanceToken,
            report = windowReport(),
            windowEpoch = epoch,
            active = windowLifecycleGate.currentState().activityStarted,
        ) { completion ->
            if (completion.instance != instanceToken || !isCurrentSceneInstance() || isFinishing || isDestroyed) return@submit
            if (!completion.result.success) {
                error = "背屏窗口验证失败：${completion.result.error}"
                finishAndRemoveTask()
                return@submit
            }
            if (completion.windowEpoch != windowLifecycleGate.currentWindowRequestEpoch()) {
                reportMeasuredWindow(ticket)
                return@submit
            }
            completeWindowReady(route)
        }
    }

    private fun completeWindowReady(route: SceneLaunchContext) {
        windowReported = true
        layoutListener?.let(window.decorView::removeOnLayoutChangeListener)
        sceneReady = true
        message = ""
        core.displaySessions.attachSceneObserver(route.owner, route.sessionId, generation, observer)
        synchronizeVisibleConsumers()
        reportActualWindowVisibility()
    }

    private fun reportActualWindowVisibility() {
        if (!::windowLifecycleGate.isInitialized) return
        val decor = window.decorView
        windowLifecycleGate.onWindowStateChanged(
            attached = decor.isAttachedToWindow,
            viewShown = decor.isShown && decor.visibility == View.VISIBLE,
            windowVisible = decor.windowVisibility == View.VISIBLE,
        )
        if (!isCurrentSceneInstance() || !sceneReady || !windowReported || isFinishing || isDestroyed) {
            applyWindowPolicy()
            return
        }
        val route = launch ?: return
        val visible = windowLifecycleGate.currentState().windowVisible
        val reported = runCatching { core.displaySessions.reportSceneVisibility(route.owner, route.sessionId, generation, visible) }
            .getOrNull()
        if (reported?.success == true) reconcileScenePresentation()
        else if (reported != null) {
            windowLifecycleGate.applyPresentationSnapshot(null, windowLifecycleGate.currentWindowRequestEpoch())
        }
    }

    /** Called on the UI thread: queued Scene events only trigger a fresh owner/session snapshot read. */
    private fun reconcileScenePresentation() {
        if (!isCurrentSceneInstance() || !sceneReady || !windowReported || isFinishing || isDestroyed) {
            synchronizeVisibleConsumers()
            return
        }
        val route = launch ?: return
        val requestEpoch = windowLifecycleGate.currentWindowRequestEpoch()
        val snapshotResult = runCatching {
            core.displaySessions.queryScenePresentation(route.owner, route.sessionId, generation)
        }
        if (snapshotResult.isFailure) {
            windowLifecycleGate.applyPresentationSnapshot(null, requestEpoch)
            error = "无法读取背屏显示状态"
            return
        }
        val snapshot = snapshotResult.getOrNull()
        windowLifecycleGate.applyPresentationSnapshot(snapshot, requestEpoch)
        if (snapshot == null) {
            coreClosed = true
            sceneReady = false
            finishAndRemoveTask()
        }
    }

    private fun onWindowLifecycleStateChanged(state: ModSceneWindowLifecycleGate.State) {
        windowActuallyVisible = state.windowVisible
        coordinatorVisible = state.coordinatorVisible
        synchronizeVisibleConsumers()
    }

    private fun synchronizeVisibleConsumers() {
        if (isSceneContentVisible()) resumeVisibleConsumers() else suspendVisibleConsumers()
        applyWindowPolicy()
    }

    /** Only visible rear scenes own a worker observer; hidden Scenes keep their Core lease and task. */
    private fun resumeVisibleConsumers() {
        if (!isCurrentSceneInstance() || !windowPolicy().showSceneContent || isFinishing || isDestroyed) return
        val route = launch ?: return
        val modId = (route.owner.principal as? SessionPrincipal.SignedMod)?.modId ?: return
        if (!moduleSlotActive) {
            moduleSlotActive = true
            broker.open(modId, mainExecutor, brokerListener)
            if (effectConsumer == null) {
                effectConsumer = ModHostEffectRouter.Consumer { _, _, effect ->
                    runOnUiThread {
                        if (!isCurrentSceneInstance() || !moduleSlotActive || !isSceneContentVisible() || isFinishing || isDestroyed) return@runOnUiThread
                        effectRequest = effect
                    }
                }
            }
            ModHostEffectRouter.consumers[modId] = requireNotNull(effectConsumer)
        }
        startHostData()
    }

    private fun suspendVisibleConsumers() {
        stopHostData()
        val modId = (launch?.owner?.principal as? SessionPrincipal.SignedMod)?.modId
        if (moduleSlotActive && modId != null) broker.closeObserver(modId, brokerListener)
        moduleSlotActive = false
        if (modId != null) effectConsumer?.let { ModHostEffectRouter.consumers.remove(modId, it) }
    }

    private fun isWindowActuallyVisible(): Boolean = windowActuallyVisible && windowReported && !isFinishing && !isDestroyed

    private fun isSceneContentVisible(): Boolean = windowPolicy().showSceneContent

    private fun applyWindowPolicy() {
        if (windowPolicy().keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun windowPolicy() = ModSceneWindowPolicy(
        windowReady = sceneReady,
        windowAttachedAndVisible = isWindowActuallyVisible(),
        coordinatorVisible = coordinatorVisible,
        keepVisibleRequested = launch?.request?.keepVisible == true,
    )

    private fun registerSceneInstance() {
        instanceToken = nextSceneInstance.incrementAndGet()
        sessionId?.let { activeSceneInstances[it] = instanceToken }
    }

    private fun isCurrentSceneInstance(): Boolean = sessionId?.let { activeSceneInstances[it] == instanceToken && instanceToken != 0L } == true

    private fun acceptSceneEvent(eventSessionId: String, eventGeneration: Long): Boolean {
        if (!isCurrentSceneInstance() || sessionId != eventSessionId || eventGeneration < generation) return false
        generation = eventGeneration
        return true
    }

    private fun windowReport(): SceneWindowReport {
        val component = componentName
        val root = window.decorView
        val width = root.width.coerceAtLeast(1)
        val height = root.height.coerceAtLeast(1)
        val display = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") display.getRealMetrics(metrics)
        val point = IntArray(2); root.getLocationOnScreen(point)
        val density = metrics.densityDpi.coerceAtLeast(1)
        val rotation = display.rotation
        val cutout = if (Build.VERSION.SDK_INT >= 28) root.rootWindowInsets?.displayCutout else null
        val safeLeft = cutout?.safeInsetLeft?.coerceIn(0, width) ?: 0
        val safeTop = cutout?.safeInsetTop?.coerceIn(0, height) ?: 0
        val safeRight = cutout?.safeInsetRight?.coerceIn(0, width - safeLeft) ?: 0
        val safeBottom = cutout?.safeInsetBottom?.coerceIn(0, height - safeTop) ?: 0
        cameraSafeInsets = CameraSafeInsets(
            left = (safeLeft * 160f / density).dp,
            top = (safeTop * 160f / density).dp,
            right = (safeRight * 160f / density).dp,
            bottom = (safeBottom * 160f / density).dp
        )
        cutoutReported = cutout != null
        if (cutout == null) Log.w("ModRearScene", "ROM did not report a verifiable display-cutout safe area; camera-safe bounds are uncertain")
        val viewport = RearViewportSnapshot(
            revision = ++viewportRevision,
            displayId = display.displayId,
            physicalDisplayPx = PixelSize(metrics.widthPixels, metrics.heightPixels),
            contentBoundsPx = PixelRect(
                point[0] + safeLeft,
                point[1] + safeTop,
                point[0] + width - safeRight,
                point[1] + height - safeBottom
            ),
            windowPx = PixelSize(width, height),
            windowDp = DpSize(width * 160f / density, height * 160f / density),
            densityDpi = density,
            renderBufferPx = null,
            displayRotation = rotation,
            contentRotation = rotation,
            inputTransform = CoordinateTransform(1f, 0f, -safeLeft.toFloat(), 0f, 1f, -safeTop.toFloat())
        )
        return SceneWindowReport(component, taskId, viewport)
    }

    private data class CameraSafeInsets(val left: Dp, val top: Dp, val right: Dp, val bottom: Dp) {
        companion object { val Zero = CameraSafeInsets(0.dp, 0.dp, 0.dp, 0.dp) }
    }

    companion object {
        private const val MAX_INITIAL_READY_RETRIES = 20
        private const val INITIAL_READY_RETRY_DELAY_MS = 150L
        private val nextSceneInstance = AtomicLong()
        private val activeSceneInstances = ConcurrentHashMap<String, Long>()
    }
}
