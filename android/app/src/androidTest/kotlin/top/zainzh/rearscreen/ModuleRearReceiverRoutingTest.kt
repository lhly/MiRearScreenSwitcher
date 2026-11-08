package top.zainzh.rearscreen

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.modules.ModuleStore
import top.zainzh.rearscreen.timer.StageTimerController

@RunWith(AndroidJUnit4::class)
class ModuleRearReceiverRoutingTest {
    private val lease = ModuleDisplayLease(
        moduleId = "stage_timer",
        versionCode = 3,
        timerGeneration = "generation-current",
        taskId = 55332,
        displayId = 1,
        nonce = 808106380L
    )

    @Test fun receiverRoutesRealProducerActionsByTheirProductionModuleField() {
        val moduleStoreChange = Intent(ModuleStore.ACTION_CHANGED)
            .putExtra("id", lease.moduleId)
            .putExtra("operation", "update")
        val sessionChange = Intent(StageTimerController.ACTION_SESSION_CHANGED)
            .putExtra("module_id", lease.moduleId)
        val visibility = ModuleDisplayRecoveryService.leaseIntent(
            ModuleDisplayRecoveryService.ACTION_VISIBILITY, lease
        )
        val close = ModuleDisplayRecoveryService.leaseIntent(
            ModuleDisplayRecoveryService.ACTION_CLOSE_ACTIVITY, lease
        )
        val leaseChanged = ModuleDisplayRecoveryService.leaseIntent(
            ModuleDisplayRecoveryService.ACTION_LEASE_CHANGED, lease
        )

        assertEquals(lease.moduleId, ModuleRearReceiverRouting.moduleIdFor(moduleStoreChange))
        listOf(sessionChange, visibility, close, leaseChanged).forEach { intent ->
            assertEquals(intent.action, lease.moduleId, ModuleRearReceiverRouting.moduleIdFor(intent))
        }
        assertTrue(RecoveryLeaseIntents.matches(leaseChanged, lease))
    }

    @Test fun onlyCurrentCompleteRunningOwnedRequestedLeaseCanBeAdoptedByRearActor() {
        val intent = ModuleDisplayRecoveryService.leaseIntent(
            ModuleDisplayRecoveryService.ACTION_LEASE_CHANGED, lease
        )

        assertEquals(lease, accepted(intent))
        assertNull(accepted(Intent(intent).putExtra(RecoveryLeaseIntents.VERSION, lease.versionCode + 1)))
        assertNull(accepted(Intent(intent).putExtra(RecoveryLeaseIntents.GENERATION, "old-generation")))
        assertNull(accepted(Intent(intent).putExtra(RecoveryLeaseIntents.TASK, lease.taskId + 1)))
        assertNull(accepted(Intent(intent).putExtra(RecoveryLeaseIntents.DISPLAY, lease.displayId + 1)))
        assertNull(accepted(Intent(intent).putExtra(RecoveryLeaseIntents.NONCE, lease.nonce + 1), leaseRequested = false))
        assertNull(accepted(Intent(intent).also { it.removeExtra(RecoveryLeaseIntents.NONCE) }))
        assertNull(accepted(intent, running = false))
        assertNull(accepted(intent, ownsActivity = false))
        assertNull(accepted(intent, leaseRequested = false))
        assertNull(accepted(intent, generation = "old-generation"))
        assertNull(accepted(intent, moduleId = "other-module"))
    }

