/**
 * 助手界面 ViewModel
 *
 * 职责：
 * - 管理聊天消息列表
 * - 调用 LLMClient 进行纯对话（无系统提示词）
 * - 暴露发送状态（loading）给 UI
 * - 支持流式输出，实时显示 AI 回复
 *
 * 设计说明：
 * - 不附加 systemPrompt，让模型自由对话
 * - 多轮消息直接透传给 LLM API，保持上下文
 * - 使用 chatStream 流式接口，token 实时推送
 */
package io.github.ztfang.eye.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ztfang.eye.engine.translation.llm.LLMClient
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val isError: Boolean = false
)

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val llmClient: LLMClient,
    val subtitleManager: SubtitleManager
) : ViewModel() {

    private val _messages = MutableStateFlow<List<AssistantMessage>>(listOf(
        AssistantMessage(
            text = "我可以帮你做实时语音翻译、聊天对话、智能问答。你想做什么呢？",
            isFromUser = false,
            timestamp = ""
        )
    ))
    val messages: StateFlow<List<AssistantMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * 发送用户消息并获取 AI 回复（流式）。
     *
     * 流程：
     * 1. 检查 API 配置是否就绪，未就绪则显示友好提示
     * 2. 追加用户消息到列表
     * 3. 构造多轮消息历史（role=user/assistant）
     * 4. 调用 LLMClient.chatStream 获取流式回复
     * 5. 实时更新 AI 消息到列表
     */
    fun sendUserMessage(text: String) {
        if (text.isBlank()) return
        val userMsg = AssistantMessage(text, isFromUser = true, timestamp = nowTime())
        _messages.value = _messages.value + userMsg

        val isConfigReady = subtitleManager.isLlmConfigReady.value
        if (!isConfigReady) {
            val tipMsg = AssistantMessage(
                text = "API 未配置，请前往设置页面配置 API Key 和 URL 后重新发送消息。",
                isFromUser = false,
                timestamp = nowTime()
            )
            _messages.value = _messages.value + tipMsg
            return
        }

        val history = _messages.value.map { msg ->
            val role = if (msg.isFromUser) "user" else "assistant"
            role to msg.text
        }

        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var accumulatedText = ""
                var aiMsgId = -1
                llmClient.chatStream(history).collect { token ->
                    accumulatedText += token
                    if (aiMsgId == -1) {
                        val aiMsg = AssistantMessage(
                            text = accumulatedText,
                            isFromUser = false,
                            timestamp = nowTime()
                        )
                        _messages.value = _messages.value + aiMsg
                        aiMsgId = _messages.value.lastIndex
                    } else {
                        val updated = _messages.value.toMutableList()
                        updated[aiMsgId] = updated[aiMsgId].copy(text = accumulatedText)
                        _messages.value = updated
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LLM chat failed: ${e.message}", e)
                val errMsg = AssistantMessage(
                    text = "请求失败：${e.message ?: "未知错误"}",
                    isFromUser = false,
                    timestamp = nowTime(),
                    isError = true
                )
                _messages.value = _messages.value + errMsg
            } finally {
                _isLoading.value = false
            }
        }
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
    }
}
