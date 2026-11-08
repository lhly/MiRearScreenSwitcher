plugins {
    id("com.android.library")
}

android {
    namespace = "dev.remrss.platform.sdk"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    buildFeatures { aidl = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
