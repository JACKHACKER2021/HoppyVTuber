import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.hoppy.vtuber"
    compileSdk = 34

    defaultConfig {
        applicationId = "cz.hoppy.vtuber"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "3.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "HoppyRelease2026!"
            keyAlias = "hoppyvtuber"
            keyPassword = "HoppyRelease2026!"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // nativní knihovny (Filament, MediaPipe) rozbalit při instalaci na disk – jinak se mapují
        // přímo z APK a při aktualizaci/instalaci v jiném profilu (druhý prostor) padá SIGBUS
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += listOf("META-INF/*.version", "META-INF/INDEX.LIST")
        }
    }

    androidResources {
        noCompress += listOf("glb", "task")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.github.sceneview:sceneview:2.2.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
}

// MediaPipe face model (3.6 MB) se stáhne automaticky při buildu
val downloadFaceModel by tasks.registering {
    val out = file("src/main/assets/face_landmarker.task")
    outputs.file(out)
    doLast {
        if (!out.exists()) {
            out.parentFile.mkdirs()
            URI("https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task")
                .toURL().openStream().use { i -> out.outputStream().use { i.copyTo(it) } }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadFaceModel) }
