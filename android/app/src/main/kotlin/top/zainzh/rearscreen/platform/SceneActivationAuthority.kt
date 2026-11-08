package top.zainzh.rearscreen.platform

import android.os.SystemClock
import java.security.SecureRandom
import java.util.Base64

/** Mints a one-use, five-second capability only after a host confirmation UI succeeds. */
class SceneActivationAuthority(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val newToken: () -> String = ::token
) : UserActivationVerifier {
    private data class Grant(val owner: SessionOwner, val requestKey: String, val expiresAt: Long)
    private val lock = Any()
    private val grants = HashMap<String, Grant>()

    internal fun issueFromConfirmedHostAction(owner: SessionOwner, request: SceneRequest): UserActivationToken {
        val value = newToken()
        synchronized(lock) {
            purgeLocked()
            grants[value] = Grant(owner, requestKey(request), clock() + TTL_MS)
        }
        return UserActivationToken(value)
    }

    override fun consume(owner: SessionOwner, request: SceneRequest, token: UserActivationToken): Boolean = synchronized(lock) {
        purgeLocked()
        val grant = grants.remove(token.opaqueValue) ?: return@synchronized false
        grant.owner == owner && grant.requestKey == requestKey(request) && clock() < grant.expiresAt
    }

    private fun purgeLocked() { grants.entries.removeAll { clock() >= it.value.expiresAt } }

    private fun requestKey(request: SceneRequest): String = buildString {
        append(request.component.flattenToString()).append('|')
        append(request.region.name).append('|').append(request.keepVisible)
        request.requiredCapabilities.sortedWith(compareBy({ it.name }, { it.minimumVersion })).forEach {
            append('|').append(it.name).append('@').append(it.minimumVersion)
        }
    }

    companion object {
        const val TTL_MS = 5_000L
        private val secureRandom = SecureRandom()
        private fun token(): String = ByteArray(24).also(secureRandom::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
