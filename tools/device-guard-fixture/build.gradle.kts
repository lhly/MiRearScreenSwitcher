apply(from = "../../gradle/device-task-guard.gradle.kts")

fun marker(name: String) {
    val path = layout.buildDirectory.file("guard-markers/$name").get().asFile
    path.parentFile.mkdirs()
    path.writeText("ran")
}

tasks.register("connectedCheck") { dependsOn(":device:connectedCheck") }
tasks.register("deviceCheck") { dependsOn(":device:deviceCheck") }
tasks.register("lint") {
    dependsOn(":device:lintDebug", ":device:lintAnalyzeDebugAndroidTest")
    doLast { marker("root-lint") }
}
tasks.register("assembleDebug") { doLast { marker("root-assemble") } }
tasks.register("compileDebugKotlin") { doLast { marker("root-compile") } }

tasks.register("clearGuardMarkers") {
    doLast {
        project.delete(layout.buildDirectory.dir("guard-markers"))
        project.delete(project(":device").layout.buildDirectory.dir("guard-markers"))
    }
}
