package top.zainzh.rearscreen

/** Presentation pairing only. Business route ownership remains in SettingsController. */
internal data class NavigationRoutePair(val parentRoute: Int, val childRoute: Int)

internal data class NavigationMotionSettle(val progress: Float, val velocity: Float)

/** Retarget an in-flight presentation from the last rendered frame and its measured velocity. */
internal class MotionVelocityTracker {
    private var previousProgress = Float.NaN
    private var previousTimeMillis = 0L
    var velocity: Float = 0f
        private set

    fun sample(progress: Float, frameTimeMillis: Long): Float {
        if (previousProgress.isFinite() && frameTimeMillis > previousTimeMillis) {
            velocity = (progress - previousProgress) * 1000f / (frameTimeMillis - previousTimeMillis)
        }
        previousProgress = progress
        previousTimeMillis = frameTimeMillis
        return velocity
    }
}

/**
 * Tracks only the seekable progress of one predictive-back preview. It never commits a route.
 * Progress is normalized from the parent (0) to its child (1), regardless of gesture direction.
 */
internal class NavigationMotion {
    private var previewing = false
    private var originProgress = 1f
    private var currentProgress = 1f
    private var previousTimeMillis = 0L
    private var routeVelocity = 0f

    val isPreviewing: Boolean get() = previewing
    val progress: Float get() = currentProgress

    fun beginBackPreview(currentRouteProgress: Float): Float {
        originProgress = currentRouteProgress.coerceIn(0f, 1f)
        currentProgress = originProgress
        previousTimeMillis = 0L
        routeVelocity = 0f
        previewing = true
        return currentProgress
    }

    fun updateBackProgress(backProgress: Float, frameTimeMillis: Long): Float? {
        if (!previewing) return null
        val nextBackProgress = backProgress.coerceIn(0f, 1f)
        val nextProgress = originProgress * (1f - nextBackProgress)
        val elapsed = frameTimeMillis - previousTimeMillis
        if (previousTimeMillis > 0L && elapsed > 0L) {
            routeVelocity = (nextProgress - currentProgress) * 1000f / elapsed
        }
        currentProgress = nextProgress
        previousTimeMillis = frameTimeMillis
        return currentProgress
    }

    fun cancelBackPreview(): NavigationMotionSettle? {
        if (!previewing) return null
        previewing = false
        return NavigationMotionSettle(originProgress, routeVelocity)
    }

    fun commitBackPreview(): NavigationMotionSettle? {
        if (!previewing) return null
        previewing = false
        return NavigationMotionSettle(0f, routeVelocity)
    }

    companion object {
        fun parentRoute(route: Int): Int? = when (route) {
            in 10..13 -> 0
            in 20..21, 100 -> 1
            30 -> 2
            40 -> 3
            else -> null
        }

        fun pairBetween(first: Int, second: Int): NavigationRoutePair? {
            val firstParent = parentRoute(first)
            val secondParent = parentRoute(second)
            return when {
                firstParent != null && firstParent == second -> NavigationRoutePair(second, first)
                secondParent != null && secondParent == first -> NavigationRoutePair(first, second)
                else -> null
            }
        }

        fun routeForTab(route: Int): Int = parentRoute(route) ?: route.coerceIn(0, 3)

        fun routeProgressForBack(originProgress: Float, backProgress: Float): Float =
            originProgress.coerceIn(0f, 1f) * (1f - backProgress.coerceIn(0f, 1f))

    fun clampProgress(progress: Float): Float = progress.coerceIn(0f, 1f)
    }
}

internal fun tabProgressAt(
    x: Float,
    width: Float,
    edge: Float,
    tabCount: Int,
    isRtl: Boolean,
): Float {
    if (tabCount <= 1 || width <= 0f) return 0f
    val tabWidth = ((width - edge * 2f) / tabCount).coerceAtLeast(1f)
    val logicalX = if (isRtl) width - x else x
    val progress = (logicalX - edge) / tabWidth - .5f
    // Keep the pointer coordinate unbounded until its grab offset is applied. Clamping here
    // creates a dead zone when the indicator is grabbed away from a tab's center near an edge.
    return progress
}

internal fun dragProgressWithGrabOffset(pointerProgress: Float, grabOffset: Float, tabCount: Int): Float =
    (pointerProgress + grabOffset).coerceIn(0f, (tabCount - 1).coerceAtLeast(0).toFloat())
