package top.zainzh.rearscreen.platform

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneRecoveryRearPowerTest {
    @Test fun readsOnlyTheCurrentSelectedRearState() {
        assertEquals(SceneRecoveryRearPower.ON, sceneRecoveryRearPower(state(Display.STATE_ON), 1))
        assertEquals(SceneRecoveryRearPower.OFF, sceneRecoveryRearPower(state(Display.STATE_DOZE_SUSPEND), 1))
        assertEquals(SceneRecoveryRearPower.REPLACED, sceneRecoveryRearPower(state(Display.STATE_ON, selectedId = 2), 1))
        assertEquals(SceneRecoveryRearPower.UNKNOWN, sceneRecoveryRearPower(state(Display.STATE_UNKNOWN), 1))
    }

    @Test fun unknownOrMismatchedIdentityNeverMasqueradesAsOn() {
        assertEquals(SceneRecoveryRearPower.UNKNOWN, sceneRecoveryRearPower(null, 1))
        assertEquals(SceneRecoveryRearPower.UNKNOWN, sceneRecoveryRearPower("{}", 1))
        assertEquals(SceneRecoveryRearPower.UNKNOWN, sceneRecoveryRearPower(
            "{\"selectedId\":1,\"selectedUnique\":\"\",\"displays\":[]}", 1))
        assertEquals(SceneRecoveryRearPower.UNKNOWN, sceneRecoveryRearPower(
            "{\"selectedId\":1,\"selectedUnique\":\"rear\",\"displays\":[{\"id\":1,\"unique\":\"other\",\"state\":2}]}", 1))
    }

    private fun state(displayState: Int, selectedId: Int = 1): String =
        "{\"selectedId\":$selectedId,\"selectedUnique\":\"panel-$selectedId\",\"displays\":[" +
            "{\"id\":$selectedId,\"unique\":\"panel-$selectedId\",\"state\":$displayState}]}"
}
