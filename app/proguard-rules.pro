# ==============================
# 基础属性保留
# ==============================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-keepattributes Exceptions

# ==============================
# 优化指令 - 缩减体积 + R8 优化；开源项目弱化命名混淆
# ==============================
-allowaccessmodification
# 不使用 -repackageclasses，保留原包结构便于 crash stack 定位
# 不使用 -mergeinterfacesaggressively，避免与 Hilt/KSP 生成代码冲突

# ==============================
# JNI 相关 - 绝对不能混淆（类名/包名/字段名被 C++ 硬编码引用）
# ==============================

# Sherpa-ONNX JNI 接口层（native 方法 + C++ 反射填充字段的数据类）
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    <fields>;
    native <methods>;
}

# Vosk ASR JNI 接口层（依赖 JNA 反射调用）
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** {
    <fields>;
    <methods>;
}

# 自研 JNI - NativeAudioProcessor（JNI 函数名含完整包路径）
-keep class io.github.ztfang.eye.engine.asr.NativeAudioProcessor { *; }
-keep class io.github.ztfang.eye.engine.asr.NativeAudioProcessor$* { *; }

# Sherpa-ONNX/VAD 引擎（System.loadLibrary 调用方）
-keep class io.github.ztfang.eye.engine.asr.SherpaOnnxAsrEngine { *; }
-keep class io.github.ztfang.eye.engine.vad.SileroVadEngine { *; }

# 所有含 native 方法的类（防御性保留类名）
-keepclasseswithmembernames class * {
    native <methods>;
}

# ==============================
# Hilt 依赖注入 - 完整 keep（修复注入失败风险）
# ==============================

# Application 类（@HiltAndroidApp 注解）
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

# ViewModel 类（@HiltViewModel 注解）
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# @AndroidEntryPoint 注解的 Activity/Service/Fragment
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# Hilt/Dagger 内部生成代码
-keep class dagger.hilt.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class androidx.lifecycle.ViewModelProvider$Factory { *; }
-keep class dagger.hilt.android.lifecycle.HiltViewModelDefaultFactory { *; }
-keep class dagger.hilt.internal.lifecycle.ViewModelFactoryImpl { *; }

# ==============================
# Room / DataStore
# ==============================
-keep class io.github.ztfang.eye.data.local.entity.** { *; }
-keep class io.github.ztfang.eye.data.local.dao.** { *; }
-keep class io.github.ztfang.eye.data.local.database.** { *; }

# ==============================
# Domain 层模型类（Room 存储/JSON 序列化，必须保留）
# ==============================
-keep class io.github.ztfang.eye.domain.model.** { *; }
-keep enum io.github.ztfang.eye.domain.model.** {
    <fields>;
    public *;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# 保留枚举的 name() 方法返回值不会被 R8 优化重命名（防止 ModelStatus.AVAILABLE.name != "AVAILABLE"）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# data class component 方法和 copy 方法保留（供 Kotlin 反射/Flow 处理使用
-keepclassmembers class io.github.ztfang.eye.domain.model.** {
    public ** component1();
    public ** component2();
    public ** component3();
    public ** component4();
    public ** component5();
    public ** component6();
    public ** component7();
    public ** copy(...);
}

# ==============================
# 应用入口
# ==============================
-keep public class io.github.ztfang.eye.MainActivity { *; }
-keep public class io.github.ztfang.eye.FloatingSubtitleService { *; }

# ==============================
# 开源透明：保留应用自身类名，仅 R8 缩减未用代码
# ==============================
-keep,allowobfuscation class io.github.ztfang.eye.** { *; }

# ==============================
# Jetpack Compose
# ==============================
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ==============================
# ML Kit（仅保留翻译相关，缩小范围）
# ==============================
-keep class com.google.mlkit.translate.** { *; }
-dontwarn com.google.mlkit.**

# ==============================
# 网络库（仅 dontwarn，编译期安全）
# ==============================
-dontwarn okhttp3.**
-dontwarn okio.**

# ==============================
# 日志移除（release 包移除调试日志，保留 w/e 用于线上问题定位）
# ==============================
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
