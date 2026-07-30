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
 * Silero VAD 引擎实现(基于 sherpa-onnx)。
 *
 * 替代 WebRtcVadEngine,采用 DNN 模型检测语音活动,精度更高。
 *
 * 核心特性:
 * - 模型: silero_vad.onnx (643KB, MIT license)
 * - 窗口: 512 samples (32ms @ 16kHz) - Silero 模型要求
 * - 阈值: 0.5
 * - 流式: acceptWaveform 持续送音频, isSpeechDetected 返回当前状态
 * - 内置: 模型放 assets/，首次启动从 assets 拷贝到 filesDir，无网络依赖
 *
 * VAD 帧参数(32ms/帧):
 * - speech start:    5~8 帧 (160~256ms)  — 连续语音帧阈值, 达到后判定开始说话
 * - soft silence:   15 帧 (480ms)         — 软静音, 超过后字幕 final 提交
 * - subtitle commit:25~32 帧 (800~1024ms) — 字幕提交阈值, 超过后强制提交
 * - stop ASR:       60 帧 (1920ms ≈ 2s)   — 停止送 ASR, 节省计算
 *
 * 适配说明:
 * - 项目音频帧为 480 samples (30ms),Silero 要求 512 samples (32ms)
 * - 内部维护缓冲区,累积到 512 samples 才送入 VAD
 * - 多余样本保留到下一帧
 *
 * 工作模式:
 * - processAudio 返回 VadResult,hasSpeech 基于当前累积窗口的检测结果
 * - 不切流,始终返回完整输入音频(保持与 WebRtcVadEngine 接口兼容)
 * - silentFrames / speechFrames 计数器由外部调用方维护(SubtitleManager)
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
     * 初始化 VAD 模型。
     * 模型内置在 assets/silero_vad.onnx，首次启动拷贝到 filesDir/models/vad/。
     * 拷贝失败不抛出异常,仅记录错误(VAD 缺失时 ASR 照常工作,只是少了静音过滤)。
     *
     * 必须在首次 processAudio 前调用。
     */
    suspend fun init(): Result<Unit> = withContext(Dispatchers.IO) {
        if (modelReady && vad != null) return@withContext Result.success(Unit)

        // 1. 从 assets 拷贝模型(挂起函数不能在 synchronized 内调用)
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

        // 2. 创建 VAD 实例(临界区内执行,保证线程安全)
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

    /**
     * 从 assets 拷贝 silero_vad.onnx 到 filesDir。
     */
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
     * 处理一帧音频(ShortArray PCM16),返回 VAD 结果。
     *
     * 内部流程:
     * 1. short → float 归一化([-1, 1])
     * 2. 累积到缓冲区,达到 windowSize (512) 后送入 VAD
     * 3. 调用 isSpeechDetected 更新内部状态
     * 4. 返回当前帧的判定结果(基于最新 VAD 状态)
     *
     * 注意: 输入帧 480 samples < 窗口 512 samples,不会立即触发检测,
     * 需累积约 2 帧(960 samples)才送入一次 VAD。返回值会延迟反映。
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
                // 1. PCM16 → float32 归一化
                val floatData = FloatArray(audioData.size) { i ->
                    audioData[i] / 32768.0f
                }

                // 2. 累积到缓冲区,达到 windowSize 后送入 VAD
                var inputOffset = 0
                while (inputOffset < floatData.size) {
                    val copyLen = minOf(WINDOW_SIZE - bufferOffset, floatData.size - inputOffset)
                    System.arraycopy(floatData, inputOffset, buffer, bufferOffset, copyLen)
                    bufferOffset += copyLen
                    inputOffset += copyLen

                    // 攒够一窗口,送入 VAD
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

    /** 判断音频是否包含语音(基于整段数据检测) */
    override fun isVoice(audio: AudioData): Boolean {
        val result = processAudio(audio.samples)
        return result.hasSpeech
    }

    /** 检测语音段(基于 VAD 队列的 segment 切分,精确到样本) */
    override fun detectVoiceSegments(audio: AudioData): List<VoiceSegment> {
        val v = vad ?: return emptyList()
        synchronized(lock) {
            try {
                v.reset()
                val floatData = FloatArray(audio.samples.size) { i ->
                    audio.samples[i] / 32768.0f
                }
                // 整段送入 VAD, 利用内置 segment 切分
                var pos = 0
                while (pos + WINDOW_SIZE <= floatData.size) {
                    val window = floatData.copyOfRange(pos, pos + WINDOW_SIZE)
                    v.acceptWaveform(window)
                    pos += WINDOW_SIZE
                }
                v.flush()
                // 从 VAD 队列取出所有语音段
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
