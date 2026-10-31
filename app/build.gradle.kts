plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// CI passes the semantic-release version: ./gradlew assembleRelease -PreleaseVersion=1.4.2
// versionCode = MAJOR * 1_000_000 + MINOR * 1_000 + PATCH, so it grows with every release and stays
// below Android's limit of 2_100_000_000. See docs/android-release.md.
val releaseVersion: String? = providers.gradleProperty("releaseVersion").orNull

fun versionCodeOf(version: String): Int {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(version)
        ?: throw GradleException("releaseVersion must be MAJOR.MINOR.PATCH, got '$version'")
    val (major, minor, patch) = match.destructured.toList().map { it.toInt() }
    if (major > 2099 || minor > 999 || patch > 999) {
        throw GradleException("releaseVersion $version does not fit the versionCode scheme")
    }
    return major * 1_000_000 + minor * 1_000 + patch
}

// The release keystore never lives in the repo; CI writes it from GitHub secrets.
val keystorePath: String? = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull

android {
    namespace = "com.walkwalk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.walkwalk"
        minSdk = 34
        targetSdk = 35
        versionCode = releaseVersion?.let(::versionCodeOf) ?: 1
        versionName = releaseVersion ?: "0.1"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_SIGNING_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG gates the demo hike
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.maplibre)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
