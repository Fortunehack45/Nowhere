import java.io.ByteArrayOutputStream

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
    return 408 + gitCommitCount + envRunNumber
}

val computedVersionCode = getDynamicBuildNumber()
val customVersionName = project.findProperty("versionName") as? String
val computedVersionName = customVersionName ?: "1.0.$computedVersionCode"

android {
    namespace = "com.fakegps.mocklocation"
    compileSdk = 36

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
        create("universalKey") {
            storeFile = file("nowhere_key.jks")
            storePassword = "nowhere123"
            keyAlias = "nowhere"
            keyPassword = "nowhere123"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("universalKey")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("universalKey")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "Nowhere-v${defaultConfig.versionName}-${name}.apk"
            }
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Core Library Desugaring for WireGuard Java 8+ features
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // WireGuard Official Android Tunnel Engine (Native ChaCha20-Poly1305 encryption & full IPv4/IPv6 routing)
    implementation("com.wireguard.android:tunnel:1.0.20230706")

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

    // OpenStreetMap (osmdroid)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Google Mobile Ads (AdMob)
    implementation("com.google.android.gms:play-services-ads:23.0.0")

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
