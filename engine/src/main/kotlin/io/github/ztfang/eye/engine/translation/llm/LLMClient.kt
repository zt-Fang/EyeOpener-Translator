/**
 * LLM HTTP 客户端
 *
 * 职责：
 * - 作为 Engine 层的 LLM 网关，统一封装大语言模型 HTTP 调用
 * - 支持 OpenAI / Claude / DeepSeek / Agnes AI 等 API
 * - 通过 [SettingsRepository] 从 DataStore 读取用户配置（provider、API Key、URL、Model）
 * - 依据 provider 路由到对应的请求格式与鉴权方式
 *
 * 三个入口方法：
 * - [translate]：智能模式翻译入口，附加 systemPrompt 约束模型为翻译角色
 * - [chat]：助手界面纯对话入口，不附加 systemPrompt，透传多轮消息
 * - [chatStream]：流式对话入口，通过 Flow 实时返回 token
 *
 * 说明：OpenAI 与 DeepSeek/Agnes AI 共用 Chat Completions 协议（兼容格式），
 * Claude 使用独立的 Messages API，请求体字段与响应解析均不同。
 *
 * 注意：所有网络调用必须切到 Dispatchers.IO，禁止阻塞主线程。
 */
package io.github.ztfang.eye.engine.translation.llm

import android.util.Log
import io.github.ztfang.eye.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * LLM 服务商枚举
 */
enum class LLMProvider {
    OPEN_AI,
    CLAUDE,
    DEEP_SEEK,
    QWEN,
    MINIMAX,
    MIMO,
    GEMINI,
    AGNES;

    val defaultBaseUrl: String get() = when (this) {
        OPEN_AI -> "https://api.openai.com/v1"
        CLAUDE -> "https://api.anthropic.com/v1"
        DEEP_SEEK -> "https://api.deepseek.com/v1"
        QWEN -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
        MINIMAX -> "https://api.minimax.chat/v1"
        MIMO -> "https://api.mimo.xiaomi.com/v1"
        GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai"
        AGNES -> "https://apihub.agnes-ai.com/v1"
    }

    val chatPath: String get() = when (this) {
        OPEN_AI, DEEP_SEEK, QWEN, MINIMAX, MIMO, GEMINI, AGNES -> "/chat/completions"
        CLAUDE -> "/messages"
    }

    val defaultModel: String get() = when (this) {
        OPEN_AI -> "gpt-4o-mini"
        CLAUDE -> "claude-3-haiku-20240307"
        DEEP_SEEK -> "deepseek-v4-flash"
        QWEN -> "qwen-turbo"
        MINIMAX -> "abab6.5s-chat"
        MIMO -> "mimo-7b-rl"
        GEMINI -> "gemini-1.5-flash"
        AGNES -> "agnes-2.0-flash"
    }

    val displayName: String get() = when (this) {
        OPEN_AI -> "OpenAI"
        CLAUDE -> "Claude"
        DEEP_SEEK -> "DeepSeek"
        QWEN -> "千问"
        MINIMAX -> "MiniMax"
        MIMO -> "MiMo"
        GEMINI -> "Gemini"
        AGNES -> "Agnes"
    }
}

