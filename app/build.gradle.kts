import java.util.Properties

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

// 发布签名配置（可选，不落密文到仓库）。
//
// 在仓库根目录放一份 keystore.properties（已 gitignore）即可启用 release 签名：
//   storeFile=eye-release.jks        # 相对仓库根目录，或绝对路径
//   storePassword=...
//   keyAlias=eye
//   keyPassword=...
//
// 文件不存在时 release 包保持未签名（与历史行为一致），CI 仍可只做构建验证。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
// 取值提到 DSL 之外：signingConfig 作用域里的 get 会解析到 ExtensionContainer.get
val releaseStoreFile = keystoreProperties.getProperty("storeFile")
val releaseStorePassword = keystoreProperties.getProperty("storePassword")
val releaseKeyAlias = keystoreProperties.getProperty("keyAlias")
val releaseKeyPassword = keystoreProperties.getProperty("keyPassword")

android {
    namespace = "io.github.ztfang.eye"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ztfang.eye"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 支持 arm64-v8a + armeabi-v7a。
        //
        // sherpa-onnx / onnxruntime 的 native 库已补全 32 位变体
        // （engine/src/main/jniLibs/armeabi-v7a/）。
        // 尽管如此，engine 初始化处仍保留 catch (Throwable) 作为兜底防护，
        // 防止任何未来库缺失或加载异常导致进程崩溃。
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = releaseStoreFile?.let { rootProject.file(it) }
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            // 开源版本：开启 R8 缩减+优化（移除未用代码/资源），关闭重度混淆
            // 防逆向目的因源码公开已失效，保留主要为缩减 APK 体积与 R8 优化
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 未配置 keystore.properties 时为 null → 沿用未签名行为
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":engine"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    // OkHttp（AppModule 中提供共享 OkHttpClient 单例）
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
}
