// Copyright (c)  2023  Xiaomi Corporation
// 本项目接入 sherpa-onnx 官方 VAD 支持(Silero / Ten)
// 来源: https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/Vad.kt
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

/** Silero VAD 模型配置 */
data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 5.0F,
)

/** Ten VAD 模型配置 */
data class TenVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 256,
    var maxSpeechDuration: Float = 5.0F,
)

/** VAD 模型统一配置 */
data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)

/** 语音段(含起始样本位置和 PCM float 数据) */
class SpeechSegment(val start: Int, val samples: FloatArray)

/**
 * Sherpa-ONNX VAD 包装类。
 *
 * 支持两种引擎:
 * - Silero VAD(windowSize=512, MIT license)
 * - Ten VAD(windowSize=256, 修改版 Apache 2.0)
 *
 * 工作模式:
 * 1. 流式: acceptWaveform 持续送音频, 通过 isSpeechDetected 判断当前是否有语音
 * 2. 段提取: acceptWaveform 送音频后, 通过 empty/pop/front 获取切分好的语音段
 *
 * 典型用法(实时检测):
 * ```
 * vad.acceptWaveform(samples)
 * if (vad.isSpeechDetected()) {
 *     // 当前有语音
 * }
 * ```
 *
 * 典型用法(段切分):
 * ```
 * vad.acceptWaveform(samples)
 * while (!vad.empty()) {
 *     val segment = vad.front()
 *     // 处理 segment.samples
 *     vad.pop()
 * }
 * ```
 */
class Vad(
    assetManager: AssetManager? = null,
    var config: VadModelConfig,
) {
    private var ptr: Long

    init {
        if (assetManager != null) {
            ptr = newFromAsset(assetManager, config)
        } else {
            ptr = newFromFile(config)
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    /** 实时计算当前帧的语音概率(0.0-1.0) */
    fun compute(samples: FloatArray): Float = compute(ptr, samples)

    /** 送入一帧音频(长度需等于 windowSize) */
    fun acceptWaveform(samples: FloatArray) = acceptWaveform(ptr, samples)

    /** 队列是否为空(无切分好的语音段) */
    fun empty(): Boolean = empty(ptr)

    /** 弹出队首语音段 */
    fun pop() = pop(ptr)

    /** 获取队首语音段(不弹出) */
    fun front(): SpeechSegment {
        return front(ptr)
    }

    /** 清空队列 */
    fun clear() = clear(ptr)

    /** 当前是否检测到语音(实时状态) */
    fun isSpeechDetected(): Boolean = isSpeechDetected(ptr)

    /** 重置 VAD 状态(清空内部缓冲和状态) */
    fun reset() = reset(ptr)

    /** 刷新内部状态, 强制输出剩余语音段 */
    fun flush() = flush(ptr)

    private external fun delete(ptr: Long)
    private external fun newFromAsset(
        assetManager: AssetManager,
        config: VadModelConfig,
    ): Long
    private external fun newFromFile(
        config: VadModelConfig,
    ): Long
    private external fun acceptWaveform(ptr: Long, samples: FloatArray)
    private external fun compute(ptr: Long, samples: FloatArray): Float
    private external fun empty(ptr: Long): Boolean
    private external fun pop(ptr: Long)
    private external fun clear(ptr: Long)
    private external fun front(ptr: Long): SpeechSegment
    private external fun isSpeechDetected(ptr: Long): Boolean
    private external fun reset(ptr: Long)
    private external fun flush(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}

/**
 * 获取 VAD 模型配置。
 *
 * @param type 0: Silero VAD (推荐), 1: Ten VAD
 * @param modelPath 模型文件路径(绝对路径), 默认空串表示走 assets
 */
fun getVadModelConfig(type: Int, modelPath: String = ""): VadModelConfig? = when (type) {
    0 -> VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = modelPath.ifEmpty { "silero_vad.onnx" },
            threshold = 0.5F,
            minSilenceDuration = 0.25F,
            minSpeechDuration = 0.25F,
            windowSize = 512,
        ),
        sampleRate = 16000,
        numThreads = 1,
        provider = "cpu",
    )
    1 -> VadModelConfig(
        tenVadModelConfig = TenVadModelConfig(
            model = modelPath.ifEmpty { "ten-vad.onnx" },
            threshold = 0.5F,
            minSilenceDuration = 0.25F,
            minSpeechDuration = 0.25F,
            windowSize = 256,
        ),
        sampleRate = 16000,
        numThreads = 1,
        provider = "cpu",
    )
    else -> null
}
