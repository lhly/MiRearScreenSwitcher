package top.zainzh.rearscreen.platform

import android.content.Context
import top.zainzh.rearscreen.ITaskService
import top.zainzh.rearscreen.RearDisplay

/**
 * Small compatibility boundary for existing URI/Tile projection ownership.
 * Scene sessions remain owned by RearSceneCoordinator; this records only the task that the
 * existing physical-cast path deliberately moved, and validates that identity before blocking a Scene.
 */
internal object LegacyDisplayOccupancy {
    private const val PREFS = "platform_display_ownership"
    private const val TASK = "physical_projection_task"
    private const val PACKAGE = "physical_projection_package"
    private const val DISPLAY = "physical_projection_display"
    private const val DISPLAY_IDENTITY = "physical_projection_identity"
    private val lock = Any()
    private val ownershipEpoch = LegacyProjectionOwnershipEpoch()

    fun epoch(): Long = ownershipEpoch.current()

    fun noteProjectionStarted() { synchronized(lock) { ownershipEpoch.projectionCommitted() } }

    fun recordPhysicalProjection(context: Context, taskId: Int, packageName: String, displayId: Int) {
        require(taskId > 0 && packageName.isNotBlank() && displayId > 0)
        val physicalIdentity = runCatching { RearDisplay.identity() }.getOrDefault("")
        synchronized(lock) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(TASK, taskId)
                .putString(PACKAGE, packageName)
                .putInt(DISPLAY, displayId)
                .putString(DISPLAY_IDENTITY, physicalIdentity)
                .apply()
            ownershipEpoch.projectionCommitted()
        }
    }

    /** Fail closed for a known owner if the privileged identity query itself cannot complete. */
    fun hasPhysicalProjection(context: Context, service: ITaskService, selectedRearDisplay: Int): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val taskId = prefs.getInt(TASK, -1)
        val packageName = prefs.getString(PACKAGE, null) ?: return false
        val displayId = prefs.getInt(DISPLAY, -1)
        val physicalIdentity = prefs.getString(DISPLAY_IDENTITY, null)
        val currentIdentity = runCatching { RearDisplay.identity() }.getOrNull()
        if (taskId <= 0 || displayId != selectedRearDisplay || physicalIdentity == null ||
            physicalIdentity.isNotBlank() && currentIdentity != physicalIdentity) {
            clearIfCurrent(context, taskId)
            return false
        }
        val stillOwned = try {
            service.isTaskOnDisplay(taskId, displayId) && service.getPackageNameFromTaskId(taskId) == packageName
        } catch (_: Exception) {
            return true
        }
        if (!stillOwned) clearIfCurrent(context, taskId)
        return stillOwned
    }

    private fun clearIfCurrent(context: Context, observedTaskId: Int) {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(TASK, -1) != observedTaskId) return
            prefs.edit().clear().apply()
            ownershipEpoch.staleRecordPruned()
        }
    }

    /** Pure identity predicate used to exercise task reuse/move-away behavior without an OEM display. */
    internal fun taskStillOwnsProjection(
        expectedTaskId: Int,
        expectedPackage: String,
        expectedDisplay: Int,
        expectedPhysicalIdentity: String,
        actualTaskId: Int,
        actualPackage: String?,
        actualDisplay: Int,
        actualPhysicalIdentity: String?
    ): Boolean = expectedTaskId > 0 && expectedPackage.isNotBlank() && expectedDisplay > 0 &&
        expectedTaskId == actualTaskId && expectedPackage == actualPackage && expectedDisplay == actualDisplay &&
        expectedPhysicalIdentity == actualPhysicalIdentity
}

/** Epoch tracks projection commits, not lazy removal of a cached owner that has already left. */
internal class LegacyProjectionOwnershipEpoch {
    private val sequence = java.util.concurrent.atomic.AtomicLong(0L)

    fun current(): Long = sequence.get()
    fun projectionCommitted(): Long = sequence.incrementAndGet()

    /** Stale metadata cleanup is not a new projection claim and must not invalidate Scene admission. */
    fun staleRecordPruned(): Long = sequence.get()
}
