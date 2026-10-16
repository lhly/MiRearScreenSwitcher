import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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
    namespace = "top.zainzh.rearscreen"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        applicationId = "top.zainzh.rearscreen"
        minSdk = 24  // Shizuku需要最低24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        versionCode = (project.findProperty("mrssVersionCode")?.toString() ?: versionProperties.getProperty("versionCode")).toInt()
        versionName = project.findProperty("mrssVersionName")?.toString() ?: versionProperties.getProperty("versionName")
        manifestPlaceholders["appLabel"] = "ReMRSS"
        manifestPlaceholders["mrssScheme"] = "mrss"
        buildConfigField("String", "URI_SCHEME", "\"mrss\"")
        buildConfigField("String", "SOURCE_TAG", "\"v${versionName}\"")
    }
    
    // Shizuku需要
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    
    lint {
        disable.add("Instantiatable")
    }

    providers.gradleProperty("mrssUpdateFixtureDir").orNull?.let {
        sourceSets.getByName("androidTest").assets.srcDir(it)
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
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "ReMRSS 调试版"
            manifestPlaceholders["mrssScheme"] = "mrss-debug"
            buildConfigField("String", "URI_SCHEME", "\"mrss-debug\"")
        }
        release {
            manifestPlaceholders["appLabel"] = "ReMRSS"
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

}

// Generated assets use the Variant API so IDE and CLI builds share the legal texts.
abstract class PrepareLegalAssets : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val legalTexts: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:javax.inject.Inject abstract val fileSystemOperations: FileSystemOperations
    @TaskAction fun generate() {
        fileSystemOperations.sync { from(legalTexts); into(outputDirectory) }
    }
}
val prepareLegalAssets = tasks.register<PrepareLegalAssets>("prepareLegalAssets") {
    legalTexts.from(rootProject.file("../LICENSE"), rootProject.file("../NOTICE"), rootProject.file("../third_party/MIUIX-LICENSE.txt"), rootProject.file("../third_party/MATERIALKOLOR-LICENSE.txt"))
    outputDirectory.set(layout.buildDirectory.dir("generated/legalAssets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareLegalAssets) { it.outputDirectory }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
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
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // Shizuku API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
