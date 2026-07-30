plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "io.github.ztfang.eye.engine"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildFeatures { buildConfig = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":domain"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.mlkit.translate)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.hilt.android)
    // Vosk ASR engine
    implementation(libs.vosk.android)
    // Sherpa-ONNX ASR engine (需手动下载 AAR 放入 libs 目录)
    // 下载地址: https://github.com/k2-fsa/sherpa-onnx/releases
    // 文件名: sherpa-onnx-v1.13.3-android.tar.bz2，解压后获取 sherpa_onnx.aar
    // implementation(files("libs/sherpa_onnx.aar"))
    ksp(libs.hilt.compiler)
}