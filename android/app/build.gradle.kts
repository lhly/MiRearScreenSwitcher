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
val inputProxyPrototype = providers.gradleProperty("mrssInputProxy").map(String::toBoolean).orElse(false)

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
        buildConfigField("boolean", "VIRTUAL_CAST_ENABLED", "true")
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
            buildConfigField("boolean", "INPUT_PROXY_PROTOTYPE", inputProxyPrototype.get().toString())
        }
        release {
            buildConfigField("boolean", "INPUT_PROXY_PROTOTYPE", "false")
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

/** The virtual display input reader is packaged as an extracted executable for each Android ABI. */
abstract class CompileRearInputReader : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourceFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val compilerFiles: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:javax.inject.Inject abstract val execution: org.gradle.process.ExecOperations
    @TaskAction fun compile() {
        val abiByCompiler = mapOf("aarch64" to "arm64-v8a", "armv7a" to "armeabi-v7a", "i686" to "x86", "x86_64" to "x86_64")
        compilerFiles.files.sortedBy { it.name }.forEach { compiler ->
            val abi = abiByCompiler.getValue(compiler.name.substringBefore('-'))
            val target = outputDirectory.file("$abi/libremrss_input_proxy.so").get().asFile
            target.parentFile.mkdirs()
            execution.exec {
                commandLine(compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                    "-fPIE", "-pie", "-Wl,-z,max-page-size=16384", sourceFile.get().asFile, "-o", target)
            }
        }
    }
}
val inputReader = tasks.register<CompileRearInputReader>("compileRearInputReader") {
    val local = Properties().apply { rootProject.file("local.properties").inputStream().use { load(it) } }
    val sdk = file(local.getProperty("sdk.dir"))
    val host = if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
    sourceFile.set(layout.projectDirectory.file("src/main/cpp/rear_input_reader.c"))
    val binaries = File(sdk, "ndk/27.0.12077973/toolchains/llvm/prebuilt/$host/bin")
    compilerFiles.from(listOf("aarch64-linux-android24-clang", "armv7a-linux-androideabi24-clang", "i686-linux-android24-clang", "x86_64-linux-android24-clang").map { File(binaries, it) })
    outputDirectory.set(layout.buildDirectory.dir("generated/inputProxyNative"))
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
    variant.sources.jniLibs?.addGeneratedSourceDirectory(inputReader) { it.outputDirectory }
    variant.packaging.jniLibs.useLegacyPackaging.set(true)
    if (variant.buildType == "release") {
        // No full kotlin-reflect or coroutine debug agent is used in production.
        // Keep debug inspector metadata, service providers, profiles and licenses.
        variant.packaging.resources.excludes.addAll(
            "kotlin/*.kotlin_builtins", "kotlin/**/*.kotlin_builtins",
            "META-INF/androidx.*.version", "DebugProbesKt.bin"
        )
    }
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
    // ComposeView is hosted directly; no activity-compose adapters are used.
    implementation("androidx.activity:activity:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // Shizuku API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
