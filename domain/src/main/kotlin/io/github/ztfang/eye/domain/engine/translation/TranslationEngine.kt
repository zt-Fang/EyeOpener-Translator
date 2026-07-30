package io.github.ztfang.eye.domain.engine.translation

import io.github.ztfang.eye.domain.model.TranslationEngine
import io.github.ztfang.eye.domain.model.TranslationResult

/**
 * 翻译引擎抽象接口。
 * 定义翻译能力的标准契约，实现类包括 ML Kit、云端 API、LLM API。
 */
interface TranslationEngine {

    /** 此引擎支持的翻译引擎类型 */
    val supportedEngine: TranslationEngine

    /** 判断引擎是否支持指定语言对 */
    fun supportsLanguage(source: String, target: String): Boolean

    /** 执行文本翻译 */
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): Result<TranslationResult>

    /** 释放引擎资源 */
    suspend fun release()
}
