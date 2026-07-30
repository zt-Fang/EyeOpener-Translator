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
import java.security.MessageDigest
import javax.inject.Inject

/**
 * 百度翻译引擎。
 *
 * - 鉴权：appid + secretKey，签名 MD5(appid + q + salt + key)
 * - 协议：POST application/x-www-form-urlencoded
 * - API Key 输入格式：`appid:secretKey`（在 CloudTranslationSettingsScreen 中提示）
 * - 支持语种：极多，包含高棉语（km）
 *
 * 官方文档：https://fanyi-api.baidu.com/doc/21
 */
class BaiduTranslationEngine @Inject constructor(
    private val client: OkHttpClient
) {

    /** 百度支持的语种代码（应用内代码 → 百度代码） */
    private val codeMap = mapOf(
        "zh" to "zh", "en" to "en", "ja" to "jp", "ko" to "kor",
        "fr" to "fra", "es" to "spa", "de" to "de", "it" to "it",
        "ru" to "ru", "pt" to "pt", "th" to "th", "vi" to "vie",
        "id" to "id", "ms" to "may", "ar" to "ara", "hi" to "hi",
        "km" to "khm", "nl" to "nl", "pl" to "pl", "tr" to "tr",
        "uk" to "ukr", "sv" to "swe", "cs" to "cs", "da" to "dan",
        "fi" to "fin", "ro" to "rom", "sk" to "sk", "bg" to "bul",
        "el" to "el", "hu" to "hu", "no" to "nor", "nb" to "nor"
    )

    /** 判断语言对是否被百度支持 */
    fun supportsLanguage(source: String, target: String): Boolean =
        codeMap[source] != null && codeMap[target] != null

    /**
     * 执行翻译。
     * @param apiKey 格式 `appid:secretKey`，由调用方解析后传入
     */
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        apiKey: String
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val (appid, secret) = parseApiKey(apiKey)
                ?: error("百度 API Key 格式错误，应为 appid:secretKey")

            val source = codeMap[sourceLanguage]
                ?: error("百度不支持源语言: $sourceLanguage")
            val target = codeMap[targetLanguage]
                ?: error("百度不支持目标语言: $targetLanguage")

            val salt = System.currentTimeMillis().toString()
            val sign = md5("$appid$text$salt$secret")

            val form = FormBody.Builder()
                .add("q", text)
                .add("from", source)
                .add("to", target)
                .add("appid", appid)
                .add("salt", salt)
                .add("sign", sign)
                .build()

            val request = Request.Builder()
                .url(BASE_URL)
                .post(form)
                .build()

            Log.d(TAG, "百度翻译: $source→$target, text='$text'")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: error("Empty response")
            if (!response.isSuccessful) {
                Log.e(TAG, "百度 API ${response.code}: $body")
                error("百度 API ${response.code}: $body")
            }

            val json = JSONObject(body)
            // 错误码字段：error_code 存在表示失败
            if (json.has("error_code")) {
                error("百度错误 ${json.optString("error_code")}: ${json.optString("error_msg")}")
            }

            val translated = json.getJSONArray("trans_result")
                .getJSONObject(0)
                .getString("dst")

            TranslationResult(
                sourceText = text,
                translatedText = translated,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                engine = AppTranslationEngine.CLOUD
            )
        }
    }

    /** 解析 `appid:secretKey` 格式 */
    private fun parseApiKey(raw: String): Pair<String, String>? {
        val trimmed = raw.trim()
        val idx = trimmed.indexOf(':')
        if (idx <= 0 || idx >= trimmed.length - 1) return null
        return trimmed.substring(0, idx) to trimmed.substring(idx + 1)
    }

    /** MD5 哈希 */
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "BaiduTranslation"
        private const val BASE_URL = "https://fanyi-api.baidu.com/api/trans/vip/translate"
    }
}
