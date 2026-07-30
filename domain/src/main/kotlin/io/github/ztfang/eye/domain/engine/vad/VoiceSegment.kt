package io.github.ztfang.eye.domain.engine.vad

/**
 * 语音活动段，描述一段连续语音在 PCM 采样序列中的位置。
 *
 * @property startSample 起始采样点索引
 * @property endSample   结束采样点索引（含）
 * @property isVoice     是否为语音段
 */
data class VoiceSegment(
    val startSample: Int,
    val endSample: Int,
    val isVoice: Boolean
)
