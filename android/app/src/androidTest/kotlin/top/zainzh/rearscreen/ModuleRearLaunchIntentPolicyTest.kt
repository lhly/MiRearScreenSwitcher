package top.zainzh.rearscreen

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleRearLaunchIntentPolicyTest {
    @Test fun missingBaseIntentWaitsAndMissingNewIntentIsRejected() {
        val missing = Intent()

        assertFalse(ModuleRearLaunchIntentPolicy.hasRequiredEnvelope(missing))
        assertEquals(
            ModuleRearLaunchIntentPolicy.CreateDisposition.WAIT_FOR_NEW_INTENT,
            ModuleRearLaunchIntentPolicy.onCreate(missing)
        )
        assertEquals(
            ModuleRearLaunchIntentPolicy.NewIntentDisposition.REJECT_MISSING_EXTRAS,
            ModuleRearLaunchIntentPolicy.onNewIntent(missing)
        )
    }

    @Test fun completeEnvelopeIsValidatedForCreateAndNewIntent() {
        val complete = Intent("fixture.LAUNCH")
            .putExtra("module_id", "stage_timer")
            .putExtra("display_id", 1)

        assertTrue(ModuleRearLaunchIntentPolicy.hasRequiredEnvelope(complete))
        assertEquals(
            ModuleRearLaunchIntentPolicy.CreateDisposition.VALIDATE_NOW,
            ModuleRearLaunchIntentPolicy.onCreate(complete)
        )
        assertEquals(
            ModuleRearLaunchIntentPolicy.NewIntentDisposition.VALIDATE,
            ModuleRearLaunchIntentPolicy.onNewIntent(complete)
        )
    }

    @Test fun blankModuleOrMissingDisplayCannotSatisfyEnvelope() {
        val blankModule = Intent().putExtra("module_id", " ").putExtra("display_id", 1)
        val noDisplay = Intent().putExtra("module_id", "stage_timer")

        listOf(blankModule, noDisplay).forEach { intent ->
            assertFalse(ModuleRearLaunchIntentPolicy.hasRequiredEnvelope(intent))
            assertEquals(
                ModuleRearLaunchIntentPolicy.CreateDisposition.WAIT_FOR_NEW_INTENT,
                ModuleRearLaunchIntentPolicy.onCreate(intent)
            )
            assertEquals(
                ModuleRearLaunchIntentPolicy.NewIntentDisposition.REJECT_MISSING_EXTRAS,
                ModuleRearLaunchIntentPolicy.onNewIntent(intent)
            )
        }
    }

    @Test fun pendingCreateFinishesOnlyWhenModuleWasNeverInitialized() {
        assertTrue(ModuleRearLaunchIntentPolicy.shouldFinishPendingCreate(moduleInitialized = false))
        assertFalse(ModuleRearLaunchIntentPolicy.shouldFinishPendingCreate(moduleInitialized = true))
    }

    @Test fun readinessRequiresCurrentTokenAndExactLoadedModuleAndDisplay() {
        val token = ModuleStartupHoldRegistry.issue()

        assertFalse(ModuleStartupHoldRegistry.isModuleLoaded(token, "stage_timer", 1))
        assertTrue(ModuleStartupHoldRegistry.markModuleLoaded(token, "stage_timer", 1))
        assertTrue(ModuleStartupHoldRegistry.isModuleLoaded(token, "stage_timer", 1))
        assertFalse(ModuleStartupHoldRegistry.isModuleLoaded(token, "other_module", 1))
        assertFalse(ModuleStartupHoldRegistry.isModuleLoaded(token, "stage_timer", 2))
        assertFalse(ModuleStartupHoldRegistry.markModuleLoaded(token, "", 1))
        assertFalse(ModuleStartupHoldRegistry.markModuleLoaded(token, "stage_timer", 0))
    }

    @Test fun staleReleasedOrExpiredTokenCannotMarkOrRetainReadiness() {
        val old = ModuleStartupHoldRegistry.issue()
        val current = ModuleStartupHoldRegistry.issue()

        assertFalse(ModuleStartupHoldRegistry.markModuleLoaded(old, "stage_timer", 1))
        assertFalse(ModuleStartupHoldRegistry.isModuleLoaded(old, "stage_timer", 1))
        assertTrue(ModuleStartupHoldRegistry.markModuleLoaded(current, "stage_timer", 1))
        assertFalse(ModuleStartupHoldRegistry.release(old))
        assertTrue(ModuleStartupHoldRegistry.isModuleLoaded(current, "stage_timer", 1))

        assertTrue(ModuleStartupHoldRegistry.release(current))
        assertFalse(ModuleStartupHoldRegistry.isModuleLoaded(current, "stage_timer", 1))

        val expiring = ModuleStartupHoldRegistry.issue()
        assertTrue(ModuleStartupHoldRegistry.markModuleLoaded(expiring, "stage_timer", 1))
        assertEquals(
            ModuleStartupHoldRegistry.State.EXPIRED,
            ModuleStartupHoldRegistry.state(expiring, expiring.deadlineElapsedMs)
        )
        assertFalse(ModuleStartupHoldRegistry.isModuleLoaded(expiring, "stage_timer", 1))
    }
}
