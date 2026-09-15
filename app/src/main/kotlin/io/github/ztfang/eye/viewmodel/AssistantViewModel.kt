/** AI 助手 ViewModel：消息列表 + LLMClient 流式对话（无 systemPrompt，多轮透传）。 */
package io.github.ztfang.eye.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ztfang.eye.engine.translation.llm.LLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 助手聊天消息 */
data class AssistantMessage(
    val text: String,
    val isFromUser: Boolean,
    val timestamp: String = "",
    val isError: Boolean = false,
)

@HiltViewModel
class AssistantViewModel
    @Inject
    constructor(
        private val llmClient: LLMClient,
        val subtitleManager: SubtitleManager,
    ) : ViewModel() {
        private val _messages =
            MutableStateFlow<List<AssistantMessage>>(
                listOf(
                    AssistantMessage(
                        text = "我可以帮你做实时语音翻译、聊天对话、智能问答。你想做什么呢？",
                        isFromUser = false,
                        timestamp = "",
                    ),
                ),
            )
        val messages: StateFlow<List<AssistantMessage>> = _messages.asStateFlow()

        private val _isLoading = MutableStateFlow(false)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        /**
         * 发送用户消息并获取 AI 回复（流式）。
         *
         * 检查 API 就绪 → 追加用户消息 → 构造多轮历史 → chatStream 流式回复实时更新。
         */
        fun sendUserMessage(text: String) {
            if (text.isBlank()) return
            val userMsg = AssistantMessage(text, isFromUser = true, timestamp = nowTime())
            _messages.value = _messages.value + userMsg

            val isConfigReady = subtitleManager.isLlmConfigReady.value
            if (!isConfigReady) {
                val tipMsg =
                    AssistantMessage(
                        text = "API 未配置，请前往设置页面配置 API Key 和 URL 后重新发送消息。",
                        isFromUser = false,
                        timestamp = nowTime(),
                    )
                _messages.value = _messages.value + tipMsg
                return
            }

            val history =
                _messages.value.map { msg ->
                    val role = if (msg.isFromUser) "user" else "assistant"
                    role to msg.text
                }

            _isLoading.value = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    // StringBuilder 累积：String += 每 token 都是 O(n) 拷贝，长回复累计 O(n²)
                    val builder = StringBuilder()
                    var aiMsgId = -1
                    var lastUiUpdateMs = 0L
                    llmClient.chatStream(history).collect { token ->
                        builder.append(token)
                        val now = System.currentTimeMillis()
                        if (aiMsgId == -1) {
                            // 首个 token：插入 AI 消息占位
                            val aiMsg =
                                AssistantMessage(
                                    text = builder.toString(),
                                    isFromUser = false,
                                    timestamp = nowTime(),
                                )
                            _messages.value = _messages.value + aiMsg
                            aiMsgId = _messages.value.lastIndex
                            lastUiUpdateMs = now
                        } else if (now - lastUiUpdateMs >= STREAM_UI_MIN_INTERVAL_MS) {
                            // 100ms 合并刷新：StateFlow 每发一次就触发一次 Compose 重组
                            lastUiUpdateMs = now
                            flushStreamingText(aiMsgId, builder)
                        }
                    }
                    // 收尾强制刷新，避免最后一个窗口内的 token 丢失
                    flushStreamingText(aiMsgId, builder)
                } catch (e: Exception) {
                    Log.e(TAG, "LLM chat failed: ${e.message}", e)
                    val errMsg =
                        AssistantMessage(
                            text = "请求失败：${e.message ?: "未知错误"}",
                            isFromUser = false,
                            timestamp = nowTime(),
                            isError = true,
                        )
                    _messages.value = _messages.value + errMsg
                } finally {
                    _isLoading.value = false
                }
            }
        }

        /** 把流式累积文本写回第 [aiMsgId] 条消息（越界/已清空时静默跳过）。 */
        private fun flushStreamingText(
            aiMsgId: Int,
            builder: StringBuilder,
        ) {
            if (aiMsgId < 0) return
            val current = _messages.value
            if (aiMsgId !in current.indices) return
            val updated = current.toMutableList()
            updated[aiMsgId] = updated[aiMsgId].copy(text = builder.toString())
            _messages.value = updated
        }

        /** 清空对话 */
        fun clearMessages() {
            _messages.value = emptyList()
        }

        private fun nowTime(): String {
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            return sdf.format(java.util.Date())
        }

        companion object {
            private const val TAG = "AssistantViewModel"

            /** 流式回复 UI 合并刷新间隔（与 SubtitleManager 保持一致） */
            private const val STREAM_UI_MIN_INTERVAL_MS = 100L
        }
    }
