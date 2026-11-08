package top.zainzh.rearscreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformService
import top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformCallback
import top.zainzh.rearscreen.platform.ipc.RemoteRearPlatformService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RemoteRearPlatformServiceTest {
    @Test fun exportedBrokerResolvesBinderCallerAndDoesNotReportUnconsentedGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        var remote: IRemoteRearPlatformService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = IRemoteRearPlatformService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        assertTrue(context.bindService(Intent(context, RemoteRearPlatformService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("broker binds", connected.await(5, TimeUnit.SECONDS))
            val service = requireNotNull(remote)
            assertEquals("remrss.platform/1", service.contractVersion)
            val snapshot = JSONObject(service.queryCapabilities(context.packageName))
            assertEquals("remrss.platform/1", snapshot.getString("contract"))
            val rearInfo = JSONObject(service.queryRearInfo(context.packageName))
            assertEquals("remrss.platform/1", rearInfo.getString("contract"))
            val displayInfo = rearInfo.optJSONObject("display")
            assertEquals("display metadata is either complete or explicitly unavailable",
                rearInfo.getBoolean("displayInfoAvailable"), displayInfo != null)
            if (displayInfo != null) {
                val dimensions = displayInfo.getJSONObject("dimensions")
                val logicalPx = dimensions.getJSONObject("logicalDisplayPx")
                assertTrue(logicalPx.getInt("width") > 0)
                assertTrue(logicalPx.getInt("height") > 0)
                val physicalMode = dimensions.optJSONObject("physicalModePx")
                if (physicalMode != null) {
                    assertTrue(physicalMode.getInt("width") > 0)
                    assertTrue(physicalMode.getInt("height") > 0)
                }
                assertTrue(dimensions.getInt("densityDpi") > 0)
                assertTrue(dimensions.getInt("rotation") in 0..3)
                assertTrue(displayInfo.getString("state") in setOf("ON", "OFF", "DOZE", "DOZE_SUSPEND", "VR", "UNKNOWN"))
            }
            val caps = snapshot.getJSONArray("capabilities")
            val scene = (0 until caps.length()).map(caps::getJSONObject).first { it.getString("name") == "rear.scene" }
            assertFalse("availability is not a user consent grant", scene.getBoolean("granted"))
            val region = (0 until caps.length()).map(caps::getJSONObject).first { it.getString("name") == "rear.region" }
            assertEquals("UNSUPPORTED", region.getString("availability"))
            val battery = (0 until caps.length()).map(caps::getJSONObject).first { it.getString("name") == "data.battery" }
            assertFalse("read-only data is not granted before visible consent", battery.getBoolean("granted"))
            var rejected = false
            try { service.queryCapabilities("android") } catch (_: SecurityException) { rejected = true }
            assertTrue("caller cannot name a different UID's package", rejected)
            val callback = object : IRemoteRearPlatformCallback.Stub() { override fun onEvent(eventJson: String) = Unit }
            val unauthorizedData = JSONObject(service.queryData(context.packageName,
                org.json.JSONArray().put("BATTERY_LEVEL_PERCENT").toString(), callback))
            assertFalse("data query must be protected by a stored consent", unauthorizedData.optBoolean("success"))
            assertEquals("E_CAPABILITY_DENIED", unauthorizedData.getString("error"))
            val consent = service.requestDataAuthorization(context.packageName,
                org.json.JSONArray().put("BATTERY_LEVEL_PERCENT").put("BATTERY_CHARGING").toString(), callback)
            assertEquals("consent UI is owned by the host", context.packageName, consent.creatorPackage)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            consent.send(context, 0, null)
            val ui = instrumentation.uiAutomation
            fun consentIsResumed(): Boolean {
                var resumed = false
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).any { it is DataAccessConfirmationActivity }
                }
                return resumed
            }
            for (attempt in 0 until 50) {
                if (consentIsResumed()) break
                Thread.sleep(100)
            }
            ui.takeScreenshot()?.let { bitmap ->
                context.getExternalFilesDir(null)?.let { dir ->
                    File(dir, "round8-data-consent.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            assertTrue("the one-shot request opens the host consent Activity", consentIsResumed())
            val downTime = SystemClock.uptimeMillis()
            ui.injectInputEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 300f, 880f, 0), true)
            ui.injectInputEvent(MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, 300f, 880f, 0), true)
            Thread.sleep(100)
            val stillDenied = JSONObject(service.queryData(context.packageName,
                org.json.JSONArray().put("BATTERY_LEVEL_PERCENT").toString(), callback))
            assertFalse("cancel does not grant data", stillDenied.optBoolean("success"))
            var activationRejected = false
            try {
                service.requestSceneActivation("android", "{}", callback)
            } catch (_: SecurityException) { activationRejected = true }
            assertTrue("scene request cannot choose another package", activationRejected)
            var backendUnavailable = false
            val ownComponent = ComponentName(context, MainActivity::class.java).flattenToString()
            try {
                service.requestSceneActivation(context.packageName, JSONObject().put("component", ownComponent).toString(), callback)
            } catch (error: IllegalStateException) {
                backendUnavailable = error.message?.let {
                    it.contains("E_BACKEND_UNAVAILABLE") || it.contains("E_BACKEND_CONNECTING") ||
                        it.contains("E_BACKEND_REQUIRES_AUTHORIZATION")
                } == true
            }
            assertTrue("an unavailable or still-connecting rear backend must not issue a pretend activation", backendUnavailable)
            service.releaseClient(context.packageName, callback)
        } finally {
            context.unbindService(connection)
        }
    }
}
