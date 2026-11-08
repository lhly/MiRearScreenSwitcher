package top.zainzh.rearscreen

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskServiceSceneLaunchOwnershipTest {
    @Test fun errorOrTimeoutAfterFreshTaskAppearsStillReturnsItsUniqueOwnedTask() {
        // Exact counterexample: `am start -W` timed out/reported Error after ATM created task 42.
        // Existing task 11 is retained, and the ticketed fresh task remains registered for cleanup.
        assertEquals(42, TaskService.uniqueFreshSceneTaskId(mapOf(11 to 0), mapOf(11 to 0, 42 to 2), 2))
    }

    @Test fun ambiguousFreshTasksNeverCauseAnExistingOrUnrelatedTaskToBeRemoved() {
        assertEquals(-1, TaskService.uniqueFreshSceneTaskId(mapOf(11 to 0), mapOf(11 to 0, 42 to 2, 43 to 2), 2))
        assertEquals(-1, TaskService.uniqueFreshSceneTaskId(mapOf(11 to 0), mapOf(11 to 0), 2))
    }

    @Test fun reusedPrimaryTaskMovingToRearIsNeverMistakenForFreshSceneTask() {
        // A singleTask component can be reused by ActivityTaskManager and moved from display 0 to 2.
        // The task id remains 11, so the all-display snapshot rejects it instead of closing it later.
        assertEquals(-1, TaskService.uniqueFreshSceneTaskId(mapOf(11 to 0), mapOf(11 to 2), 2))
    }

    @Test fun uniqueTaskCreatedOnPrimaryDisplayIsNotAcceptedAsRearScene() {
        assertEquals(-1, TaskService.uniqueFreshSceneTaskId(emptyMap(), mapOf(42 to 0), 2))
    }
}
