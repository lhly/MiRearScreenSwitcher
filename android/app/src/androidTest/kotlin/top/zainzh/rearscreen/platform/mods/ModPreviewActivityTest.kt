package top.zainzh.rearscreen.platform.mods

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.platform.ComposeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.platform.RearCapabilityCore

@RunWith(AndroidJUnit4::class)
class ModPreviewActivityTest {
    private fun assertOnlyFixtureIdentityRemains(before: ModStoreTestSnapshot, after: ModStoreTestSnapshot, id: String, author: String) {
        // Product uninstall deliberately retains author trust and the publisher binding
        // to prevent another key from taking over this Mod ID. Only those expected rows
        // may persist in the dedicated test-host namespace; all installed/model data is gone.
        assertEquals("fixture install/model/package rows are gone", before.entries, after.entries)
        assertEquals("only this fixture's expected trust may remain", before.trustedAuthors + author, after.trustedAuthors)
        assertEquals("only this fixture's expected publisher binding may remain",
            before.publishedModOwners + (id to author), after.publishedModOwners)
    }
    @Test fun previewUsesNativeRendererWithoutSceneLeaseOrPersistingModel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val archive = instrumentation.context.assets.open("platform/mods/preview-lifecycle.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(archive.inputStream())
        val productionBefore = ModPackageStore.productionSnapshotForTest(context)
        require(ModPackageStore.installed(context, pkg.id) == null) {
            "preview test-host fixture ID must start unused; preserving all pre-existing data"
        }
        val mod = ModPackageStore.install(context, archive,
            ModInstallDecision(pkg.authorFingerprint, setOf("data.battery", "rear.scene")))
        val before = ModPackageStore.model(context, mod.id)
        assertTrue(ModPackageStore.setEnabled(context, mod.id, true).enabled)
        assertFalse(RearCapabilityCore.hasActiveSceneSessions())
        try {
            val scenario = ActivityScenario.launch<ModPreviewActivity>(
                Intent(context, ModPreviewActivity::class.java).putExtra("mod_id", mod.id)
            )
            try {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    assertNotNull(content.getChildAt(0))
                    assertTrue(content.getChildAt(0) is ComposeView)
                    assertFalse(activity.isFinishing)
                }
                assertFalse(RearCapabilityCore.hasActiveSceneSessions())
                assertEquals(before?.revision, ModPackageStore.model(context, mod.id)?.revision)
            } finally {
                scenario.close()
            }
        } finally {
            ModPackageStore.uninstall(context, mod.id)
            assertOnlyFixtureIdentityRemains(productionBefore, ModPackageStore.productionSnapshotForTest(context), mod.id, mod.authorFingerprint)
        }
    }
}
