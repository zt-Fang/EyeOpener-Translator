package io.github.ztfang.eye.domain.engine.vad

/** VAD 判定结果 */
data class VadResult(
    val hasSpeech: Boolean,
    val noSpeech: Boolean = !hasSpeech,
    val audioData: ShortArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VadResult) return false
        return hasSpeech == other.hasSpeech && audioData.contentEquals(other.audioData)
    }

    override fun hashCode(): Int {
        return hasSpeech.hashCode() * 31 + audioData.contentHashCode()
    }
}
