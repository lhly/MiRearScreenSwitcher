package top.zainzh.rearscreen

import android.app.Activity
import androidx.activity.ComponentActivity

/** Window-level transitions for the four self-contained settings screens. */
internal object SharedActivityTransitions {
    internal data class Profile(
        val openEnter: Int,
        val openExit: Int,
        val closeEnter: Int,
        val closeExit: Int,
        val backgroundColor: Int,
    )

    private val profile = Profile(
        openEnter = R.anim.miuix_activity_open_enter,
        openExit = R.anim.miuix_activity_open_exit,
        closeEnter = R.anim.miuix_activity_close_enter,
        closeExit = R.anim.miuix_activity_close_exit,
        backgroundColor = R.color.remrss_background,
    )

    private val eligibleActivities = setOf(
        RearScreenshotActivity::class.java,
        TutorialActivity::class.java,
        DensityCompatibilityActivity::class.java,
        PermissionChecksActivity::class.java,
    )

    /** Exact class matching keeps modules, permission launches, and transparent hosts untouched. */
    internal fun profileFor(activityClass: Class<*>): Profile? =
        profile.takeIf { activityClass in eligibleActivities }

    fun apply(activity: ComponentActivity) {
        val transition = profileFor(activity.javaClass) ?: return
        val background = activity.getColor(transition.backgroundColor)
        activity.overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_OPEN,
            transition.openEnter,
            transition.openExit,
            background,
        )
        activity.overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            transition.closeEnter,
            transition.closeExit,
            background,
        )
    }
}
