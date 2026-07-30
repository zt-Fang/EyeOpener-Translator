package io.github.ztfang.eye.engine.vad

import android.content.Context
import android.util.Log
import io.github.ztfang.eye.domain.engine.vad.VADEngine
import io.github.ztfang.eye.domain.engine.vad.VadResult
import io.github.ztfang.eye.domain.engine.vad.VoiceSegment
import io.github.ztfang.eye.domain.model.AudioData
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Silero VAD 引擎（sherpa-onnx，silero_vad.onnx 643KB，assets 内置免下载）。
 * Silero 窗口 512 samples(32ms)，项目帧 480 samples(30ms)，内部缓冲攒够一窗再送。
 * 帧级阈值常量见 companion（计数器由 SubtitleManager 维护）。
 */
@Singleton
class SileroVadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : VADEngine {

    private val lock = Any()

    /** Sherpa-ONNX VAD 实例(懒加载) */
    @Volatile
    private var vad: Vad? = null

    /** PCM float 缓冲区(累积到 windowSize 才送入 VAD) */
    private val buffer = FloatArray(WINDOW_SIZE * 2)
    private var bufferOffset = 0

    /** 当前是否检测到语音(基于最近一次 acceptWaveform 结果) */
    @Volatile
    private var speechDetected = false

    /** 模型文件是否已就绪 */
    @Volatile
    private var modelReady = false

    /** 模型目标目录(filesDir/models/vad/) */
    private val modelDir: File by lazy {
        File(context.filesDir, "models/vad").also { it.mkdirs() }
    }

    /** 模型文件路径 */
    private val modelFile: File by lazy {
        File(modelDir, "silero_vad.onnx")
    }

