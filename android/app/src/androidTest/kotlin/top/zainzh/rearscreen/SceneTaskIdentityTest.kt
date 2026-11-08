package top.zainzh.rearscreen

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.os.SystemClock
import org.junit.Assert.assertNotNull
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class SceneTaskIdentityTest {
    private interface FakeTaskManager {
        fun getTasks(maxNum: Int, filterOnlyVisibleRecents: Boolean, keepIntentExtra: Boolean, displayId: Int): List<Any>
    }
    private class FakeTaskInfo(
        @JvmField val taskId: Int,
        @JvmField val displayId: Int,
        @JvmField val baseActivity: ComponentName,
        @JvmField val topActivity: ComponentName,
        private val visible: Boolean = true
    ) {
        fun isVisible(): Boolean = visible
    }
    // Sanitized excerpt captured from `adb -s emulator-5556 shell am stack list`; unrelated task
    // rows are omitted. This preserves the real RootTask/taskId/component syntax without storing a dump.
    private val actualAvdExcerpt = """
        RootTask id=113 bounds=[0,0][1080,2400] displayId=0 userId=0
          taskId=113: dev.remrss.sample/dev.remrss.sample.MainActivity bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{dev.remrss.sample/dev.remrss.sample.MainActivity}
    """.trimIndent()

    // A second display/task row models the same captured syntax for display identity classification.
    private val actualStackFormat = """
        RootTask id=113 bounds=[0,0][1080,2400] displayId=0 userId=0
          taskId=113: dev.remrss.sample/dev.remrss.sample.MainActivity bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{dev.remrss.sample/dev.remrss.sample.MainActivity}
        RootTask id=7 bounds=[0,0][1080,2400] displayId=2 userId=0
          taskId=55948: dev.remrss.sample/dev.remrss.sample.MainActivity bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{dev.remrss.sample/dev.remrss.sample.MainActivity}
          taskId=55949: dev.remrss.sample/dev.remrss.sample.RearSceneActivity bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{dev.remrss.sample/dev.remrss.sample.RearSceneActivity}
    """.trimIndent()

    @Test fun productionTaskSnapshotExecutesActivityTaskManagerBinderQuery() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        val started = SystemClock.elapsedRealtime()
        try {
        val manager = RearSystemBridge.service("activity_task", "android.app.IActivityTaskManager\$Stub")
        val methods = manager.javaClass.methods.filter { it.name == "getTasks" }
        assertTrue("AVD API 36 Binder proxy exposes the display-scoped task query",
            methods.any { it.parameterTypes.size == 4 && it.parameterTypes[0] == Int::class.javaPrimitiveType
                && it.parameterTypes[1] == Boolean::class.javaPrimitiveType
                && it.parameterTypes[2] == Boolean::class.javaPrimitiveType
                && it.parameterTypes[3] == Int::class.javaPrimitiveType })
        val records = RearSystemBridge.runningTaskSnapshot()
        val self = records.singleOrNull { it.taskId == activity.taskId }
        assertNotNull("the real Binder query must return the target's own live task", self)
        val record = requireNotNull(self)
        val expected = ComponentName(context, MainActivity::class.java).flattenToString()
        assertEquals("stable task root/base component", expected, record.baseComponent)
        assertEquals("the foreground target is the current top Activity", expected, record.topComponent)
        assertEquals("task display matches the actual Activity display", activity.display?.displayId ?: 0, record.displayId)
        assertEquals("TaskInfo visibility is read separately from base/top ownership", true, record.visible)
        assertTrue(record.taskId >= 0)
        println("SCENE_TASK_QUERY method=getTasks/4 display=INVALID_DISPLAY callerUid=${android.os.Process.myUid()} " +
            "task=${record.taskId} base=${record.baseComponent} top=${record.topComponent} display=${record.displayId} " +
            "elapsedMs=${SystemClock.elapsedRealtime() - started}")
        } finally {
            instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
        }
    }

    @Test fun modernBinderQueryRequestsAllDisplaysUsingInvalidDisplay() {
        var calledDisplay = Int.MIN_VALUE
        var calledMax = 0
        val fake = Proxy.newProxyInstance(FakeTaskManager::class.java.classLoader,
            arrayOf(FakeTaskManager::class.java)) { _, method, args ->
            assertEquals("getTasks", method.name)
            calledMax = args!![0] as Int
            calledDisplay = args[3] as Int
            listOf(FakeTaskInfo(71, 4, ComponentName("sample", "sample.Scene"), ComponentName("sample", "sample.Child")))
        }
        val records = RearSystemBridge.runningTaskSnapshot(fake)
        assertEquals(1000, calledMax)
        assertEquals("INVALID_DISPLAY queries every display instead of only the default", -1, calledDisplay)
        assertEquals(1, records.size)
        assertEquals("sample/sample.Scene", records.single().baseComponent)
        assertEquals("sample/sample.Child", records.single().topComponent)
        assertEquals(true, records.single().visible)
    }

    @Test fun shellSnapshotRequiresExactTaskComponentAndDisplay() {
        val valid = SceneTaskIdentity.verifyShellSnapshot(actualStackFormat, 0, false, false,
            55949, "dev.remrss.sample/dev.remrss.sample.RearSceneActivity", 2)
        assertEquals(SceneTaskIdentity.Status.VERIFIED, valid.status)
        assertEquals(55949, valid.actual.taskId)
        assertEquals("dev.remrss.sample/dev.remrss.sample.RearSceneActivity", valid.actual.topComponent)

        assertEquals(SceneTaskIdentity.Status.COMPONENT_MISMATCH,
            SceneTaskIdentity.verifyShellSnapshot(actualStackFormat, 0, false, false, 55949,
                "dev.remrss.sample/dev.remrss.sample.MainActivity", 2).status)
        assertEquals(SceneTaskIdentity.Status.DISPLAY_MISMATCH,
            SceneTaskIdentity.verifyShellSnapshot(actualStackFormat, 0, false, false, 55949,
                "dev.remrss.sample/dev.remrss.sample.RearSceneActivity", 0).status)
        assertEquals(SceneTaskIdentity.Status.TASK_NOT_FOUND,
            SceneTaskIdentity.verifyShellSnapshot(actualStackFormat, 0, false, false, 42,
                "dev.remrss.sample/dev.remrss.sample.RearSceneActivity", 2).status)
    }

    @Test fun sanitizedActualAvdOutputParsesWithoutDumpingOtherApplicationTasks() {
        val parsed = SceneTaskIdentity.parseStackList(actualAvdExcerpt)
        assertEquals(1, parsed.size)
        val result = SceneTaskIdentity.verify(parsed, 113,
            "dev.remrss.sample/dev.remrss.sample.MainActivity", 0, "fixture")
        assertTrue(result.verified())
        assertEquals(0, result.actual.displayId)
    }

    @Test fun stableTaskOwnerSurvivesLegalChildOrTemporaryCoverWithoutRevokingLease() {
        val scene = "dev.remrss.sample/dev.remrss.sample.RearSceneActivity"
        val child = "dev.remrss.sample/dev.remrss.sample.HelpActivity"
        val task = listOf(SceneTaskIdentity.Record(55949, 2, scene, child))
        assertTrue(SceneTaskIdentity.verifyOwner(task, 55949, scene, 2, "fixture").verified())
        val ready = SceneTaskIdentity.verifyReady(task, 55949, scene, 2, "fixture")
        assertTrue("top Activity is visibility, not stable task ownership", ready.verified())
        assertEquals("top_activity_changed_visibility_is_separate", ready.detail)
    }

    @Test fun timeoutIsNotMisreportedAsMalformedOrUnsupportedOutput() {
        val timeout = SceneTaskIdentity.verifyShellSnapshot("", -1, true, false, 55949,
            "dev.remrss.sample/dev.remrss.sample.RearSceneActivity", 2)
        assertEquals(SceneTaskIdentity.Status.QUERY_TIMEOUT, timeout.status)
        assertEquals("timeout_3000ms", timeout.detail)

        val malformed = SceneTaskIdentity.verifyShellSnapshot("shell output changed", 0, false, false, 55949,
            "dev.remrss.sample/dev.remrss.sample.RearSceneActivity", 2)
        assertEquals(SceneTaskIdentity.Status.PARSE_ERROR, malformed.status)
        assertFalse(malformed.verified())
    }

    @Test fun realShellTimeoutIsBoundedAndClassifiedSeparately() {
        val started = SystemClock.elapsedRealtime()
        val shell = SceneTaskShellRunner.run("exec sleep 2", 150)
        val elapsed = SystemClock.elapsedRealtime() - started
        assertTrue("shell timeout must remain bounded; elapsed=${elapsed}ms", elapsed < 1500)
        val classified = SceneTaskIdentity.verifyShellSnapshot(shell.output, shell.exitCode, shell.timedOut,
            shell.truncated, 55949, "dev.remrss.sample/dev.remrss.sample.RearSceneActivity", 2)
        assertEquals(SceneTaskIdentity.Status.QUERY_TIMEOUT, classified.status)
    }

    @Test fun shellSnapshotRefusesTruncatedOrNonzeroOutputRatherThanAcceptingPartialIdentity() {
        val truncated = SceneTaskIdentity.verifyShellSnapshot(actualStackFormat, 0, false, true, 55949,
            "dev.remrss.sample/dev.remrss.sample.RearSceneActivity", 2)
        assertEquals(SceneTaskIdentity.Status.QUERY_UNAVAILABLE, truncated.status)
        assertEquals("output_limit_65536", truncated.detail)
        assertEquals(SceneTaskIdentity.Status.QUERY_UNAVAILABLE,
            SceneTaskIdentity.verifyShellSnapshot(actualStackFormat, 1, false, false, 55949,
                "dev.remrss.sample/dev.remrss.sample.RearSceneActivity", 2).status)
    }
}
