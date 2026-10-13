import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.tgwgroup.MiRearScreenSwitcher"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        applicationId = "com.tgwgroup.MiRearScreenSwitcher"
        minSdk = 24  // Shizuku需要最低24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        manifestPlaceholders["appLabel"] = "MRSS"
        manifestPlaceholders["mrssScheme"] = "mrss"
        buildConfigField("String", "URI_SCHEME", "\"mrss\"")
    }
    
    // Shizuku需要
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    
    lint {
        disable.add("Instantiatable")
    }

    signingConfigs {
        // 仅当 key.properties 存在时才创建 release 签名配置，避免空值强制转换报错
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = keystoreProperties["storeFile"]?.let { file(it) }
                storePassword = keystoreProperties["storePassword"] as String
                storeType = keystoreProperties.getProperty("storeType", "JKS")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-hyperos4-fix"
            manifestPlaceholders["appLabel"] = "MRSS 修复测试"
            manifestPlaceholders["mrssScheme"] = "mrss-debug"
            buildConfigField("String", "URI_SCHEME", "\"mrss-debug\"")
        }
        release {
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            // A local suffix allows a personal signing key without replacing the author's app.
            applicationIdSuffix = keystoreProperties.getProperty("releaseApplicationIdSuffix", "")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // V20: NDK and CMake configuration
    
}

val validateReleaseSigning by tasks.registering {
    doLast {
        check(keystorePropertiesFile.exists()) {
            "Release signing is not configured. Create android/key.properties with your release keystore."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

flutter {
    source = "../.."
}

dependencies {
    // Shizuku API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
