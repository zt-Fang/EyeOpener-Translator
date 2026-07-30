package io.github.ztfang.eye.domain.engine.asr

/** ASR (Automatic Speech Recognition) 引擎抽象 */
interface AsrEngine {
    /** 初始化模型（下载、加载等）。幂等。 */
    suspend fun init(modelPath: String): Result<Unit>

    /** 释放模型资源 */
    suspend fun release()

    /** 引擎是否就绪 */
    fun isReady(): Boolean

    /** 送入一帧音频数据（PCM16 short 数组，16kHz） */
    fun feedAudio(samples: ShortArray)

    /** 触发解码并获取当前结果 */
    fun decodeAndGetResult(): String

    /** 判断是否到达句尾 */
    fun isEndpoint(): Boolean

    /** 重置当前 stream，开始新的一句话识别 */
    fun resetStream()
}
