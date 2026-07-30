package io.github.ztfang.eye.engine.translation.cloud

import android.util.Log
import io.github.ztfang.eye.domain.model.TranslationResult
import io.github.ztfang.eye.domain.model.TranslationEngine as AppTranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

/**
 * Papago 翻译引擎（Naver）。
 *
 * - 鉴权：X-Naver-Client-Id / X-Naver-Client-Secret（Header）
 * - 协议：POST application/x-www-form-urlencoded
 * - 支持语种：ko, en, ja, zh-CN, zh-TW, vi, id, th, de, ru, es, it, fr, hi, pt
 * - 不支持高棉语等小语种 → supportsLanguage 返回 false，由 UseCase 静默跳过
 *
 * 官方文档：https://developers.naver.com/docs/papago/papago-nmt-api-reference.md
 */
class PapagoTranslationEngine @Inject constructor(
    private val client: OkHttpClient
) {

    /** 判断语言对是否被 Papago 支持（zh → zh-CN） */
    fun supportsLanguage(source: String, target: String): Boolean =
        normalize(source) != null && normalize(target) != null

    /** 执行翻译 */
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        clientId: String,
        clientSecret: String
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val source = normalize(sourceLanguage)
                ?: error("Papago 不支持源语言: $sourceLanguage")
            val target = normalize(targetLanguage)
                ?: error("Papago 不支持目标语言: $targetLanguage")

            val form = FormBody.Builder()
                .add("source", source)
                .add("target", target)
                .add("text", text)
                .build()

            val request = Request.Builder()
                .url(BASE_URL)
                .addHeader("X-Naver-Client-Id", clientId)
                .addHeader("X-Naver-Client-Secret", clientSecret)
                .post(form)
                .build()

            Log.d(TAG, "Papago 翻译: $source→$target, text='$text'")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: error("Empty response")
            if (!response.isSuccessful) {
                Log.e(TAG, "Papago API ${response.code}: $body")
                error("Papago API ${response.code}: $body")
            }

            val translated = JSONObject(body)
                .getJSONObject("message")
                .getJSONObject("result")
                .getString("translatedText")

            TranslationResult(
                sourceText = text,
                translatedText = translated,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                engine = AppTranslationEngine.CLOUD
            )
        }
    }

    /** 将应用内语言代码归一化为 Papago 代码（zh → zh-CN） */
    private fun normalize(code: String): String? {
        return when (code) {
            "zh" -> "zh-CN"
            "ko", "en", "ja", "vi", "id", "th", "de", "ru",
            "es", "it", "fr", "hi", "pt" -> code
            "zh-CN", "zh-TW" -> code
            else -> null
        }
    }

    companion object {
        private const val TAG = "PapagoTranslation"
        private const val BASE_URL = "https://openapi.naver.com/v1/papago/n2mt"
    }
}
