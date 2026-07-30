package io.github.ztfang.eye.engine.translation.llm

import io.github.ztfang.eye.domain.engine.translation.TranslationEngine
import io.github.ztfang.eye.domain.model.TranslationEngine as AppTranslationEngine
import io.github.ztfang.eye.domain.model.TranslationResult
import io.github.ztfang.eye.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * LLM API 翻译引擎。
 * 基于 [LLMClient] 实现，支持 OpenAI/Claude/DeepSeek 三家大语言模型。
 * 特点：不限制语言对（大模型几乎支持所有语言），翻译质量最高但依赖网络。
 */
class LLMTranslationEngine @Inject constructor(
    private val client: LLMClient,
    private val settingsRepository: SettingsRepository
) : TranslationEngine {

    /** AI 翻译引擎 */
    override val supportedEngine: AppTranslationEngine = AppTranslationEngine.AI

    /**
     * 翻译提示词模板（与 SubtitleManager.translateWithPolishAndContext 统一）。
     * 适用所有 ASR 源：X-ASR 已带标点时润色规则近似 no-op；Vosk 无标点时润色生效。
     */
    private val basePrompt = """
        You are a real-time speech translation assistant.
        Task: lightly polish the ASR transcript and translate it from {source} to {target}.

        Polish rules (apply only when needed):
        1. Remove filler words and disfluencies (e.g. um, uh, like, you know, 所以, 然后, 那个)
        2. Fix punctuation and sentence boundaries
        3. Fix common ASR homophone errors

        Translation rules:
        1. Translate to {target}, preserve original meaning, stay coherent with context
        2. Return ONLY the translated text, no explanations, no quotes
        3. Do NOT wrap in markdown code blocks or quotes
        4. If input is empty or whitespace, output empty string
    """.trimIndent()

    /** LLM 几乎支持所有语言对，直接返回 true */
    override fun supportsLanguage(source: String, target: String): Boolean = true

    /**
     * 执行翻译。
     * 1. 构建带语言参数的 system prompt
     * 2. 调用 [LLMClient.translate] 发送请求
     * 3. 封装结果为 [TranslationResult]
     */
    override suspend fun translate(
        text: String, sourceLanguage: String, targetLanguage: String
    ): Result<TranslationResult> = runCatching {
        val prompt = basePrompt
            .replace("{source}", sourceLanguage).replace("{target}", targetLanguage)

        val translatedText = client.translate(
            text = text,
            systemPrompt = prompt,
            model = ""
        )

        TranslationResult(
            sourceText = text,
            translatedText = translatedText,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            engine = AppTranslationEngine.AI
        )
    }

    /** LLM 引擎无需释放资源（网络客户端由 OkHttp 管理） */
    override suspend fun release() { /* no-op */ }
}
