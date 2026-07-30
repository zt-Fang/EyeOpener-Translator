package io.github.ztfang.eye.data.repository

import io.github.ztfang.eye.data.local.datastore.SettingsDataStore
import io.github.ztfang.eye.domain.model.CloudTranslationProvider
import io.github.ztfang.eye.domain.model.DisplayMode
import io.github.ztfang.eye.domain.model.TranslationEngine
import io.github.ztfang.eye.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 设置仓库实现：DataStore 基本类型 → 领域层枚举 */
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: SettingsDataStore
) : SettingsRepository {

    /** DUAL/TARGET_ONLY 为旧版本枚举名，做兼容映射 */
    override val displayMode: Flow<DisplayMode> = dataStore.displayMode.map { mode ->
        when (mode) {
            "DUAL" -> DisplayMode.BILINGUAL
            "TARGET_ONLY" -> DisplayMode.TRANSLATION_ONLY
            else -> try { DisplayMode.valueOf(mode) } catch (_: Exception) { DisplayMode.BILINGUAL }
        }
    }
    override suspend fun setDisplayMode(mode: DisplayMode) = dataStore.setDisplayMode(mode.name)

    // ============ 悬浮窗位置和大小 ============
    override val overlayX: Flow<Int> = dataStore.overlayX
    override val overlayY: Flow<Int> = dataStore.overlayY
    override val overlayWidth: Flow<Int> = dataStore.overlayWidth
    override val overlayHeight: Flow<Int> = dataStore.overlayHeight
    override suspend fun setOverlayX(x: Int) = dataStore.setOverlayX(x)
    override suspend fun setOverlayY(y: Int) = dataStore.setOverlayY(y)
    override suspend fun setOverlayWidth(width: Int) = dataStore.setOverlayWidth(width)
    override suspend fun setOverlayHeight(height: Int) = dataStore.setOverlayHeight(height)

    // ============ 语言设置 ============
    override val sourceLanguage: Flow<String> = dataStore.sourceLanguage
    override val targetLanguage: Flow<String> = dataStore.targetLanguage
    override suspend fun setSourceLanguage(code: String) = dataStore.setSourceLang(code)
    override suspend fun setTargetLanguage(code: String) = dataStore.setTargetLang(code)

    /** 旧值 FAST/HIGH_QUALITY 兼容映射为 LOCAL */
    override val translationEngine: Flow<TranslationEngine> = dataStore.translationEngine.map { engine ->
        when (engine) {
            "FAST", "HIGH_QUALITY" -> TranslationEngine.LOCAL
            else -> try { TranslationEngine.valueOf(engine) } catch (_: Exception) { TranslationEngine.LOCAL }
        }
    }
    override suspend fun setTranslationEngine(engine: TranslationEngine) =
        dataStore.setTranslationEngine(engine.name)

    override val cloudTranslationProvider: Flow<CloudTranslationProvider> =
        dataStore.cloudTranslationProvider.map { provider ->
            try { CloudTranslationProvider.valueOf(provider) } catch (_: Exception) {
                CloudTranslationProvider.PAPAGO
            }
        }
    override suspend fun setCloudTranslationProvider(provider: CloudTranslationProvider) =
        dataStore.setCloudTranslationProvider(provider.name)

    override val cloudTranslationApiKey: Flow<String> = dataStore.cloudTranslationApiKey
    override suspend fun setCloudTranslationApiKey(key: String) =
        dataStore.setCloudTranslationApiKey(key)

    // ============ API Key（已加密存储） ============
    override val openAiKey: Flow<String> = dataStore.openAiKey
    override val claudeKey: Flow<String> = dataStore.claudeKey
    override val openAiKeyProvider: Flow<String> = dataStore.openAiKeyProvider
    override suspend fun setOpenAiKey(key: String) = dataStore.setOpenAiKey(key)
    override suspend fun setClaudeKey(key: String) = dataStore.setClaudeKey(key)
    override suspend fun setOpenAiKeyProvider(provider: String) = dataStore.setOpenAiKeyProvider(provider)

    // ============ 个性化设置 ============
    override val accentColorIndex: Flow<Int> = dataStore.accentColorIndex
    override val backgroundTransparency: Flow<Float> = dataStore.backgroundTransparency
    override val fontSize: Flow<Float> = dataStore.fontSize
    override suspend fun setAccentColorIndex(index: Int) = dataStore.setAccentColorIndex(index)
    override suspend fun setBackgroundTransparency(value: Float) = dataStore.setBackgroundTransparency(value)
    override suspend fun setFontSize(value: Float) = dataStore.setFontSize(value)

    // ============ 音频源 ============
    override val audioSource: Flow<Int> = dataStore.audioSource
    override suspend fun setAudioSource(source: Int) = dataStore.setAudioSource(source)

    // ============ LLM 自定义设置 ============
    override val llmUrl: Flow<String> = dataStore.llmUrl
    override val llmModel: Flow<String> = dataStore.llmModel
    override val llmProvider: Flow<String> = dataStore.llmProvider
    /** LLM 配置是否就绪：当前 provider 对应的 API Key 和 URL 均非空（UI 据此弹配置提示） */
    override val isLlmConfigReady: Flow<Boolean> = kotlinx.coroutines.flow.combine(
        dataStore.llmProvider,
        dataStore.llmUrl,
        dataStore.openAiKey,
        dataStore.claudeKey
    ) { provider, url, openAi, claude ->
        val apiKey = when (provider.uppercase()) {
            "CLAUDE" -> claude
            else -> openAi  // OPEN_AI, QWEN, MINIMAX, MIMO, GEMINI, AGNES, DEEP_SEEK 共用 openAiKey
        }
        url.isNotBlank() && apiKey.isNotBlank()
    }.distinctUntilChanged()
    override suspend fun setLlmUrl(url: String) = dataStore.setLlmUrl(url)
    override suspend fun setLlmModel(model: String) = dataStore.setLlmModel(model)
    override suspend fun setLlmProvider(provider: String) = dataStore.setLlmProvider(provider)

    override val showOnboarding: Flow<Boolean> = dataStore.showOnboarding
    override suspend fun setShowOnboarding(show: Boolean) = dataStore.setShowOnboarding(show)

    override val interfaceLanguage: Flow<String> = dataStore.interfaceLanguage
    override suspend fun setInterfaceLanguage(language: String) = dataStore.setInterfaceLanguage(language)
}
