import java.util.Properties

plugins {
    alias(libs.plugins.anstelle.android.application)
    alias(libs.plugins.anstelle.android.compose)
    alias(libs.plugins.anstelle.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.anstelle.webui)
}

// Optional release signing: keystore.properties (not checked in) with
// storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are
// signed with the debug key so they can still be installed for testing.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

/** True when a release build is asked for; debug builds stay one APK. */
val releaseBuild = gradle.startParameter.taskNames.any { "release" in it.lowercase() }

android {
    namespace = "tv.anstelle.app"

    defaultConfig {
        applicationId = "tv.anstelle.app"
        versionCode = 1
        versionName = "0.1.0"
    }

    splits {
        abi {
            // Release builds come as one APK per ABI, so a device stores only the native code it
            // runs (FFmpeg, libass and the C++ runtime, 4.3 to 5.8 MB each); a universal one too.
            isEnable = releaseBuild
            reset()
            // No 32-bit x86 device ships; the emulators are x86_64.
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            optimization {
                keepRules {
                    // The Jellyfin SDK keeps every API model whole, about 21,000 methods and a fifth
                    // of the code; kotlinx.serialization's own rules keep what the app reads.
                    ignoreFrom("org.jellyfin.sdk:jellyfin-core-android")
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // Web UI files are already gzipped by the copyWebUi task; don't deflate them again.
        noCompress += "gzip"
        // The app is in English; the libraries' translations of their own few strings would only mix languages.
        localeFilters += "en"
    }

    packaging {
        // Keeps 32-bit x86 out of debug and universal APKs too.
        jniLibs.excludes += "lib/x86/**"
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/9/previous-compilation-data.bin",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }
}

baselineProfile {
    // Profiles are generated explicitly (:app:generateBaselineProfile), not on every release build.
    automaticGenerationDuringBuild = false
    saveInSrc = true
    dexLayoutOptimization = true
}

androidComponents {
    // The profile and benchmark builds are signed like debug ones, so they install over a debug
    // install on a test device and keep its sign-ins and library.
    onVariants(selector().withBuildType("benchmarkRelease")) { it.signingConfig.setConfig(android.signingConfigs.getByName("debug")) }
    onVariants(selector().withBuildType("nonMinifiedRelease")) { it.signingConfig.setConfig(android.signingConfigs.getByName("debug")) }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.data)
    implementation(projects.core.theme)
    implementation(projects.core.jellyfin)
    implementation(projects.core.player)

    implementation(projects.feature.guide)
    implementation(projects.feature.player)
    implementation(projects.feature.ondemand)
    implementation(projects.feature.profiles)
    implementation(projects.feature.settings)
    implementation(projects.feature.dashboard)

    implementation(projects.webserver)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.viewmodel.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.profileinstaller)
    // Logs janky frames in debug builds.
    implementation(libs.androidx.metrics.performance)
    // Posters and channel logos, fetched through the shared OkHttp client.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    baselineProfile(projects.baselineprofile)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
