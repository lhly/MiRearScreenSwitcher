package top.zainzh.rearscreen.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformService
import top.zainzh.rearscreen.platform.ipc.RearDisplayDimensions
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RearInfoQueryTest {
    @Test fun physicalModeAndLogicalOverrideRemainDistinctAndRotationDoesNotRewriteMode() {
        // Counterexample to getRealMetrics-as-physical: a logical override is 1200x1500
        // while the current physical mode raster is 1768x2208, with a quarter-turn rotation.
        val dimensions = RearDisplayDimensions(1768, 2208, 1200, 1500, 420, 1)
        val encoded = dimensions.toJson()
        assertEquals(1768, encoded.getJSONObject("physicalModePx").getInt("width"))
        assertEquals(2208, encoded.getJSONObject("physicalModePx").getInt("height"))
        assertEquals(1200, encoded.getJSONObject("logicalDisplayPx").getInt("width"))
        assertEquals(1500, encoded.getJSONObject("logicalDisplayPx").getInt("height"))
        assertEquals(1, encoded.getInt("rotation"))

        assertTrue("logical override remains distinct from the mode raster",
            encoded.getJSONObject("physicalModePx").getInt("width") !=
                encoded.getJSONObject("logicalDisplayPx").getInt("width"))
    }

    @Test fun unavailablePhysicalModeDoesNotBorrowLogicalDimensions() {
        val encoded = RearDisplayDimensions(null, null, 960, 540, 320, 0).toJson()
        assertTrue(encoded.isNull("physicalModePx"))
        assertEquals(960, encoded.getJSONObject("logicalDisplayPx").getInt("width"))
        assertEquals(540, encoded.getJSONObject("logicalDisplayPx").getInt("height"))
    }

    @Test fun nonNullDisplayFixtureReadsDensityAndRotationFromNestedDimensions() {
        val panel = JSONObject().put("displayId", 2)
            .put("dimensions", RearDisplayDimensions(1768, 2208, 1200, 1500, 420, 1).toJson())
            .put("state", "ON")
        assertDisplayDimensions(panel, requireExactFixture = true)
    }

    @Test fun rearInfoReturnsPanelUnitsAndExplicitMetadataAvailability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        var service: IRemoteRearPlatformService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IRemoteRearPlatformService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
        }
        assertTrue(context.bindService(Intent(context, RemoteRearPlatformService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("host broker binds", connected.await(5, TimeUnit.SECONDS))
            val info = JSONObject(requireNotNull(service).queryRearInfo(context.packageName))
            assertEquals("remrss.platform/1", info.getString("contract"))
            assertTrue(info.has("deviceAvailable"))
            assertTrue(info.has("backendConnected"))
            val panelInfoAvailable = info.getBoolean("displayInfoAvailable")
            val panel = info.optJSONObject("display")
            if (!panelInfoAvailable) {
                assertNull("unreadable panel metadata must stay explicitly null", panel)
            } else {
                assertNotNull(panel)
                assertDisplayDimensions(requireNotNull(panel))
            }
            println("REAR_INFO deviceAvailable=${info.getBoolean("deviceAvailable")} backendConnected=${info.getBoolean("backendConnected")} panelInfoAvailable=$panelInfoAvailable")
        } finally {
            context.unbindService(connection)
        }
    }

    private fun assertDisplayDimensions(panel: JSONObject, requireExactFixture: Boolean = false) {
        val dimensions = panel.getJSONObject("dimensions")
        val logicalPx = dimensions.getJSONObject("logicalDisplayPx")
        val physicalMode = dimensions.optJSONObject("physicalModePx")
        assertTrue("logical display width is px", logicalPx.getInt("width") > 0)
        assertTrue("logical display height is px", logicalPx.getInt("height") > 0)
        if (physicalMode != null) {
            assertTrue("physical mode width is px", physicalMode.getInt("width") > 0)
            assertTrue("physical mode height is px", physicalMode.getInt("height") > 0)
        }
        assertTrue("logical density is explicitly DPI", dimensions.getInt("densityDpi") > 0)
        assertTrue("rotation uses display quarter-turn constants", dimensions.getInt("rotation") in 0..3)
        if (requireExactFixture) {
            val exactMode = requireNotNull(physicalMode)
            assertEquals(1768, exactMode.getInt("width"))
            assertEquals(2208, exactMode.getInt("height"))
            assertEquals(1200, logicalPx.getInt("width"))
            assertEquals(1500, logicalPx.getInt("height"))
            assertEquals(420, dimensions.getInt("densityDpi"))
            assertEquals(1, dimensions.getInt("rotation"))
        }
        assertTrue("power state is named", panel.getString("state") in setOf("ON", "OFF", "DOZE", "DOZE_SUSPEND", "VR", "UNKNOWN"))
    }
}
