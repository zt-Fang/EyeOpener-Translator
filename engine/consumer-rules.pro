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

# 注：原自研 JNI（NativeAudioProcessor / libeye_native.so）已移除，
# 该类全项目无实例化点、属死代码，engine/src/main/cpp 与 CMake 目标一并删除。

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

# ==============================
# ML Kit Translation（本模块依赖，规则随 consumer-rules 下发给使用方）
# ==============================
# 真实包名（务必与 MlKitTranslationEngine.kt 的 import 保持一致）：
#   com.google.mlkit.nl.translate.*     → TranslateLanguage / Translator / TranslatorOptions
#                                         / Translation / TranslateRemoteModel
#   com.google.mlkit.common.model.*     → RemoteModelManager / DownloadConditions
#   com.google.mlkit.nl.languageid.*    → 语言识别（TranslateLanguage 内部可能引用）
# 踩坑记录：若写成 com.google.mlkit.translate.**（少一层 nl）则该包不存在，
# keep 命中 0 个类 → R8 会删掉上述全部公开 API，release 包运行本地翻译必然
# NoClassDefFoundError，且 debug 包（isMinifyEnabled=false）无法复现。
-keep class com.google.mlkit.nl.translate.** { *; }
-keep class com.google.mlkit.nl.languageid.** { *; }
-keep class com.google.mlkit.common.** { *; }
-keep class com.google.mlkit.vision.text.** { *; }
-dontwarn com.google.mlkit.**
