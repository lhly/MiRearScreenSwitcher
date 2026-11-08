import org.gradle.api.DefaultTask

abstract class ManagedDeviceInstrumentationTestSetupTask : DefaultTask()
abstract class ManagedDeviceSetupTask : DefaultTask()
abstract class ManagedDeviceInstrumentationTestTask : DefaultTask()
abstract class DeviceProviderInstrumentTestTask : DefaultTask()
abstract class ManagedDeviceCleanTask : DefaultTask()

fun marker(name: String) {
    val path = layout.buildDirectory.file("guard-markers/$name").get().asFile
    path.parentFile.mkdirs()
    path.writeText("ran")
}

tasks.register<ManagedDeviceInstrumentationTestSetupTask>("pixel2Api35Setup") {
    doLast { marker("setup") }
}
tasks.register<ManagedDeviceInstrumentationTestTask>("pixel2Api35DebugAndroidTest") {
    dependsOn("pixel2Api35Setup")
    doLast { marker("managed-test") }
}
tasks.register<DeviceProviderInstrumentTestTask>("connectedDebugAndroidTest") {
    doLast { marker("connected-test") }
}
tasks.register("connectedCheck") { dependsOn("connectedDebugAndroidTest") }
tasks.register<ManagedDeviceCleanTask>("cleanManagedDevices") {
    doLast { marker("clean-managed") }
}
tasks.register("deviceCheck") { dependsOn("pixel2Api35DebugAndroidTest") }
tasks.register<ManagedDeviceSetupTask>("customDeviceSetup") {
    doLast { marker("custom-setup") }
}
tasks.register("installDebug") { doLast { marker("install") } }
tasks.register("uninstallAll") { doLast { marker("uninstall") } }
tasks.register("lintDebug") { doLast { marker("lint-debug") } }
tasks.register("lintAnalyzeDebugAndroidTest") { doLast { marker("lint-androidtest") } }
