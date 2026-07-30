package io.github.ztfang.eye.engine.translation.cloud

import android.util.Log
import io.github.ztfang.eye.domain.model.TranslationResult
import io.github.ztfang.eye.domain.model.TranslationEngine as AppTranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject

/**
 * Google Cloud Translation 引擎。
 *
 * - 鉴权：API Key 作为 query parameter (?key=KEY)
 * - 协议：POST application/json
 * - 支持语种：100+ 种，覆盖几乎所有 ISO-639-1 语言
 * - 语言代码与应用内代码一致（en/zh/ja/ko/fr/de/es 等），无需映射
 *
 * 官方文档：https://cloud.google.com/translate/docs/reference/rest/v2/translate
 */
class GoogleCloudTranslationEngine @Inject constructor(
    private val client: OkHttpClient
) {

    /** Google Cloud Translation 支持的常见语种（ISO-639-1） */
    private val supportedCodes = setOf(
        "en", "zh", "zh-CN", "zh-TW", "ja", "ko", "fr", "de", "es",
        "it", "ru", "pt", "nl", "pl", "tr", "ar", "hi", "th", "vi",
        "id", "ms", "bn", "fa", "he", "cs", "da", "fi", "el", "hu",
        "no", "ro", "sk", "sv", "uk", "ca", "hr", "lt", "lv", "sl",
        "bg", "et", "fil", "gu", "kk", "km", "ky", "lo", "mk", "mn",
        "my", "ne", "si", "sr", "ta", "te", "uz", "ka", "am", "az",
        "be", "bn-BD", "eo", "gl", "is", "ga", "mt", "mr", "pa", "sa",
        "sn", "sw", "cy", "tg", "br"
    )

    /** 判断语言对是否被 Google Cloud Translation 支持 */
    fun supportsLanguage(source: String, target: String): Boolean =
        supportedCodes.contains(source) && supportedCodes.contains(target)

    /** 执行翻译 */
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        apiKey: String
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey.trim()
            if (key.isEmpty()) error("Google Cloud Translation API Key 为空")

            // 请求体：JSON 格式
            val jsonBody = JSONObject().apply {
                put("q", text)
                put("source", sourceLanguage)
                put("target", targetLanguage)
                put("format", "text")
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL?key=$key")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            Log.d(TAG, "Google Cloud 翻译: $sourceLanguage→$targetLanguage, text='$text'")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: error("Empty response")
            if (!response.isSuccessful) {
                Log.e(TAG, "Google Cloud API ${response.code}: $body")
                error("Google Cloud API ${response.code}: $body")
            }

            val translated = JSONObject(body)
                .getJSONObject("data")
                .getJSONArray("translations")
                .getJSONObject(0)
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

    companion object {
        private const val TAG = "GoogleCloudTranslation"
        private const val BASE_URL = "https://translation.googleapis.com/language/translate/v2"
    }
}
