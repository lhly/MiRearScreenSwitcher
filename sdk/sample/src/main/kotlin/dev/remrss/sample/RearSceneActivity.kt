package dev.remrss.sample

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.remrss.platform.sdk.RearClient
import dev.remrss.platform.sdk.RearSceneWindowPolicy
import dev.remrss.platform.sdk.RearScenePresentationRequestEpoch
import dev.remrss.platform.sdk.RearViewportReporter
import dev.remrss.platform.sdk.RearWindowReport
import java.io.Closeable

/** A minimal client-owned Activity. Its lease/revision state lives in Application across recreation. */
class RearSceneActivity : Activity() {
    private lateinit var scoreView: TextView
    private lateinit var statusView: TextView
    private lateinit var blackCover: View
    private lateinit var sceneLayout: LinearLayout
    private val sample get() = application as SampleApplication
    private val ticket get() = intent.getStringExtra(RearClient.EXTRA_LAUNCH_TICKET).orEmpty()
    private var scoreSubscription: Closeable? = null
    private var visibilitySubscription: Closeable? = null
    private var leaseSubscription: Closeable? = null
    private var readySubscription: Closeable? = null
    private var readyRetrySubscription: Closeable? = null
    private var bootstrapStarted = false
    private var viewportReportInFlight = false
    private var viewportRefreshPending = false
    private var sceneVisible = false
    private var windowActuallyVisible = false
    private var blackCoverVisible: Boolean? = null
    private val visibilityBootstrap = SceneVisibilityBootstrap()
    private val windowRequestEpoch = RearScenePresentationRequestEpoch()
    private var activityStarted = false
    private var viewportLayoutListener: View.OnLayoutChangeListener? = null
    private var attachListener: View.OnAttachStateChangeListener? = null
    private var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ticket.isBlank()) { finishAndRemoveTask(); return }
        RearSceneWindowPolicy.applySceneLease(this, requested = false, leaseActive = false, windowActuallyVisible = false)
        scoreView = TextView(this).apply { textSize = 44f; gravity = Gravity.CENTER }
        statusView = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }
        sceneLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        sceneLayout.addView(TextView(this).apply { text = "Scoreboard · Rear scene"; textSize = 20f; gravity = Gravity.CENTER })
        sceneLayout.addView(scoreView, LinearLayout.LayoutParams(-1, 160))
        sceneLayout.addView(Button(this).apply { text = "+1 on rear"; setOnClickListener { sample.addPoint() } })
        sceneLayout.addView(Button(this).apply { text = "Close rear scene"; setOnClickListener {
            sample.lease?.let { lease -> sample.client.closeScene(lease.sessionId) }
            finishAndRemoveTask()
        } })
        sceneLayout.addView(statusView, LinearLayout.LayoutParams(-1, -2))
        blackCover = View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            visibility = View.VISIBLE
            isClickable = true
            isFocusable = true
        }
        val sceneRoot = object : FrameLayout(this) {
            override fun onWindowVisibilityChanged(visibility: Int) {
                super.onWindowVisibilityChanged(visibility)
                post { refreshActualWindowVisibility() }
            }

            override fun onVisibilityChanged(changedView: View, visibility: Int) {
                super.onVisibilityChanged(changedView, visibility)
                post { refreshActualWindowVisibility() }
            }
        }.apply {
            addView(sceneLayout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(blackCover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(sceneRoot)
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            applyCameraSafePadding()
            view.post { refreshActualWindowVisibility() }
            insets
        }
        viewportLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyCameraSafePadding()
            refreshActualWindowVisibility()
            sample.lease?.takeIf { it.launchTicket == ticket && it.isReady }?.let(::reportCurrentViewport)
        }.also(window.decorView::addOnLayoutChangeListener)
        attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { view.post { refreshActualWindowVisibility() } }
            override fun onViewDetachedFromWindow(view: View) {
                if (!isChangingConfigurations) setActualWindowVisible(false)
            }
        }.also(window.decorView::addOnAttachStateChangeListener)
        focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
            // Focus is only a cue to re-check this window. Main-display focus loss alone is not
            // evidence that the independent rear window became hidden.
            window.decorView.post { refreshActualWindowVisibility() }
        }.also(window.decorView.viewTreeObserver::addOnWindowFocusChangeListener)
        renderScore(sample.score())
        statusView.text = "正在连接背屏会话…"
        window.decorView.post { bootstrap() }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        windowRequestEpoch.invalidate()
        sample.invalidatePresentationQueries()
        visibilityBootstrap.beginWindowEpoch()
        sceneVisible = false
        if (::blackCover.isInitialized) blackCover.visibility = View.VISIBLE
        visibilitySubscription = sample.observeSceneVisibility({ visible ->
            runOnUiThread {
                sceneVisible = visible
                if (!visible && sample.lease?.launchTicket != ticket) visibilityBootstrap.revoke()
                updateVisibilitySurface()
                updateScoreSubscription()
                applySceneWindowPolicy()
                window.decorView.post { refreshActualWindowVisibility() }
            }
        }, emitCurrent = false)
        sample.lease?.takeIf { it.launchTicket == ticket && it.isReady }?.let(::reconcileCurrentPresentation)
        window.decorView.post {
            bootstrap()
            refreshActualWindowVisibility()
        }
    }

    override fun onResume() {
        super.onResume()
        updateScoreSubscription()
        applySceneWindowPolicy()
        window.decorView.post { refreshActualWindowVisibility() }
    }

    override fun onPause() {
        updateScoreSubscription()
        applySceneWindowPolicy()
        window.decorView.post { refreshActualWindowVisibility() }
        super.onPause()
    }

    override fun onStop() {
        activityStarted = false
        windowRequestEpoch.invalidate()
        sample.invalidatePresentationQueries()
        scoreSubscription?.close(); scoreSubscription = null
        visibilitySubscription?.close(); visibilitySubscription = null
        leaseSubscription?.close(); leaseSubscription = null
        readySubscription?.close(); readySubscription = null
        readyRetrySubscription?.close(); readyRetrySubscription = null
        bootstrapStarted = false
        window.decorView.post { refreshActualWindowVisibility() }
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The manifest handles these changes without recreating this Activity. Wait through the
        // next layout pass, then report what the real window now measures (not guessed config px).
        window.decorView.requestLayout()
        window.decorView.post { window.decorView.post {
            sample.lease?.takeIf { it.launchTicket == ticket && it.isReady }?.let(::reportCurrentViewport)
        } }
    }

    override fun onDestroy() {
        viewportLayoutListener?.let(window.decorView::removeOnLayoutChangeListener)
        viewportLayoutListener = null
        attachListener?.let(window.decorView::removeOnAttachStateChangeListener)
        attachListener = null
        focusListener?.let { if (window.decorView.viewTreeObserver.isAlive) window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
        focusListener = null
        super.onDestroy()
    }

    private fun bootstrap() {
        if (bootstrapStarted || isFinishing || isDestroyed) return
        bootstrapStarted = true
        val current = sample.lease?.takeIf { it.launchTicket == ticket }
        if (current?.isReady == true) attachAndReportViewport(current)
        else {
            leaseSubscription = sample.observeLease(ticket) { lease ->
                if (isFinishing || isDestroyed) return@observeLease
                readySubscription?.close()
                readySubscription = lease.whenReady { attachAndReportViewport(lease) }
                if (!lease.isReady) reportInitialReady()
            }
            if (current == null) reportInitialReady()
        }
    }

    private fun reportInitialReady(attempt: Int = 0, expectedGeneration: Long? = null) {
        if (isFinishing || isDestroyed) return
        val activeLease = sample.lease?.takeIf { it.launchTicket == ticket }
        if (expectedGeneration != null && activeLease == null) return
        if (activeLease?.isReady == true || expectedGeneration != null && activeLease?.generation != expectedGeneration) return
        if (!sample.readyGate.begin(ticket)) {
            // A replacement Activity may attach while the previous Activity still owns the
            // one in-flight binder call. Wait for that same ticket attempt; never race it.
            val waiter: () -> Unit = {
                window.decorView.post {
                    if (!isFinishing && !isDestroyed) {
                        val current = sample.lease?.takeIf { it.launchTicket == ticket }
                        if (current?.isReady != true) reportInitialReady(expectedGeneration = current?.generation)
                    }
                }
            }
            readyRetrySubscription?.close()
            readyRetrySubscription = sample.readyGate.whenRetryable(ticket, waiter)
            return
        }
        sample.client.reportWindowReady(this, ticket, callback = { result ->
            val operation = result.getOrNull()
            if (operation?.success == true) {
                sample.readyGate.complete(ticket, TicketReadyGate.Completion.SUCCEEDED)
                sample.markReady(ticket, 1L)
                sample.publishStatus("Rear window ready")
                window.decorView.post { refreshActualWindowVisibility() }
                if (!isFinishing && !isDestroyed) statusView.text = "背屏窗口已就绪"
                return@reportWindowReady
            }
            val error = operation?.error ?: result.exceptionOrNull()?.message ?: "E_UNKNOWN"
            val retryable = operation?.retryable == true || error == "E_NOT_CONNECTED" || error == "E_WINDOW_NOT_READY"
            if (retryable && attempt < 20) {
                sample.readyGate.complete(ticket, TicketReadyGate.Completion.RETRYABLE_FAILURE)
                if (isFinishing || isDestroyed) return@reportWindowReady
                statusView.text = "等待窗口就绪（${attempt + 1}/20）"
                val retryGeneration = activeLease?.generation ?: expectedGeneration
                window.decorView.postDelayed({
                    val current = sample.lease?.takeIf { it.launchTicket == ticket } ?: return@postDelayed
                    if (current.isReady || retryGeneration != null && current.generation != retryGeneration || isFinishing || isDestroyed) return@postDelayed
                    reportInitialReady(attempt + 1, current.generation)
                }, 150)
            } else {
                sample.readyGate.complete(ticket, TicketReadyGate.Completion.TERMINAL_FAILURE)
                if (isFinishing || isDestroyed) return@reportWindowReady
                failAndFinish("背屏启动失败：$error")
            }
        })
    }

    private fun attachAndReportViewport(lease: SampleLease) {
        if (isFinishing || isDestroyed || !lease.isReady) return
        visibilityBootstrap.onLeaseReady()?.let { reportSceneVisibility(lease, it) }
        applySceneWindowPolicy()
        sample.client.attachObserver(lease.sessionId, lease.generation) { attached ->
            if (isFinishing || isDestroyed) return@attachObserver
            if (attached.getOrNull()?.success != true) {
                failAndFinish("背屏会话已失效：${attached.getOrNull()?.error ?: attached.exceptionOrNull()?.message ?: "E_ATTACH_FAILED"}")
                return@attachObserver
            }
            reportCurrentViewport(lease)
        }
    }

    private fun reportCurrentViewport(lease: SampleLease, attempt: Int = 0) {
        if (isFinishing || isDestroyed || !lease.isReady) return
        if (viewportReportInFlight) { viewportRefreshPending = true; return }
        val measured = runCatching { RearViewportReporter.capture(this, 1L) }.getOrElse {
            failAndFinish("背屏视口测量失败：${it.message}"); return
        }
        val key = ViewportSignature.from(measured).toString()
        val revision = runCatching { lease.nextViewportRevision(key) }.getOrElse {
            failAndFinish("背屏视口更新失败：${it.message}"); return
        } ?: return
        viewportReportInFlight = true
        sendViewportReport(lease, measured.copy(revision = revision), key, attempt)
    }

    private fun sendViewportReport(lease: SampleLease, report: RearWindowReport, key: String, attempt: Int) {
        sample.client.reportViewport(lease.sessionId, lease.generation, report) { result ->
            val operation = result.getOrNull()
            if (operation?.success == true) {
                lease.markViewportAccepted(key, report.revision)
                if (!isFinishing && !isDestroyed) statusView.text = "背屏窗口已就绪 · 视口 ${report.revision}"
                finishViewportReport(lease)
                return@reportViewport
            }
            val error = operation?.error ?: result.exceptionOrNull()?.message ?: "E_UNKNOWN"
            if ((operation?.retryable == true || error == "E_NOT_CONNECTED" || error == "E_WINDOW_NOT_READY") && attempt < 10) {
                window.decorView.postDelayed({
                    if (!isFinishing && !isDestroyed) sendViewportReport(lease, report, key, attempt + 1)
                    else finishViewportReport(lease)
                }, 150)
            } else {
                finishViewportReport(lease)
                failAndFinish("背屏窗口更新失败：$error")
            }
        }
    }

    private fun finishViewportReport(lease: SampleLease) {
        viewportReportInFlight = false
        if (viewportRefreshPending) {
            viewportRefreshPending = false
            window.decorView.post { reportCurrentViewport(lease) }
        }
    }

    private fun failAndFinish(message: String) {
        if (isFinishing || isDestroyed) return
        sample.publishStatus(message)
        statusView.text = message
        statusView.visibility = View.VISIBLE
        window.decorView.postDelayed({ if (!isFinishing) finishAndRemoveTask() }, 450)
    }

    private fun reportSceneVisibility(lease: SampleLease, report: SceneVisibilityBootstrap.Report) {
        val requestEpoch = windowRequestEpoch.begin()
        Log.i(TAG_VISIBILITY, "submit epoch=${report.windowEpoch} sequence=${report.sequence} visible=${report.visible} ready=${lease.isReady} coreVisible=$sceneVisible windowVisible=$windowActuallyVisible")
        sample.client.reportVisibility(lease.sessionId, lease.generation, report.visible) { result ->
            val operation = result.getOrNull()
            val accepted = operation?.success == true
            visibilityBootstrap.onReportFinished(report, accepted)
            Log.i(TAG_VISIBILITY, "result epoch=${report.windowEpoch} sequence=${report.sequence} accepted=$accepted error=${operation?.error ?: "none"} windowVisible=$windowActuallyVisible coreVisible=$sceneVisible")
            if (!windowRequestEpoch.isCurrent(requestEpoch) || isFinishing || isDestroyed || !activityStarted) return@reportVisibility
            if (!accepted && !isFinishing && !isDestroyed) {
                sample.publishStatus("背屏可见性更新失败：${operation?.error ?: result.exceptionOrNull()?.message ?: "E_VISIBILITY_FAILED"}")
            } else {
                sample.reconcileScenePresentation(lease) {
                    windowRequestEpoch.isCurrent(requestEpoch) && activityStarted && !isFinishing && !isDestroyed
                }
            }
        }
    }

    private fun reconcileCurrentPresentation(lease: SampleLease) {
        val requestEpoch = windowRequestEpoch.begin()
        sample.reconcileScenePresentation(lease) {
            windowRequestEpoch.isCurrent(requestEpoch) && activityStarted && !isFinishing && !isDestroyed
        }
    }

    private fun applySceneWindowPolicy() {
        val lease = sample.lease?.takeIf { it.launchTicket == ticket }
        val before = window.decorView.keepScreenOnFlag()
        RearSceneWindowPolicy.applySceneLease(
            this,
            requested = lease?.keepVisible == true,
            leaseActive = lease != null && !isFinishing && !isDestroyed,
            windowActuallyVisible = windowActuallyVisible,
            sceneVisible = sceneVisible
        )
        val after = window.decorView.keepScreenOnFlag()
        if (before != after) Log.i(TAG_VISIBILITY, "keepFlag enabled=$after requested=${lease?.keepVisible == true} lease=${lease != null} windowVisible=$windowActuallyVisible coreVisible=$sceneVisible")
    }

    private fun refreshActualWindowVisibility() {
        if (isDestroyed || isFinishing || !::statusView.isInitialized) return
        val decor = window.decorView
        val displayOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            decor.display?.state == Display.STATE_ON
        } else true
        val bounds = Rect()
        val visible = decor.isAttachedToWindow && decor.windowVisibility == View.VISIBLE &&
            decor.visibility == View.VISIBLE && decor.width > 0 && decor.height > 0 && displayOn &&
            decor.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0
        setActualWindowVisible(visible)
    }

    private fun setActualWindowVisible(visible: Boolean) {
        val changed = windowActuallyVisible != visible
        windowActuallyVisible = visible
        applySceneWindowPolicy()
        updateVisibilitySurface()
        updateScoreSubscription()
        val report = visibilityBootstrap.onWindowVisibility(visible)
        if (changed) Log.i(TAG_VISIBILITY, "windowVisibility visible=$visible ready=${sample.lease?.takeIf { it.launchTicket == ticket }?.isReady == true} coreVisible=$sceneVisible")
        if (report != null) sample.lease?.takeIf { it.launchTicket == ticket }?.let { reportSceneVisibility(it, report) }
    }

    private fun updateVisibilitySurface() {
        if (::blackCover.isInitialized) {
            val shouldCover = sceneBlackCoverVisible(sceneVisible, windowActuallyVisible)
            if (blackCoverVisible != shouldCover) {
                blackCoverVisible = shouldCover
                Log.i(TAG_VISIBILITY, "consumer_black=$shouldCover coreVisible=$sceneVisible windowVisible=$windowActuallyVisible ready=${sample.lease?.takeIf { it.launchTicket == ticket }?.isReady == true}")
            }
            blackCover.visibility = if (shouldCover) View.VISIBLE else View.GONE
        }
    }

    private fun updateScoreSubscription() {
        val shouldObserve = windowActuallyVisible && sceneVisible && !isFinishing && !isDestroyed
        if (shouldObserve && scoreSubscription == null) scoreSubscription = sample.observeScore(::renderScore)
        else if (!shouldObserve && scoreSubscription != null) {
            scoreSubscription?.close()
            scoreSubscription = null
        }
    }

    private fun applyCameraSafePadding() {
        if (!::sceneLayout.isInitialized || isDestroyed) return
        val insets = runCatching { RearViewportReporter.cameraSafeInsets(this) }.getOrNull() ?: return
        val base = (20 * resources.displayMetrics.density).toInt()
        sceneLayout.setPadding(base + insets[0], base + insets[1], base + insets[2], base + insets[3])
    }

    private fun renderScore(value: Int) {
        runOnUiThread { if (::scoreView.isInitialized) scoreView.text = value.toString() }
    }

    private fun View.keepScreenOnFlag(): Boolean =
        window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    private companion object { const val TAG_VISIBILITY = "RearSceneVisibility" }
}
