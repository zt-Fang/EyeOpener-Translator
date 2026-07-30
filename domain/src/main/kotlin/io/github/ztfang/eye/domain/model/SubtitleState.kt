package io.github.ztfang.eye.domain.model

/** ASR 引擎类型枚举 */
enum class AsrEngineType {
    /** Vosk 多语种引擎（默认，支持多种语言） */
    VOSK,
    /** Sherpa-ONNX 流式 Zipformer（中文优化，准确率更高） */
    SHERPA_ONNX,
    /** Sherpa-ONNX 孟加拉语专用（Zipformer bn-vosk，全模式共用） */
    SHERPA_ONNX_BN,
    /** Sherpa-ONNX Nemotron 3.5 多语种（40 locale，per-stream language） */
    SHERPA_ONNX_NEMOTRON
}

/** 单句字幕条目 */
data class SubtitleLine(
    val sourceText: String,
    val translatedText: String = "",
    val subtitleType: SubtitleType = SubtitleType.FINAL
)

/** 字幕 UI 完整状态 */
data class SubtitleState(
    val lines: List<SubtitleLine> = emptyList(),
    val engine: TranslationEngine = TranslationEngine.LOCAL,
    val displayMode: DisplayMode = DisplayMode.BILINGUAL,
    val sourceLanguage: String = "en",
    val targetLanguage: String = "zh",
    /** 当前使用的 ASR 引擎类型 */
    val asrEngineType: AsrEngineType = AsrEngineType.VOSK,
)
