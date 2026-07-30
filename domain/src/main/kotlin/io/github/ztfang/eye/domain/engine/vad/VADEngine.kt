package io.github.ztfang.eye.domain.engine.vad

import io.github.ztfang.eye.domain.model.AudioData

/** VAD (Voice Activity Detection) 引擎抽象 */
interface VADEngine {
    /** 处理音频,返回 VAD 结果 */
    fun processAudio(audioData: ShortArray): VadResult

    /** 判断音频是否包含语音 */
    fun isVoice(audio: AudioData): Boolean

    /** 检测语音段，返回所有语音/静音片段的起止采样点 */
    fun detectVoiceSegments(audio: AudioData): List<VoiceSegment>

    /** 重置内部状态 */
    fun reset()
}
