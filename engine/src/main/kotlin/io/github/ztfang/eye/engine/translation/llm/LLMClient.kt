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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
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
import javax.inject.Singleton

/** 协程取消时立即中断底层 OkHttp 连接，避免网络差时 IO 线程被阻塞调用占满 */
private suspend fun Call.executeCancellable() =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { this@executeCancellable.cancel() }
        cont.resumeWith(runCatching { this@executeCancellable.execute() })
    }

/**
 * LLM 服务商枚举
 */
enum class LLMProvider {
    OPEN_AI,
    OPENROUTER,
    CLAUDE,
    DEEP_SEEK,
    ZHIPU,
    QWEN,
    MINIMAX,
    MIMO,
    GEMINI,
    AGNES,
    SILICONFLOW,
    CUSTOM,
    ;

    val defaultBaseUrl: String get() =
        when (this) {
            OPEN_AI -> "https://api.openai.com/v1"
            OPENROUTER -> "https://openrouter.ai/api/v1"
            CLAUDE -> "https://api.anthropic.com/v1"
            DEEP_SEEK -> "https://api.deepseek.com/v1"
            ZHIPU -> "https://open.bigmodel.cn/api/paas/v4"
            QWEN -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
            MINIMAX -> "https://api.minimax.chat/v1"
            MIMO -> "https://api.mimo.xiaomi.com/v1"
            GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai"
            AGNES -> "https://apihub.agnes-ai.com/v1"
            SILICONFLOW -> "https://api.siliconflow.cn/v1"
            CUSTOM -> ""
        }

    val chatPath: String get() =
        when (this) {
            OPEN_AI, OPENROUTER, DEEP_SEEK, ZHIPU, QWEN, MINIMAX, MIMO, GEMINI, AGNES, SILICONFLOW, CUSTOM -> "/chat/completions"
            CLAUDE -> "/messages"
        }

    /**
     * 仅收录经各服务商官网核实、支持 SSE 流式输出、可用于翻译/对话的 chat 模型。
     * 嵌入/视觉/已废弃模型已剔除（如 Gemini 1.5 系列、DeepSeek 旧 deepseek-v4-flash 命名等）。
     * defaultModel 取首项；CUSTOM 无预设，留空由用户自由填写。
     */
    val models: List<String> get() =
        when (this) {
            OPEN_AI ->
                listOf(
                    "gpt-5.1",
                    "gpt-5",
                    "gpt-5-mini",
                    "gpt-4.1",
                    "gpt-4o",
                    "gpt-4o-mini",
                )
            OPENROUTER ->
                listOf(
                    "openai/gpt-5.1",
                    "anthropic/claude-sonnet-4-6",
                    "deepseek/deepseek-chat",
                    "google/gemini-2.5-flash",
                    "qwen/qwen3-235b-a22b",
                    "z-ai/glm-5",
                    "meta-llama/llama-3.3-70b-instruct:free",
                )
            CLAUDE ->
                listOf(
                    "claude-sonnet-4-6",
                    "claude-opus-4-7",
                    "claude-haiku-4-5-20251001",
                )
            DEEP_SEEK ->
                listOf(
                    "deepseek-chat",
                    "deepseek-reasoner",
                )
            ZHIPU ->
                listOf(
                    "glm-5",
                    "glm-4.7",
                    "glm-4.7-flash",
                    "glm-4.6",
                    "glm-4.5",
                    "glm-4-flash",
                )
            QWEN ->
                listOf(
                    "qwen-max",
                    "qwen-plus",
                    "qwen-turbo",
                    "qwen3-max",
                    "qwen3-coder",
                )
            MINIMAX ->
                listOf(
                    "abab6.5s-chat",
                    "abab6.5g-chat",
                    "abab5.5-chat",
                )
            MIMO ->
                listOf(
                    "mimo-v2.5-pro",
                    "mimo-v2-pro",
                    "mimo-v2-omni",
                    "mimo-v2-flash",
                )
            GEMINI ->
                listOf(
                    "gemini-2.5-flash",
                    "gemini-2.5-flash-lite",
                    "gemini-2.5-pro",
                    "gemini-3-flash-preview",
                    "gemini-3.1-flash",
                    "gemini-3.1-pro-preview",
                )
            AGNES ->
                listOf(
                    "agnes-2.5-flash",
                    "agnes-2.5-pro",
                    "agnes-2.5-pro-alpha",
                    "agnes-2.0-flash",
                )
            SILICONFLOW ->
                listOf(
                    "deepseek-ai/DeepSeek-V3",
                    "Qwen/Qwen3-8B",
                    "deepseek-ai/DeepSeek-V4-Flash",
                    "THUDM/GLM-5",
                    "THUDM/GLM-4.7",
                    "moonshotai/Kimi-K2",
                    "Qwen/Qwen3.6-27B",
                    "MiniMaxAI/MiniMax-M3",
                )
            CUSTOM -> emptyList()
        }

