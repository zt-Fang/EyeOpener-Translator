package io.github.ztfang.eye.domain.model

/**
 * 翻译结果数据类。
 * 包含原文、译文、语言对和翻译引擎信息。
 */
data class TranslationResult(
    val sourceText: String,          // 原文
    val translatedText: String,      // 译文
    val sourceLanguage: String,      // 源语言代码
    val targetLanguage: String,      // 目标语言代码
    val engine: TranslationEngine,   // 翻译引擎
    val isFinal: Boolean = false     // 是否为最终结果（false 表示临时翻译）
)
