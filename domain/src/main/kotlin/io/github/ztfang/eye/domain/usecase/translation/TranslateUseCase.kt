package io.github.ztfang.eye.domain.usecase.translation

import io.github.ztfang.eye.domain.engine.translation.TranslationEngine
import io.github.ztfang.eye.domain.model.TranslationEngine as AppTranslationEngine
import io.github.ztfang.eye.domain.model.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 翻译用例：按 [AppTranslationEngine] 路由到对应引擎（ML Kit / 云端 API / LLM）。
 * 云引擎未配置或语言对不支持时返回失败，不降级。
 */
class TranslateUseCase(
    private val engines: Map<AppTranslationEngine, TranslationEngine>
) {

    /** 执行翻译；语言对不支持时静默失败，由上层决定不响应 */
    suspend fun execute(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        engine: AppTranslationEngine = AppTranslationEngine.LOCAL
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        android.util.Log.i(LOG_TAG, ">>> UseCase: text='$text', src=$sourceLanguage, tgt=$targetLanguage, engine=$engine")

        val impl = engines[engine]
        if (impl == null) {
            android.util.Log.e(LOG_TAG, "引擎未注册: engine=$engine, 已注册=${engines.keys}")
            return@withContext Result.failure(
                IllegalArgumentException("No engine registered for: $engine")
            )
        }

        if (!impl.supportsLanguage(sourceLanguage, targetLanguage)) {
            android.util.Log.i(LOG_TAG, "语言对不支持: $sourceLanguage → $targetLanguage (engine=$engine)")
            return@withContext Result.failure(
                UnsupportedOperationException(
                    "Engine $engine does not support $sourceLanguage → $targetLanguage"
                )
            )
        }

        android.util.Log.i(LOG_TAG, ">>> 调用engine.translate: engine=$engine")
        val result = impl.translate(text, sourceLanguage, targetLanguage)
        android.util.Log.i(LOG_TAG, ">>> engine.translate返回: success=${result.isSuccess}, text='${result.getOrNull()?.translatedText}'")
        result
    }

    companion object {
        private const val LOG_TAG = "TranslateUseCase"
    }
}
