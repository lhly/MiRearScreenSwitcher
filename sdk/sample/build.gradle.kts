plugins {
    id("com.android.application")
}

android {
    namespace = "dev.remrss.sample"
    compileSdk = 37
    buildFeatures { buildConfig = true }
    val rootValidation = providers.gradleProperty("mrssRootValidation").orNull == "true"
    defaultConfig {
        applicationId = "dev.remrss.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }
    buildTypes {
        getByName("debug") {
            val hostPackage = if (rootValidation) "top.zainzh.rearscreen.rootdebug" else "top.zainzh.rearscreen.debug"
            buildConfigField("String", "REM_RSS_HOST_PACKAGE", "\"$hostPackage\"")
            manifestPlaceholders["remrssHostPackage"] = hostPackage
        }
        getByName("release") {
            buildConfigField("String", "REM_RSS_HOST_PACKAGE", "\"top.zainzh.rearscreen\"")
            manifestPlaceholders["remrssHostPackage"] = "top.zainzh.rearscreen"
            // The independent SDK consumer never receives the Host's private Release key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":platform-sdk"))
    testImplementation("junit:junit:4.13.2")
}
