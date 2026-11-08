import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val hostPackage = providers.gradleProperty("remrssHostPackage").orElse("top.zainzh.rearscreen").get()

android {
    namespace = "dev.remrss.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.remrss.sample.consumer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "REM_RSS_HOST_PACKAGE", "\"$hostPackage\"")
        manifestPlaceholders["remrssHostPackage"] = hostPackage
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        getByName("release") {
            // Sample signing is independent. Never use the ReMRSS host's private key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("11")
    }
}

dependencies {
    implementation(files("libs/platform-sdk-release.aar"))
    testImplementation("junit:junit:4.13.2")
}
