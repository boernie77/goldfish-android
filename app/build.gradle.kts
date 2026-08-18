import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release-Signing-Credentials liegen NICHT im Klartext im Repo — kommen aus
// keystore.properties (git-ignoriert, liegt nur lokal). Siehe keystore.properties.example.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.goldfish.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.goldfish.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 101
        versionName = "1.2.69"
        // libVLC ist riesig (35MB+ pro ABI). Nur arm64-v8a + armeabi-v7a
        // bauen — deckt > 99% der Android-Geraete ab (alle modernen
        // Tablets, Phones) und vermeidet x86_64-Konflikte die zu Crashes
        // beim Native-Loading fuehren koennten.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.window)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)

    // Images
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    // Media3 / ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    // FFmpeg-Decoder-Extension fuer Codecs, die der eingebaute MediaCodec
    // nicht handhabt (z.B. AC-3, DTS, manche HEVC-Profile, ProRes …) —
    // ohne das spielten viele .mp4 nicht ab, obwohl sie in VLC laufen.
    implementation(libs.nextlib.media3ext)
    // FFmpeg-basierter MediaThumbnailRetriever als Fallback fuer
    // MediaMetadataRetriever — gibt es Frames auch bei Files raus die der
    // System-Codec nicht oeffnen kann (sonst keine Vorschaubilder).
    implementation(libs.nextlib.mediainfo)
    // Komplette libVLC-Engine als interner Fallback-Player fuer Files
    // die ExoPlayer (auch mit FFmpeg-Extension + Tolerant-Extractors)
    // nicht abspielen kann. AAB-Wachstum ca. +35 MB.
    implementation(libs.libvlc.all)

    // WorkManager
    implementation(libs.workmanager)

    debugImplementation(libs.androidx.ui.tooling)
}