    @Test fun endSessionChangeFinishesCoveredOldTaskAndResumedTaskButPreservesTimerSemantics() {
        val endWhileNotificationCoversRear = Intent(StageTimerController.ACTION_SESSION_CHANGED)
            .putExtra("module_id", lease.moduleId)
        assertEquals(lease.moduleId, ModuleRearReceiverRouting.moduleIdFor(endWhileNotificationCoversRear))
        assertTrue(ModuleRearAnimationRestorePolicy.shouldFinishAfterSessionChange(
            endWhileNotificationCoversRear, lease.moduleId, "g1", "g1", "STOPPED"))
        assertEquals(false, ModuleRearAnimationRestorePolicy.shouldFinishAfterSessionChange(
            Intent(endWhileNotificationCoversRear).putExtra("module_id", "other"),
            lease.moduleId, "g1", "g1", "STOPPED"))
        assertEquals(false, ModuleRearAnimationRestorePolicy.shouldFinishAfterSessionChange(
            Intent(endWhileNotificationCoversRear).setAction("other"),
            lease.moduleId, "g1", "g1", "STOPPED"))
        assertTrue(ModuleRearAnimationRestorePolicy.shouldFinishAfterSessionChange(
            endWhileNotificationCoversRear, lease.moduleId, "g1", "g2", "RUNNING"))
        assertEquals(false, ModuleRearAnimationRestorePolicy.shouldFinishAfterSessionChange(
            endWhileNotificationCoversRear, lease.moduleId, "g1", "g1", "FINISHED"))
        assertEquals(false, ModuleRearAnimationRestorePolicy.shouldFinishAfterSessionChange(
            endWhileNotificationCoversRear, lease.moduleId, "g1", "g1", "INTERRUPTED"))
        assertEquals(false, ModuleRearAnimationRestorePolicy.shouldFinishAfterSessionChange(
            endWhileNotificationCoversRear, lease.moduleId, "g1", "g1", "RUNNING"))
        assertEquals(false, ModuleRearAnimationRestorePolicy.shouldFinishAfterSessionChange(
            endWhileNotificationCoversRear, lease.moduleId, "g1", "g1", "PAUSED"))
        assertTrue(ModuleRearAnimationRestorePolicy.shouldFinishStaleTimerTask("g1", "g2", "RUNNING"))
        assertEquals(false, ModuleRearAnimationRestorePolicy.shouldFinishStaleTimerTask("g1", "g1", "RUNNING"))
        assertEquals(false, ModuleRearAnimationRestorePolicy.shouldFinishStaleTimerTask("g1", "g1", "PAUSED"))
        assertEquals(false, ModuleRearAnimationRestorePolicy.shouldFinishStaleTimerTask(null, "", "IDLE"))
    }

    @Test fun animationRestoreSeparatesRecoveryPausedStaleAndLegacyTasks() {
        fun route(status: String, recoveryLeaseValid: Boolean = false,
                  identityMatches: Boolean = true, enabled: Boolean = true,
                  versionMatches: Boolean = true, generationMatches: Boolean = true,
                  isModuleTask: Boolean = true) = ModuleRearAnimationRestorePolicy.animationRestoreRoute(
            isModuleTask, identityMatches, enabled, versionMatches, generationMatches, status, recoveryLeaseValid
        )

        assertEquals(AnimationRestoreDisposition.RECOVERY, route("RUNNING", recoveryLeaseValid = true))
        assertEquals(AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK, route("RUNNING"))
        assertEquals(AnimationRestoreDisposition.RESTORE_EXISTING_MODULE_TASK, route("PAUSED"))
        assertEquals(AnimationRestoreDisposition.RESTORE_EXISTING_MODULE_TASK, route("FINISHED"))
        assertEquals(AnimationRestoreDisposition.RESTORE_EXISTING_MODULE_TASK, route("INTERRUPTED"))
        assertEquals(AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK, route("STOPPED"))
        assertEquals(AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK, route("PAUSED", identityMatches = false))
        assertEquals(AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK, route("PAUSED", enabled = false))
        assertEquals(AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK, route("PAUSED", versionMatches = false))
        assertEquals(AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK, route("PAUSED", generationMatches = false))
        assertEquals(AnimationRestoreDisposition.LEGACY_FALLBACK, route("RUNNING", isModuleTask = false))
    }

