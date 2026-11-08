package top.zainzh.rearscreen

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryLeaseIntentsTest {
    private val lease = ModuleDisplayLease(
        moduleId = "fixture",
        versionCode = 4,
        timerGeneration = "generation-a",
        taskId = 42,
        displayId = 7,
        nonce = 99L
    )

    @Test fun productionLeaseIntentContainsCompleteIdentityAndMatches() {
        val intent = RecoveryLeaseIntents.create("fixture.ACTION", lease, "top.zainzh.fixture")

        assertEquals("fixture.ACTION", intent.action)
        assertEquals("top.zainzh.fixture", intent.`package`)
        assertEquals(lease.moduleId, intent.getStringExtra(RecoveryLeaseIntents.MODULE))
        assertEquals(lease.versionCode, intent.getIntExtra(RecoveryLeaseIntents.VERSION, -1))
        assertEquals(lease.timerGeneration, intent.getStringExtra(RecoveryLeaseIntents.GENERATION))
        assertEquals(lease.taskId, intent.getIntExtra(RecoveryLeaseIntents.TASK, -1))
        assertEquals(lease.displayId, intent.getIntExtra(RecoveryLeaseIntents.DISPLAY, -1))
        assertEquals(lease.nonce, intent.getLongExtra(RecoveryLeaseIntents.NONCE, -1L))
        assertTrue(RecoveryLeaseIntents.matches(intent, lease))
    }

    @Test fun oldGenerationTaskDisplayOrNonceCannotMatchNewLease() {
        val intent = RecoveryLeaseIntents.create("fixture.ACTION", lease, "top.zainzh.fixture")

        assertFalse(RecoveryLeaseIntents.matches(intent, lease.copy(timerGeneration = "generation-b")))
        assertFalse(RecoveryLeaseIntents.matches(intent, lease.copy(taskId = 43)))
        assertFalse(RecoveryLeaseIntents.matches(intent, lease.copy(displayId = 8)))
        assertFalse(RecoveryLeaseIntents.matches(intent, lease.copy(nonce = 100L)))
        assertFalse(RecoveryLeaseIntents.matches(intent, lease.copy(versionCode = 5)))
        assertFalse(RecoveryLeaseIntents.matches(intent, lease.copy(moduleId = "other")))
    }

    @Test fun cancelCloseAndVisibilityUseTheSameCompleteProductionLeaseIdentity() {
        listOf(
            ModuleDisplayRecoveryService.ACTION_CANCEL,
            ModuleDisplayRecoveryService.ACTION_CLOSE_ACTIVITY,
            ModuleDisplayRecoveryService.ACTION_VISIBILITY
        ).forEach { action ->
            val intent = RecoveryLeaseIntents.create(action, lease, "top.zainzh.fixture")
            assertEquals(action, intent.action)
            assertTrue("$action must carry full lease identity", RecoveryLeaseIntents.matches(intent, lease))
            assertEquals(lease.displayId, intent.getIntExtra(RecoveryLeaseIntents.DISPLAY, -1))
            assertEquals(lease.nonce, intent.getLongExtra(RecoveryLeaseIntents.NONCE, -1L))
        }
    }

    @Test fun releasingLeaseSendsOnlyItsOwnBlackOffNoticeAndCannotMatchReplacementLease() {
        val replacement = lease.copy(timerGeneration = "generation-b", nonce = 100L)
        val notice = ModuleDisplayRecoveryService.revokedLeaseVisibilityIntent(lease)

        assertEquals(ModuleDisplayRecoveryService.ACTION_VISIBILITY, notice.action)
        assertFalse(notice.getBooleanExtra(ModuleDisplayRecoveryService.EXTRA_FRONT, true))
        assertFalse(notice.getBooleanExtra(ModuleDisplayRecoveryService.EXTRA_BLACK_COVER, true))
        assertTrue(RecoveryLeaseIntents.matches(notice, lease))
        assertFalse("old release event must not clear a replacement actor lease", RecoveryLeaseIntents.matches(notice, replacement))
    }

    @Test fun missingDisplayOrNonceMakesLeaseIntentStale() {
        val noDisplay = RecoveryLeaseIntents.create("fixture.ACTION", lease, "top.zainzh.fixture")
        noDisplay.removeExtra(RecoveryLeaseIntents.DISPLAY)
        val noNonce = RecoveryLeaseIntents.create("fixture.ACTION", lease, "top.zainzh.fixture")
        noNonce.removeExtra(RecoveryLeaseIntents.NONCE)

        assertFalse(RecoveryLeaseIntents.matches(noDisplay, lease))
        assertFalse(RecoveryLeaseIntents.matches(noNonce, lease))
    }
}
