package io.github.ztfang.eye.domain.engine.vad

/**
 * VAD 判定结果。
 *
 * 只保留 [hasSpeech]：
 * - `noSpeech` 恒等于 `!hasSpeech`，属于冗余字段，调用方从未读取；
 * - `audioData` 从未被任何调用方消费（SubtitleManager 只用判定结果决定是否送音），
 *   保留它还会让每帧结果多持有一次帧缓冲引用，并使 data class 被迫手写
 *   `equals`/`hashCode` 做内容比较。
 */
data class VadResult(
    val hasSpeech: Boolean,
)
