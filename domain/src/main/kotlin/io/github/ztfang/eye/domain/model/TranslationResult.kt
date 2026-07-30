package io.github.ztfang.eye.domain.model

/** 翻译结果 */
data class TranslationResult(
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val engine: TranslationEngine,
    val isFinal: Boolean = false     // false = 临时翻译
)
