package top.zainzh.rearscreen.platform

import android.content.pm.ActivityInfo

/** Only components that declare a dedicated, non-reparentable standard task can be Scene targets. */
object SceneActivityLaunchPolicy {
    @JvmStatic fun allows(info: ActivityInfo, packageName: String): Boolean =
        info.enabled && info.applicationInfo?.enabled != false &&
            info.launchMode == ActivityInfo.LAUNCH_MULTIPLE &&
            info.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_NONE &&
            info.flags and ActivityInfo.FLAG_ALLOW_TASK_REPARENTING == 0 &&
            !info.taskAffinity.isNullOrBlank() && info.taskAffinity != packageName
}
