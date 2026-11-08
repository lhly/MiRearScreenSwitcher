package top.zainzh.rearscreen

import android.content.Intent
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/** One bounded, in-process token bridges the explicit launch gate and the timer start callback. */
internal object ModuleStartupHoldRegistry {
    const val MAX_DURATION_MS = 5_000L
    const val EXTRA_NONCE = "module_startup_nonce"
    const val EXTRA_DEADLINE = "module_startup_deadline_elapsed"

    data class Token(val nonce: Long, val deadlineElapsedMs: Long)
    enum class State { ACTIVE, EXPIRED, STALE }

    private val nonceSequence = AtomicLong(SystemClock.elapsedRealtime().coerceAtLeast(1L))
    @Volatile private var activeToken: Token? = null
    @Volatile private var loadedLaunch: LoadedLaunch? = null

    private data class LoadedLaunch(val token: Token, val moduleId: String, val displayId: Int)

    @Synchronized fun issue(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Token {
        loadedLaunch = null
        return Token(nonceSequence.incrementAndGet(), nowElapsedMs + MAX_DURATION_MS).also { activeToken = it }
    }

    @Synchronized fun read(intent: Intent): Token? {
        val nonce = intent.getLongExtra(EXTRA_NONCE, -1L)
        val deadline = intent.getLongExtra(EXTRA_DEADLINE, -1L)
        if (nonce <= 0L || deadline <= 0L) return null
        return Token(nonce, deadline)
    }

    @Synchronized fun write(intent: Intent, token: Token): Intent = intent
        .putExtra(EXTRA_NONCE, token.nonce)
        .putExtra(EXTRA_DEADLINE, token.deadlineElapsedMs)

    @Synchronized fun state(token: Token, nowElapsedMs: Long = SystemClock.elapsedRealtime()): State {
        if (activeToken != token) return State.STALE
        if (nowElapsedMs >= token.deadlineElapsedMs) {
            activeToken = null
            if (loadedLaunch?.token == token) loadedLaunch = null
            return State.EXPIRED
        }
        return State.ACTIVE
    }

    @Synchronized fun release(token: Token): Boolean {
        if (activeToken != token) return false
        activeToken = null
        if (loadedLaunch?.token == token) loadedLaunch = null
        return true
    }

    @Synchronized fun markModuleLoaded(token: Token, moduleId: String, displayId: Int): Boolean {
        if (moduleId.isBlank() || displayId <= 0 || state(token) != State.ACTIVE) return false
        loadedLaunch = LoadedLaunch(token, moduleId, displayId)
        return true
    }

    @Synchronized fun isModuleLoaded(token: Token, moduleId: String, displayId: Int): Boolean =
        state(token) == State.ACTIVE && loadedLaunch == LoadedLaunch(token, moduleId, displayId)
}

/** Distinguishes a stale task base intent from a new explicit module launch. */
internal object ModuleRearLaunchIntentPolicy {
    enum class CreateDisposition { VALIDATE_NOW, WAIT_FOR_NEW_INTENT }
    enum class NewIntentDisposition { VALIDATE, REJECT_MISSING_EXTRAS }

    fun hasRequiredEnvelope(intent: Intent): Boolean =
        intent.getStringExtra("module_id").orEmpty().isNotBlank() && intent.hasExtra("display_id")

    fun onCreate(intent: Intent): CreateDisposition =
        if (hasRequiredEnvelope(intent)) CreateDisposition.VALIDATE_NOW else CreateDisposition.WAIT_FOR_NEW_INTENT

    fun onNewIntent(intent: Intent): NewIntentDisposition =
        if (hasRequiredEnvelope(intent)) NewIntentDisposition.VALIDATE else NewIntentDisposition.REJECT_MISSING_EXTRAS

    fun shouldFinishPendingCreate(moduleInitialized: Boolean): Boolean = !moduleInitialized
}

/** Shared launch decision policy used by ModuleRearActivity and focused unit tests. */
internal class ModuleStartupHoldPolicy(
    private val registry: ModuleStartupHoldRegistry = ModuleStartupHoldRegistry
) {
    fun shouldAcquire(token: ModuleStartupHoldRegistry.Token?, nowElapsedMs: Long,
                      explicitGate: Boolean, moduleValid: Boolean, displayMatches: Boolean): Boolean =
        token != null && explicitGate && moduleValid && displayMatches &&
            registry.state(token, nowElapsedMs) == ModuleStartupHoldRegistry.State.ACTIVE

    fun shouldReapply(token: ModuleStartupHoldRegistry.Token?, nowElapsedMs: Long): Boolean =
        token != null && registry.state(token, nowElapsedMs) == ModuleStartupHoldRegistry.State.ACTIVE

    fun shouldRelease(token: ModuleStartupHoldRegistry.Token?, nowElapsedMs: Long,
                      timerRunning: Boolean, keepVisible: Boolean, leaseRequested: Boolean): Boolean =
        token != null && registry.state(token, nowElapsedMs) == ModuleStartupHoldRegistry.State.ACTIVE &&
            timerRunning && (!keepVisible || leaseRequested)
}
