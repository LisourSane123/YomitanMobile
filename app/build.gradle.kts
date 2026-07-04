import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing is loaded from app/keystore.properties (gitignored). The
// file must define: storeFile, storePassword, keyAlias, keyPassword. When
// the file is absent (dev machines, CI build of the debug variant) the
// release block falls back to NO signing config — assembleRelease will
// then fail loudly rather than silently producing an unsigned APK.
val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.yomitanmobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yomitanmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProperties.getProperty("storeFile")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the signing config when keystore.properties exists.
            // Otherwise leave signingConfig=null so an unconfigured machine
            // produces an obviously-unsigned APK that won't install, rather
            // than silently signing with the debug key.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    testOptions {
        unitTests {
            // Robolectric-backed Compose UI tests need real Android resources
            // on the JVM classpath.
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // The AnkiDroid API artifact ships a lint AAR that bans direct time
        // sources (Date(), Calendar.getInstance(), System.currentTimeMillis())
        // to enforce their internal Time-abstraction. We don't use that
        // abstraction — disable the rules so they don't gate our release
        // build for code paths that have nothing to do with the AnkiDroid
        // module's testability concerns.
        disable += setOf(
            "DirectDateInstantiation",
            "DirectCalendarInstanceUsage",
            "DirectSystemCurrentTimeMillisUsage"
        )
    }

    bundle {
        language {
            // MainActivity rewrites Configuration.locale at runtime so the
            // user can switch UI language without reinstalling. The default
            // AAB behaviour splits language resources by system locale and
            // streams them on demand — but our in-app switcher reaches for
            // a locale that may not have been downloaded yet, silently
            // falling back to the system language. Bundling every locale
            // into the base APK keeps the switcher honest at the cost of a
            // few hundred KB.
            enableSplit = false
        }
    }
}

// Pin the JDK used to compile Java + Kotlin to a toolchain Gradle locates
// itself (scans standard install dirs, incl. /usr/lib/jvm). This replaces the
// machine-specific org.gradle.java.home that used to live in gradle.properties,
// so a checkout builds identically on any machine that has *a* JDK 17 installed
// — no per-developer path editing.
kotlin {
    jvmToolchain(17)
}

// Dependency-bump notes (P2-16):
//   * Kotlin stays at 1.9.22 for this release. Bumping to Kotlin 2.x is
//     prerequisite for Compose BOM 2024+, Room 2.7+, and Hilt 2.52+ — that
//     migration touches every KSP-generated file and moves the Compose
//     compiler from a separate version to a bundled Kotlin plugin. Out of
//     scope for the pre-launch hardening pass; tracked in TO_FIX.
//   * All versions below are the latest stable points that still run on
//     Kotlin 1.9.22 + KSP 1.9.22-1.0.17.
dependencies {
    // Core
    // P2-16 dep bumps reverted 2026-05-14 — a downstream regression
    // surfaced on Android 16 during dictionary download. Suspect path is
    // lifecycle 2.8.x + kotlinx-coroutines 1.8.x interaction with the
    // newer OS. Reverted to the versions that shipped with the working
    // build. Kotlin 2.x migration is the proper unlock for re-bumping.
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // AnkiDroid API
    implementation("com.github.ankidroid:Anki-Android:api-v1.1.0")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    // Pinned to the same 1.7.3 line as coroutines-android so test runtime
    // matches production.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Robolectric + Compose UI test so interaction (tap-to-reveal furigana)
    // can be exercised on the JVM without a device.
    testImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // Instrumentation tests
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
