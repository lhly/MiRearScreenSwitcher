package top.zainzh.rearscreen

import android.content.pm.ActivityInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.platform.SceneActivityLaunchPolicy

@RunWith(AndroidJUnit4::class)
class SceneActivityLaunchPolicyTest {
    private fun activityInfo(launchMode: Int = ActivityInfo.LAUNCH_MULTIPLE, affinity: String? = "example.scoreboard.scene", flags: Int = 0) =
        ActivityInfo().apply {
            enabled = true
            this.launchMode = launchMode
            documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_NONE
            taskAffinity = affinity
            this.flags = flags
        }

    @Test fun onlyDedicatedStandardNonReparentableNonDocumentActivityCanOwnSceneTask() {
        assertTrue(SceneActivityLaunchPolicy.allows(activityInfo(), "example.scoreboard"))
        assertFalse("singleTask can move the existing primary-screen task", SceneActivityLaunchPolicy.allows(
            activityInfo(launchMode = ActivityInfo.LAUNCH_SINGLE_TASK), "example.scoreboard"))
        assertFalse(SceneActivityLaunchPolicy.allows(
            activityInfo(launchMode = ActivityInfo.LAUNCH_SINGLE_INSTANCE), "example.scoreboard"))
        assertFalse("default package affinity is not isolated", SceneActivityLaunchPolicy.allows(
            activityInfo(affinity = "example.scoreboard"), "example.scoreboard"))
        assertFalse("reparenting can move user task later", SceneActivityLaunchPolicy.allows(
            activityInfo(flags = ActivityInfo.FLAG_ALLOW_TASK_REPARENTING), "example.scoreboard"))
        assertFalse("document tasks have separate reuse semantics", SceneActivityLaunchPolicy.allows(
            activityInfo().apply { documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_ALWAYS }, "example.scoreboard"))
    }
}
