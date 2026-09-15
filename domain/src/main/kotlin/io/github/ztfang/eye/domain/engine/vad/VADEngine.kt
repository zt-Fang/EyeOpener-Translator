package io.github.ztfang.eye.domain.engine.vad

/**
 * VAD (Voice Activity Detection) 引擎抽象。
 *
 * 只保留实时判定所需的最小接口：调用方（SubtitleManager）逐帧调用 [processAudio]，
 * 按结果决定是否继续向 ASR 送音。
 *
 * 原有的 `isVoice` / `detectVoiceSegments` / `reset` 属于早期「整段音频切分 +
 * 手动重置」方案的遗留：当前实现是逐帧流式送音，切句由 VAD 静音帧计数与 ASR 自身
 * endpoint 负责，这三个方法已无任何调用方，故连同其配套模型（AudioData、
 * VoiceSegment）一并移除。
 */
interface VADEngine {
    /** 处理一帧 PCM16 音频，返回当前是否检测到语音 */
    fun processAudio(audioData: ShortArray): VadResult
}
