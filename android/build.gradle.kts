allprojects {
    repositories {
        google()
        mavenCentral()
        // Shizuku仓库
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.buildDir = file("../build")
subprojects {
    project.buildDir = file("${rootProject.buildDir}/${project.name}")
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
