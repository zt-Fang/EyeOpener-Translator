package io.github.ztfang.eye.domain.model

/**
 * 翻译引擎类型：LOCAL=ML Kit 离线；CLOUD=Papago/百度/DeepL/Azure/Google 选一；AI=LLM API。
 * 引擎不支持某语种时上层静默不响应。
 */
enum class TranslationEngine {
    LOCAL,
    CLOUD,
    AI,
}

/** 云端翻译服务商，用于 [TranslationEngine.CLOUD] 模式 */
enum class CloudTranslationProvider {
    PAPAGO,
    BAIDU,
    DEEPL,
    AZURE,
    GOOGLE,
}
