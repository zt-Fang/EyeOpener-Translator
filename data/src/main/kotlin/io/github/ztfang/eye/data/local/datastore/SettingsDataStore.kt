package io.github.ztfang.eye.data.local.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ztfang.eye.data.local.crypto.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore 实例，存储在 filesDir/eye_opener_settings.preferences_pb */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "eye_opener_settings")

/**
 * 设置数据存储。
 * 基于 DataStore Preferences 实现，提供应用配置的持久化存储。
 * API Key 等敏感字段通过 [CryptoManager] 加密后存储。
 */
class SettingsDataStore(private val context: Context) {

    /** AES/GCM 加密器 — API Key 等敏感字段在存入 DataStore 前加密 */
    private val crypto = CryptoManager()

    // ============ 显示模式 ============
    val displayMode: Flow<String> = context.dataStore.data.map { it[DISPLAY_MODE] ?: "BILINGUAL" }

    // ============ 悬浮窗位置和大小 ============
    val overlayX: Flow<Int> = context.dataStore.data.map { it[OVERLAY_X] ?: 0 }
    val overlayY: Flow<Int> = context.dataStore.data.map { it[OVERLAY_Y] ?: 0 }
    val overlayWidth: Flow<Int> = context.dataStore.data.map { it[OVERLAY_WIDTH] ?: 0 }
    val overlayHeight: Flow<Int> = context.dataStore.data.map { it[OVERLAY_HEIGHT] ?: 0 }

    // ============ 语言设置 ============
    val sourceLanguage: Flow<String> = context.dataStore.data.map { it[SOURCE_LANG] ?: "en" }
    val targetLanguage: Flow<String> = context.dataStore.data.map { it[TARGET_LANG] ?: "zh" }

    // ============ 翻译引擎 ============
    val translationEngine: Flow<String> = context.dataStore.data.map { it[TRANSLATION_ENGINE] ?: "LOCAL" }

    // ============ 云端翻译服务商 ============
    val cloudTranslationProvider: Flow<String> = context.dataStore.data.map { it[CLOUD_TRANSLATION_PROVIDER] ?: "PAPAGO" }

    // ============ 云端翻译 API Key（加密存储） ============
    val cloudTranslationApiKey: Flow<String> = context.dataStore.data.map { crypto.decrypt(it[CLOUD_TRANSLATION_API_KEY] ?: "") }

    // ============ API Key（读取时自动解密） ============
    // DataStore 里存的是 Base64(IV + ciphertext + tag)
    val openAiKey: Flow<String> = context.dataStore.data.map { crypto.decrypt(it[OPENAI_KEY] ?: "") }
    val claudeKey: Flow<String> = context.dataStore.data.map { crypto.decrypt(it[CLAUDE_KEY] ?: "") }
    // 记录哪个服务商配置了 openAiKey，用于显示时判断是否回显
    val openAiKeyProvider: Flow<String> = context.dataStore.data.map { it[OPENAI_KEY_PROVIDER] ?: "" }

    // ============ 设置写入方法 ============
    suspend fun setDisplayMode(mode: String) {
        Log.i(TAG, "写入设置: displayMode=$mode")
        context.dataStore.edit { it[DISPLAY_MODE] = mode }
    }
    suspend fun setOverlayX(x: Int) { context.dataStore.edit { it[OVERLAY_X] = x } }
    suspend fun setOverlayY(y: Int) { context.dataStore.edit { it[OVERLAY_Y] = y } }
    suspend fun setOverlayWidth(width: Int) { context.dataStore.edit { it[OVERLAY_WIDTH] = width } }
    suspend fun setOverlayHeight(height: Int) { context.dataStore.edit { it[OVERLAY_HEIGHT] = height } }
    suspend fun setSourceLang(code: String) {
        Log.i(TAG, "写入设置: sourceLanguage=$code")
        context.dataStore.edit { it[SOURCE_LANG] = code }
    }
    suspend fun setTargetLang(code: String) {
        Log.i(TAG, "写入设置: targetLanguage=$code")
        context.dataStore.edit { it[TARGET_LANG] = code }
    }
    suspend fun setTranslationEngine(engine: String) {
        Log.i(TAG, "写入设置: translationEngine=$engine")
        context.dataStore.edit { it[TRANSLATION_ENGINE] = engine }
    }
    suspend fun setCloudTranslationProvider(provider: String) {
        Log.i(TAG, "写入设置: cloudTranslationProvider=$provider")
        context.dataStore.edit { it[CLOUD_TRANSLATION_PROVIDER] = provider }
    }
    suspend fun setCloudTranslationApiKey(key: String) {
        context.dataStore.edit {
            it[CLOUD_TRANSLATION_API_KEY] = if (key.isBlank()) "" else crypto.encrypt(key)
        }
    }

    /** API Key 存入前加密 */
    suspend fun setOpenAiKey(key: String) {
        context.dataStore.edit { it[OPENAI_KEY] = if (key.isBlank()) "" else crypto.encrypt(key) }
    }
    suspend fun setClaudeKey(key: String) {
        context.dataStore.edit { it[CLAUDE_KEY] = if (key.isBlank()) "" else crypto.encrypt(key) }
    }
    suspend fun setOpenAiKeyProvider(provider: String) {
        context.dataStore.edit { it[OPENAI_KEY_PROVIDER] = provider }
    }

