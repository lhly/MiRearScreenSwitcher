package top.zainzh.rearscreen

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.platform.*

@RunWith(AndroidJUnit4::class)
class SceneActivationAuthorityTest {
    private val owner = SessionOwner(
        10001, "top.zainzh.rearscreen", "cert",
        SessionPrincipal.SignedMod("author", "scoreboard", "session-1")
    )
    private val request = SceneRequest(ComponentName("top.zainzh.rearscreen", "top.zainzh.rearscreen.ModRearSceneActivity"))

    @Test fun activationIsOwnerAndRequestBoundAndSingleUse() {
        var now = 10L
        var tokenIndex = 0
        val authority = SceneActivationAuthority(clock = { now }, newToken = { "opaque-${++tokenIndex}" })
        val token = authority.issueFromConfirmedHostAction(owner, request)
        assertFalse(authority.consume(owner.copy(principal = SessionPrincipal.SignedMod("author", "other", "session-1")), request, token))

        val retryToken = authority.issueFromConfirmedHostAction(owner, request)
        assertFalse(authority.consume(owner, request.copy(keepVisible = false), retryToken))

        val valid = authority.issueFromConfirmedHostAction(owner, request)
        assertTrue(authority.consume(owner, request, valid))
        assertFalse("the same confirmation cannot be replayed", authority.consume(owner, request, valid))

        val expired = authority.issueFromConfirmedHostAction(owner, request)
        now += SceneActivationAuthority.TTL_MS
        assertFalse("expired confirmation is not accepted", authority.consume(owner, request, expired))
    }
}
