package top.zainzh.rearscreen.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LegacyProjectionOwnershipEpochTest {
    @Test fun pruningReturnedPhysicalTaskDoesNotLookLikeANewProjectionCommit() {
        val ownership = LegacyProjectionOwnershipEpoch()
        val beforePhysicalCast = ownership.current()
        val physicalCommit = ownership.projectionCommitted()

        // Direct return moves the task to display 0. The next Scene authorization lazily clears
        // this cached task record, but that cleanup is not a competing new cast.
        val afterStaleRecordPruned = ownership.staleRecordPruned()
        assertEquals(physicalCommit, afterStaleRecordPruned)
        assertNotEquals(beforePhysicalCast, afterStaleRecordPruned)

        // A real cast which commits during authorization still advances the fence.
        val newProjectionCommit = ownership.projectionCommitted()
        assertNotEquals(afterStaleRecordPruned, newProjectionCommit)
    }
}
