package io.github.ztfang.eye.engine.translation.cloud

import android.util.Log
import io.github.ztfang.eye.domain.engine.translation.TranslationEngine
import io.github.ztfang.eye.domain.model.CloudTranslationProvider
import io.github.ztfang.eye.domain.model.TranslationEngine as AppTranslationEngine
import io.github.ztfang.eye.domain.model.TranslationResult
import io.github.ztfang.eye.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端翻译引擎路由器。
 *
 * - 实现 [TranslationEngine] 接口，注册到 [io.github.ztfang.eye.domain.usecase.translation.TranslateUseCase] 的 Map 中
 * - 根据 [SettingsRepository.cloudTranslationProvider] 路由到具体云服务商实现
 * - API Key 统一存储在 [SettingsRepository.cloudTranslationApiKey]，各 provider 按自身格式解析：
 *   - Papago: `clientId:clientSecret`
 *   - 百度: `appid:secretKey`
 *   - DeepL: 仅 AuthKey（Free Key 以 `:fx` 结尾）
 *   - Azure: `key` 或 `region:key`
 *   - Google: 仅 API Key
 * - 语言对不支持时返回 UnsupportedOperationException（静默跳过，由 UseCase 不响应）
 */
@Singleton
class CloudTranslationEngine @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val papago: PapagoTranslationEngine,
    private val baidu: BaiduTranslationEngine,
    private val deepL: DeepLTranslationEngine,
    private val azure: AzureTranslationEngine,
    private val google: GoogleCloudTranslationEngine
) : TranslationEngine {

    /** 云端翻译引擎 */
    override val supportedEngine: AppTranslationEngine = AppTranslationEngine.CLOUD

    /** 用于订阅 provider Flow 的长生命周期协程作用域（与 Singleton 一致） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 缓存的 provider 值，由订阅协程同步更新 */
    @Volatile
    private var cachedProvider: CloudTranslationProvider = CloudTranslationProvider.PAPAGO

    init {
        // 订阅 DataStore，确保 cachedProvider 与设置同步
        scope.launch {
            try {
                settingsRepository.cloudTranslationProvider.collect { p ->
                    cachedProvider = p
                    Log.d(TAG, "Cloud provider 切换: $p")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cloud provider 订阅失败，使用默认 PAPAGO", e)
            }
        }
    }

    /** 按当前 provider 判断语言对是否被支持 */
    override fun supportsLanguage(source: String, target: String): Boolean {
        return when (cachedProvider) {
            CloudTranslationProvider.PAPAGO -> papago.supportsLanguage(source, target)
            CloudTranslationProvider.BAIDU -> baidu.supportsLanguage(source, target)
            CloudTranslationProvider.DEEPL -> deepL.supportsLanguage(source, target)
            CloudTranslationProvider.AZURE -> azure.supportsLanguage(source, target)
            CloudTranslationProvider.GOOGLE -> google.supportsLanguage(source, target)
        }
    }

    /**
     * 执行翻译。
     * 1. 读取当前 provider 和 API Key
     * 2. API Key 为空时返回失败（静默跳过）
     * 3. 路由到对应 provider 实现
     */
    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): Result<TranslationResult> {
        val provider = cachedProvider
        val apiKey = settingsRepository.cloudTranslationApiKey.first().trim()

        if (apiKey.isBlank()) {
            Log.w(TAG, "云端翻译 API Key 未配置，provider=$provider")
            return Result.failure(IllegalStateException("Cloud API key not configured"))
        }

        return when (provider) {
            CloudTranslationProvider.PAPAGO -> {
                val (id, secret) = parsePapagoKey(apiKey)
                    ?: return Result.failure(IllegalStateException("Papago key format error: clientId:clientSecret"))
                papago.translate(text, sourceLanguage, targetLanguage, id, secret)
            }
            CloudTranslationProvider.BAIDU ->
                baidu.translate(text, sourceLanguage, targetLanguage, apiKey)
            CloudTranslationProvider.DEEPL ->
                deepL.translate(text, sourceLanguage, targetLanguage, apiKey)
            CloudTranslationProvider.AZURE ->
                azure.translate(text, sourceLanguage, targetLanguage, apiKey)
            CloudTranslationProvider.GOOGLE ->
                google.translate(text, sourceLanguage, targetLanguage, apiKey)
        }
    }

    /** 云端引擎无需释放资源（OkHttp 客户端由外部管理） */
    override suspend fun release() { /* no-op */ }

    /** 解析 Papago `clientId:clientSecret` 格式 */
    private fun parsePapagoKey(raw: String): Pair<String, String>? {
        val idx = raw.indexOf(':')
        if (idx <= 0 || idx >= raw.length - 1) return null
        return raw.substring(0, idx) to raw.substring(idx + 1)
    }

    companion object {
        private const val TAG = "CloudTranslationEngine"
    }
}

