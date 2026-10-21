plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.morphcam.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.morphcam.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the CI-built release APK installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }
}

// MediaPipe pose model, fetched at build time instead of being committed.
val poseModel = file("src/main/assets/pose_landmarker_full.task")
val downloadPoseModel by tasks.registering {
    outputs.file(poseModel)
    doLast {
        if (!poseModel.exists()) {
            poseModel.parentFile.mkdirs()
            val url = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/" +
                "pose_landmarker_full/float16/latest/pose_landmarker_full.task"
            java.net.URL(url).openStream().use { input ->
                poseModel.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadPoseModel) }

dependencies {
    val camerax = "1.3.4"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