    @Test fun idleAttachedTaskAdoptsOnlyItsAcceptedLeaseBeforePausedRestore() {
        val history = ModuleRearAnimationTaskHistory()
        val idleIdentity = ModuleRearAnimationTaskIdentity(
            taskId = lease.taskId, moduleId = lease.moduleId, versionCode = lease.versionCode, generation = ""
        )
        history.attach(idleIdentity)
        history.capture(1L, lease.taskId)
        assertNull(ModuleRearAnimationRestorePolicy.acceptedLeaseIdentity(
            idleIdentity, lease.taskId + 1, lease.moduleId, lease.versionCode, lease.timerGeneration
        ))
        assertNull(ModuleRearAnimationRestorePolicy.acceptedLeaseIdentity(
            idleIdentity, lease.taskId, "other-module", lease.versionCode, lease.timerGeneration
        ))
        val adopted = ModuleRearAnimationRestorePolicy.acceptedLeaseIdentity(
            idleIdentity, lease.taskId, lease.moduleId, lease.versionCode, lease.timerGeneration
        )
        assertTrue(history.update(adopted!!))
        history.capture(2L, lease.taskId)
        assertEquals(lease.timerGeneration, adopted?.generation)
        assertEquals("", history.captureFor(1L)?.identity?.generation)
        assertEquals(lease.timerGeneration, history.captureFor(2L)?.identity?.generation)
        assertEquals(AnimationRestoreDisposition.RESTORE_EXISTING_MODULE_TASK,
            ModuleRearAnimationRestorePolicy.animationRestoreRoute(
                isModuleTask = true, identityMatches = adopted?.taskId == lease.taskId,
                moduleEnabled = true, versionMatches = adopted?.versionCode == lease.versionCode,
                generationMatches = adopted?.generation == lease.timerGeneration,
                status = "PAUSED", recoveryLeaseValid = false
            ))
        assertEquals(AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK,
            ModuleRearAnimationRestorePolicy.animationRestoreRoute(
                isModuleTask = true, identityMatches = true, moduleEnabled = true, versionMatches = true,
                generationMatches = false, status = "PAUSED", recoveryLeaseValid = false
            ))

        val nextTask = lease.copy(taskId = lease.taskId + 1, timerGeneration = "generation-next", nonce = lease.nonce + 1)
        history.attach(ModuleRearAnimationTaskIdentity(
            nextTask.taskId, nextTask.moduleId, nextTask.versionCode, nextTask.timerGeneration
        ))
        val lateOldCapture = history.captureFor(2L)?.identity
        assertEquals(lease.taskId, lateOldCapture?.taskId)
        assertEquals(AnimationRestoreDisposition.SUPPRESS_STALE_MODULE_TASK,
            ModuleRearAnimationRestorePolicy.animationRestoreRoute(
                isModuleTask = true, identityMatches = true, moduleEnabled = true, versionMatches = true,
                generationMatches = lateOldCapture?.generation == nextTask.timerGeneration,
                status = "RUNNING", recoveryLeaseValid = false
            ))
    }

    @Test fun existingPausedRestoreRequiresSameCurrentTaskAndNoCastOwner() {
        assertTrue(ModuleRearAnimationRestorePolicy.canRestoreExistingTask(lease.taskId, lease.taskId, false, false))
        assertEquals(false, ModuleRearAnimationRestorePolicy.canRestoreExistingTask(lease.taskId, lease.taskId + 1, false, false))
        assertEquals(false, ModuleRearAnimationRestorePolicy.canRestoreExistingTask(lease.taskId, lease.taskId, true, false))
        assertEquals(false, ModuleRearAnimationRestorePolicy.canRestoreExistingTask(lease.taskId, lease.taskId, false, true))
        assertEquals(false, ModuleRearAnimationRestorePolicy.canRestoreExistingTask(-1, -1, false, false))
    }

    private fun accepted(
        intent: Intent,
        moduleId: String = lease.moduleId,
        generation: String = lease.timerGeneration,
        running: Boolean = true,
        ownsActivity: Boolean = true,
        leaseRequested: Boolean = true
    ) = ModuleRearReceiverRouting.acceptedLeaseChanged(
        intent, moduleId, lease.versionCode, generation, lease.taskId, lease.displayId,
        running, ownsActivity, leaseRequested
    )
}
