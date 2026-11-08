package top.zainzh.rearscreen

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleDisplayRecoveryStateTest {
    private fun lease(generation: String = "g1", nonce: Long = 1) =
        ModuleDisplayLease("fixture", 1, generation, 42, 1, nonce)

    private class FakePort : ModuleDisplayRecoveryActions.Port {
        var covered = false
        var ownerAllowed = true
        var ownForeground = true
        var rearOn = true
        var covers = 0
        var wakes = 0
        var focuses = 0
        var shown = 0
        var shownBlack = 0
        var uncovered = 0
        override fun ownerEligible(cover: ModuleDisplayRecoveryState.Cover?) = ownerAllowed
        override fun coverRear(cover: ModuleDisplayRecoveryState.Cover): Boolean {
            covers++
            if (!ownerAllowed) return false
            covered = true
            return true
        }
        override fun onUncovered(cover: ModuleDisplayRecoveryState.Cover) { uncovered++; covered = false }
        override fun rearDisplayIsOn() = rearOn
        override fun ownerEligibleForVisibleRestore(cover: ModuleDisplayRecoveryState.Cover) = ownerAllowed && ownForeground
        override fun wakeRearOnce(): Boolean { wakes++; rearOn = true; return ownerAllowed }
        override fun focusExistingTask(): Boolean { focuses++; return ownForeground }
        override fun showAndRefresh() { shown++ }
        override fun ownerEligibleForAnimationRestore(coveredBlack: Boolean) = ownerAllowed && covered == coveredBlack
        override fun wakeRearOnce(coveredBlack: Boolean): Boolean { wakes++; rearOn = true; return ownerAllowed }
        override fun focusExistingTask(coveredBlack: Boolean): Boolean { focuses++; return ownForeground }
        override fun showAndRefresh(coveredBlack: Boolean) { if (coveredBlack) shownBlack++ else shown++ }
    }

    @Test fun initialFarDoesNothing() {
        val port = FakePort()
        val actions = ModuleDisplayRecoveryActions(ModuleDisplayRecoveryState(lease()), port)
        assertEquals(ModuleDisplayRecoveryState.FarAction.IGNORE, actions.stableFar())
        assertEquals(0, port.covers + port.wakes + port.focuses + port.shown)
    }

    @Test fun stableNearUsesBlackCoverAndDoesNotIssueSleepOrWake() {
        val port = FakePort()
        val actions = ModuleDisplayRecoveryActions(ModuleDisplayRecoveryState(lease()), port)
        assertNotNull(actions.stableNear())
        assertEquals(1, port.covers)
        assertTrue(port.covered)
        assertEquals(0, port.wakes)
        assertEquals(0, port.focuses)
    }

    @Test fun nearRevalidatesOwnerBeforeCoveringAndRollsBackRejectedLeaseState() {
        val state = ModuleDisplayRecoveryState(lease())
        val port = FakePort().apply { ownerAllowed = false }
        val actions = ModuleDisplayRecoveryActions(state, port)
        assertNull(actions.stableNear())
        assertFalse(state.isCovered())
        assertFalse(port.covered)
    }

    @Test fun stableFarRestoresOnlyWhenSameTaskStillOwnsRearAndDoesNotWakeOrFocus() {
        val port = FakePort()
        val actions = ModuleDisplayRecoveryActions(ModuleDisplayRecoveryState(lease()), port)
        actions.stableNear()
        assertEquals(ModuleDisplayRecoveryState.FarAction.RESTORE_VISIBLE_UI, actions.stableFar())
        assertFalse(port.covered)
        assertEquals(1, port.shown)
        assertEquals(0, port.wakes)
        assertEquals(0, port.focuses)
    }

    @Test fun stableFarDoesNotRevealContentAfterThirdPartyTakesRear() {
        val port = FakePort()
        val actions = ModuleDisplayRecoveryActions(ModuleDisplayRecoveryState(lease()), port)
        actions.stableNear()
        port.ownForeground = false
        actions.stableFar()
        assertEquals(0, port.shown)
        assertEquals(0, port.wakes)
        assertEquals(0, port.focuses)
    }

    @Test fun coveredMainLockCreatesOneExactLeaseTokenAndDefersRestoreUntilFar() {
        val expected = lease()
        val state = ModuleDisplayRecoveryState(expected)
        val cover = state.stableNear(true)!!
        val token = state.screenOff(frontModuleWasVisible = true, coveredNow = true)!!
        assertTrue(state.validBlackCoverLockRecovery(expected, token))
        assertTrue(state.isCurrent(cover))
        assertEquals(ModuleDisplayRecoveryState.FarAction.RESTORE_VISIBLE_UI, state.stableFar()!!.second)
        assertFalse(state.isCovered())
        assertFalse(state.validBlackCoverLockRecovery(expected, token))
        assertTrue("original screen-off token remains available for the one-shot service callback", state.lockRecoveryValid(token))
    }

    @Test fun blackCoverLockRecoveryRejectsOldLeaseOldTokenAndStop() {
        val current = lease(nonce = 2)
        val state = ModuleDisplayRecoveryState(current)
        state.stableNear(true)
        val token = state.screenOff(true, true)!!
        assertFalse(state.validBlackCoverLockRecovery(lease(nonce = 1), token))
        assertTrue(state.validBlackCoverLockRecovery(current, token))
        state.consumeLockRecovery(token)
        assertFalse(state.validBlackCoverLockRecovery(current, token))
        val next = state.screenOff(true, true)!!
        state.invalidate()
        assertFalse(state.validBlackCoverLockRecovery(current, next))
    }

    @Test fun screenOnCancelsLockTokenEvenWhileBlackCoverRemains() {
        val state = ModuleDisplayRecoveryState(lease())
        state.stableNear(true)
        val token = state.screenOff(true, true)!!
        state.consumeLockRecovery(token)
        assertFalse(state.validBlackCoverLockRecovery(lease(), token))
        assertTrue(state.isCovered())
    }

    @Test fun homeOrUncoveredFarWithoutOwnForegroundNeverBorrowsLockTokenToStealRear() {
        val port = FakePort()
        val state = ModuleDisplayRecoveryState(lease())
        val actions = ModuleDisplayRecoveryActions(state, port)
        actions.stableNear()
        val token = state.screenOff(true, true)!!
        port.ownForeground = false
        actions.stableFar()
        assertTrue(state.lockRecoveryValid(token))
        assertEquals(0, port.shown)
        assertEquals(0, port.wakes)
        assertEquals(0, port.focuses)
    }

    @Test fun coveredManualRearOffDoesNotCancelLeaseButStopDoes() {
        val state = ModuleDisplayRecoveryState(lease())
        state.stableNear(true)
        state.manualRearOff()
        assertTrue(state.isActive())
        state.invalidate()
        assertFalse(state.isActive())
        assertNull(state.stableFar())
    }

    @Test fun animationReturnDuringBlackCoverRestoresOnlyBlackContent() {
        val port = FakePort()
        val actions = ModuleDisplayRecoveryActions(ModuleDisplayRecoveryState(lease()), port)
        actions.stableNear()
        assertTrue(actions.restoreAnimation())
        assertEquals(1, port.wakes)
        assertEquals(1, port.focuses)
        assertEquals(1, port.shownBlack)
        assertEquals(0, port.shown)
    }

    @Test fun r5PendingLockExitRequiresExactLeaseAndCurrentToken() {
        val expected = lease()
        val state = ModuleDisplayRecoveryState(expected)
        val token = state.screenOff(frontModuleWasVisible = true, coveredNow = false)!!
        assertTrue(state.shouldRetainPendingLockExit(expected, token, mainDisplayOn = false, keyguardLocked = true))
        assertFalse(state.shouldRetainPendingLockExit(expected, token, mainDisplayOn = true, keyguardLocked = false))
        assertFalse(state.shouldRetainPendingLockExit(lease(nonce = 9), token, mainDisplayOn = false, keyguardLocked = true))
        state.consumeLockRecovery(token)
        assertFalse("a user exit after successful black lock recovery must not borrow a consumed token",
            state.shouldRetainPendingLockExit(expected, token, mainDisplayOn = false, keyguardLocked = true))
    }

    @Test fun coverModeNoticeIsLocalizedForSupportedResourceLocales() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        fun message(languageTag: String): String {
            val config = Configuration(app.resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(languageTag))
            return app.createConfigurationContext(config).getString(R.string.native_modules_cover_black_note)
        }
        assertTrue(message("zh-CN").contains("计时仍继续"))
        assertTrue(message("zh-TW").contains("計時仍會繼續"))
        assertTrue(message("zh-HK").contains("計時仍會繼續"))
        assertTrue(message("en").contains("timer continues"))
    }

    @Test fun blackCoverShowsFinalStateOnlyForSameTimerGeneration() {
        assertEquals(ModuleRearCoverSessionPolicy.Update.SHOW_FINAL_STATE,
            ModuleRearCoverSessionPolicy.onSessionUpdate(true, "g1", "g1", running = false, leaseCurrent = true))
        assertEquals(ModuleRearCoverSessionPolicy.Update.KEEP_BLACK,
            ModuleRearCoverSessionPolicy.onSessionUpdate(true, "g1", "g1", running = true, leaseCurrent = true))
        assertEquals(ModuleRearCoverSessionPolicy.Update.FINISH_STALE_SESSION,
            ModuleRearCoverSessionPolicy.onSessionUpdate(true, "g1", "g2", running = true, leaseCurrent = true))
        assertEquals(ModuleRearCoverSessionPolicy.Update.FINISH_STALE_SESSION,
            ModuleRearCoverSessionPolicy.onSessionUpdate(true, null, "g1", running = false, leaseCurrent = false))
        assertEquals("invalid lease for same generation clears black without showing a replacement session",
            ModuleRearCoverSessionPolicy.Update.SHOW_FINAL_STATE,
            ModuleRearCoverSessionPolicy.onSessionUpdate(true, "g1", "g1", running = true, leaseCurrent = false))
    }
}