    /**
     * 从 assets 拷贝模型并创建 VAD，须在首次 processAudio 前调用。
     * 拷贝失败仅报错不抛异常：VAD 缺失时 ASR 照常工作，仅少静音过滤。
     */
    suspend fun init(): Result<Unit> = withContext(Dispatchers.IO) {
        if (modelReady && vad != null) return@withContext Result.success(Unit)

        // 挂起函数不能在 synchronized 内调用，先拷贝
        if (!modelFile.exists() || modelFile.length() < MIN_MODEL_SIZE) {
            Log.i(TAG, "init: 模型不存在, 从 assets 拷贝...")
            val copyResult = copyFromAssets()
            if (copyResult.isFailure) {
                Log.e(TAG, "init: 模型拷贝失败: ${copyResult.exceptionOrNull()?.message}")
                return@withContext Result.failure(
                    copyResult.exceptionOrNull() ?: RuntimeException("拷贝失败")
                )
            }
            Log.i(TAG, "init: 模型拷贝完成")
        }

        synchronized(lock) {
            try {
                Log.i(TAG, "init: 模型已就绪 ${modelFile.absolutePath} (${modelFile.length()} B)")

                val config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = modelFile.absolutePath,
                        threshold = THRESHOLD,
                        minSilenceDuration = MIN_SILENCE_DURATION,
                        minSpeechDuration = MIN_SPEECH_DURATION,
                        windowSize = WINDOW_SIZE,
                        maxSpeechDuration = MAX_SPEECH_DURATION,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                )

                try { vad?.release() } catch (_: Exception) {}
                vad = Vad(config = config)
                modelReady = true
                bufferOffset = 0
                speechDetected = false
                Log.i(TAG, "init: Silero VAD 创建成功, window=$WINDOW_SIZE, threshold=$THRESHOLD")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "init failed: ${e.message}", e)
                modelReady = false
                Result.failure(e)
            }
        }
    }

    private fun copyFromAssets(): Result<Unit> {
        return try {
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            modelFile.delete()
            Result.failure(e)
        }
    }

    /**
     * 处理一帧 PCM16：归一化后攒够 512 样本送 VAD，返回最新检测状态。
     * 输入帧 480 < 窗口 512，约 2 帧才检测一次，结果有延迟。
     */
    override fun processAudio(audioData: ShortArray): VadResult {
        // VAD 未就绪时回退为"始终判定有语音"，避免 asrPaused 永不送音导致 ASR 哑火
        val v = vad ?: return VadResult(
            hasSpeech = true,
            noSpeech = false,
            audioData = audioData,
        )

        synchronized(lock) {
            try {
                val floatData = FloatArray(audioData.size) { i ->
                    audioData[i] / 32768.0f
                }

                var inputOffset = 0
                while (inputOffset < floatData.size) {
                    val copyLen = minOf(WINDOW_SIZE - bufferOffset, floatData.size - inputOffset)
                    System.arraycopy(floatData, inputOffset, buffer, bufferOffset, copyLen)
                    bufferOffset += copyLen
                    inputOffset += copyLen

                    if (bufferOffset >= WINDOW_SIZE) {
                        val window = FloatArray(WINDOW_SIZE) { i -> buffer[i] }
                        v.acceptWaveform(window)
                        speechDetected = v.isSpeechDetected()
                        bufferOffset = 0
                    }
                }

                return VadResult(
                    hasSpeech = speechDetected,
                    noSpeech = !speechDetected,
                    audioData = audioData,
                )
            } catch (e: Exception) {
                Log.e(TAG, "processAudio failed: ${e.message}", e)
                // 异常时同样回退为"始终有语音"，保证 ASR 持续送音
                return VadResult(
                    hasSpeech = true,
                    noSpeech = false,
                    audioData = audioData,
                )
            }
        }
    }

    override fun isVoice(audio: AudioData): Boolean {
        val result = processAudio(audio.samples)
        return result.hasSpeech
    }

    /** 整段送 VAD，用内置 segment 切分 */
    override fun detectVoiceSegments(audio: AudioData): List<VoiceSegment> {
        val v = vad ?: return emptyList()
        synchronized(lock) {
            try {
                v.reset()
                val floatData = FloatArray(audio.samples.size) { i ->
                    audio.samples[i] / 32768.0f
                }
                var pos = 0
                while (pos + WINDOW_SIZE <= floatData.size) {
                    val window = floatData.copyOfRange(pos, pos + WINDOW_SIZE)
                    v.acceptWaveform(window)
                    pos += WINDOW_SIZE
                }
                v.flush()
                val segments = mutableListOf<VoiceSegment>()
                while (!v.empty()) {
                    val seg = v.front()
                    segments.add(VoiceSegment(seg.start, seg.start + seg.samples.size, true))
                    v.pop()
                }
                return segments
            } catch (e: Exception) {
                Log.e(TAG, "detectVoiceSegments failed: ${e.message}", e)
                return emptyList()
            }
        }
    }

    /** 重置 VAD 内部状态(清空隐状态和缓冲区) */
    override fun reset() {
        synchronized(lock) {
            try {
                vad?.reset()
                bufferOffset = 0
                speechDetected = false
            } catch (e: Exception) {
                Log.e(TAG, "reset failed: ${e.message}", e)
            }
        }
    }

    private companion object {
        const val TAG = "SileroVadEngine"

        /** assets 中的模型文件名 */
        const val ASSET_NAME = "silero_vad.onnx"

        /** 最小模型文件大小(用于完整性校验, 略小于实际值留余量) */
        const val MIN_MODEL_SIZE = 600L * 1024L

        /** 采样率 16kHz */
        const val SAMPLE_RATE = 16000

        /** Silero VAD 窗口大小(512 samples = 32ms) */
        const val WINDOW_SIZE = 512

        /** 语音检测阈值(0.0-1.0, 高于阈值视为语音) */
        const val THRESHOLD = 0.5f

        /** 最小静音时长(秒),低于此值不切分段 */
        const val MIN_SILENCE_DURATION = 0.25f

        /** 最小语音时长(秒),低于此值不切分段 */
        const val MIN_SPEECH_DURATION = 0.25f

        /** 最大语音时长(秒),超过此值强制切分 */
        const val MAX_SPEECH_DURATION = 5.0f

        // ========== VAD 帧参数(32ms/帧, 供调用方 SubtitleManager 使用) ==========
        // 注意: 这些是配置常量, 实际计数器在 SubtitleManager 中维护

        /** speech start: 连续 5 帧语音 (160ms) 判定为开始说话 */
        const val SPEECH_START_FRAMES = 5

        /** soft silence: 连续 15 帧静音 (480ms) 触发软静音 */
        const val SOFT_SILENCE_FRAMES = 15

        /** subtitle commit: 连续 25~32 帧静音 (800~1024ms) 提交字幕 */
        const val SUBTITLE_COMMIT_FRAMES = 28  // 取中间值约 896ms

        /** stop ASR: 连续 60 帧静音 (~2s) 停止送入 ASR */
        const val STOP_ASR_FRAMES = 60
    }
}
