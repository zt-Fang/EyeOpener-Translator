package io.github.ztfang.eye.domain.model

/** 表示一段 PCM 16-bit 音频数据 */
data class AudioData(
    val samples: ShortArray,
    val sampleRate: Int = 16000,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioData) return false
        return samples.contentEquals(other.samples) && sampleRate == other.sampleRate
    }

    override fun hashCode(): Int {
        return samples.contentHashCode() * 31 + sampleRate
    }
}
