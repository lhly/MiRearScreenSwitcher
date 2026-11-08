package top.zainzh.rearscreen.platform.mods

import androidx.test.ext.junit.runners.AndroidJUnit4
import top.zainzh.rearscreen.platform.data.DataField
import top.zainzh.rearscreen.platform.data.DataSnapshot
import top.zainzh.rearscreen.platform.data.DataValue
import androidx.test.platform.app.InstrumentationRegistry
import top.zainzh.rearscreen.platform.RearCapabilityCore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModHostDataSnapshotTest {
    private fun assertOnlyFixtureIdentityRemains(before: ModStoreTestSnapshot, after: ModStoreTestSnapshot, id: String, author: String) {
        // Trust/publisher identity is intentionally durable after uninstall. The target
        // appId is the isolated test host, and no installed/model rows may remain.
        assertEquals("fixture install/model/package rows are gone", before.entries, after.entries)
        assertEquals("only this fixture's expected trust may remain", before.trustedAuthors + author, after.trustedAuthors)
        assertEquals("only this fixture's expected publisher binding may remain",
            before.publishedModOwners + (id to author), after.publishedModOwners)
    }
    @Test fun closedLeaseEpochRejectsCallbacksAlreadyQueuedForMain() {
        val epochs = ModDataSubscriptionEpoch()
        val first = epochs.start()
        assertTrue(epochs.isCurrent(first))
        epochs.stop()
        assertFalse(epochs.isCurrent(first))
        val second = epochs.start()
        assertTrue(epochs.isCurrent(second))
        assertFalse("an old queued callback must not cross into a later visible lease", epochs.isCurrent(first))
    }

    @Test fun batteryAndMetricsConvertOnlyProviderFieldsToStableBindings() {
        val battery = ModHostDataSnapshot.toJson(DataSnapshot(mapOf(
            DataField.BATTERY_LEVEL_PERCENT to DataValue.Number(76.0, "percent", 10L),
            DataField.BATTERY_CHARGING to DataValue.Flag(true, 10L)
        ), 10L))
        assertEquals(76.0, battery.getJSONObject("battery").getDouble("level"), 0.0)
        assertEquals(100, battery.getJSONObject("battery").getInt("scale"))
        assertTrue(battery.getJSONObject("battery").getBoolean("charging"))
        assertFalse(battery.has("systemMetrics"))

        val metrics = ModHostDataSnapshot.toJson(DataSnapshot(mapOf(
            DataField.SYSTEM_CPU_UTILIZATION_PERCENT to DataValue.Number(12.5, "percent", 20L),
            DataField.SYSTEM_MEMORY_AVAILABLE_BYTES to DataValue.Number(1024.0, "byte", 20L),
            DataField.SYSTEM_MEMORY_TOTAL_BYTES to DataValue.Number(2048.0, "byte", 20L),
            DataField.SYSTEM_GPU_UTILIZATION_PERCENT to DataValue.Unavailable(DataValue.Reason.UNSUPPORTED, 20L)
        ), 20L))
        val values = metrics.getJSONObject("systemMetrics")
        assertEquals(12.5, values.getJSONObject("cpu").getDouble("utilizationPercent"), 0.0)
        assertEquals(1024.0, values.getJSONObject("memory").getDouble("availableBytes"), 0.0)
        assertFalse(values.has("gpu"))
    }

    @Test fun emptyCoreSnapshotClearsPreviouslyVisibleBindings() {
        val empty = ModHostDataSnapshot.toJson(DataSnapshot(emptyMap(), 30L))
        assertFalse(empty.has("battery"))
        assertFalse(empty.has("systemMetrics"))
    }

    @Test fun requiredBatteryRevokeImmediatelyClearsTheActiveBinding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val archive = instrumentation.context.assets.open("platform/mods/preview-lifecycle.zip").use { it.readBytes() }
        val pkg = ModPackageReader.read(archive.inputStream())
        val productionBefore = ModPackageStore.productionSnapshotForTest(context)
        require(ModPackageStore.installed(context, pkg.id) == null) {
            "data test-host fixture ID must start unused; preserving all pre-existing data"
        }
        val installed = ModPackageStore.install(context, archive,
            ModInstallDecision(pkg.authorFingerprint, setOf("data.battery", "rear.scene")))
        ModPackageStore.setEnabled(context, installed.id, true)
        val core = RearCapabilityCore.get(context)
        val owner = requireNotNull(core.modDataOwner(installed.id))
        val delivered = CopyOnWriteArrayList<org.json.JSONObject>()
        val firstUpdate = CountDownLatch(1)
        val revokedUpdate = CountDownLatch(1)
        var subscription: java.io.Closeable? = null
        try {
            subscription = requireNotNull(ModHostDataSnapshot.observe(core, owner, ModHostDataSnapshot.fieldsFor(pkg)) {
                delivered += it
                if (delivered.size == 1) firstUpdate.countDown() else revokedUpdate.countDown()
            })
            assertTrue("initial authorized snapshot", firstUpdate.await(5, TimeUnit.SECONDS))
            assertTrue(delivered.first().has("battery"))
            ModPackageStore.setCapabilityGrant(context, installed.id, "data.battery", 1, false)
            assertEquals(false, ModPackageStore.installed(context, installed.id)?.enabled)
            assertTrue("revocation clears without waiting for another battery broadcast", revokedUpdate.await(2, TimeUnit.SECONDS))
            assertFalse(delivered.last().has("battery"))
        } finally {
            subscription?.close()
            ModPackageStore.uninstall(context, installed.id)
            assertOnlyFixtureIdentityRemains(productionBefore, ModPackageStore.productionSnapshotForTest(context), installed.id, installed.authorFingerprint)
        }
    }
}
