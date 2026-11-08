import java.util.Properties

plugins {
    id("com.android.library")
}

val sdkVersionProperties = Properties().apply {
    file("version.properties").inputStream().use { load(it) }
}
val sdkVersion = requireNotNull(sdkVersionProperties.getProperty("sdkVersion")) {
    "sdk/android/version.properties must define sdkVersion"
}

android {
    namespace = "dev.remrss.platform.sdk"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
