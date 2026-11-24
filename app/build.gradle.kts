import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release key lives outside the project (~/keys/privacy-music-release.properties: storeFile, storePassword,
// keyAlias, keyPassword). Without that file the release build is simply left unsigned.
val releaseKey: Properties? = File(System.getProperty("user.home"), "keys/privacy-music-release.properties")
    .takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "com.example.privacymusic"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.privacymusic"
        minSdk = 26
        targetSdk = 37
        versionCode = 19
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            optimization {
                enable = true
            }
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
    dependenciesInfo {
        // Don't embed a Google-readable dependency block in the APK/AAB signature.
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(libs.okhttp)
    implementation(libs.newpipe.extractor)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.coroutines.guava)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Hard guarantee: the build fails if anything pulls in Google Play Services, Firebase,
// Google Sign-In / OAuth, Play Core or an analytics/crash SDK.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    incoming.afterResolve {
        val banned = Regex(
            "^(com\\.google\\.android\\.gms|com\\.google\\.firebase|com\\.google\\.android\\.play|" +
                "com\\.google\\.android\\.libraries\\.identity|com\\.google\\.api-client|com\\.google\\.oauth-client|" +
                "com\\.google\\.auth|com\\.google\\.android\\.datatransport|com\\.crashlytics|io\\.sentry|" +
                "com\\.google\\.android\\.ump|com\\.google\\.ads)(:|\\.|$)"
        )
        val hits = resolutionResult.allComponents.mapNotNull { it.moduleVersion }
            .map { "${it.group}:${it.name}:${it.version}" }
            .filter { banned.containsMatchIn(it) }
        if (hits.isNotEmpty()) throw GradleException("Privacy policy violation, banned dependencies: $hits")
    }
}
