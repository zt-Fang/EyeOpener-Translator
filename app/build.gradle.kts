plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Kotlin 2.3+ 使用 compilerOptions DSL 替代废弃的 kotlinOptions
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "io.github.ztfang.eye"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ztfang.eye"
        minSdk = 24; targetSdk = 36
        versionCode = 4; versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        release {
            // 开源版本：关闭代码混淆与资源压缩，保证源码与构建产物行为一致、便于调试
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // 自定义APK输出文件名
    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                outputFileName = "EyeOpener.apk"
            }
        }
    }
}

dependencies {
    implementation(project(":domain")); implementation(project(":data")); implementation(project(":engine"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.ui.tooling); implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation); implementation(libs.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.activity.compose); implementation(libs.androidx.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose); implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.core); implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime); implementation(libs.room.ktx)
    implementation(libs.androidx.activity.ktx); implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout); implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core); androidTestImplementation(libs.androidx.junit)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    // OkHttp（AppModule 中提供共享 OkHttpClient 单例）
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
}
