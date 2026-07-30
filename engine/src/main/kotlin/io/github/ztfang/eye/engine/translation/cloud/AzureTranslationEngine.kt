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
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Azure AI 文本翻译引擎。
 *
 * - 鉴权：Ocp-Apim-Subscription-Key（+ 可选 Ocp-Apim-Subscription-Region）
 * - 协议：POST application/json，body 为 [{"Text": "..."}]
 * - API Key 格式：`key` 或 `region:key`（region 为空时使用 global）
 * - 支持语种：极多，包含高棉语（km）
 *
 * 官方文档：https://learn.microsoft.com/azure/ai-services/translator/reference/v3-0-translate
 */
class AzureTranslationEngine @Inject constructor(
    private val client: OkHttpClient
) {

    /** Azure 支持的语种代码（应用内代码 → Azure 代码） */
    private val codeMap = mapOf(
        "af" to "af", "sq" to "sq", "am" to "am", "ar" to "ar",
        "hy" to "hy", "az" to "az", "bn" to "bn", "bs" to "bs",
        "bg" to "bg", "ca" to "ca", "zh" to "zh-Hans", "hr" to "hr",
        "cs" to "cs", "da" to "da", "nl" to "nl", "en" to "en",
        "et" to "et", "fi" to "fi", "fr" to "fr", "gl" to "gl",
        "ka" to "ka", "de" to "de", "el" to "el", "gu" to "gu",
        "ht" to "ht", "he" to "he", "hi" to "hi", "hu" to "hu",
        "is" to "is", "id" to "id", "ga" to "ga", "it" to "it",
        "ja" to "ja", "kn" to "kn", "kk" to "kk", "km" to "km",
        "ko" to "ko", "ku" to "ku", "lv" to "lv", "lt" to "lt",
        "mk" to "mk", "ms" to "ms", "ml" to "ml", "mt" to "mt",
        "mr" to "mr", "mn" to "mn-Cyrl", "my" to "my", "ne" to "ne",
        "nb" to "nb", "no" to "nb", "ps" to "ps", "fa" to "fa",
        "pl" to "pl", "pt" to "pt", "pa" to "pa", "ro" to "ro",
        "ru" to "ru", "sm" to "sm", "sr" to "sr-Cyrl", "sk" to "sk",
        "sl" to "sl", "es" to "es", "sw" to "sw", "sv" to "sv",
        "ta" to "ta", "te" to "te", "th" to "th", "to" to "to",
        "tr" to "tr", "uk" to "uk", "ur" to "ur", "vi" to "vi",
        "cy" to "cy", "zu" to "zu"
    )

    /** 判断语言对是否被 Azure 支持 */
    fun supportsLanguage(source: String, target: String): Boolean =
        codeMap[source] != null && codeMap[target] != null

    /** 执行翻译 */
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        apiKey: String
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val source = codeMap[sourceLanguage]
                ?: error("Azure 不支持源语言: $sourceLanguage")
            val target = codeMap[targetLanguage]
                ?: error("Azure 不支持目标语言: $targetLanguage")

            val (key, region) = parseApiKey(apiKey)

            val jsonBody = JSONArray().apply {
                put(JSONObject().put("Text", text))
            }.toString().toRequestBody(JSON_MEDIA)

            val url = "$BASE_URL?api-version=3.0&from=$source&to=$target"

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Ocp-Apim-Subscription-Key", key)
                .addHeader("Content-Type", "application/json; charset=UTF-8")
                .post(jsonBody)

            // region 非空时附加（multi-service 资源必须）
            if (region.isNotEmpty()) {
                requestBuilder.addHeader("Ocp-Apim-Subscription-Region", region)
            }

            Log.d(TAG, "Azure 翻译: $source→$target, text='$text'")
            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: error("Empty response")
            if (!response.isSuccessful) {
                Log.e(TAG, "Azure API ${response.code}: $body")
                error("Azure API ${response.code}: $body")
            }

            val translated = JSONArray(body)
                .getJSONObject(0)
                .getJSONArray("translations")
                .getJSONObject(0)
                .getString("text")

            TranslationResult(
                sourceText = text,
                translatedText = translated,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                engine = AppTranslationEngine.CLOUD
            )
        }
    }

    /** 解析 `key` 或 `region:key` 格式 */
    private fun parseApiKey(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        val idx = trimmed.indexOf(':')
        return if (idx <= 0 || idx >= trimmed.length - 1) {
            trimmed to ""  // 仅 key，无 region
        } else {
            trimmed.substring(0, idx) to trimmed.substring(idx + 1)
        }
    }

    companion object {
        private const val TAG = "AzureTranslation"
        private const val BASE_URL = "https://api.cognitive.microsofttranslator.com/translate"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
