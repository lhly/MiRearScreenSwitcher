import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val versionProperties = Properties().apply {
    rootProject.file("../version.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.tgwgroup.MiRearScreenSwitcher"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        applicationId = "com.tgwgroup.MiRearScreenSwitcher"
        minSdk = 24  // Shizuku需要最低24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        versionCode = (project.findProperty("mrssVersionCode")?.toString() ?: versionProperties.getProperty("versionCode")).toInt()
        versionName = project.findProperty("mrssVersionName")?.toString() ?: versionProperties.getProperty("versionName")
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
            versionNameSuffix = "-native-debug"
            manifestPlaceholders["appLabel"] = "MRSS 原生测试"
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

dependencies {
    implementation("androidx.core:core:1.13.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // Shizuku API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
