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
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("glb", "task") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.github.sceneview:sceneview:2.2.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
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
