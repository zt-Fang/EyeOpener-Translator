/**
 * Sherpa-ONNX 流式 ASR 引擎实现（Zipformer Transducer 中文模型）。
 *
 * 基于 sherpa-onnx JNI 接口，使用 OnlineTransducerModelConfig 加载
 * encoder/decoder/joiner 模型，提供实时流式语音识别。
 *
 * 支持模型：
 * - X-ASR-zh-en-960ms（中英混说）
 * - BN Vosk 2026-02-09（孟加拉语）
 * - Nemotron 3.5 320ms int8（多语种 40 locale，per-stream language）
 *
 * 数据流：录音线程 → feedAudio → stream.acceptWaveform → decode → result
 */
package io.github.ztfang.eye.engine.asr

import android.util.Log
import io.github.ztfang.eye.domain.engine.asr.AsrEngine
import io.github.ztfang.eye.domain.model.SherpaOnnxModel
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sherpa-ONNX 流式 ASR 引擎（多模型共用）。
 *
 * 特性：
 * - 真流式：OnlineStream.acceptWaveform 持续送音频，decode 实时获取 partial
 * - 端点检测：enableEndpoint 检测句尾，触发 final 结果
 * - 线程安全：native 调用均通过 synchronized 保护
 * - 多模型：X-ASR / BN Vosk / Nemotron 3.5 共用同一引擎实例，按 modelId 切换
 * - 动态语种：Nemotron 3.5 支持 per-stream language，切语种无需重载模型
 */
@Singleton
class SherpaOnnxAsrEngine @Inject constructor() : AsrEngine {

    private val lock = Any()

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var stream: OnlineStream? = null

    /** 当前已加载的模型路径，防止相同模型重复 init */
    @Volatile
    var loadedModelPath: String? = null
        private set

    /** 当前加载的模型 ID（用于判断引擎类型 / 模型是否需要切换） */
    @Volatile
    var loadedModelId: String? = null
        private set

    @Volatile
    private var isInitializing: Boolean = false

    /** 最近一次 partial 结果，用于去重 */
    @Volatile
    private var lastPartial: String = ""

    /**
     * 当前 Nemotron per-stream language 代码（如 "ja"/"en"/"zh-CN"）。
     * 仅 Nemotron 3.5 生效；X-ASR / BN 忽略。
     * 默认 "auto" 自动检测。
     */
    @Volatile
    private var currentLanguage: String = "auto"

    /** partial/final 结果流，供外部收集 */
    val partialResultFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val finalResultFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun isReady(): Boolean = recognizer != null

    /**
     * 初始化引擎，加载 Zipformer Transducer 模型。
     *
     * @param modelPath 模型目录，包含 encoder/decoder/joiner ONNX 文件和 tokens.txt
     */
    override suspend fun init(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (isInitializing) {
            return@withContext Result.success(Unit)
        }
        if (isReady() && loadedModelPath == modelPath) {
            return@withContext Result.success(Unit)
        }
        isInitializing = true
        synchronized(lock) {
            try {
                releaseInternal()
                Log.i(TAG, "init: loading Sherpa-ONNX model from $modelPath")

                val modelDir = File(modelPath)
                // 修复：根据 modelPath 末段目录名反查模型配置，避免硬编码 DEFAULT_ZH
                // modelPath 形如 .../files/models/sherpa-onnx/<modelId>/
                val modelId = modelDir.name
                val model = SherpaOnnxModel.fromModelId(modelId) ?: SherpaOnnxModel.DEFAULT_ZH
                Log.i(TAG, "init: resolved model=$modelId -> ${model.name}")

                // 构建 Transducer 模型配置（Zipformer）
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDir, model.encoderFile).absolutePath,
                        decoder = File(modelDir, model.decoderFile).absolutePath,
                        joiner = File(modelDir, model.joinerFile).absolutePath,
                    ),
                    tokens = File(modelDir, model.tokensFile).absolutePath,
                    numThreads = 2,
                    modelType = model.modelType,   // 修复：使用模型自身声明的 type（zipformer / zipformer2）
                    provider = "cpu",
                    debug = false,
                )

                val recognizerConfig = OnlineRecognizerConfig(
                    modelConfig = modelConfig,
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                    endpointConfig = com.k2fsa.sherpa.onnx.getEndpointConfig()  // 连读短停顿切句（rule2=0.6s）
                )

                recognizer = OnlineRecognizer(config = recognizerConfig)
                stream = recognizer!!.createStream()
                loadedModelPath = modelPath
                loadedModelId = modelId
                lastPartial = ""

                // Nemotron 3.5 需要 per-stream language 设置
                // 通过 setOption("language", lang) 指定识别语种，否则默认 auto
                if (model == SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8) {
                    val lang = currentLanguage.ifEmpty { "auto" }
                    try {
                        stream!!.setOption("language", lang)
                        Log.i(TAG, "init: Nemotron language set to $lang")
                    } catch (e: Exception) {
                        // sherpa-onnx 版本不支持 setOption 时仅告警，不阻断初始化
                        Log.w(TAG, "init: setOption(language) failed, " +
                                "need sherpa-onnx v1.13.3+: ${e.message}")
                    }
                }

