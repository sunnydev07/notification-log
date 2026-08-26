// AGP 9 provides Kotlin out of the box; only the Compose compiler plugin
// (version-matched to Kotlin) is applied separately.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sunny.notificationlog"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sunny.notificationlog"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
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

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Give Robolectric the real merged manifest/resources, so the
            // manifest-declared NotificationLogApp application class exists
            // during JVM tests (the ViewModel tests depend on it).
            isIncludeAndroidResources = true
        }
    }
}

// Export Room's schema JSON to /app/schemas on every schema change. This gives
// us a versioned history of the database layout, which future phases will need
// to write correct migrations instead of guessing what changed.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Newest BOM whose artifacts still compile against the latest *stable*
    // platform (API 36 / Android 16). Later BOMs require the Android 17 beta.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    // Jetpack Compose (project is wired for it now; screens start in Phase 3).
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    // Search/clear icons. Newer Material 3 versions no longer pull
    // material-icons-core in transitively, so it's declared explicitly.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Lifecycle runtime for observing ON_RESUME from composables, plus the
    // viewModel() composable that hands HistoryScreen its ViewModel.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.core:core-ktx:1.18.0")

    // Google Play Billing (phase 7, one-time "Pro" purchase). Justified per
    // AGENTS.md: there is no SDK alternative for Play in-app purchases.
    // billing-ktx adds the coroutine wrappers (queryProductDetails etc.).
    // v9 requires compileSdk >= 35; we build against 36. The
    // com.android.vending.BILLING permission is merged in by this library
    // automatically — nothing to declare in the manifest.
    implementation("com.android.billingclient:billing-ktx:9.1.0")

    // Room (persistence). room-ktx adds coroutine/Flow support for DAOs;
    // the compiler via ksp() generates the database implementation at compile
    // time, which is where Room catches SQL errors before the app ever runs.
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    // Robolectric runs Android framework code (Room, PackageManager...) on the
    // desktop JVM, so DAO logic is tested without an emulator.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
