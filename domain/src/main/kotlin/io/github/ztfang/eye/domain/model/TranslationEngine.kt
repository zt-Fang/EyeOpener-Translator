package io.github.ztfang.eye.domain.model

/**
 * 翻译引擎类型。
 *
 * 三种引擎分别对应不同的翻译后端：
 * - [LOCAL]  本地翻译：Google ML Kit 离线模型，响应最快
 * - [CLOUD]  云端翻译：Papago / 百度 / DeepL / Azure AI 文本翻译，4 选 1
 * - [AI]     AI 翻译：LLM API（OpenAI / Claude / DeepSeek 等），上下文理解最强
 *
 * 注：ASR 引擎选择与翻译引擎解耦，仅由源语言决定。
 * 若引擎不支持某语种，上层静默不响应（不弹错误）。
 */
enum class TranslationEngine {
    LOCAL,
    CLOUD,
    AI,
}

/**
 * 云端翻译服务商标号。
 * 用于 [TranslationEngine.CLOUD] 模式下选择具体的云端翻译 API。
 */
enum class CloudTranslationProvider {
    PAPAGO,
    BAIDU,
    DEEPL,
    AZURE,
    GOOGLE,
}
