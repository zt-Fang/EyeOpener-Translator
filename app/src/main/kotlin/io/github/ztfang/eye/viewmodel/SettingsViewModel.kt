package io.github.ztfang.eye.viewmodel
import android.util.Log
import io.github.ztfang.eye.domain.model.CloudTranslationProvider
import io.github.ztfang.eye.domain.model.DisplayMode
import io.github.ztfang.eye.domain.model.DownloadProgress
import io.github.ztfang.eye.domain.model.ModelCatalog
import io.github.ztfang.eye.domain.model.ModelState
import io.github.ztfang.eye.domain.model.SherpaOnnxModel
import io.github.ztfang.eye.domain.model.TranslationEngine
import io.github.ztfang.eye.domain.model.ModelFileSpec
import io.github.ztfang.eye.domain.repository.SettingsRepository
import io.github.ztfang.eye.domain.usecase.model.ModelManagementUseCase
import io.github.ztfang.eye.engine.ModelPreparer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelManagementUseCase: ModelManagementUseCase,
    private val modelPreparer: ModelPreparer,
) : ViewModel() {
    val displayMode: Flow<DisplayMode> = settingsRepository.displayMode
    val sourceLanguage: Flow<String> = settingsRepository.sourceLanguage
    val targetLanguage: Flow<String> = settingsRepository.targetLanguage
    val translationEngine: Flow<TranslationEngine> = settingsRepository.translationEngine
    val cloudTranslationProvider: Flow<CloudTranslationProvider> = settingsRepository.cloudTranslationProvider
    val cloudTranslationApiKey: Flow<String> = settingsRepository.cloudTranslationApiKey
    val accentColorIndex: Flow<Int> = settingsRepository.accentColorIndex
    val backgroundTransparency: Flow<Float> = settingsRepository.backgroundTransparency
    val fontSize: Flow<Float> = settingsRepository.fontSize
    val openAiKey: Flow<String> = settingsRepository.openAiKey
    val claudeKey: Flow<String> = settingsRepository.claudeKey
    val openAiKeyProvider: Flow<String> = settingsRepository.openAiKeyProvider
    val audioSource: Flow<Int> = settingsRepository.audioSource
    val llmUrl: Flow<String> = settingsRepository.llmUrl
    val llmModel: Flow<String> = settingsRepository.llmModel
    val llmProvider: Flow<String> = settingsRepository.llmProvider
    val showOnboarding: Flow<Boolean> = settingsRepository.showOnboarding
    val interfaceLanguage: Flow<String> = settingsRepository.interfaceLanguage

    val allModels: Flow<List<ModelState>> = modelManagementUseCase.observeAllModels()
        .also { flow ->
            // allModels Flow 变化时打日志，方便排查
            viewModelScope.launch {
                flow.collect { list ->
                    val summary = list.joinToString { "[${it.modelName}=${it.status.name}@${it.progress} localPath=${it.localPath != null}]" }
                    Log.d(TAG_VM, "[ALL_MODELS] size=${list.size}, data=$summary")
                }
            }
        }
    private val _downloadProgressMap = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgressMap: StateFlow<Map<String, DownloadProgress>> = _downloadProgressMap.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    fun observeModel(name: String): Flow<ModelState> = modelManagementUseCase.observeModel(name)

    fun clearDownloadError() {
        _downloadError.value = null
    }

    fun downloadModel(name: String, url: String) {
        viewModelScope.launch {
            Log.d(TAG_VM, "[START] downloadModel name=$name, url=$url")
            _downloadError.value = null
            _downloadProgressMap.value = _downloadProgressMap.value + (name to DownloadProgress(
                modelName = name,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                speedBytesPerSec = 0L
            ))
            val result = modelManagementUseCase.startDownload(name, url) { progress ->
                if (progress.bytesDownloaded and 0xFFFFFL == 0L || progress.bytesDownloaded == progress.totalBytes) {
                    Log.v(TAG_VM, "[PROGRESS] $name: ${progress.bytesDownloaded}/${progress.totalBytes} (${progress.fraction})")
                }
                _downloadProgressMap.value = _downloadProgressMap.value + (name to progress)
            }
            Log.d(TAG_VM, "[RESULT] downloadModel $name: success=${result.isSuccess}, ex=${result.exceptionOrNull()?.message}")
            result.onFailure { e ->
                _downloadError.value = "下载失败: ${e.message}"
                Log.e(TAG_VM, "[FAIL] downloadModel $name", e)
            }
            _downloadProgressMap.value = _downloadProgressMap.value - name
            Log.d(TAG_VM, "[CLEANUP] downloadModel $name 从 progressMap 移除, 剩余keys=${_downloadProgressMap.value.keys}")
        }
    }

    /**
     * 下载 Vosk ASR 模型（30–82 MB zip，解压至 filesDir/models/vosk/<lang>/）。
     * 下载中文模型前检查 Sherpa-ONNX 互斥，存在则提示先删除。
     */
    fun downloadVoskModel(languageCode: String) {
        viewModelScope.launch {
            Log.d(TAG_VM, "[START] downloadVoskModel language=$languageCode")
            _downloadError.value = null

            val modelName = ModelCatalog.voskModelName(languageCode)
            val zipSpec = ModelCatalog.voskZipSpec(languageCode)
            if (zipSpec == null) {
                _downloadError.value = "不支持的语种: $languageCode"
                Log.e(TAG_VM, "[FAIL] downloadVoskModel zipSpec为空, lang=$languageCode")
                return@launch
            }
            Log.d(TAG_VM, "[INFO] vosk modelName=$modelName, totalSize=${zipSpec.sizeBytes/1024/1024}MB, extractDir=${modelPreparer.asrModelDir(languageCode).absolutePath}")
            _downloadProgressMap.value = _downloadProgressMap.value + (modelName to DownloadProgress(
                modelName = modelName,
                bytesDownloaded = 0L,
                totalBytes = zipSpec.sizeBytes,
                speedBytesPerSec = 0L
            ))
            val extractDir = modelPreparer.asrModelDir(languageCode).absolutePath
            val result = modelManagementUseCase.downloadAndExtractVosk(
                modelName = modelName,
                zipSpec = zipSpec,
                extractDir = extractDir,
                onProgress = { progress ->
                    if (progress.bytesDownloaded and 0xFFFFFL == 0L || progress.bytesDownloaded == progress.totalBytes) {
                        Log.v(TAG_VM, "[PROGRESS] vosk $languageCode: ${progress.bytesDownloaded}/${progress.totalBytes} (${progress.fraction})")
                    }
                    _downloadProgressMap.value = _downloadProgressMap.value + (modelName to progress)
                }
            )
            Log.d(TAG_VM, "[RESULT] downloadVoskModel $languageCode: success=${result.isSuccess}, ex=${result.exceptionOrNull()?.message}")
            result.onFailure { e ->
                _downloadError.value = "${languageCode.uppercase()} 模型下载失败: ${e.message}"
                Log.e(TAG_VM, "[FAIL] downloadVoskModel $languageCode", e)
            }
            _downloadProgressMap.value = _downloadProgressMap.value - modelName
            Log.d(TAG_VM, "[CLEANUP] vosk $languageCode 从 progressMap 移除, 剩余keys=${_downloadProgressMap.value.keys}")
        }
    }

    fun downloadSherpaOnnxModel(modelId: String) {
        viewModelScope.launch {
            Log.d(TAG_VM, "[START] downloadSherpaOnnxModel modelId=$modelId")
            _downloadError.value = null

            val modelName = ModelCatalog.sherpaOnnxModelName(modelId)
            val extractDir = modelPreparer.sherpaOnnxModelDir(modelId).absolutePath
            Log.d(TAG_VM, "[INFO] sherpa modelName=$modelName, extractDir=$extractDir")

            // 分支：若模型声明了 files（多文件直链），走 downloadSherpaOnnxFiles；否则走 tar.bz2
            val fileSpecs = ModelCatalog.sherpaOnnxFileSpecs(modelId)
            if (fileSpecs != null) {
                // 多文件直链下载（如 X-ASR，无 tar.bz2 直链）
                val totalBytes = fileSpecs.sumOf { it.sizeBytes }
                Log.d(TAG_VM, "[INFO] sherpa files=${fileSpecs.size}, totalSize=${totalBytes/1024/1024}MB")
                _downloadProgressMap.value = _downloadProgressMap.value + (modelName to DownloadProgress(
                    modelName = modelName,
                    bytesDownloaded = 0L,
                    totalBytes = totalBytes,
                    speedBytesPerSec = 0L
                ))
                val result = modelManagementUseCase.downloadSherpaOnnxFiles(
                    modelName = modelName,
                    files = fileSpecs,
                    targetDir = extractDir,
                    onProgress = { progress ->
                        if (progress.bytesDownloaded and 0xFFFFFL == 0L || progress.bytesDownloaded == progress.totalBytes) {
                            Log.v(TAG_VM, "[PROGRESS] sherpa-files $modelId: ${progress.bytesDownloaded}/${progress.totalBytes} (${progress.fraction})")
                        }
                        _downloadProgressMap.value = _downloadProgressMap.value + (modelName to progress)
                    }
                )
                Log.d(TAG_VM, "[RESULT] sherpa-files $modelId: success=${result.isSuccess}, ex=${result.exceptionOrNull()?.message}")
                result.onFailure { e ->
                    _downloadError.value = "Sherpa-ONNX 模型下载失败: ${e.message}"
                    Log.e(TAG_VM, "[FAIL] downloadSherpaOnnxFiles $modelId", e)
                }
                _downloadProgressMap.value = _downloadProgressMap.value - modelName
                Log.d(TAG_VM, "[CLEANUP] sherpa-files $modelId 从 progressMap 移除, 剩余keys=${_downloadProgressMap.value.keys}")
                return@launch
            }

            // tar.bz2 下载流程（原有逻辑）
            val tarSpec = ModelCatalog.sherpaOnnxTarSpec(modelId)
            if (tarSpec == null) {
                _downloadError.value = "不支持的 Sherpa-ONNX 模型: $modelId"
                Log.e(TAG_VM, "[FAIL] sherpa tarSpec为空, modelId=$modelId")
                return@launch
            }
            Log.d(TAG_VM, "[INFO] sherpa tar size=${tarSpec.sizeBytes/1024/1024}MB, url=${tarSpec.url}")

            _downloadProgressMap.value = _downloadProgressMap.value + (modelName to DownloadProgress(
                modelName = modelName,
                bytesDownloaded = 0L,
                totalBytes = tarSpec.sizeBytes,
                speedBytesPerSec = 0L
            ))
            val result = modelManagementUseCase.downloadAndExtractSherpaOnnx(
                modelName = modelName,
                tarSpec = tarSpec,
                extractDir = extractDir,
                onProgress = { progress ->
                    if (progress.bytesDownloaded and 0xFFFFFL == 0L || progress.bytesDownloaded == progress.totalBytes) {
                        Log.v(TAG_VM, "[PROGRESS] sherpa-tar $modelId: ${progress.bytesDownloaded}/${progress.totalBytes} (${progress.fraction})")
                    }
                    _downloadProgressMap.value = _downloadProgressMap.value + (modelName to progress)
                }
            )
            Log.d(TAG_VM, "[RESULT] sherpa-tar $modelId: success=${result.isSuccess}, ex=${result.exceptionOrNull()?.message}")
            result.onFailure { e ->
                _downloadError.value = "Sherpa-ONNX 模型下载失败: ${e.message}"
                Log.e(TAG_VM, "[FAIL] downloadAndExtractSherpaOnnx $modelId", e)
            }
            _downloadProgressMap.value = _downloadProgressMap.value - modelName
            Log.d(TAG_VM, "[CLEANUP] sherpa-tar $modelId 从 progressMap 移除, 剩余keys=${_downloadProgressMap.value.keys}")
        }
    }

    /** 获取所有支持的 Sherpa-ONNX 模型列表 */
    fun getSherpaOnnxModels(): List<SherpaOnnxModel> = modelPreparer.getSherpaOnnxModels()

    fun deleteModel(name: String) {
        viewModelScope.launch {
            modelManagementUseCase.removeModel(name)
        }
    }

    suspend fun isModelAvailable(name: String): Boolean = modelManagementUseCase.isModelAvailable(name)

    fun setSourceLanguage(code: String) { viewModelScope.launch { settingsRepository.setSourceLanguage(code) } }
    fun setTargetLanguage(code: String) { viewModelScope.launch { settingsRepository.setTargetLanguage(code) } }
    fun setTranslationEngine(engine: TranslationEngine) { viewModelScope.launch { settingsRepository.setTranslationEngine(engine) } }
    fun setCloudTranslationProvider(provider: CloudTranslationProvider) { viewModelScope.launch { settingsRepository.setCloudTranslationProvider(provider) } }
    fun setCloudTranslationApiKey(key: String) { viewModelScope.launch { settingsRepository.setCloudTranslationApiKey(key) } }
    fun setDisplayMode(mode: DisplayMode) { viewModelScope.launch { settingsRepository.setDisplayMode(mode) } }
    fun setAccentColorIndex(index: Int) { viewModelScope.launch { settingsRepository.setAccentColorIndex(index) } }
    fun setBackgroundTransparency(value: Float) { viewModelScope.launch { settingsRepository.setBackgroundTransparency(value) } }
    fun setFontSize(value: Float) { viewModelScope.launch { settingsRepository.setFontSize(value) } }
    fun setAudioSource(source: Int) { viewModelScope.launch { settingsRepository.setAudioSource(source) } }
    fun setOpenAiKey(key: String) { viewModelScope.launch { settingsRepository.setOpenAiKey(key) } }
    fun setClaudeKey(key: String) { viewModelScope.launch { settingsRepository.setClaudeKey(key) } }
    fun setOpenAiKeyProvider(provider: String) { viewModelScope.launch { settingsRepository.setOpenAiKeyProvider(provider) } }
    fun setLlmUrl(url: String) { viewModelScope.launch { settingsRepository.setLlmUrl(url) } }
    fun setLlmModel(model: String) { viewModelScope.launch { settingsRepository.setLlmModel(model) } }
    fun setLlmProvider(provider: String) { viewModelScope.launch { settingsRepository.setLlmProvider(provider) } }
    fun setShowOnboarding(show: Boolean) { viewModelScope.launch { settingsRepository.setShowOnboarding(show) } }
    fun setInterfaceLanguage(language: String) { viewModelScope.launch { settingsRepository.setInterfaceLanguage(language) } }

    private companion object {
        private const val TAG_VM = "SettingsViewModel"
    }
}