    val defaultModel: String get() = models.firstOrNull() ?: ""

    val displayName: String get() =
        when (this) {
            OPEN_AI -> "OpenAI"
            OPENROUTER -> "OpenRouter"
            CLAUDE -> "Claude"
            DEEP_SEEK -> "DeepSeek"
            ZHIPU -> "智谱"
            QWEN -> "千问"
            MINIMAX -> "MiniMax"
            MIMO -> "MiMo"
            GEMINI -> "Gemini"
            AGNES -> "Agnes"
            SILICONFLOW -> "硅基流动"
            CUSTOM -> "自定义"
        }
}

/**
 * LLM HTTP 客户端（进程内单例）
 *
 * 单例意义：内部持有 OkHttpClient（连接池 + 线程池）。不加 @Singleton 时每个注入点
 * （SubtitleManager / AssistantViewModel / SettingsViewModel / ModelPreparer / LLMTranslationEngine）
 * 各持一份，5 份连接池互不复用 → 每次翻译都重新走 TCP + TLS 握手，弱网下延迟与失败率显著上升。
 * 同目录其他引擎（MlKit/Cloud/Vosk/Sherpa/Vad/ModelPreparer）均已是 @Singleton。
 *
 * 复用 AppModule 提供的共享 OkHttpClient：通过 newBuilder() 派生，**共享同一个连接池与
 * 调度线程池**，仅覆盖超时（LLM 需要比云端翻译更短的读超时，见下），避免两份连接池各自建连。
 */
