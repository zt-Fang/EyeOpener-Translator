package io.github.ztfang.eye.domain.engine.asr

/** ASR (Automatic Speech Recognition) 引擎抽象 */
interface AsrEngine {
    /** 初始化模型（下载、加载等）。幂等。 */
    suspend fun init(modelPath: String): Result<Unit>

    /**
     * 释放模型资源。
     *
     * 注意：当前没有调用方——引擎切换走的是 [init] 内部的先释放再加载，
     * 进程退出则由系统回收。保留此方法是为了给「彻底卸载模型」留出正确入口，
     * 接入时需同时确认 native 侧释放不会影响正在进行的 [feedAudio]。
     */
    suspend fun release()

    /** 引擎是否就绪 */
    fun isReady(): Boolean

    /** 送入一帧音频数据（PCM16 short 数组，16kHz） */
    fun feedAudio(samples: ShortArray)

    /**
     * 触发解码并取回当前文本。
     *
     * 仅对需要显式驱动解码的引擎有意义（Sherpa-ONNX）；句尾判定在实现内部完成，
     * 结果通过 partial/final Flow 推送，返回值仅作诊断用。
     */
    fun decodeAndGetResult(): String

    /** 重置当前 stream，开始新的一句话识别 */
    fun resetStream()
}