    // ============ 个性化设置 ============
    // 颜色索引 0-5（对应 SwatchPalette 6 色），默认 1（蓝）
    val accentColorIndex: Flow<Int> = context.dataStore.data.map { it[ACCENT_COLOR_INDEX] ?: 1 }
    // 背景透明度 0..1，默认 0.75
    val backgroundTransparency: Flow<Float> = context.dataStore.data.map { it[BACKGROUND_TRANSPARENCY] ?: 0.75f }
    // 字体大小（sp，连续值 12f..32f），默认 18f
    val fontSize: Flow<Float> = context.dataStore.data.map { it[FONT_SIZE] ?: 18f }

    suspend fun setAccentColorIndex(index: Int) {
        context.dataStore.edit { it[ACCENT_COLOR_INDEX] = index.coerceIn(0, 5) }
    }
    suspend fun setBackgroundTransparency(value: Float) {
        context.dataStore.edit { it[BACKGROUND_TRANSPARENCY] = value.coerceIn(0f, 1f) }
    }
    suspend fun setFontSize(value: Float) {
        context.dataStore.edit { it[FONT_SIZE] = value.coerceIn(12f, 32f) }
    }

    // ============ 音频源 ============
    // 0=麦克风, 1=应用内声音(AudioPlaybackCapture, Android 10+)
    val audioSource: Flow<Int> = context.dataStore.data.map { it[AUDIO_SOURCE] ?: 0 }

    suspend fun setAudioSource(source: Int) {
        Log.i(TAG, "写入设置: audioSource=$source (0=麦克风, 1=应用内声音)")
        context.dataStore.edit { it[AUDIO_SOURCE] = source.coerceIn(0, 1) }
    }

    // ============ 界面语言 ============
    // "zh"=简体中文, "en"=English
    val interfaceLanguage: Flow<String> = context.dataStore.data.map { it[INTERFACE_LANGUAGE] ?: "zh" }

    suspend fun setInterfaceLanguage(language: String) {
        Log.i(TAG, "写入设置: interfaceLanguage=$language")
        context.dataStore.edit { it[INTERFACE_LANGUAGE] = language }
        // 同步写入 SharedPreferences，供 Application.attachBaseContext() 读取
        context.getSharedPreferences("eye_opener_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("interface_language", language)
            .apply()
    }

    // ============ LLM 自定义设置 ============
    val llmUrl: Flow<String> = context.dataStore.data.map { it[LLM_URL] ?: "" }
    val llmModel: Flow<String> = context.dataStore.data.map { it[LLM_MODEL] ?: "" }
    val llmProvider: Flow<String> = context.dataStore.data.map { it[LLM_PROVIDER] ?: "OPEN_AI" }

    suspend fun setLlmUrl(v: String) { context.dataStore.edit { it[LLM_URL] = v } }
    suspend fun setLlmModel(v: String) { context.dataStore.edit { it[LLM_MODEL] = v } }
    suspend fun setLlmProvider(v: String) { context.dataStore.edit { it[LLM_PROVIDER] = v } }

    // ============ 引导开关 ============
    /** 是否显示首次引导，默认 true（显示） */
    val showOnboarding: Flow<Boolean> = context.dataStore.data.map { it[SHOW_ONBOARDING] ?: true }
    suspend fun setShowOnboarding(show: Boolean) { context.dataStore.edit { it[SHOW_ONBOARDING] = show } }

    companion object {
        private const val TAG = "SettingsDataStore"
        // DataStore 键定义
        private val DISPLAY_MODE = stringPreferencesKey("display_mode")
        private val OVERLAY_X = intPreferencesKey("overlay_x")
        private val OVERLAY_Y = intPreferencesKey("overlay_y")
        private val OVERLAY_WIDTH = intPreferencesKey("overlay_width")
        private val OVERLAY_HEIGHT = intPreferencesKey("overlay_height")
        private val SOURCE_LANG = stringPreferencesKey("source_lang")
        private val TARGET_LANG = stringPreferencesKey("target_lang")
        private val TRANSLATION_ENGINE = stringPreferencesKey("translation_engine")
        private val CLOUD_TRANSLATION_PROVIDER = stringPreferencesKey("cloud_translation_provider")
        private val CLOUD_TRANSLATION_API_KEY = stringPreferencesKey("cloud_translation_api_key_enc")
        private val OPENAI_KEY = stringPreferencesKey("openai_key_enc")
        private val CLAUDE_KEY = stringPreferencesKey("claude_key_enc")
        private val OPENAI_KEY_PROVIDER = stringPreferencesKey("openai_key_provider")
        private val ACCENT_COLOR_INDEX = intPreferencesKey("accent_color_index")
        private val BACKGROUND_TRANSPARENCY = floatPreferencesKey("background_transparency")
        private val FONT_SIZE = floatPreferencesKey("font_size")
        private val AUDIO_SOURCE = intPreferencesKey("audio_source")

        private val LLM_URL = stringPreferencesKey("llm_url")
        private val LLM_MODEL = stringPreferencesKey("llm_model")
        private val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        private val SHOW_ONBOARDING = booleanPreferencesKey("show_onboarding")
        private val INTERFACE_LANGUAGE = stringPreferencesKey("interface_language")
    }
}