class LLMClient @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun translate(
        text: String,
        systemPrompt: String,
        model: String
    ): String {
        val provider = resolveProvider()
        val apiKey = resolveApiKey(provider)
        val baseUrl = settingsRepository.llmUrl.first().trim()
            .ifEmpty { provider.defaultBaseUrl }
        val effectiveModel = model.ifBlank {
            settingsRepository.llmModel.first().trim().ifEmpty { provider.defaultModel }
        }
        val fullUrl = baseUrl.trimEnd('/') + provider.chatPath

        Log.d("LLMClient", "translate: provider=$provider, url=$fullUrl, model=$effectiveModel")

        return withContext(Dispatchers.IO) {
            when (provider) {
                LLMProvider.OPEN_AI, LLMProvider.DEEP_SEEK,
                LLMProvider.QWEN, LLMProvider.MINIMAX,
                LLMProvider.MIMO, LLMProvider.GEMINI,
                LLMProvider.AGNES ->
                    openAiTranslate(fullUrl, apiKey, effectiveModel, systemPrompt, text)
                LLMProvider.CLAUDE ->
                    claudeTranslate(fullUrl, apiKey, effectiveModel, systemPrompt, text)
            }
        }
    }

    suspend fun validateConfig(): Result<Unit> {
        val provider = resolveProvider()
        val apiKey = resolveApiKey(provider)
        val url = settingsRepository.llmUrl.first().trim()
        return when {
            url.isBlank() && apiKey.isBlank() -> Result.failure(
                IllegalStateException("请配置 API Key 和 URL")
            )
            apiKey.isBlank() -> Result.failure(
                IllegalStateException("请填写 API Key")
            )
            else -> Result.success(Unit)
        }
    }

    suspend fun chat(messages: List<Pair<String, String>>): String {
        val provider = resolveProvider()
        val apiKey = resolveApiKey(provider)
        val baseUrl = settingsRepository.llmUrl.first().trim()
            .ifEmpty { provider.defaultBaseUrl }
        val effectiveModel = settingsRepository.llmModel.first().trim()
            .ifEmpty { provider.defaultModel }
        val fullUrl = baseUrl.trimEnd('/') + provider.chatPath

        Log.d("LLMClient", "chat: provider=$provider, url=$fullUrl, msgs=${messages.size}")

        return withContext(Dispatchers.IO) {
            when (provider) {
                LLMProvider.OPEN_AI, LLMProvider.DEEP_SEEK,
                LLMProvider.QWEN, LLMProvider.MINIMAX,
                LLMProvider.MIMO, LLMProvider.GEMINI,
                LLMProvider.AGNES ->
                    openAiChat(fullUrl, apiKey, effectiveModel, messages)
                LLMProvider.CLAUDE ->
                    claudeChat(fullUrl, apiKey, effectiveModel, messages)
            }
        }
    }

    fun chatStream(messages: List<Pair<String, String>>): Flow<String> = flow {
        val provider = resolveProvider()
        val apiKey = resolveApiKey(provider)
        val baseUrl = settingsRepository.llmUrl.first().trim()
            .ifEmpty { provider.defaultBaseUrl }
        val effectiveModel = settingsRepository.llmModel.first().trim()
            .ifEmpty { provider.defaultModel }
        val fullUrl = baseUrl.trimEnd('/') + provider.chatPath

        Log.d("LLMClient", "chatStream: provider=$provider, url=$fullUrl")

        try {
            when (provider) {
                LLMProvider.OPEN_AI, LLMProvider.DEEP_SEEK,
                LLMProvider.QWEN, LLMProvider.MINIMAX,
                LLMProvider.MIMO, LLMProvider.GEMINI,
                LLMProvider.AGNES ->
                    openAiChatStream(fullUrl, apiKey, effectiveModel, messages).collect { emit(it) }
                LLMProvider.CLAUDE ->
                    claudeChatStream(fullUrl, apiKey, effectiveModel, messages).collect { emit(it) }
            }
        } catch (e: Exception) {
            // 流式失败 → 降级到非流式，一次性 emit 完整回复
            Log.w("LLMClient", "stream failed, fallback to non-stream: ${e.message}")
            val reply = withContext(Dispatchers.IO) {
                when (provider) {
                    LLMProvider.CLAUDE -> claudeChat(fullUrl, apiKey, effectiveModel, messages)
                    else -> openAiChat(fullUrl, apiKey, effectiveModel, messages)
                }
            }
            emit(reply)
        }
    }

    private fun openAiChatStream(
        url: String, apiKey: String, model: String,
        messages: List<Pair<String, String>>
    ): Flow<String> = flow {
        val msgs = JSONArray().apply {
            messages.forEach { (role, content) ->
                put(JSONObject().apply {
                    put("role", role); put("content", content)
                })
            }
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", msgs)
            put("max_tokens", 2048)
            put("temperature", 0.7)
            put("stream", true)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string() ?: ""
            Log.e("LLMClient", "stream API error ${response.code}: $body")
            error("API error ${response.code}: $body")
        }

        val reader = BufferedReader(InputStreamReader(response.body?.byteStream(), "UTF-8"))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { l ->
                    // 跳过空行、注释行（: 开头的心跳）
                    if (l.isBlank() || l.startsWith(":")) return@let
                    // 兼容 "data:" 和 "data: " 两种格式
                    val jsonStr = when {
                        l.startsWith("data: ") -> l.substring(6)
                        l.startsWith("data:") -> l.substring(5)
                        else -> return@let  // 非 data 行跳过
                    }
                    val trimmed = jsonStr.trim()
                    if (trimmed == "[DONE]") return@flow
                    try {
                        val json = JSONObject(trimmed)
                        val choices = json.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            if (delta != null && delta.has("content") && !delta.isNull("content")) {
                                val content = delta.optString("content", "")
                                if (content.isNotEmpty() && content != "null") {
                                    emit(content)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("LLMClient", "stream parse skip: $trimmed")
                    }
                }
            }
        } finally {
            reader.close()
            response.body?.close()
        }
    }

    private fun claudeChatStream(
        url: String, apiKey: String, model: String,
        messages: List<Pair<String, String>>
    ): Flow<String> = flow {
        val msgs = JSONArray().apply {
            messages.forEach { (role, content) ->
                put(JSONObject().apply {
                    put("role", role); put("content", content)
                })
            }
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("max_tokens", 2048)
            put("messages", msgs)
            put("stream", true)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string() ?: ""
            Log.e("LLMClient", "Claude stream API error ${response.code}: $body")
            error("Claude API error ${response.code}: $body")
        }

        val reader = BufferedReader(InputStreamReader(response.body?.byteStream(), "UTF-8"))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { l ->
                    if (l.startsWith("data: ")) {
                        val jsonStr = l.substring(6)
                        if (jsonStr == "[DONE]") return@flow
                        try {
                            val json = JSONObject(jsonStr)
                            val delta = json.getJSONArray("content")
                                .getJSONObject(0)
                            if (delta.has("text")) {
                                emit(delta.getString("text"))
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } finally {
            reader.close()
            response.body?.close()
        }
    }

    private fun openAiChat(
        url: String, apiKey: String, model: String,
        messages: List<Pair<String, String>>
    ): String {
        val msgs = JSONArray().apply {
            messages.forEach { (role, content) ->
                put(JSONObject().apply {
                    put("role", role); put("content", content)
                })
            }
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", msgs)
            put("max_tokens", 2048)
            put("temperature", 0.7)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: error("Empty response body")
        if (!response.isSuccessful) {
            Log.e("LLMClient", "chat API error ${response.code}: $body")
            error("API error ${response.code}: $body")
        }
        val json = JSONObject(body)
        return json.getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message")
            .getString("content").trim()
    }

    private fun claudeChat(
        url: String, apiKey: String, model: String,
        messages: List<Pair<String, String>>
    ): String {
        val msgs = JSONArray().apply {
            messages.forEach { (role, content) ->
                put(JSONObject().apply {
                    put("role", role); put("content", content)
                })
            }
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("max_tokens", 2048)
            put("messages", msgs)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: error("Empty response body")
        if (!response.isSuccessful) {
            Log.e("LLMClient", "chat API error ${response.code}: $body")
            error("API error ${response.code}: $body")
        }
        val json = JSONObject(body)
        return json.getJSONArray("content")
            .getJSONObject(0).getString("text").trim()
    }

    private suspend fun resolveProvider(): LLMProvider {
        val name = settingsRepository.llmProvider.first()
        return try { LLMProvider.valueOf(name) } catch (_: Exception) { LLMProvider.OPEN_AI }
    }

    private suspend fun resolveApiKey(provider: LLMProvider): String {
        val raw = when (provider) {
            LLMProvider.CLAUDE -> settingsRepository.claudeKey.first()
            else -> settingsRepository.openAiKey.first()
        }
        return raw.trim().replace(Regex("[\\r\\n\\s]+"), "")
    }

    private fun openAiTranslate(
        url: String, apiKey: String, model: String,
        systemPrompt: String, text: String
    ): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system"); put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user"); put("content", text)
            })
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 1024)
            put("temperature", 0.3)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: error("Empty response body")
        if (!response.isSuccessful) {
            Log.e("LLMClient", "API error ${response.code}: $body")
            error("API error ${response.code}: $body")
        }
        val json = JSONObject(body)
        return json.getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message")
            .getString("content").trim()
    }

    private fun claudeTranslate(
        url: String, apiKey: String, model: String,
        systemPrompt: String, text: String
    ): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user"); put("content", text)
            })
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("system", systemPrompt)
            put("messages", messages)
            put("max_tokens", 1024)
            put("temperature", 0.3)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: error("Empty response body")
        if (!response.isSuccessful) {
            Log.e("LLMClient", "Claude API error ${response.code}: $body")
            error("Claude API error ${response.code}: $body")
        }
        val json = JSONObject(body)
        return json.getJSONArray("content")
            .getJSONObject(0).getString("text").trim()
    }
}
