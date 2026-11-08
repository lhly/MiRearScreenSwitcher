package top.zainzh.rearscreen.platform.mods

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModDebugReloadStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: ModPackageStoreTestSession
    private lateinit var productionBefore: ModStoreTestSnapshot

    @Before fun setup() {
        productionBefore = ModPackageStore.productionSnapshotForTest(context)
        store = ModPackageStoreTestSession.create(context, "debug-reload-${System.nanoTime()}")
    }

    @After fun teardown() {
        store.close()
        assertEquals(productionBefore, ModPackageStore.productionSnapshotForTest(context))
    }

    @Test fun reloadKeepsModelGrantAndNormalVersionCodeWhileRejectingOtherDataVersions() {
        val archive = InstrumentationRegistry.getInstrumentation().context.assets.open("platform/mods/counter-transaction-v1.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(archive.inputStream())
        val installed = store.install(archive, ModInstallDecision(pkg.authorFingerprint, pkg.requiredCapabilities.map { it.name }.toSet()))
        store.setEnabled(installed.id, true)
        store.commitInitialState(installed.id, JSONObject().put("count", 17))
        val original = requireNotNull(store.model(installed.id))
        val reloaded = ModPackageStore.debugReload(context, archive, store.storage)
        assertEquals(installed.versionCode, reloaded.versionCode)
        assertEquals(installed.dataVersion, reloaded.dataVersion)
        assertTrue(reloaded.enabled)
        assertEquals(original.revision, store.model(installed.id)?.revision)
        assertEquals(original.state?.getInt("count"), store.model(installed.id)?.state?.getInt("count"))
        assertEquals(installed.grantedCapabilities, reloaded.grantedCapabilities)

        val incompatible = InstrumentationRegistry.getInstrumentation().context.assets.open("platform/mods/counter-transaction-v2-invalid-migration.zip").use { it.readBytes() }
        val failure = assertThrows(IllegalArgumentException::class.java) { ModPackageStore.debugReload(context, incompatible, store.storage) }
        assertTrue(failure.message?.contains("versionCode") == true || failure.message?.contains("dataVersion") == true)
        assertEquals(original.state?.getInt("count"), store.model(installed.id)?.state?.getInt("count"))
        assertEquals(pkg.versionCode, store.installed(installed.id)?.versionCode)
    }
}
