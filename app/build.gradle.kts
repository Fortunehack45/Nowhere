import java.io.ByteArrayOutputStream
import java.util.Properties
import com.android.build.gradle.api.ApkVariantOutput

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Compute dynamic build number and version progression
val versionMajor = (project.findProperty("VERSION_MAJOR") as? String)?.toIntOrNull() ?: 1
val versionMinor = (project.findProperty("VERSION_MINOR") as? String)?.toIntOrNull() ?: 0

fun getDynamicBuildNumber(): Int {
    val propCode = (project.findProperty("versionCode") as? String)?.toIntOrNull()
    if (propCode != null && propCode > 0) return propCode

    val gitCommitCount = runCatching {
        val stdout = ByteArrayOutputStream()
        project.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            standardOutput = stdout
        }
        stdout.toString().trim().toInt()
    }.getOrNull() ?: 58

    val envRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
    val calculated = 2550 + gitCommitCount + envRunNumber
    return maxOf(2550, calculated)
}

val computedVersionCode = getDynamicBuildNumber()
val customVersionName = project.findProperty("versionName") as? String
val computedVersionName = customVersionName ?: "1.0.$computedVersionCode"

// Secure Keystore Properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun getSecret(key: String, envKey: String): String? {
    return System.getenv(envKey)
        ?: (project.findProperty(key) as? String)
        ?: keystoreProperties.getProperty(key)
}

val releaseKeystorePath = getSecret("NOWHERE_KEYSTORE_PATH", "NOWHERE_KEYSTORE_PATH") ?: "nowhere_key.jks"
val releaseKeystorePassword = getSecret("NOWHERE_KEYSTORE_PASSWORD", "NOWHERE_KEYSTORE_PASSWORD")
val releaseKeyAlias = getSecret("NOWHERE_KEY_ALIAS", "NOWHERE_KEY_ALIAS") ?: "nowhere"
val releaseKeyPassword = getSecret("NOWHERE_KEY_PASSWORD", "NOWHERE_KEY_PASSWORD") ?: releaseKeystorePassword

android {
    namespace = "com.fakegps.mocklocation"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.nowhere.gps.locationchanger"
        minSdk = 26
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = computedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keyFile = file(releaseKeystorePath)
            if (keyFile.exists() && !releaseKeystorePassword.isNullOrBlank()) {
                storeFile = keyFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            val relConfig = signingConfigs.findByName("release")
            if (relConfig != null && relConfig.storeFile != null && relConfig.storeFile!!.exists()) {
                signingConfig = relConfig
            }
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val relConfig = signingConfigs.findByName("release")
            if (relConfig != null && relConfig.storeFile != null && relConfig.storeFile!!.exists()) {
                signingConfig = relConfig
            }
            manifestPlaceholders["admobAppId"] = "ca-app-pub-5191202278112313~3868363285"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    applicationVariants.all {
        val variantName = name
        outputs.all {
            val apkOutput = this as? ApkVariantOutput
            apkOutput?.outputFileName = "Nowhere-v${computedVersionName}-${variantName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable.addAll(listOf("BlockedPrivateApi", "DiscouragedPrivateApi"))
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Core Library Desugaring for Java 8+ features
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Official WireGuard Android Tunnel Engine with 16 KB Page-Size Support (NDK r27+ ELF Aligned)
    implementation(files("libs/tunnel-16kb.aar"))

    // AndroidX & Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager for Reliable Background Automation Scheduling
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // OpenStreetMap (osmdroid)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Google Mobile Ads (AdMob)
    implementation("com.google.android.gms:play-services-ads:23.0.0")
    // Google User Messaging Platform (UMP) Consent SDK
    implementation("com.google.android.ump:user-messaging-platform:2.2.0")

    // Google Play Services Fused Location (Hardware & GMS Fused Provider Mock Shield)
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // Google Play Billing (Subscriptions & In-App Purchases) - Version 8.0.0
    implementation("com.android.billingclient:billing:8.0.0")

    // Google Play In-App Updates
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // QR Code Generation for Hotspot Tethering
    implementation("com.google.zxing:core:3.5.3")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("org.robolectric:android-all:14-robolectric-10818077")
    testImplementation("androidx.test:core:1.5.0")
}

tasks.register<Zip>("packageNativeDebugSymbols") {
    description = "Packages native .so libraries into native-debug-symbols.zip for Google Play Console"
    group = "publishing"
    from(zipTree("libs/tunnel-16kb.aar")) {
        include("jni/**")
        eachFile {
            path = path.removePrefix("jni/")
        }
        includeEmptyDirs = false
    }
    archiveFileName.set("native-debug-symbols.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs"))
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    finalizedBy("packageNativeDebugSymbols")
}

