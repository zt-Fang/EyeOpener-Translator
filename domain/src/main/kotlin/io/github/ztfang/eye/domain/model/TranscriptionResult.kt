package io.github.ztfang.eye.domain.model

/** whisper 识别结果 */
data class TranscriptionResult(
    val text: String,
    val isFinal: Boolean,           // true = 完整句子, false = 临时片段
    val segments: List<Segment> = emptyList(),
) {
    data class Segment(
        val text: String,
        val t0Ms: Long,             // 起始时间 (ms)
        val t1Ms: Long,             // 结束时间 (ms)
    )
}
