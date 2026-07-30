package io.github.ztfang.eye.domain.usecase.translation

import io.github.ztfang.eye.domain.engine.translation.TranslationEngine
import io.github.ztfang.eye.domain.model.TranslationEngine as AppTranslationEngine
import io.github.ztfang.eye.domain.model.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 翻译用例。
 * UI 层通过此 UseCase 调用翻译，内部根据 [AppTranslationEngine] 路由到对应的引擎
 * （ML Kit / 云端 API / LLM）。
 *
 * - CLOUD 模式：根据当前选择的 [CloudTranslationProvider] 在引擎集合中查找对应实例；
 *   若对应云引擎未配置或语言对不支持，返回失败（不降级）。
 * - LOCAL / AI 模式：直接按枚举值查找。
 *
 * UI 层无需知道具体引擎实现。
 */
class TranslateUseCase(
    private val engines: Map<AppTranslationEngine, TranslationEngine>
) {

    /**
     * 执行翻译。
     * 1. 根据 engine 查找对应的引擎实例
     * 2. 检查引擎是否支持指定语言对
     * 3. 调用引擎翻译方法
     *
     * @return 成功返回 [TranslationResult]，失败返回异常信息
     */
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
            // 引擎不支持该语言对时静默返回失败，由上层决定不响应（不弹错误）
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
