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
        // 与 :app 保持一致，支持 arm64-v8a + armeabi-v7a。
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests {
            // 诊断文案等纯函数单测不应依赖 Android 运行时；
            // 置 true 后 android.util.Log 等未实现的桩方法返回默认值而非抛异常。
            isReturnDefaultValues = true
        }
    }
    // 无 externalNativeBuild：原自研 JNI（libeye_native.so / src/main/cpp，CMake 目标 eye_native）
    // 的唯一使用者 NativeAudioProcessor 全项目无实例化点，属死代码，已连同 cpp 目录一并删除。
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
    // Vosk ASR engine（AAR 自带 libvosk.so，含 armeabi-v7a/arm64-v8a）
    implementation(libs.vosk.android)
    // Sherpa-ONNX ASR engine：不使用 AAR，native 库以二进制形式直接提交在仓库里 ——
    //   engine/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so
    //   engine/src/main/jniLibs/arm64-v8a/libonnxruntime.so
    //   engine/src/main/jniLibs/armeabi-v7a/libsherpa-onnx-jni.so  (32-bit)
    //   engine/src/main/jniLibs/armeabi-v7a/libonnxruntime.so      (32-bit)
    // Kotlin 绑定为 vendored 源码：engine/src/main/kotlin/com/k2fsa/sherpa/onnx/
    // 来源：https://github.com/k2-fsa/sherpa-onnx/releases（sherpa-onnx-*-android.tar.bz2）
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
}
