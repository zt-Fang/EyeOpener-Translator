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
 * Sherpa-ONNX 流式 ASR 引擎（X-ASR / BN / Nemotron 3.5 共用，按 modelId 切换）。
 * 数据流：feedAudio → stream.acceptWaveform → decode → partial/final Flow。
 * Nemotron 支持 per-stream language，切语种无需重载模型。native 调用均 synchronized 保护。
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

    /** Nemotron per-stream language（仅 Nemotron 生效，其余模型忽略） */
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

    /** 加载模型目录（含 encoder/decoder/joiner ONNX 和 tokens.txt） */
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
                // modelPath 末段目录名即 modelId（.../models/sherpa-onnx/<modelId>/）
                val modelId = modelDir.name
                val model = SherpaOnnxModel.fromModelId(modelId) ?: SherpaOnnxModel.DEFAULT_ZH
                Log.i(TAG, "init: resolved model=$modelId -> ${model.name}")

                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDir, model.encoderFile).absolutePath,
                        decoder = File(modelDir, model.decoderFile).absolutePath,
                        joiner = File(modelDir, model.joinerFile).absolutePath,
                    ),
                    tokens = File(modelDir, model.tokensFile).absolutePath,
                    numThreads = 2,
                    modelType = model.modelType,
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

                // Nemotron 需 setOption("language", ...) 指定语种，否则 auto
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
     * 切换 Nemotron 识别语种（仅 Nemotron 生效，无需重载模型）；
     * stream 未创建时缓存，init 后生效。"auto" = 自动检测。
     */
    fun setLanguage(language: String) {
        val lang = language.ifEmpty { "auto" }
        currentLanguage = lang
        synchronized(lock) {
            val s = stream ?: return
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

    override fun feedAudio(samples: ShortArray) {
        // PCM16 → float32 [-1, 1]
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

    /** 解码并返回当前文本；到句尾时发 final 并重置 */
    override fun decodeAndGetResult(): String {
        synchronized(lock) {
            val rec = recognizer ?: return ""
            val s = stream ?: return ""
            try {
                // 循环上限防死循环
                var count = 0
                while (rec.isReady(s) && count < 100) {
                    rec.decode(s)
                    count++
                }

                val result = rec.getResult(s)
                val text = result.text.trim()

                if (rec.isEndpoint(s) && text.isNotEmpty()) {
                    finalResultFlow.tryEmit(text)
                    rec.reset(s)
                    lastPartial = ""
                    return text
                }

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
