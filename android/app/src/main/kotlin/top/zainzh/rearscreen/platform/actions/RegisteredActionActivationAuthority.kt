package top.zainzh.rearscreen.platform.actions

import android.os.SystemClock
import top.zainzh.rearscreen.platform.SessionOwner
import java.security.SecureRandom
import java.util.Base64

/** Opaque one-use host capability. It is never serialized to a Mod or SDK caller. */
@JvmInline internal value class RegisteredActionActivation internal constructor(internal val value: String)

internal class RegisteredActionActivationAuthority(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val newValue: () -> String = ::newOpaqueValue,
) {
    private data class Grant(val owner: SessionOwner, val ref: RegisteredActionRef, val expiresAt: Long)
    private val lock = Any()
    private val grants = HashMap<String, Grant>()

    /** Call directly from a host-owned native click callback, never from Mod-controlled data. */
    fun issueFromHostClick(
        owner: SessionOwner,
        ref: RegisteredActionRef,
        hostGestureElapsedRealtimeMs: Long = elapsedRealtime(),
    ): RegisteredActionActivation {
        val value = newValue()
        synchronized(lock) {
            purgeLocked()
            require(hostGestureElapsedRealtimeMs <= elapsedRealtime()) { "host gesture timestamp is in the future" }
            grants[value] = Grant(owner, ref, hostGestureElapsedRealtimeMs + TTL_MS)
        }
        return RegisteredActionActivation(value)
    }

    fun consume(owner: SessionOwner, ref: RegisteredActionRef, activation: RegisteredActionActivation): Boolean = synchronized(lock) {
        purgeLocked()
        val grant = grants[activation.value] ?: return@synchronized false
        if (grant.owner != owner || grant.ref != ref || elapsedRealtime() >= grant.expiresAt) {
            if (elapsedRealtime() >= grant.expiresAt) grants.remove(activation.value)
            return@synchronized false
        }
        grants.remove(activation.value)
        true
    }

    private fun purgeLocked() { grants.entries.removeAll { elapsedRealtime() >= it.value.expiresAt } }

    companion object {
        const val TTL_MS = 5_000L
        private val random = SecureRandom()
        private fun newOpaqueValue(): String = ByteArray(24).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
