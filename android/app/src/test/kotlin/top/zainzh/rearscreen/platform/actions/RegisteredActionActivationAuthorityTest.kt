package top.zainzh.rearscreen.platform.actions

import org.junit.Assert.*
import org.junit.Test
import top.zainzh.rearscreen.platform.SessionOwner
import top.zainzh.rearscreen.platform.SessionPrincipal

class RegisteredActionActivationAuthorityTest {
    @Test fun activationIsSingleUseBoundToOwnerAndRefAndExpiresAfterFiveSeconds() {
        var now = 1_000L
        var tokenId = 0
        val authority = RegisteredActionActivationAuthority({ now }, { "opaque-${++tokenId}" })
        val owner = SessionOwner(12, "mod.host", "signer", SessionPrincipal.SignedMod("author", "timer", "session"))
        val otherOwner = owner.copy(principal = SessionPrincipal.SignedMod("author", "other", "session"))
        val ref = RegisteredActionRef("opaque-ref", 1)
        val otherRef = RegisteredActionRef("other-ref", 1)
        val token = authority.issueFromHostClick(owner, ref)

        assertFalse(authority.consume(otherOwner, ref, token))
        assertFalse(authority.consume(owner, otherRef, token))
        assertTrue(authority.consume(owner, ref, token))
        assertFalse(authority.consume(owner, ref, token))

        val expiring = authority.issueFromHostClick(owner, ref)
        now += 5_000L
        assertFalse(authority.consume(owner, ref, expiring))
    }

    @Test fun delayedReducerCannotExtendTheOriginalGestureWindow() {
        var now = 5_000L
        val authority = RegisteredActionActivationAuthority({ now }, { "host-token" })
        val owner = SessionOwner(1, "app", "signer", SessionPrincipal.AndroidClient(1, "app", "signer", 2))
        val ref = RegisteredActionRef("action", 1)
        val token = authority.issueFromHostClick(owner, ref, hostGestureElapsedRealtimeMs = 5_000L)
        now = 9_999L
        assertTrue(authority.consume(owner, ref, token))
        val expired = authority.issueFromHostClick(owner, ref, hostGestureElapsedRealtimeMs = 5_000L)
        now = 10_000L
        assertFalse(authority.consume(owner, ref, expired))
    }
}
