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
 * DeepL 翻译引擎。
 *
 * - 鉴权：Authorization: DeepL-Auth-Key KEY
 * - 协议：POST application/x-www-form-urlencoded
 * - Free Key 以 `:fx` 结尾 → 使用 api-free.deepl.com；Pro Key 使用 api.deepl.com
 * - 支持语种：EN, DE, FR, ES, IT, JA, KO, ZH, RU, PT, NL, PL, BG, CS, DA, ET, FI, EL, HU, LT, LV, RO, SK, SL, SV
 * - 不支持高棉语 → supportsLanguage 返回 false
 *
 * 官方文档：https://developers.deepl.com/docs/api-reference/translate/openapi-spec-for-text-translation
 */
class DeepLTranslationEngine @Inject constructor(
    private val client: OkHttpClient
) {

    /** DeepL 支持的语种代码（应用内代码 → DeepL 大写代码） */
    private val codeMap = mapOf(
        "en" to "EN", "de" to "DE", "fr" to "FR", "es" to "ES",
        "it" to "IT", "ja" to "JA", "ko" to "KO", "zh" to "ZH",
        "ru" to "RU", "pt" to "PT", "nl" to "NL", "pl" to "PL",
        "bg" to "BG", "cs" to "CS", "da" to "DA", "et" to "ET",
        "fi" to "FI", "el" to "EL", "hu" to "HU", "lt" to "LT",
        "lv" to "LV", "ro" to "RO", "sk" to "SK", "sl" to "SL",
        "sv" to "SV"
    )

    /** 判断语言对是否被 DeepL 支持 */
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
                ?: error("DeepL 不支持源语言: $sourceLanguage")
            val target = codeMap[targetLanguage]
                ?: error("DeepL 不支持目标语言: $targetLanguage")

            val key = apiKey.trim()
            val baseUrl = if (key.endsWith(":fx")) FREE_BASE_URL else PRO_BASE_URL

            val form = FormBody.Builder()
                .add("text", text)
                .add("source_lang", source)
                .add("target_lang", target)
                .build()

            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "DeepL-Auth-Key $key")
                .post(form)
                .build()

            Log.d(TAG, "DeepL 翻译: $source→$target, text='$text'")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: error("Empty response")
            if (!response.isSuccessful) {
                Log.e(TAG, "DeepL API ${response.code}: $body")
                error("DeepL API ${response.code}: $body")
            }

            val translated = JSONObject(body)
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

    companion object {
        private const val TAG = "DeepLTranslation"
        private const val PRO_BASE_URL = "https://api.deepl.com/v2/translate"
        private const val FREE_BASE_URL = "https://api-free.deepl.com/v2/translate"
    }
}
