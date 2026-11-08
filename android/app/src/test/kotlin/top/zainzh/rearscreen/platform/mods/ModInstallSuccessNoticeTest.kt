package top.zainzh.rearscreen.platform.mods

import org.junit.Assert.assertEquals
import org.junit.Test

class ModInstallSuccessNoticeTest {
    @Test fun firstInstallKeepsDefaultDisabledGuidance() {
        assertEquals("已安装 Counter；默认保持停用，请先审核设置与能力。", modInstallSuccessNotice("Counter", replacing = false, enabled = false))
    }

    @Test fun updateReportsRetainedDataAndActualEnabledState() {
        assertEquals("已更新 Counter；原有配置与数据已保留，当前仍为启用状态。", modInstallSuccessNotice("Counter", replacing = true, enabled = true))
        assertEquals("已更新 Counter；原有配置与数据已保留，当前仍为停用状态。", modInstallSuccessNotice("Counter", replacing = true, enabled = false))
    }
}
