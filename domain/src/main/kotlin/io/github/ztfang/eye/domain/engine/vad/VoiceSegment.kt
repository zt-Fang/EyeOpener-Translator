package io.github.ztfang.eye.domain.engine.vad

/** 一段连续语音在 PCM 采样序列中的位置（endSample 含） */
data class VoiceSegment(
    val startSample: Int,
    val endSample: Int,
    val isVoice: Boolean
)
