package io.github.ztfang.eye.domain.model

data class HistoryRecord(
    val id: Long = 0,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val timestamp: Long,
    val isFavorite: Boolean = false
)