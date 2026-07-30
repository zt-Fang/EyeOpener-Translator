package io.github.ztfang.eye.engine.asr

import android.util.Log
import io.github.ztfang.eye.domain.engine.asr.AsrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vosk 流式 ASR 引擎（多语种 small 模型）。
 *
 * 数据流：录音线程 → feedAudio → acceptWaveForm → partial/final Flow
 * 无中间缓冲层，直接送 Vosk，避免 RingBuffer 线程安全问题。
 */
@Singleton
class VoskAsrEngine @Inject constructor() : AsrEngine {

    private val lock = Any()

    @Volatile
    private var model: Model? = null

    @Volatile
    private var recognizer: Recognizer? = null

    @Volatile
    private var currentLanguage: String = "zh"

    /** 当前已加载的模型路径，防止相同模型重复 init；外部只读以判断是否需要切换 */
    @Volatile
    var loadedModelPath: String? = null
        private set

    /** 是否正在初始化中，防止并发 init 竞态 */
    @Volatile
    private var isInitializing: Boolean = false

    // partial/final 结果流，extraBufferCapacity 避免实时音频丢结果
    val partialResultFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val finalResultFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun isReady(): Boolean = recognizer != null

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
                Log.i(TAG, "init: loading model from $modelPath")
                model = Model(modelPath)
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat()).also { it.setWords(false) }
                loadedModelPath = modelPath
                Log.i(TAG, "init: recognizer ready, language=$currentLanguage")
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

    suspend fun setLanguage(languageCode: String, modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (currentLanguage == languageCode && isReady()) {
                return@withContext Result.success(Unit)
            }
            currentLanguage = languageCode
            runCatching {
                releaseInternal()
                model = Model(modelPath)
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat()).also { it.setWords(false) }
                loadedModelPath = modelPath
                Log.i(TAG, "setLanguage: ready for $languageCode")
                Unit
            }.onFailure {
                Log.e(TAG, "setLanguage failed: ${it.message}", it)
                releaseInternal()
            }
        }
    }

    override suspend fun release() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
        loadedModelPath = null
    }

    /** 直送 Vosk 识别器；acceptWaveForm 轻量不阻塞录音线程，partial/final 经 Flow 发射 */
    override fun feedAudio(samples: ShortArray) {
        // 跳过过小帧，防止Kaldi特征提取断言崩溃（ExtractWindow/BestPathEnd等）
        if (samples.size < MIN_FRAME_SAMPLES) return
        synchronized(lock) {
            val rec = recognizer ?: return
            try {
                val hasFinal = rec.acceptWaveForm(samples, samples.size)
                val partialText = parsePartial(rec.getPartialResult())
                if (partialText.isNotEmpty()) {
                    Log.d(TAG, "feedAudio: partial=\"$partialText\"")
                    partialResultFlow.tryEmit(partialText)
                }
                if (hasFinal) {
                    val finalText = parseText(rec.getResult())
                    if (finalText.isNotEmpty()) {
                        Log.d(TAG, "feedAudio: final=\"$finalText\"")
                        finalResultFlow.tryEmit(finalText)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "feedAudio failed: ${e.message}")
            }
        }
    }

    override fun decodeAndGetResult(): String {
        synchronized(lock) {
            val rec = recognizer ?: return ""
            return try {
                parsePartial(rec.getPartialResult())
            } catch (e: Exception) {
                ""
            }
        }
    }

    override fun isEndpoint(): Boolean = false

    override fun resetStream() {
        synchronized(lock) {
            try {
                recognizer?.reset()
            } catch (e: Exception) {
                Log.e(TAG, "resetStream failed: ${e.message}")
            }
        }
    }

    /** 从 Vosk final JSON 提取 text 字段 */
    private fun parseText(json: String): String {
        if (json.isBlank()) return ""
        val match = TEXT_REGEX.find(json)
        return match?.groupValues?.get(1)?.unescapeJson()?.trim() ?: ""
    }

    /** 从 Vosk partial JSON 提取 partial 字段 */
    private fun parsePartial(json: String): String {
        if (json.isBlank()) return ""
        val match = PARTIAL_REGEX.find(json)
        return match?.groupValues?.get(1)?.unescapeJson()?.trim() ?: ""
    }

    companion object {
        private const val TAG = "VoskAsrEngine"
        private const val SAMPLE_RATE = 16000
        /** 最小送入帧长（样本数），低于此值会触发Kaldi特征提取断言崩溃 */
        private const val MIN_FRAME_SAMPLES = 400
        private val TEXT_REGEX = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)""")
        private val PARTIAL_REGEX = Regex(""""partial"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    }

    private fun String.unescapeJson(): String =
        this.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
}
