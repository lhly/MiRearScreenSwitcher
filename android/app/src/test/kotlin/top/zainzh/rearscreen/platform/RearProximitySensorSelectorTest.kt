package top.zainzh.rearscreen.platform

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RearProximitySensorSelectorTest {
    @Test fun productionMonitorUsesAllSensorTypesNotOnlyAndroidProximityType() {
        assertEquals(Sensor.TYPE_ALL, RearProximitySensorSelector.SENSOR_LIST_TYPE)
    }

    @Test fun selectsOemPrivateRearSensorAndPrefersWakeupVariant() {
        val selected = RearProximitySensorSelector.selectIndex(listOf(
            RearProximitySensorCandidate(8, "proximity Proximity Sensor", "android.sensor.proximity", wakeUp = true),
            RearProximitySensorCandidate(33171123, "tcs3760_sec_back Proximity Back Sensor", "xiaomi.sensor.proxmity_back", wakeUp = false),
            RearProximitySensorCandidate(33171123, "tcs3760_sec_back Proximity Back Sensor", "xiaomi.sensor.proxmity_back", wakeUp = true),
            RearProximitySensorCandidate(33171124, "tcs3760_sec_back Proximity Back Sensor Strm", "xiaomi.sensor.proxmity_back.factory", wakeUp = false)
        ))

        assertEquals("custom sensor types must not be lost to TYPE_PROXIMITY filtering", 2, selected)
    }

    @Test fun neverFallsBackToFrontOrFactoryProximitySources() {
        assertNull(RearProximitySensorSelector.selectIndex(listOf(
            RearProximitySensorCandidate(8, "proximity Proximity Sensor", "android.sensor.proximity", wakeUp = true),
            RearProximitySensorCandidate(33171005, "tcs3760 Proximity Sensor Strm", "xiaomi.sensor.proxmity.factory", wakeUp = false),
            RearProximitySensorCandidate(33171124, "tcs3760_sec_back Proximity Back Sensor Strm", "xiaomi.sensor.proxmity_back.factory", wakeUp = false)
        )))
    }
}
