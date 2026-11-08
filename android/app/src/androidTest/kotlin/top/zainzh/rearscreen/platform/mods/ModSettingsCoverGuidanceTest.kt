package top.zainzh.rearscreen.platform.mods

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.R
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/** Uses only the M12 test-host's normal app store and a unique signed fixture. */
@RunWith(AndroidJUnit4::class)
class ModSettingsCoverGuidanceTest {
    @Test fun grantedRearSceneShowsConditionalCoverGuidanceBesideAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val archive = instrumentation.context.assets.open("platform/mods/scene-info.zip").use { it.readBytes() }
        val verified = ModPackageReader.read(ByteArrayInputStream(archive))
        assertEquals("platform.sceneinfo", verified.id)
        assertTrue(verified.requiredCapabilities.any { it.name == "rear.scene" })
        val beforeMods = ModPackageStore.list(context)
        val authorWasTrusted = ModPackageStore.isAuthorTrusted(context, verified.authorFingerprint)
        assertEquals("the isolated host starts without this fixture", null, ModPackageStore.installed(context, verified.id))
        assertTrue("no other test-host Mod shares this fixture author", beforeMods.none { it.authorFingerprint == verified.authorFingerprint })

        val installed = ModPackageStore.install(
            context,
            archive,
            ModInstallDecision(verified.authorFingerprint, verified.requiredCapabilities.map { it.name }.toSet()),
        )
        val scenario: ActivityScenario<ModSettingsActivity>
        try {
            ModPackageStore.setEnabled(context, installed.id, true)
            scenario = ActivityScenario.launch(Intent(context, ModSettingsActivity::class.java))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val note = activity.getString(R.string.mod_scene_cover_behavior_note)
                assertTrue(note.contains("开启遮挡处理后"))
                assertTrue(note.contains("全黑不等于硬件息屏"))
                val root = activity.window.decorView
                assertTrue("management screen has a laid out native surface", root.width > 0 && root.height > 0)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            assertTrue("captured the rendered native settings screen", bitmap.width > 0 && bitmap.height > 0)
            val output = File(context.cacheDir, "mod-settings-cover-guidance-m12.png")
            FileOutputStream(output).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            scenario.close()
        } finally {
            runCatching { ModPackageStore.uninstall(context, installed.id) }
            if (!authorWasTrusted) runCatching { ModPackageStore.revokeAuthorTrust(context, verified.authorFingerprint) }
            assertEquals("normal test-host inventory is restored after fixture teardown", beforeMods, ModPackageStore.list(context))
            assertEquals(authorWasTrusted, ModPackageStore.isAuthorTrusted(context, verified.authorFingerprint))
        }
    }
}
