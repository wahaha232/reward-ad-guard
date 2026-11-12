import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/*
 * One signing key for every machine that builds this app.
 *
 * Without it each computer signs with its own ~/.android/debug.keystore, and an
 * APK built on one machine cannot update the copy installed from another
 * (2026-11-02: the phone held a build signed by a second PC, so the laptop's
 * fix could only be installed by uninstalling, which loses the guard's data,
 * accessibility grant and battery exemption).
 *
 * The keystore and its passwords stay out of the repo, in the uncommitted
 * local.properties:
 *
 *     rewardadguard.signing.storeFile=D:\\Claude\\keystore\\<file>.keystore
 *     rewardadguard.signing.storePassword=...
 *     rewardadguard.signing.keyAlias=...
 *     rewardadguard.signing.keyPassword=...
 *
 * When they are missing the build still works with the machine's default debug
 * key, but warns that the APK will not update a phone installed from elsewhere.
 */
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val sharedKeystore = localProps.getProperty("rewardadguard.signing.storeFile")
    ?.let { file(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "com.rewardadguard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rewardadguard.app"
        minSdk = 26
        targetSdk = 36
        // Bump both for every GitHub release: the in-app cloud update compares
        // versionName with the release tag (TOOLS/release.sh).
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // Debug-only utility APK for personal sideloading. No release signing config.
    signingConfigs {
        if (sharedKeystore != null) {
            create("shared") {
                storeFile = sharedKeystore
                storePassword = localProps.getProperty("rewardadguard.signing.storePassword")
                keyAlias = localProps.getProperty("rewardadguard.signing.keyAlias")
                keyPassword = localProps.getProperty("rewardadguard.signing.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (sharedKeystore != null) {
                signingConfig = signingConfigs.getByName("shared")
            } else {
                logger.warn(
                    "reward-ad-guard: rewardadguard.signing.storeFile is not set in local.properties; " +
                        "signing with this machine's default debug key. The APK will not update a copy " +
                        "installed from another computer."
                )
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric needs the merged resources/manifest to build an
            // Android environment (and to open Room's SQLite database).
            isIncludeAndroidResources = true
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "reward-ad-guard-${name}.apk"
        }
    }
}

dependencies {
    // ---------- Jetpack Compose & Material 3 ----------
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // ---------- Activity / Lifecycle / Navigation ----------
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // ---------- Coroutines ----------
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---------- Room (event log + statistics) ----------
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ---------- DataStore (settings) ----------
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---------- On-device OCR (collector fallback) ----------
    // Bundled Chinese model: works without Play Services and offline, which a
    // collection run needs. ~+20 MB APK, the price of not depending on a
    // download at the moment an ad is on screen (integration plan §6.2).
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // ---------- Unit Test (pure JVM, no device required) ----------
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Runs Android framework classes (Context, SQLite, PackageManager) on the
    // JVM so manager/session logic can be tested without a device.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