                Log.i(TAG, "init: Sherpa-ONNX recognizer ready, model=$modelPath, type=${model.modelType}")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "init failed: ${e.message}", e)
                releaseInternal()
                Result.failure(e)
            } finally {
                isInitializing = false
            }
        }
    }

    override suspend fun release() {
        synchronized(lock) {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        try {
            stream?.release()
            stream = null
        } catch (_: Exception) {}
        try {
            recognizer?.release()
            recognizer = null
        } catch (_: Exception) {}
        loadedModelPath = null
        loadedModelId = null
        lastPartial = ""
    }

    /**
     * 动态切换 Nemotron 3.5 识别语种（per-stream language）。
     *
     * - 仅对 Nemotron 3.5 模型生效，X-ASR / BN Vosk 忽略
     * - 切换无需重载模型，仅更新 stream 的 language 选项
     * - 调用时若 stream 尚未创建，会缓存 currentLanguage，待 init 后生效
     *
     * @param language 语言代码（ISO 639-1，如 "ja"/"en"/"zh-CN"），"auto" 自动检测
     */
    fun setLanguage(language: String) {
        val lang = language.ifEmpty { "auto" }
        currentLanguage = lang
        synchronized(lock) {
            val s = stream ?: return
            // 仅 Nemotron 模型调用 setOption，避免对其他模型造成异常
            if (loadedModelId == SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8.modelId) {
                try {
                    s.setOption("language", lang)
                    Log.i(TAG, "setLanguage: Nemotron language -> $lang")
                } catch (e: Exception) {
                    Log.e(TAG, "setLanguage failed: ${e.message}", e)
                }
            } else {
                Log.d(TAG, "setLanguage: current model($loadedModelId) " +
                        "ignores language option, lang=$lang skipped")
            }
        }
    }

    /**
     * 送入一帧 PCM16 音频数据。
     * Sherpa-ONNX 需要 float 数组，这里做 short→float 转换。
     */
    override fun feedAudio(samples: ShortArray) {
        // PCM16 → float32（归一化到 [-1, 1]）
        val floatSamples = FloatArray(samples.size) { i ->
            samples[i] / 32768.0f
        }

        synchronized(lock) {
            val s = stream ?: return
            val rec = recognizer ?: return
            try {
                s.acceptWaveform(floatSamples, SAMPLE_RATE)
            } catch (e: Exception) {
                Log.e(TAG, "feedAudio failed: ${e.message}", e)
            }
        }
    }

    /**
     * 触发解码并返回当前识别文本。
     * 同时检测端点，到达句尾时触发 final 结果。
     */
    override fun decodeAndGetResult(): String {
        synchronized(lock) {
            val rec = recognizer ?: return ""
            val s = stream ?: return ""
            try {
                // 解码（加循环上限保护，避免异常情况下死循环）
                var count = 0
                while (rec.isReady(s) && count < 100) {
                    rec.decode(s)
                    count++
                }

                // 获取结果
                val result = rec.getResult(s)
                val text = result.text.trim()

                // 端点检测 → final 结果
                if (rec.isEndpoint(s) && text.isNotEmpty()) {
                    finalResultFlow.tryEmit(text)
                    rec.reset(s)
                    lastPartial = ""
                    return text
                }

                // partial 结果去重
                if (text.isNotEmpty() && text != lastPartial) {
                    lastPartial = text
                    partialResultFlow.tryEmit(text)
                }
                return text
            } catch (e: Exception) {
                Log.e(TAG, "decodeAndGetResult failed: ${e.message}", e)
                return ""
            }
        }
    }

    /** 是否到达句尾（端点） */
    override fun isEndpoint(): Boolean {
        synchronized(lock) {
            val rec = recognizer ?: return false
            val s = stream ?: return false
            return try {
                rec.isEndpoint(s)
            } catch (_: Exception) {
                false
            }
        }
    }

    /** 重置当前 stream，开始新一句话 */
    override fun resetStream() {
        synchronized(lock) {
            val rec = recognizer ?: return
            val s = stream ?: return
            try {
                rec.reset(s)
                lastPartial = ""
            } catch (e: Exception) {
                Log.e(TAG, "resetStream failed: ${e.message}", e)
            }
        }
    }

    /** 检查模型文件是否完整 */
    fun isModelComplete(modelDir: File, model: SherpaOnnxModel): Boolean {
        return File(modelDir, model.encoderFile).exists() &&
               File(modelDir, model.decoderFile).exists() &&
               File(modelDir, model.joinerFile).exists() &&
               File(modelDir, model.tokensFile).exists()
    }

    companion object {
        private const val TAG = "SherpaOnnxAsrEngine"
        private const val SAMPLE_RATE = 16000
    }
}
