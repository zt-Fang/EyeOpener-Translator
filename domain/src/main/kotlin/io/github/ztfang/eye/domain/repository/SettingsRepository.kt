package io.github.ztfang.eye.domain.repository

import io.github.ztfang.eye.domain.model.CloudTranslationProvider
import io.github.ztfang.eye.domain.model.DisplayMode
import io.github.ztfang.eye.domain.model.Language
import io.github.ztfang.eye.domain.model.TranslationEngine
import kotlinx.coroutines.flow.Flow

/**
 * 设置仓库接口。
 * 定义应用配置的读写能力，底层基于 DataStore 实现。
 */
interface SettingsRepository {

    // 显示模式
    val displayMode: Flow<DisplayMode>
    suspend fun setDisplayMode(mode: DisplayMode)

    // 悬浮窗位置和大小
    val overlayX: Flow<Int>
    val overlayY: Flow<Int>
    val overlayWidth: Flow<Int>
    val overlayHeight: Flow<Int>
    suspend fun setOverlayX(x: Int)
    suspend fun setOverlayY(y: Int)
    suspend fun setOverlayWidth(width: Int)
    suspend fun setOverlayHeight(height: Int)

    // 语言设置
    val sourceLanguage: Flow<String>
    val targetLanguage: Flow<String>
    suspend fun setSourceLanguage(code: String)
    suspend fun setTargetLanguage(code: String)

    // 翻译引擎（LOCAL/CLOUD/AI）
    val translationEngine: Flow<TranslationEngine>
    suspend fun setTranslationEngine(engine: TranslationEngine)

    // 云端翻译服务商（CLOUD 模式下选择具体云服务）
    val cloudTranslationProvider: Flow<CloudTranslationProvider>
    suspend fun setCloudTranslationProvider(provider: CloudTranslationProvider)

    // 云端翻译 API Key（各服务商共用一个字段，按当前 provider 读取）
    val cloudTranslationApiKey: Flow<String>
    suspend fun setCloudTranslationApiKey(key: String)

    // API Key（已加密存储）
    val openAiKey: Flow<String>
    val claudeKey: Flow<String>
    val openAiKeyProvider: Flow<String>
    suspend fun setOpenAiKey(key: String)
    suspend fun setClaudeKey(key: String)
    suspend fun setOpenAiKeyProvider(provider: String)

    // 个性化设置：侧边栏颜色索引、背景透明度、字体大小（sp 连续值）
    val accentColorIndex: Flow<Int>
    val backgroundTransparency: Flow<Float>
    val fontSize: Flow<Float>
    suspend fun setAccentColorIndex(index: Int)
    suspend fun setBackgroundTransparency(value: Float)
    suspend fun setFontSize(value: Float)

    // 音频源：0=麦克风，1=应用内声音（AudioPlaybackCapture，Android 10+）
    val audioSource: Flow<Int>
    suspend fun setAudioSource(source: Int)

    // LLM 自定义设置
    val llmUrl: Flow<String>
    val llmModel: Flow<String>
    val llmProvider: Flow<String>
    val isLlmConfigReady: Flow<Boolean>
    suspend fun setLlmUrl(url: String)
    suspend fun setLlmModel(model: String)
    suspend fun setLlmProvider(provider: String)

    // 引导开关
    val showOnboarding: Flow<Boolean>
    suspend fun setShowOnboarding(show: Boolean)

    // 界面语言
    val interfaceLanguage: Flow<String>
    suspend fun setInterfaceLanguage(language: String)
}
