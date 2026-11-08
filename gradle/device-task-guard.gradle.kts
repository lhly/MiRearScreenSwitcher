// Shared by the Android build and the no-Android-plugin fixture.
val deviceTaskTypes = setOf(
    "DeviceProviderInstrumentTestTask",
    "ManagedDeviceInstrumentationTestTask",
    "ManagedDeviceTestTask",
    "ManagedDeviceInstrumentationTestSetupTask",
    "ManagedDeviceSetupTask",
    "ManagedDeviceCleanTask",
)

allprojects {
    tasks.configureEach {
        val lowerName = name.lowercase()
        val typeNames = generateSequence(javaClass as Class<*>?) { it.superclass }
            .map { it.simpleName }
            .toSet()
        val isInstallOrUninstall = lowerName.startsWith("install") || lowerName.startsWith("uninstall")
        val isConnectedTest = lowerName.startsWith("connected")
        val isManagedDeviceLifecycle = typeNames.any { it in deviceTaskTypes } || lowerName == "cleanmanageddevices"
        val isManagedInstrumentation = lowerName.endsWith("androidtest") &&
            !lowerName.startsWith("lint") &&
            !lowerName.startsWith("assemble") &&
            !lowerName.startsWith("package") &&
            !lowerName.startsWith("check") &&
            !lowerName.startsWith("validate")
        val isDeviceCheck = lowerName == "devicecheck" || lowerName == "deviceandroidtest"

        if (isInstallOrUninstall || isConnectedTest || isManagedDeviceLifecycle || isManagedInstrumentation || isDeviceCheck) {
            doFirst {
                throw GradleException(
                    "拒绝直接执行设备任务 '$path'。请先构建 APK，再使用 tools/guarded_device.py 指定私有 ADB server 与唯一目标。"
                )
            }
        }
    }
}
