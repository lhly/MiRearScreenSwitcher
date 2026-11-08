package top.zainzh.rearscreen.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyDisplayOccupancyTest {
    @Test fun onlyTheSamePhysicalTaskPackageAndDisplayRemainOwned() {
        assertTrue(LegacyDisplayOccupancy.taskStillOwnsProjection(
            71, "reader.app", 2, "rear-panel-A", 71, "reader.app", 2, "rear-panel-A"
        ))
        assertFalse("moved-to-main no longer occupies rear", matches(task = 71, packageName = "reader.app", display = 0, physical = "rear-panel-A"))
        assertFalse("reused task id for another package is not our cast", matches(task = 71, packageName = "other.app", display = 2, physical = "rear-panel-A"))
        assertFalse("same package/task on another display is not rear occupancy", matches(task = 71, packageName = "reader.app", display = 3, physical = "rear-panel-A"))
        assertFalse("replacement panel invalidates stale physical owner", matches(task = 71, packageName = "reader.app", display = 2, physical = "rear-panel-B"))
    }

    private fun matches(task: Int, packageName: String, display: Int, physical: String) =
        LegacyDisplayOccupancy.taskStillOwnsProjection(71, "reader.app", 2, "rear-panel-A", task, packageName, display, physical)
}
