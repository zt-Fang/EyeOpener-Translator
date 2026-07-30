# engine 模块消费者混淆规则
# 此文件自动应用到依赖 engine 模块的模块

# ==============================
# JNI 相关 - 绝对不能混淆
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

# Vosk JNA 库警告抑制
-dontwarn org.vosk.**
-dontwarn com.sun.jna.**
