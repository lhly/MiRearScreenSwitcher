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
    private const val LAUNCHER_DISABLED = "physical_projection_launcher_disabled"
    private const val GENERATION = "physical_projection_generation"
    private const val GENERATION_SEQUENCE = "physical_projection_generation_sequence"
    private val lock = Any()
    private val ownershipEpoch = LegacyProjectionOwnershipEpoch()

    fun epoch(): Long = ownershipEpoch.current()

    fun noteProjectionStarted() { synchronized(lock) { ownershipEpoch.projectionCommitted() } }

    fun recordPhysicalProjection(context: Context, taskId: Int, packageName: String, displayId: Int) {
        require(taskId > 0 && packageName.isNotBlank() && displayId > 0)
        val physicalIdentity = runCatching { RearDisplay.identity() }.getOrDefault("")
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val generation = prefs.getLong(GENERATION_SEQUENCE, 0L) + 1L
            prefs.edit()
                .putLong(GENERATION_SEQUENCE, generation)
                .putLong(GENERATION, generation)
                .putInt(TASK, taskId)
                .putString(PACKAGE, packageName)
                .putInt(DISPLAY, displayId)
                .putString(DISPLAY_IDENTITY, physicalIdentity)
                .putBoolean(LAUNCHER_DISABLED, false)
                .apply()
            ownershipEpoch.projectionCommitted()
        }
    }

    @JvmStatic fun physicalProjectionGeneration(context: Context, taskId: Int, displayId: Int): Long =
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (taskId <= 0 || prefs.getInt(TASK, -1) != taskId || prefs.getInt(DISPLAY, -1) != displayId)
                return@synchronized 0L
            val current = prefs.getLong(GENERATION, 0L)
            if (current > 0L) return@synchronized current
            val migrated = prefs.getLong(GENERATION_SEQUENCE, 0L) + 1L
            if (!prefs.edit().putLong(GENERATION_SEQUENCE, migrated).putLong(GENERATION, migrated).commit())
                return@synchronized 0L
            migrated
        }

    /** Returns the exact committed owner; retains a launcher cleanup record after its task leaves. */
    @JvmStatic fun physicalProjectionTask(context: Context, service: ITaskService, selectedRearDisplay: Int): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val taskId = prefs.getInt(TASK, -1)
        val packageName = prefs.getString(PACKAGE, null) ?: return null
        val displayId = prefs.getInt(DISPLAY, -1)
        val physicalIdentity = prefs.getString(DISPLAY_IDENTITY, null)
        val currentIdentity = runCatching { RearDisplay.identity() }.getOrNull()
        if (taskId <= 0 || displayId != selectedRearDisplay || physicalIdentity == null ||
            physicalIdentity.isNotBlank() && currentIdentity != physicalIdentity) {
            clearIfCurrent(context, taskId)
            return null
        }
        val stillOwned = try {
            service.isTaskOnDisplay(taskId, displayId) && service.getPackageNameFromTaskId(taskId) == packageName
        } catch (_: Exception) { null }
        if (stillOwned == null) return null
        if (!stillOwned && !prefs.getBoolean(LAUNCHER_DISABLED, false)) {
            clearIfCurrent(context, taskId)
            return null
        }
        return "$packageName:$taskId"
    }

    @JvmStatic fun physicalProjectionLauncherDisabled(context: Context, taskId: Int, displayId: Int): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return taskId > 0 && prefs.getInt(TASK, -1) == taskId && prefs.getInt(DISPLAY, -1) == displayId &&
            prefs.getBoolean(LAUNCHER_DISABLED, false)
    }

    @JvmStatic fun markPhysicalLauncherDisabled(context: Context, taskId: Int, displayId: Int): Boolean =
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (taskId <= 0 || prefs.getInt(TASK, -1) != taskId || prefs.getInt(DISPLAY, -1) != displayId)
                return@synchronized false
            prefs.edit().putBoolean(LAUNCHER_DISABLED, true).commit()
        }

    @JvmStatic fun clearPhysicalProjection(context: Context, taskId: Int, displayId: Int, generation: Long): Boolean =
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (generation <= 0L || prefs.getInt(TASK, -1) != taskId || prefs.getInt(DISPLAY, -1) != displayId ||
                prefs.getLong(GENERATION, 0L) != generation) return@synchronized false
            prefs.edit().remove(TASK).remove(PACKAGE).remove(DISPLAY).remove(DISPLAY_IDENTITY)
                .remove(LAUNCHER_DISABLED).remove(GENERATION).apply()
            true
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
        if (!stillOwned && !prefs.getBoolean(LAUNCHER_DISABLED, false)) clearIfCurrent(context, taskId)
        return stillOwned
    }

    private fun clearIfCurrent(context: Context, observedTaskId: Int) {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(TASK, -1) != observedTaskId) return
            prefs.edit().remove(TASK).remove(PACKAGE).remove(DISPLAY).remove(DISPLAY_IDENTITY)
                .remove(LAUNCHER_DISABLED).remove(GENERATION).apply()
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