@Singleton
class LLMClient
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        sharedClient: OkHttpClient,
    ) {
        private val client =
            sharedClient
                .newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                // 读超时 30s：上层 translate/chat 用 withTimeout(7s) 兜底，120s 会让慢调用在协程取消后仍
                // 阻塞 IO 线程直到 120s，网络差时 IO 线程池被占满。30s 作为最坏情况上限即可。
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        suspend fun translate(
            text: String,
            systemPrompt: String,
            model: String,
        ): String {
            val provider = resolveProvider()
            val apiKey = resolveApiKey(provider)
            val baseUrl =
                settingsRepository.llmUrl
                    .first()
                    .trim()
                    .ifEmpty { provider.defaultBaseUrl }
            val effectiveModel =
                model.ifBlank {
                    settingsRepository.llmModel
                        .first()
                        .trim()
                        .ifEmpty { provider.defaultModel }
                }
            val fullUrl = baseUrl.trimEnd('/') + provider.chatPath

            Log.d("LLMClient", "translate: provider=$provider, url=$fullUrl, model=$effectiveModel")

            return withContext(Dispatchers.IO) {
                when (provider) {
                    LLMProvider.OPEN_AI, LLMProvider.OPENROUTER,
                    LLMProvider.DEEP_SEEK, LLMProvider.ZHIPU,
                    LLMProvider.QWEN, LLMProvider.MINIMAX,
                    LLMProvider.MIMO, LLMProvider.GEMINI,
                    LLMProvider.AGNES, LLMProvider.SILICONFLOW,
                    LLMProvider.CUSTOM,
                    ->
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
                url.isBlank() && apiKey.isBlank() ->
                    Result.failure(
                        IllegalStateException("请配置 API Key 和 URL"),
                    )
                apiKey.isBlank() ->
                    Result.failure(
                        IllegalStateException("请填写 API Key"),
                    )
                else -> Result.success(Unit)
            }
        }

        suspend fun chat(messages: List<Pair<String, String>>): String {
            val provider = resolveProvider()
            val apiKey = resolveApiKey(provider)
            val baseUrl =
                settingsRepository.llmUrl
                    .first()
                    .trim()
                    .ifEmpty { provider.defaultBaseUrl }
            val effectiveModel =
                settingsRepository.llmModel
                    .first()
                    .trim()
                    .ifEmpty { provider.defaultModel }
            val fullUrl = baseUrl.trimEnd('/') + provider.chatPath

            Log.d("LLMClient", "chat: provider=$provider, url=$fullUrl, msgs=${messages.size}")

            return withContext(Dispatchers.IO) {
                when (provider) {
                    LLMProvider.OPEN_AI, LLMProvider.OPENROUTER,
                    LLMProvider.DEEP_SEEK, LLMProvider.ZHIPU,
                    LLMProvider.QWEN, LLMProvider.MINIMAX,
                    LLMProvider.MIMO, LLMProvider.GEMINI,
                    LLMProvider.AGNES, LLMProvider.SILICONFLOW,
                    LLMProvider.CUSTOM,
                    ->
                        openAiChat(fullUrl, apiKey, effectiveModel, messages)
                    LLMProvider.CLAUDE ->
                        claudeChat(fullUrl, apiKey, effectiveModel, messages)
                }
            }
        }

        fun chatStream(messages: List<Pair<String, String>>): Flow<String> =
            flow {
                val provider = resolveProvider()
                val apiKey = resolveApiKey(provider)
                val baseUrl =
                    settingsRepository.llmUrl
                        .first()
                        .trim()
                        .ifEmpty { provider.defaultBaseUrl }
                val effectiveModel =
                    settingsRepository.llmModel
                        .first()
                        .trim()
                        .ifEmpty { provider.defaultModel }
                val fullUrl = baseUrl.trimEnd('/') + provider.chatPath

                Log.d("LLMClient", "chatStream: provider=$provider, url=$fullUrl")

                // 流式阶段是否已经吐出过内容：决定失败时能否降级重试。
                // 已经吐过内容再降级重发，会造成「同一句翻译两份结果」叠加到 UI 上，故必须禁止。
                var emittedAny = false
                try {
                    when (provider) {
                        LLMProvider.OPEN_AI, LLMProvider.OPENROUTER,
                        LLMProvider.DEEP_SEEK, LLMProvider.ZHIPU,
                        LLMProvider.QWEN, LLMProvider.MINIMAX,
                        LLMProvider.MIMO, LLMProvider.GEMINI,
                        LLMProvider.AGNES, LLMProvider.SILICONFLOW,
                        LLMProvider.CUSTOM,
                        ->
                            openAiChatStream(fullUrl, apiKey, effectiveModel, messages).collect { token ->
                                emittedAny = true
                                emit(token)
                            }
                        LLMProvider.CLAUDE ->
                            claudeChatStream(fullUrl, apiKey, effectiveModel, messages).collect { token ->
                                emittedAny = true
                                emit(token)
                            }
                    }
                } catch (e: Exception) {
                    // ==============================
                    // 错误可见化（2026-09-13 修正）
                    // ==============================
                    // 旧实现把真实错误只写进 Log.w 然后静默降级 —— 而 vivo 等国产 ROM 会屏蔽
                    // 第三方应用 logcat，用户和开发者都拿不到线索，UI 只剩「翻译失败」四个字。
                    // 现在：把原始错误信息（含 HTTP code / 响应体片段）保留在异常里向上传播，
                    // 上层 [SubtitleManager.buildAiErrorMessage] 会据此给出可行动的文案。
                    val detail = (e.message ?: e.javaClass.simpleName).trim()
                    Log.w("LLMClient", "stream failed, fallback to non-stream: $detail")

                    // 已吐过内容 → 不允许降级：重发会把重复内容叠加到同一行。
                    if (emittedAny) throw e

                    // 配置/鉴权/路径类错误降级也没有意义（非流式会以同样原因失败），直接上抛，
                    // 让用户看到真实原因，而不是被"降级后再失败"掩盖成通用文案。
                    if (isConfigLevelError(detail)) throw e

                    try {
                        val reply =
                            withContext(Dispatchers.IO) {
                                when (provider) {
                                    LLMProvider.CLAUDE -> claudeChat(fullUrl, apiKey, effectiveModel, messages)
                                    else -> openAiChat(fullUrl, apiKey, effectiveModel, messages)
                                }
                            }
                        emit(reply)
                    } catch (fallbackError: Exception) {
                        // 降级也失败：把两次错误都带上，避免只看到后者而丢失根因
                        val fallbackDetail = (fallbackError.message ?: fallbackError.javaClass.simpleName).trim()
                        Log.e("LLMClient", "fallback non-stream also failed: $fallbackDetail")
                        throw RuntimeException("流式失败($detail)；非流式重试亦失败($fallbackDetail)", fallbackError)
                    }
                }
            }.flowOn(Dispatchers.IO) // SSE 逐行读取是阻塞 IO：必须切到 IO 线程，禁止在调用方（可能是主线程）上读流

        /**
         * 判断是否为「换种请求方式也没用」的配置级错误。
         *
         * 这类错误（鉴权失败、路径/模型不存在、限流）与非流式共用相同的 URL/Key/Model，
         * 降级重试只是白等一轮，还会把真实原因藏在后面的失败里。
         *
         * 注意 HTTP 状态码的匹配方式：旧实现用 `detail.contains("401")` 这种裸子串匹配，
         * 响应体里任何含这几个数字的文本（token 计数、模型名、时间戳）都会被误判，
         * 从而错误地跳过降级。这里要求数字出现在 HTTP 状态语境中。
         */
        private fun isConfigLevelError(detail: String): Boolean =
            CONFIG_LEVEL_MARKERS.any { detail.contains(it, ignoreCase = true) } ||
                HTTP_STATUS_ERROR_REGEX.containsMatchIn(detail)

        private fun openAiChatStream(
            url: String,
            apiKey: String,
            model: String,
            messages: List<Pair<String, String>>,
        ): Flow<String> =
            flow {
                val msgs =
                    JSONArray().apply {
                        messages.forEach { (role, content) ->
                            put(
                                JSONObject().apply {
                                    put("role", role)
                                    put("content", content)
                                },
                            )
                        }
                    }
                val requestBody =
                    JSONObject()
                        .apply {
                            put("model", model)
                            put("messages", msgs)
                            put("max_tokens", 2048)
                            put("temperature", 0.7)
                            put("stream", true)
                        }.toString()
                        .toRequestBody(jsonMediaType)

                val request =
                    Request
                        .Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "text/event-stream")
                        .post(requestBody)
                        .build()

                val call = client.newCall(request)
                val response = call.executeCancellable()
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
                            val jsonStr =
                                when {
                                    l.startsWith("data: ") -> l.substring(6)
                                    l.startsWith("data:") -> l.substring(5)
                                    else -> return@let // 非 data 行跳过
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
            url: String,
            apiKey: String,
            model: String,
            messages: List<Pair<String, String>>,
        ): Flow<String> =
            flow {
                val msgs =
                    JSONArray().apply {
                        messages.forEach { (role, content) ->
                            put(
                                JSONObject().apply {
                                    put("role", role)
                                    put("content", content)
                                },
                            )
                        }
                    }
                val requestBody =
                    JSONObject()
                        .apply {
                            put("model", model)
                            put("max_tokens", 2048)
                            put("messages", msgs)
                            put("stream", true)
                        }.toString()
                        .toRequestBody(jsonMediaType)

                val request =
                    Request
                        .Builder()
                        .url(url)
                        .addHeader("x-api-key", apiKey)
                        .addHeader("anthropic-version", "2023-06-01")
                        .addHeader("Content-Type", "application/json")
                        .post(requestBody)
                        .build()

                val call = client.newCall(request)
                val response = call.executeCancellable()
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
                                    val delta =
                                        json
                                            .getJSONArray("content")
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

        private suspend fun openAiChat(
            url: String,
            apiKey: String,
            model: String,
            messages: List<Pair<String, String>>,
        ): String {
            val msgs =
                JSONArray().apply {
                    messages.forEach { (role, content) ->
                        put(
                            JSONObject().apply {
                                put("role", role)
                                put("content", content)
                            },
                        )
                    }
                }
            val requestBody =
                JSONObject()
                    .apply {
                        put("model", model)
                        put("messages", msgs)
                        put("max_tokens", 2048)
                        put("temperature", 0.7)
                    }.toString()
                    .toRequestBody(jsonMediaType)

            val request =
                Request
                    .Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

            // 协程取消时立即中断底层连接，避免 withTimeout(7s) 取消后阻塞调用仍占用 IO 线程到 30s 读超时
            val call = client.newCall(request)
            val response = call.executeCancellable()
            val body = response.body?.string() ?: error("Empty response body")
            if (!response.isSuccessful) {
                Log.e("LLMClient", "chat API error ${response.code}: $body")
                error("API error ${response.code}: $body")
            }
            val json = JSONObject(body)
            return json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }

        private suspend fun claudeChat(
            url: String,
            apiKey: String,
            model: String,
            messages: List<Pair<String, String>>,
        ): String {
            val msgs =
                JSONArray().apply {
                    messages.forEach { (role, content) ->
                        put(
                            JSONObject().apply {
                                put("role", role)
                                put("content", content)
                            },
                        )
                    }
                }
            val requestBody =
                JSONObject()
                    .apply {
                        put("model", model)
                        put("max_tokens", 2048)
                        put("messages", msgs)
                    }.toString()
                    .toRequestBody(jsonMediaType)

            val request =
                Request
                    .Builder()
                    .url(url)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

            val call = client.newCall(request)
            val response = call.executeCancellable()
            val body = response.body?.string() ?: error("Empty response body")
            if (!response.isSuccessful) {
                Log.e("LLMClient", "chat API error ${response.code}: $body")
                error("API error ${response.code}: $body")
            }
            val json = JSONObject(body)
            return json
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
        }

        /**
         * 从服务商 API 拉取可用模型列表（GET {baseUrl}/models）。
         * OpenAI 兼容服务商（含硅基流动/DeepSeek/智谱/千问/Gemini OpenAI 兼容层等）返回 data[].id，
         * Claude Messages API 返回 data[].id，均以 Bearer / x-api-key 鉴权。
         * 返回去重后的模型 ID 列表；失败时 Result.failure（UI 层回退到 [LLMProvider.models] 预设）。
         */
        suspend fun fetchModels(
            provider: LLMProvider,
            baseUrl: String,
            apiKey: String,
        ): Result<List<String>> =
            try {
                val base = baseUrl.trim().ifEmpty { provider.defaultBaseUrl }.trimEnd('/')
                val ids =
                    withContext(Dispatchers.IO) {
                        val request =
                            Request
                                .Builder()
                                .url("$base/models")
                                .apply {
                                    if (provider == LLMProvider.CLAUDE) {
                                        addHeader("x-api-key", apiKey)
                                        addHeader("anthropic-version", "2023-06-01")
                                    } else {
                                        addHeader("Authorization", "Bearer $apiKey")
                                    }
                                }.build()
                        val call = client.newCall(request)
                        val response = call.executeCancellable()
                        val body = response.body?.string() ?: ""
                        if (!response.isSuccessful) {
                            Log.e("LLMClient", "fetchModels error ${response.code}: $body")
                            error("HTTP ${response.code}")
                        }
                        val json = JSONObject(body)
                        val arr = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val id = obj.optString("id", "").ifEmpty { obj.optString("name", "") }
                            if (id.isNotBlank()) list.add(id)
                        }
                        list.distinct()
                    }
                Result.success(ids)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

        private suspend fun resolveProvider(): LLMProvider {
            val name = settingsRepository.llmProvider.first()
            return try {
                LLMProvider.valueOf(name)
            } catch (_: Exception) {
                LLMProvider.OPEN_AI
            }
        }

        private suspend fun resolveApiKey(provider: LLMProvider): String {
            val raw =
                when (provider) {
                    LLMProvider.CLAUDE -> settingsRepository.claudeKey.first()
                    else -> settingsRepository.openAiKey.first()
                }
            // 复用预编译正则：翻译每句都会走到这里，旧实现每次 new Regex
            return raw.trim().replace(WHITESPACE_REGEX, "")
        }

        private suspend fun openAiTranslate(
            url: String,
            apiKey: String,
            model: String,
            systemPrompt: String,
            text: String,
        ): String {
            val messages =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", text)
                        },
                    )
                }
            val requestBody =
                JSONObject()
                    .apply {
                        put("model", model)
                        put("messages", messages)
                        put("max_tokens", 1024)
                        put("temperature", 0.3)
                    }.toString()
                    .toRequestBody(jsonMediaType)

            val request =
                Request
                    .Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

            val call = client.newCall(request)
            val response = call.executeCancellable()
            val body = response.body?.string() ?: error("Empty response body")
            if (!response.isSuccessful) {
                Log.e("LLMClient", "API error ${response.code}: $body")
                error("API error ${response.code}: $body")
            }
            val json = JSONObject(body)
            return json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }

        private suspend fun claudeTranslate(
            url: String,
            apiKey: String,
            model: String,
            systemPrompt: String,
            text: String,
        ): String {
            val messages =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", text)
                        },
                    )
                }
            val requestBody =
                JSONObject()
                    .apply {
                        put("model", model)
                        put("system", systemPrompt)
                        put("messages", messages)
                        put("max_tokens", 1024)
                        put("temperature", 0.3)
                    }.toString()
                    .toRequestBody(jsonMediaType)

            val request =
                Request
                    .Builder()
                    .url(url)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

            val call = client.newCall(request)
            val response = call.executeCancellable()
            val body = response.body?.string() ?: error("Empty response body")
            if (!response.isSuccessful) {
                Log.e("LLMClient", "Claude API error ${response.code}: $body")
                error("Claude API error ${response.code}: $body")
            }
            val json = JSONObject(body)
            return json
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
        }

        companion object {
            /** 去掉 API Key 中的换行/空白（用户从网页复制时常带上）。预编译避免每次调用重新编译。 */
            private val WHITESPACE_REGEX = Regex("[\\r\\n\\s]+")

            /**
             * HTTP 状态语境下的错误码。要求数字紧跟在 `API error` / `HTTP` / `status` / `code`
             * 之后，避免把响应体里无关的 "401" 之类数字误判成配置级错误。
             */
            private val HTTP_STATUS_ERROR_REGEX =
                Regex("""(?i)\b(?:api\s+error|http|status(?:\s+code)?|code)\s*[:=]?\s*(401|403|404|429)\b""")

            /**
             * 「换请求方式也没用」的文本特征（不含数字型状态码，那部分交给
             * [HTTP_STATUS_ERROR_REGEX] 做语境匹配）：
             *  - 鉴权类：invalid api key、unauthorized、authentication
             *  - 路径/模型类：model not found、no such model
             *  - 限流类：rate limit
             */
            private val CONFIG_LEVEL_MARKERS =
                listOf(
                    "invalid api key",
                    "invalid_api_key",
                    "unauthorized",
                    "authentication",
                    "model not found",
                    "no such model",
                    "rate limit",
                )
        }
    }
