/**
 * 字幕核心管理器：音频采集 → VAD(仅UI) → ASR → 翻译 → 悬浮窗状态。
 * partial 实时显示，final 触发翻译；源语种切换动态加载模型。
 */
package io.github.ztfang.eye.viewmodel

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import io.github.ztfang.eye.domain.model.AsrEngineType
import io.github.ztfang.eye.domain.model.DisplayMode
import io.github.ztfang.eye.domain.model.SubtitleLine
import io.github.ztfang.eye.domain.model.SubtitleState
import io.github.ztfang.eye.domain.model.SubtitleType
import io.github.ztfang.eye.domain.model.SherpaOnnxModel
import io.github.ztfang.eye.domain.model.TranslationEngine
import io.github.ztfang.eye.domain.model.TranslationResult
import io.github.ztfang.eye.domain.model.ModelStatus
import io.github.ztfang.eye.domain.model.ModelState
import io.github.ztfang.eye.domain.repository.SettingsRepository
import io.github.ztfang.eye.domain.usecase.translation.TranslateUseCase
import io.github.ztfang.eye.engine.ModelPreparer
import io.github.ztfang.eye.domain.engine.asr.AsrEngine
import io.github.ztfang.eye.domain.engine.vad.VADEngine
import io.github.ztfang.eye.domain.engine.vad.VadResult
import io.github.ztfang.eye.engine.asr.VoskAsrEngine
import io.github.ztfang.eye.engine.asr.SherpaOnnxAsrEngine
import io.github.ztfang.eye.engine.asr.VoskLanguageMap
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubtitleManager @Inject constructor(
    private val translateUseCase: TranslateUseCase,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: io.github.ztfang.eye.domain.repository.HistoryRepository,
    private val vadEngine: VADEngine,
    private val voskAsrEngine: VoskAsrEngine,
    private val sherpaOnnxAsrEngine: SherpaOnnxAsrEngine,
    private val modelPreparer: ModelPreparer,
    private val mlKitEngine: io.github.ztfang.eye.engine.translation.mlkit.MlKitTranslationEngine,
    private val llmClient: io.github.ztfang.eye.engine.translation.llm.LLMClient,
    private val modelRepository: io.github.ztfang.eye.domain.repository.ModelRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _subtitleState = MutableStateFlow(SubtitleState())
    val subtitleState: StateFlow<SubtitleState> = _subtitleState.asStateFlow()

    private val _translationResult = MutableStateFlow<TranslationResult?>(null)
    val translationResult: StateFlow<TranslationResult?> = _translationResult.asStateFlow()

    private val _isTranslating = MutableStateFlow(false)
    val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()

    /**
     * 模型下载状态缓存（快照）。
     * 用于 [resolveAsrEngine] 在非挂起上下文中判断"理想引擎的模型是否已下载"，
     * 当 Sherpa/Nemotron 模型未下载时静默回退 Vosk，避免悬浮窗报"模型未下载"。
     * 由 [observeModelDownloads] 订阅 ModelRepository 的 Flow 实时更新。
     */
    @Volatile
    private var cachedModelStates: Map<String, ModelState> = emptyMap()

    /** 最后一次收到 final 结果的时间戳，用于文本留存超时清理 */
    @Volatile
    private var lastFinalTimeMs: Long = 0L

    // ==================== 助手语音输入模式 ====================
    // 独立于悬浮字幕的一次性语音识别，用于助手界面按住说话。
    // 复用音频采集 + Vosk 引擎，但不触发翻译、不更新悬浮字幕状态。

    private val _voiceInputText = MutableStateFlow("")
    /** 语音输入实时识别文本（partial + 已提交 final） */
    val voiceInputText: StateFlow<String> = _voiceInputText.asStateFlow()

    private var isInputMode = false
    /** 输入模式下实际使用的引擎类型（按源语言解析，与翻译引擎解耦） */
    private var inputModeEngine: AsrEngineType? = null
    /** 输入模式下累积的已提交 final 文本 */
    private val inputFinalBuffer = StringBuilder()

    /**
     * 启动语音输入模式：复用 ASR + 音频采集，结果写 voiceInputText，不触发翻译。
     * ASR 引擎由源语言唯一决定，不做 fallback，未下载提示模型名。
     */
    fun startVoiceInput() {
        if (isInputMode) return
        isInputMode = true
        inputFinalBuffer.setLength(0)
        _voiceInputText.value = ""
        val sourceLanguage = _subtitleState.value.sourceLanguage
        scope.launch {
            val actualEngine = currentAsrEngineType
            inputModeEngine = actualEngine
            val modelId = resolveSherpaModelId(sourceLanguage)
            if (actualEngine == AsrEngineType.SHERPA_ONNX_NEMOTRON) {
                sherpaOnnxAsrEngine.setLanguage(resolveNemotronLanguage(sourceLanguage))
            }
            val result = when (actualEngine) {
                AsrEngineType.VOSK -> modelPreparer.prepareAsr(sourceLanguage)
                AsrEngineType.SHERPA_ONNX,
                AsrEngineType.SHERPA_ONNX_BN,
                AsrEngineType.SHERPA_ONNX_NEMOTRON -> {
                    if (modelId == null) {
                        Result.failure(IllegalStateException(
                            "源语言 $sourceLanguage 无对应 Sherpa-ONNX 模型，请切换语言"
                        ))
                    } else {
                        modelPreparer.prepareSherpaOnnxAsr(modelId)
                    }
                }
            }
            result.onSuccess {
                if (!isRecording) {
                    startAudioProcessing()
                } else {
                    currentAsrEngine.resetStream()
                }
            }.onFailure { e ->
                Log.e(LOG_TAG, "startVoiceInput: 模型准备失败, engine=$actualEngine, lang=$sourceLanguage", e)
                _runtimeError.value = buildString {
                    append("语音输入模型未就绪，请前往模型下载界面下载对应模型")
                    append("（错误：")
                    append(e.message ?: "未知")
                    append("）")
                }
                isInputMode = false
                inputModeEngine = null
            }
        }
    }

    /**
     * 停止语音输入，返回完整识别文本（原始ASR结果，未润色）。
     */
    fun stopVoiceInput(): String {
        if (!isInputMode) return ""
        isInputMode = false
        val result = inputFinalBuffer.toString()
        inputFinalBuffer.setLength(0)
        _voiceInputText.value = ""
        val prevEngine = inputModeEngine
        inputModeEngine = null
        // 若悬浮字幕未激活，停止音频采集节省资源
        if (!_isOverlayActive.value) {
            stopAudioProcessing()
        } else if (prevEngine != null && prevEngine != currentAsrEngineType) {
            // 输入模式用了不同引擎，结束后切回字幕模式的引擎并重置
            currentAsrEngine.resetStream()
        }
        return result.trim()
    }

    /** 语音输入文本轻量润色：去语气词/修正标点/修同音字；LLM 未配置或失败时返回原文。 */
    suspend fun polishVoiceInput(originalText: String): String = withContext(Dispatchers.IO) {
        if (originalText.isBlank()) return@withContext ""
        if (!isLlmConfigReady.value) return@withContext originalText

        try {
            val systemPrompt = """你是文本润色助手。
任务：对语音识别文本做轻量润色。

规则：
1. 去掉语气词和口头禅（嗯、啊、那个、就是说、然后呢、对吧、哦、呃）
2. 修正标点和断句，使表达更通顺
3. 修正常见 ASR 同音字错误，保持原意
4. 只输出润色后的文本，不要解释，不要加引号
5. 如果原文已经很通顺，直接输出原文
6. 保持原文的口语化风格，不要过度润色"""

            val polished = llmClient.chat(listOf(
                "system" to systemPrompt,
                "user" to originalText
            )).trim()

            if (polished.isNotBlank()) polished else originalText
        } catch (e: Exception) {
            Log.w(LOG_TAG, "语音输入润色失败，返回原始文本: ${e.message}")
            originalText
        }
    }

    /** 悬浮窗位置与尺寸：从 DataStore 持续同步，支持双向更新 */
    val overlayX: StateFlow<Int> = settingsRepository.overlayX
        .stateIn(scope, SharingStarted.Eagerly, 0)
    val overlayY: StateFlow<Int> = settingsRepository.overlayY
        .stateIn(scope, SharingStarted.Eagerly, 0)
    val overlayWidth: StateFlow<Int> = settingsRepository.overlayWidth
        .stateIn(scope, SharingStarted.Eagerly, 0)
    val overlayHeight: StateFlow<Int> = settingsRepository.overlayHeight
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** 个性化设置：暴露给悬浮窗 Service 实时应用 */
    val accentColorIndex: StateFlow<Int> = settingsRepository.accentColorIndex
        .stateIn(scope, SharingStarted.Eagerly, 1)
    val backgroundTransparency: StateFlow<Float> = settingsRepository.backgroundTransparency
        .stateIn(scope, SharingStarted.Eagerly, 0.75f)
    val fontSize: StateFlow<Float> = settingsRepository.fontSize
        .stateIn(scope, SharingStarted.Eagerly, 18f)

    /**
     * ASR 引擎类型（仅由源语言唯一映射，与翻译引擎解耦；不做模型可用性判断）。
     *
     * 规则（每种语言对应唯一默认引擎，无 fallback）：
     * - zh/en → X-ASR (SHERPA_ONNX)
     * - bn → BN 专用 (SHERPA_ONNX_BN)
     * - Nemotron 26 语 → Nemotron 3.5 (SHERPA_ONNX_NEMOTRON)
     * - 其余语言 → Vosk
     */
    private val currentAsrEngineType: AsrEngineType
        get() = resolveAsrEngine(_subtitleState.value.sourceLanguage)

    /**
     * 检查某个【指定引擎+语言代码的模型文件是否真的就绪（文件系统级+完整检查）。
     *
     * 打印详细目录用于诊断"明明下载了但说未下载"问题。
     */
    private fun checkEngineReadyAndLog(engine: AsrEngineType, languageCode: String): Boolean {
        val ready: Boolean
        val details: String
        when (engine) {
            AsrEngineType.VOSK -> {
                val supported = VoskLanguageMap.getModelName(languageCode) != null
                val dir = modelPreparer.asrModelDir(languageCode)
                val am = File(dir, "am").isDirectory
                val conf = File(dir, "conf").isDirectory
                val graph = File(dir, "graph").isDirectory
                val dirExists = dir.exists()
                val listStr = runCatching {
                    dir.listFiles()?.joinToString(limit = 5) { f -> f.name }
                }.getOrDefault("<list_dir_err>")
                ready = supported && am && conf && graph
                details = buildString {
                    append("Vosk lang=$languageCode, supported=$supported, dir=$dir, exists=$dirExists")
                    append(", am=$am, conf=$conf, graph=$graph, files(top5)=$listStr")
                }
            }
            AsrEngineType.SHERPA_ONNX,
            AsrEngineType.SHERPA_ONNX_BN,
            AsrEngineType.SHERPA_ONNX_NEMOTRON -> {
                val modelId = resolveSherpaModelId(languageCode)
                if (modelId == null) {
                    ready = false
                    details = "Sherpa lang=$languageCode, engine=$engine, no modelId (unexpected)"
                } else {
                    // 先执行 isSherpaOnnxAsrReady：内部会先自愈 tokens.txt.part.corrupted → tokens.txt
                    ready = modelPreparer.isSherpaOnnxAsrReady(modelId)
                    // 自愈后重新计算文件存在性（日志用），确保打印的是自愈后状态
                    val dir = modelPreparer.sherpaOnnxModelDir(modelId)
                    val model = SherpaOnnxModel.fromModelId(modelId)
                    val enc = model?.let { File(dir, it.encoderFile).exists() } == true
                    val dec = model?.let { File(dir, it.decoderFile).exists() } == true
                    val join = model?.let { File(dir, it.joinerFile).exists() } == true
                    // tokens 兼容 .part / .part.corrupted（历史残留），只要存在且>0字节即认为存在
                    val tokOfficial = model?.let { m ->
                        val tf = File(dir, m.tokensFile)
                        tf.exists() && tf.length() > 0L
                    } == true
                    val tokAlias = model != null && runCatching {
                        dir.listFiles()?.any { f ->
                            val n = f.name
                            (n == "${model.tokensFile}.part" || n == "${model.tokensFile}.part.corrupted") && f.length() > 0L
                        } == true
                    }.getOrDefault(false)
                    val tok = tokOfficial || tokAlias
                    val listStr = runCatching {
                        dir.listFiles()?.joinToString(limit = 5) { f -> "${f.name}(${f.length()})" }
                    }.getOrDefault("<list_dir_err>")
                    val key = when (engine) {
                        AsrEngineType.SHERPA_ONNX -> "SHERPA_ONNX_ASR_${SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelId}"
                        AsrEngineType.SHERPA_ONNX_BN -> "SHERPA_ONNX_ASR_${SherpaOnnxModel.BN_VOSK_2026_02_09.modelId}"
                        AsrEngineType.SHERPA_ONNX_NEMOTRON -> "SHERPA_ONNX_ASR_${SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8.modelId}"
                        else -> error("unreachable")
                    }
                    val byRepoPath = modelRepository.getModelPath(key)
                    details = buildString {
                        append("Sherpa engine=$engine, modelId=$modelId, dir=$dir, exists=${dir.exists()}")
                        append(", enc=$enc, dec=$dec, join=$join, tok=$tok(tokOfficial=$tokOfficial, tokAlias=$tokAlias)")
                        append(", modelRepo.getModelPath($key)=$byRepoPath, files(top5)=$listStr")
                    }
                }
            }
        }
        Log.w(LOG_TAG, "[MODEL_CHECK] $details → ready=$ready")
        return ready
    }

    /**
     * 检查某语言的 ASR 模型是否完整就绪。
     */
    private fun isModelReadyFor(languageCode: String): Boolean =
        checkEngineReadyAndLog(resolveAsrEngine(languageCode), languageCode)

    /**
     * 返回当前引擎类型下，某语言应该下载模型名（用于精准错误提示）。
     */
    private fun expectedModelNameFor(languageCode: String, engine: AsrEngineType = resolveAsrEngine(languageCode)): String = when (engine) {
        AsrEngineType.VOSK -> "VOSK_ASR_${languageCode.uppercase()}"
        AsrEngineType.SHERPA_ONNX -> "SHERPA_ONNX_ASR_${SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelId}"
        AsrEngineType.SHERPA_ONNX_BN -> "SHERPA_ONNX_ASR_${SherpaOnnxModel.BN_VOSK_2026_02_09.modelId}"
        AsrEngineType.SHERPA_ONNX_NEMOTRON -> "SHERPA_ONNX_ASR_${SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8.modelId}"
    }

    /** 返回 UI 可读模型名称，运行时错误提示用。 */
    private fun displayModelNameFor(languageCode: String, engine: AsrEngineType = resolveAsrEngine(languageCode)): String = when (engine) {
        AsrEngineType.VOSK -> "Vosk ${VoskLanguageMap.getDisplayName(languageCode)}"
        AsrEngineType.SHERPA_ONNX -> SherpaOnnxModel.X_ASR_ZH_EN_960MS.displayName + " (Sherpa X-ASR)"
        AsrEngineType.SHERPA_ONNX_BN -> SherpaOnnxModel.BN_VOSK_2026_02_09.displayName + " (Sherpa BN)"
        AsrEngineType.SHERPA_ONNX_NEMOTRON -> SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8.displayName + " (Nemotron 多语种)"
    }

    /**
     * 【强制唯一引擎映射】每种语言只流一种 ASR 模型（用户最新要求）。
     *
     * 主界面三种模式已改成三种翻译引擎，和 ASR 引擎解耦。ASR 映射：
     *  - zh / en → SHERPA_ONNX (X-ASR-zh-en 960ms，161MB)
     *  - bn → SHERPA_ONNX_BN (孟加拉语专用，83MB)
     *  - Nemotron 26 语(日/韩/西/法/德/俄等) → SHERPA_ONNX_NEMOTRON (多语种 685MB 大包)
     *  - 其它小语种 → Vosk(按语言单独小包)
     *
     *  不再支持「同语言多引擎选已下载」，因为每种语言用户只能下到唯一的那一种模型。
     */
    private fun resolveAsrEngine(sourceLanguage: String): AsrEngineType = when {
        sourceLanguage == "zh" || sourceLanguage == "en" -> AsrEngineType.SHERPA_ONNX
        sourceLanguage == "bn" -> AsrEngineType.SHERPA_ONNX_BN
        SherpaOnnxModel.NEMOTRON_LANGUAGES.contains(sourceLanguage) -> AsrEngineType.SHERPA_ONNX_NEMOTRON
        else -> AsrEngineType.VOSK
    }.also { eng ->
        Log.i(LOG_TAG, "[RESOLVE] lang=$sourceLanguage → force engine=$eng")
    }

    /** 解析 Sherpa-ONNX 模型 ID：zh/en→X-ASR, bn→BN Vosk, Nemotron 语种→Nemotron 3.5, 其他→null(Vosk) */
    private fun resolveSherpaModelId(languageCode: String): String? = when (languageCode) {
        "zh", "en" -> SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelId
        "bn" -> SherpaOnnxModel.BN_VOSK_2026_02_09.modelId
        else -> if (SherpaOnnxModel.NEMOTRON_LANGUAGES.contains(languageCode))
            SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8.modelId
        else null
    }

    /**
     * Nemotron per-stream language 代码：sherpa-onnx 接受 ISO 639-1 bare code（'en'/'ja'/'auto'），
     * 内部自动映射 locale，无需转 'en-US'。zh 实际走 X-ASR，传 'zh' 仅为防御。
     */
    private fun resolveNemotronLanguage(languageCode: String): String = languageCode

    /** 当前使用的 ASR 引擎实例（根据翻译模式动态切换；输入模式下可能被覆盖） */
    private val currentAsrEngine: AsrEngine
        get() = when (inputModeEngine ?: currentAsrEngineType) {
            AsrEngineType.VOSK -> voskAsrEngine
            // 所有 Sherpa-ONNX 变体共用同一引擎实例（通过 modelId 区分加载的模型）
            AsrEngineType.SHERPA_ONNX,
            AsrEngineType.SHERPA_ONNX_BN,
            AsrEngineType.SHERPA_ONNX_NEMOTRON -> sherpaOnnxAsrEngine
        }

    /**
     * LLM（大模型）配置是否就绪：API Key 和 URL 都不为空。
     * 用于 UI 层判断点击 AI 模式时是否需要弹配置提示。
     */
    val isLlmConfigReady: StateFlow<Boolean> = settingsRepository.isLlmConfigReady
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** 音频输入源：0=麦克风，1=应用内声音（AudioPlaybackCapture，Android 10+） */
    val audioSource: StateFlow<Int> = MutableStateFlow(0).also { flow ->
        scope.launch { settingsRepository.audioSource.collect { new ->
            val old = flow.value
            flow.value = new
            // 录音中切换音频源 → 重启音频采集
            if (old != new && isRecording) {
                restartAudioProcessing()
            }
        } }
    }.asStateFlow()

    private val _isOverlayActive = MutableStateFlow(false)
    val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()

    private val _runtimeError = MutableStateFlow<String?>(null)
    val runtimeError: StateFlow<String?> = _runtimeError.asStateFlow()

    private val _vadState = MutableStateFlow(VadState.LISTENING)
    val vadState: StateFlow<VadState> = _vadState.asStateFlow()

    enum class VadState {
        LISTENING,
        SILENT
    }

    init {
        // 初始化 Silero VAD 引擎(异步从 assets 拷贝模型并加载)
        if (vadEngine is io.github.ztfang.eye.engine.vad.SileroVadEngine) {
            scope.launch(Dispatchers.IO) {
                vadEngine.init().onFailure { e ->
                    Log.e(LOG_TAG, "Silero VAD 初始化失败: ${e.message}", e)
                    _runtimeError.value = "语音活动检测引擎初始化失败"
                }
            }
        }
        scope.launch {
            settingsRepository.displayMode.collect { mode ->
                _subtitleState.value = _subtitleState.value.copy(displayMode = mode)
            }
        }
        scope.launch {
            var lastEngineType: AsrEngineType? = null
            settingsRepository.translationEngine.collect { engine ->
                // 引擎切换诊断：确认 collect 收到新值
                Log.i(LOG_TAG, "translationEngine.collect: engine=$engine, prev=${_subtitleState.value.engine}")
                _subtitleState.value = _subtitleState.value.copy(engine = engine)
                val s = _subtitleState.value
                // 异步准备翻译模型，不阻塞 collect（避免 ML Kit 下载时收不到后续引擎切换）
                scope.launch {
                    modelPreparer.prepareTranslation(
                        engine = engine,
                        sourceLanguage = s.sourceLanguage,
                        targetLanguage = s.targetLanguage
                    ).onFailure { e ->
                        Log.w(LOG_TAG, "prepareTranslation 失败: ${e.message}")
                        _runtimeError.value = "翻译模型未就绪：${e.message}"
                    }
                }
                // ASR 引擎与翻译引擎解耦，引擎切换不再触发 ASR 重启
                val newEngineType = currentAsrEngineType
                if (lastEngineType == null) lastEngineType = newEngineType
            }
        }
        scope.launch {
            settingsRepository.sourceLanguage.distinctUntilChanged().collect { lang ->
                _subtitleState.value = _subtitleState.value.copy(sourceLanguage = lang)
                if (isRecording) {
                    reloadAsrModel(lang)
                }
            }
        }
        scope.launch {
            settingsRepository.targetLanguage.distinctUntilChanged().collect { lang ->
                _subtitleState.value = _subtitleState.value.copy(targetLanguage = lang)
            }
        }
        setupAsrFlowListeners()
        observeModelDownloads()
    }

    /**
     * 监听模型下载状态变化。
     *
     * 场景1：模型下载变 AVAILABLE 后清除"未下载"错误并重启采集加载新模型。
     * 场景2：同步 [cachedModelStates] 快照，供 [resolveAsrEngine] 非挂起判断模型是否就绪，
     *        未就绪静默回退 Vosk，避免悬浮窗误报。
     */
    private fun observeModelDownloads() {
        scope.launch {
            modelRepository.observeAllModels().collect { models ->
                // 写缓存快照（先保存，再判断后续逻辑）
                cachedModelStates = models.associateBy { it.modelName }

                // 任意模型变为可用时，检查是否需要清除"未下载"错误
                val anyReady = models.any { it.status == ModelStatus.AVAILABLE }
                if (!anyReady) return@collect

                val hasDownloadError = _runtimeError.value?.contains("语音识别模型未下载") == true
                if (hasDownloadError) {
                    Log.i(LOG_TAG, "observeModelDownloads: 模型已下载，清除错误" +
                            ", isRecording=$isRecording")
                    _runtimeError.value = null
                    if (isRecording) {
                        restartAudioProcessing()
                    }
                }
            }
        }
    }

    /**
     * 监听两个 ASR 引擎的 partial/final 结果流，统一处理 UI 更新和翻译触发。
     * 同时收集两个引擎的 flow：未初始化的引擎不会发送结果，已激活的引擎正常工作。
     */
    private fun setupAsrFlowListeners() {
        // 收集 Vosk 引擎结果
        scope.launch {
            voskAsrEngine.partialResultFlow.collect { text ->
                handlePartialResult(text)
            }
        }
        scope.launch {
            voskAsrEngine.finalResultFlow.collect { text ->
                handleFinalResult(text)
            }
        }
        // 收集 Sherpa-ONNX 引擎结果
        scope.launch {
            sherpaOnnxAsrEngine.partialResultFlow.collect { text ->
                handlePartialResult(text)
            }
        }
        scope.launch {
            sherpaOnnxAsrEngine.finalResultFlow.collect { text ->
                handleFinalResult(text)
            }
        }
    }

    /** 处理 partial 识别结果（语音输入模式 / 字幕模式分流） */
    private fun handlePartialResult(text: String) {
        Log.d(LOG_TAG, "handlePartialResult: text=\"$text\", isInputMode=$isInputMode")
        if (isInputMode) {
            partialDisplayBuilder.setLength(0)
            if (inputFinalBuffer.isNotEmpty()) {
                partialDisplayBuilder.append(inputFinalBuffer)
            }
            partialDisplayBuilder.append(text)
            _voiceInputText.value = partialDisplayBuilder.toString()
        } else {
            // 两行交替模式下，partial 直接显示当前流内容
            // 旧 final 已在另一行显示，Sherpa resetStream 后 partial 是新句内容
            updateSourceText(text, isFinal = false)
        }
    }

    /** 处理 final 识别结果（语音输入模式 / 字幕模式分流） */
    private fun handleFinalResult(text: String) {
        Log.d(LOG_TAG, "handleFinalResult: text=\"$text\", isInputMode=$isInputMode")
        lastFinalTimeMs = System.currentTimeMillis()
        if (isInputMode) {
            inputFinalBuffer.append(text)
            _voiceInputText.value = inputFinalBuffer.toString()
        } else {
            // 先归档当前行（如果有内容的话）
            if (currentSourceBuffer.isNotEmpty()) {
                archiveCurrentLine()
            }
            // 新句子：直接作为 final 行
            updateSourceText(text, isFinal = true)

            // 移除严格长度限制，所有非空 final 都触发翻译
            if (text.isNotBlank()) {
                translate(text)
            }
            currentAsrEngine.resetStream()
        }
    }

    /**
     * 按句末标点切分文本。
     * 保留句末标点在切分结果中，最后一段若无句末标点则视为未完成句保留在缓冲区。
     * 支持中英文标点：。！？.!?；;
     */
    private fun splitBySentenceEnd(text: String): List<String> {
        if (text.isEmpty()) return listOf(text)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?'
                || ch == '；' || ch == ';') {
                result.add(current.toString())
                current.setLength(0)
            }
        }
        // 剩余未完成句
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    private suspend fun reloadAsrModel(languageCode: String) {
        audioMutex.withLock {
            Log.i(LOG_TAG, "reloadAsrModel: 切换语种到 $languageCode")
            isRecording = false
            audioProcessingJob?.cancel()
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null
            delay(100)

            // 严格唯一引擎：语言→引擎→prepare，失败直接报错，不跨引擎 fallback
            val engine = currentAsrEngineType
            val loadResult = when (engine) {
                AsrEngineType.VOSK -> modelPreparer.prepareAsr(languageCode)
                AsrEngineType.SHERPA_ONNX,
                AsrEngineType.SHERPA_ONNX_BN,
                AsrEngineType.SHERPA_ONNX_NEMOTRON -> {
                    val modelId = resolveSherpaModelId(languageCode)
                    if (modelId == null) {
                        Result.failure(IllegalStateException(
                            "源语言 $languageCode 无对应 Sherpa-ONNX 模型，请切换语言"
                        ))
                    } else {
                        if (engine == AsrEngineType.SHERPA_ONNX_NEMOTRON) {
                            sherpaOnnxAsrEngine.setLanguage(resolveNemotronLanguage(languageCode))
                        }
                        modelPreparer.prepareSherpaOnnxAsr(modelId)
                    }
                }
            }
            loadResult.onSuccess {
                Log.i(LOG_TAG, "reloadAsrModel: 模型加载成功，引擎=$engine, 重启音频采集")
                startAudioProcessingLocked()
            }.onFailure { e ->
                Log.e(LOG_TAG, "reloadAsrModel: 模型加载失败, engine=$engine, lang=$languageCode", e)
                _runtimeError.value = buildString {
                    append("语音识别模型加载失败，请前往模型下载界面下载对应模型")
                    append("（错误：")
                    append(e.message ?: "未知")
                    append("）")
                }
            }
        }
    }

    /**
     * 当前句子累积的 final 原文缓冲。
     * 一个句子内多个 final 累积，遇到句末标点或 endpoint 切句时完成一句。
     */
    private val currentSourceBuffer = StringBuilder()

    /** 当前句子累积的译文缓冲。 */
    private val currentTranslationBuffer = StringBuilder()

    /** 历史字幕行（已完成的句子），用于向上滚动查看历史 */
    private val historyLines = mutableListOf<SubtitleLine>()

    /** 滑动窗口 partial 翻译版本号，避免重复触发 partial 翻译 */
    private val partialTranslationVersion = AtomicLong(0)

    /** 上次 partial 翻译的文本长度，用于判断是否触发新的 partial 翻译 */
    @Volatile
    private var lastPartialTranslateLength = 0

    /** 多行滚动+滑动窗口：final 独立成行向上滚动，partial 达步长触发预览翻译，历史保留 MAX_HISTORY_LINES 条。 */
    fun updateSourceText(text: String, isFinal: Boolean) {
        val current = _subtitleState.value

        if (isFinal) {
            // final：设置为当前行内容
            currentSourceBuffer.setLength(0)
            currentSourceBuffer.append(text)
            // 重置 partial 翻译基准
            lastPartialTranslateLength = 0
        } else {
            // partial：检查是否触发滑动窗口预览翻译
            val fullText = currentSourceBuffer.toString() + text
            val triggerLen = if (current.sourceLanguage.startsWith("en", ignoreCase = true)) {
                PARTIAL_TRANSLATE_STEP_EN
            } else {
                PARTIAL_TRANSLATE_STEP_ZH
            }
            if (fullText.length - lastPartialTranslateLength >= triggerLen && fullText.length >= MIN_PARTIAL_TRANSLATE_LENGTH) {
                lastPartialTranslateLength = fullText.length
                translatePartial(fullText)
            }
        }

        // 组装当前行
        val currentLine = SubtitleLine(
            sourceText = if (isFinal) currentSourceBuffer.toString() else currentSourceBuffer.toString() + text,
            translatedText = currentTranslationBuffer.toString(),
            subtitleType = if (isFinal) SubtitleType.FINAL else SubtitleType.PARTIAL
        )

        // 历史行 + 当前行
        val allLines = historyLines.toMutableList()
        allLines.add(currentLine)

        // 只保留最近 MAX_HISTORY_LINES 行
        val displayLines = if (allLines.size > MAX_HISTORY_LINES) {
            allLines.takeLast(MAX_HISTORY_LINES)
        } else {
            allLines
        }

        _subtitleState.value = current.copy(lines = displayLines)
    }

    /**
     * 完成当前句并归档到历史（endpoint 切句 / VAD 长停顿触发）。
     * 清空当前缓冲，新句从空开始。
     */
    private fun archiveCurrentLine() {
        if (currentSourceBuffer.isBlank()) return
        val line = SubtitleLine(
            sourceText = currentSourceBuffer.toString(),
            translatedText = currentTranslationBuffer.toString(),
            subtitleType = SubtitleType.FINAL
        )
        historyLines.add(line)
        if (historyLines.size > MAX_HISTORY_LINES - 1) {
            historyLines.removeAt(0)
        }
        currentSourceBuffer.setLength(0)
        currentTranslationBuffer.setLength(0)
        lastPartialTranslateLength = 0
        Log.d(LOG_TAG, "archiveCurrentLine: 归档句子，历史行数=${historyLines.size}")
    }

    /**
     * 滑动窗口 partial 翻译：轻量预览，只更新 UI 不保存历史。
     * 使用版本号管理，避免旧结果覆盖新结果。
     */
    private fun translatePartial(text: String) {
        val version = partialTranslationVersion.incrementAndGet()
        scope.launch {
            val state = _subtitleState.value
            try {
                // 只做直接翻译，不润色，速度优先
                translateUseCase.execute(
                    text, state.sourceLanguage, state.targetLanguage, state.engine
                ).onSuccess { result ->
                    // 仅当版本匹配时更新当前行译文
                    if (partialTranslationVersion.get() == version && currentSourceBuffer.isNotEmpty()) {
                        currentTranslationBuffer.setLength(0)
                        currentTranslationBuffer.append(result.translatedText)
                        // 更新 UI
                        val current = _subtitleState.value
                        val lines = current.lines.toMutableList()
                        if (lines.isNotEmpty()) {
                            val lastIdx = lines.size - 1
                            lines[lastIdx] = lines[lastIdx].copy(
                                translatedText = result.translatedText
                            )
                            _subtitleState.value = current.copy(lines = lines)
                        }
                    }
                }
            } catch (_: Exception) {
                // partial 翻译失败静默处理
            }
        }
    }

    /** 最近翻译过的句子（上下文窗口，方案 C） */
    private val recentSentences = mutableListOf<String>()
    private val MAX_CONTEXT_SENTENCES = 3

    /**
     * 翻译版本号：每次新 translate 递增，翻译返回时校验版本，旧版本结果优雅丢弃。
     * 不再 cancel 旧翻译任务，连读场景下多句翻译可并发执行，互不阻塞。
     */
    private val translationVersion = AtomicLong(0)

    /**
     * 调度翻译：不 cancel 旧任务，递增版本号，仅最新版本更新 UI（避免连读场景旧句译文丢失）。
     * AI 链路：ASR→LLM 流式（润色+翻译）；LOCAL/CLOUD：ASR→显示原文→翻译→显示译文。
     */
    fun translate(text: String) {
        // 递增版本号，停止录音时用于使在途结果失效
        val version = translationVersion.incrementAndGet()

        scope.launch {
            val state = _subtitleState.value
            if (text.isBlank()) return@launch

            delay(TRANSLATE_DEBOUNCE_MS)

            _isTranslating.value = true

            // AI 引擎：统一走润色+翻译（X-ASR 已带标点时润色规则近似 no-op，Vosk 无标点时润色生效）
            if (state.engine == TranslationEngine.AI && text.length >= MIN_POLISH_LENGTH) {
                translateWithPolishAndContext(text, state, version)
            } else {
                // LOCAL/CLOUD 引擎：直接翻译
                translateUseCase.execute(
                    text, state.sourceLanguage, state.targetLanguage, state.engine
                ).onSuccess { result ->
                    if (translationVersion.get() == version) {
                        appendTranslationResult(text, result.translatedText, state)
                    }
                }.onFailure { e ->
                    if (e is UnsupportedOperationException) {
                        // 引擎不支持该语言对，静默不响应（不弹错）
                        Log.i(LOG_TAG, "翻译引擎不支持此语言对，跳过: ${e.message}")
                        _isTranslating.value = false
                    } else {
                        Log.e(LOG_TAG, "翻译失败", e)
                        _runtimeError.value = "翻译失败，请检查网络或配置"
                        _isTranslating.value = false
                    }
                }
            }
        }
    }

    /**
     * 智能模式：润色+翻译合并 + 上下文感知（方案 C+E）。
     * 一次 LLM 调用完成润色和翻译，减少往返延迟。
     * 译文通过流式输出逐字显示（方案 D）。
     */
    private suspend fun translateWithPolishAndContext(
        originalText: String,
        state: SubtitleState,
        version: Long
    ) {
        try {
            withTimeout(POLISH_TIMEOUT_MS + TRANSLATE_TIMEOUT_MS) {
                // 构建上下文（方案 C）：最近 2-3 句
                val context = if (recentSentences.isNotEmpty()) {
                    "上下文：${recentSentences.takeLast(2).joinToString(" ")}\n"
                } else {
                    ""
                }

                // 统一英文提示词：润色+翻译合并，适用所有 ASR 源
                // X-ASR 已带标点时润色规则近似 no-op；Vosk 无标点时润色生效
                val systemPrompt = """
                    You are a real-time speech translation assistant.
                    Task: lightly polish the ASR transcript and translate it from ${state.sourceLanguage} to ${state.targetLanguage}.

                    Polish rules (apply only when needed):
                    1. Remove filler words and disfluencies (e.g. um, uh, like, you know, 所以, 然后, 那个)
                    2. Fix punctuation and sentence boundaries
                    3. Fix common ASR homophone errors

                    Translation rules:
                    1. Translate to ${state.targetLanguage}, preserve original meaning, stay coherent with context
                    2. Return ONLY the translated text, no explanations, no quotes
                    3. Do NOT wrap in markdown code blocks or quotes
                    4. If input is empty or whitespace, output empty string

                    $context
                """.trimIndent()

                val polishedAndTranslated = llmClient.chat(listOf(
                    "system" to systemPrompt,
                    "user" to originalText
                )).trim()

                if (polishedAndTranslated.isNotBlank() && translationVersion.get() == version) {
                    appendTranslationResult(originalText, polishedAndTranslated, state)
                    // 记录到上下文窗口
                    recentSentences.add(originalText)
                    if (recentSentences.size > MAX_CONTEXT_SENTENCES) {
                        recentSentences.removeAt(0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "AI 翻译失败/超时，降级为普通翻译: ${e.message}")
            // 降级：直接翻译，不润色
            translateUseCase.execute(
                originalText, state.sourceLanguage, state.targetLanguage, state.engine
            ).onSuccess { result ->
                if (translationVersion.get() == version) {
                    appendTranslationResult(originalText, result.translatedText, state)
                }
            }.onFailure { e ->
                if (e is UnsupportedOperationException) {
                    // 引擎不支持该语言对，静默不响应（不弹错）
                    Log.i(LOG_TAG, "翻译引擎不支持此语言对，跳过: ${e.message}")
                    _isTranslating.value = false
                } else {
                    _runtimeError.value = "翻译失败，请检查网络或配置"
                    _isTranslating.value = false
                }
            }
        }
    }

    /**
     * 追加翻译结果到当前行 + 保存历史记录。
     * 多行模式：译文替换当前行（最后一行）的 translatedText。
     */
    private fun appendTranslationResult(
        sourceText: String,
        translatedText: String,
        state: SubtitleState
    ) {
        // 设置当前行译文缓冲
        currentTranslationBuffer.setLength(0)
        currentTranslationBuffer.append(translatedText)

        // 更新最后一行（当前行）译文
        val current = _subtitleState.value
        val lines = current.lines.toMutableList()
        if (lines.isNotEmpty()) {
            val lastIdx = lines.size - 1
            lines[lastIdx] = lines[lastIdx].copy(translatedText = translatedText)
            _subtitleState.value = current.copy(lines = lines)
        }

        _translationResult.value = TranslationResult(sourceText, translatedText, state.sourceLanguage, state.targetLanguage, state.engine, isFinal = true)
        _isTranslating.value = false

        // 保存到历史记录
        scope.launch(Dispatchers.IO) {
            historyRepository.insertRecord(
                io.github.ztfang.eye.domain.model.HistoryRecord(
                    sourceText = sourceText,
                    translatedText = translatedText,
                    sourceLanguage = state.sourceLanguage,
                    targetLanguage = state.targetLanguage,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateSourceLanguage(code: String) {
        scope.launch { settingsRepository.setSourceLanguage(code) }
    }

    fun updateTargetLanguage(code: String) {
        scope.launch { settingsRepository.setTargetLanguage(code) }
    }

    fun updateTranslationEngine(engine: TranslationEngine) {
        // 引擎切换诊断：确认点击是否到达 ViewModel
        Log.i(LOG_TAG, "updateTranslationEngine: $engine, current=${_subtitleState.value.engine}")
        scope.launch { settingsRepository.setTranslationEngine(engine) }
    }

    fun updateDisplayMode(mode: DisplayMode) {
        scope.launch { settingsRepository.setDisplayMode(mode) }
    }

    fun setOverlayX(x: Int) { scope.launch { settingsRepository.setOverlayX(x) } }
    fun setOverlayY(y: Int) { scope.launch { settingsRepository.setOverlayY(y) } }
    fun setOverlayWidth(width: Int) { scope.launch { settingsRepository.setOverlayWidth(width) } }
    fun setOverlayHeight(height: Int) { scope.launch { settingsRepository.setOverlayHeight(height) } }

    fun setOverlayActive(active: Boolean) {
        _isOverlayActive.value = active
        if (active) _runtimeError.value = null
    }

    fun clearRuntimeError() { _runtimeError.value = null }

    /**
     * 检查 ASR 模型是否已下载，未下载则弹窗提醒。
     * 源语言切换时调用。根据传入的源语言（非 currentAsrEngineType）解析引擎，
     * 避免因 updateSourceLanguage 异步流未传播导致引擎类型用旧语言。
     */
    fun checkAsrModel(sourceLanguage: String, displayName: String) {
        scope.launch(Dispatchers.IO) {
            val engine = resolveAsrEngine(sourceLanguage)
            Log.i(LOG_TAG, "checkAsrModel: engine=$engine, lang=$sourceLanguage")
            val ready = when (engine) {
                AsrEngineType.VOSK -> {
                    val modelDir = modelPreparer.asrModelDir(sourceLanguage)
                    val amDir = java.io.File(modelDir, "am")
                    val confDir = java.io.File(modelDir, "conf")
                    val graphDir = java.io.File(modelDir, "graph")
                    amDir.exists() && confDir.exists() && graphDir.exists()
                }
                AsrEngineType.SHERPA_ONNX,
                AsrEngineType.SHERPA_ONNX_BN,
                AsrEngineType.SHERPA_ONNX_NEMOTRON -> {
                    val modelId = resolveSherpaModelId(sourceLanguage)
                        ?: return@launch // 无对应 Sherpa 模型，跳过检查
                    modelPreparer.isSherpaOnnxAsrReady(modelId)
                }
            }
            if (!ready) {
                Log.w(LOG_TAG, "checkAsrModel: 模型未就绪, engine=$engine, lang=$sourceLanguage")
                _asrDownloadRequest.value = AsrDownloadRequest(sourceLanguage, displayName)
            }
        }
    }

    /** ASR 模型下载请求 */
    data class AsrDownloadRequest(
        val languageCode: String,
        val displayName: String,
        val requestId: Long = System.currentTimeMillis()
    )

    private val _asrDownloadRequest = MutableStateFlow<AsrDownloadRequest?>(null)
    val asrDownloadRequest: StateFlow<AsrDownloadRequest?> = _asrDownloadRequest.asStateFlow()

    fun dismissAsrDownload() { _asrDownloadRequest.value = null }

    /**
     * 启动悬浮字幕后调用，确保 ASR 和翻译模型已加载。
     * ASR 引擎类型仅由源语言唯一决定（与翻译引擎解耦），**不做 fallback**：
     * - 未下载 → 精准提示"请下载 XX 模型"，不会偷偷切到其他语种模型导致乱码
     */
    fun ensureModelsLoaded() {
        val s = _subtitleState.value
        val engine = s.engine
        val asrEngine = currentAsrEngineType
        val lang = s.sourceLanguage
        Log.i(LOG_TAG, "[ENSURE] ensureModelsLoaded: asrEngine=$asrEngine, transEngine=$engine, source=$lang, target=${s.targetLanguage}")
        scope.launch {
            Log.w(LOG_TAG, "[ENSURE] ===== ASR 模型准备诊断开始, lang=$lang, pickedEngine=$asrEngine =====")
            Log.w(LOG_TAG, "[ENSURE] 候选引擎逐个自检：")
            val candidates = when {
                lang == "zh" || lang == "en" -> listOf(AsrEngineType.SHERPA_ONNX, AsrEngineType.VOSK)
                lang == "bn" -> listOf(AsrEngineType.SHERPA_ONNX_BN, AsrEngineType.VOSK)
                SherpaOnnxModel.NEMOTRON_LANGUAGES.contains(lang) -> listOf(AsrEngineType.SHERPA_ONNX_NEMOTRON, AsrEngineType.VOSK)
                else -> listOf(AsrEngineType.VOSK)
            }
            for (c in candidates) {
                checkEngineReadyAndLog(c, lang)
            }
            Log.w(LOG_TAG, "[ENSURE] ===== ASR 模型准备诊断结束, pickedEngine=$asrEngine =====")

            // 1. 翻译模型准备（失败仅告警，不阻断 ASR）
            modelPreparer.prepareTranslation(
                engine = engine,
                sourceLanguage = lang,
                targetLanguage = s.targetLanguage
            ).onFailure { e ->
                Log.e(LOG_TAG, "[ENSURE] 翻译模型准备失败: ${e.message}")
                _runtimeError.value = "翻译模型未就绪：${e.message}"
            }
            // 2. ASR 模型准备 —— 严格按映射引擎，无 fallback；但 prepare 前后打印关键信息
            val prepare: Result<Unit> = when (asrEngine) {
                AsrEngineType.VOSK -> {
                    val dir = modelPreparer.asrModelDir(lang)
                    val listStr = runCatching {
                        dir.listFiles()?.joinToString(limit = 10) { f -> f.name }
                    }.getOrDefault("<list_dir_err>")
                    Log.i(LOG_TAG, "[ENSURE] 准备 Vosk 模型, lang=$lang, dir=$dir, exists=${dir.exists()}, files(top10)=$listStr")
                    modelPreparer.prepareAsr(lang)
                }
                AsrEngineType.SHERPA_ONNX,
                AsrEngineType.SHERPA_ONNX_BN,
                AsrEngineType.SHERPA_ONNX_NEMOTRON -> {
                    val modelId = resolveSherpaModelId(lang)
                    if (modelId == null) {
                        Result.failure(IllegalStateException(
                            "源语言 $lang 无对应 Sherpa-ONNX 模型，请切换语言或下载对应模型"
                        ))
                    } else {
                        val dir = modelPreparer.sherpaOnnxModelDir(modelId)
                        val listStr = runCatching {
                            dir.listFiles()?.joinToString(limit = 10) { f -> f.name }
                        }.getOrDefault("<list_dir_err>")
                        Log.i(LOG_TAG, "[ENSURE] 准备 Sherpa-ONNX 模型, modelId=$modelId, lang=$lang, dir=$dir, exists=${dir.exists()}, files(top10)=$listStr")
                        if (asrEngine == AsrEngineType.SHERPA_ONNX_NEMOTRON) {
                            val resolved = resolveNemotronLanguage(lang)
                            Log.i(LOG_TAG, "[ENSURE] Nemotron 选语言: lang=$lang → resolvedNemotronLang=$resolved")
                            sherpaOnnxAsrEngine.setLanguage(resolved)
                        }
                        modelPreparer.prepareSherpaOnnxAsr(modelId)
                    }
                }
            }
            prepare.onSuccess {
                Log.i(LOG_TAG, "[ENSURE] ASR 模型 prepare 成功, engine=$asrEngine, lang=$lang")
            }
            prepare.onFailure { err ->
                Log.e(LOG_TAG, "[ENSURE] ASR 准备失败, engine=$asrEngine, lang=$lang: ${err.message}", err)
                _runtimeError.value = "语音识别模型未下载，请前往模型下载界面下载对应模型"
            }
        }
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var audioProcessingJob: Job? = null

    /** 音频采集互斥锁，防止 restartAudioProcessing 被并发调用导致状态错乱 */
    private val audioMutex = Mutex()

    /** partial 显示用 StringBuilder（复用，减少 GC） */
    private val partialDisplayBuilder = StringBuilder()

    /** MediaProjection 实例（应用内声音捕获所需，需在前台服务中创建） */
    private var mediaProjection: MediaProjection? = null

    /** 待处理的 MediaProjection 授权结果（resultCode + intent data），
     *  由 Activity 授权后保存，在 FloatingSubtitleService 成为前台服务后再创建实例。
     *  原因：Android 14+ 要求 getMediaProjection() 时必须已有 MEDIA_PROJECTION 类型的前台服务在运行，
     *        否则抛出 SecurityException。 */
    private var pendingProjectionResultCode: Int? = null
    private var pendingProjectionData: android.content.Intent? = null

    /**
     * 保存 MediaProjection 授权结果（不立即创建实例）。
     * 必须在 FloatingSubtitleService 成为前台服务后，调用 createMediaProjectionFromFgs() 才真正创建。
     * 重复调用会覆盖旧 token。
     */
    fun saveMediaProjectionToken(resultCode: Int, data: android.content.Intent?) {
        if (data == null) {
            return
        }
        pendingProjectionResultCode = resultCode
        pendingProjectionData = data
        mediaProjection = null
    }

    /**
     * 检查是否有待处理的 MediaProjection token（或已有实例）。
     * 用于 FloatingSubtitleService 决定是否声明 MEDIA_PROJECTION FGS 类型。
     */
    fun hasMediaProjectionToken(): Boolean =
        mediaProjection != null || (pendingProjectionResultCode != null && pendingProjectionData != null)

    /**
     * 从前台服务中创建 MediaProjection 实例。
     * 必须在服务已启动为前台服务（含 MEDIA_PROJECTION 类型）之后调用。
     */
    fun createMediaProjectionFromFgs(context: android.content.Context): Boolean {
        if (mediaProjection != null) return true
        val resultCode = pendingProjectionResultCode ?: return false
        val data = pendingProjectionData ?: return false
        return try {
            val mpm = context.getSystemService(
                android.content.Context.MEDIA_PROJECTION_SERVICE
            ) as android.media.projection.MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
            mediaProjection = projection
            pendingProjectionResultCode = null
            pendingProjectionData = null
            true
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "MediaProjection 创建失败：安全异常")
            false
        } catch (e: Exception) {
            Log.e(LOG_TAG, "MediaProjection 创建失败", e)
            false
        }
    }

    /**
     * 设置 MediaProjection 实例（兼容旧调用方式，直接注入实例）。
     */
    fun setMediaProjection(projection: MediaProjection?) {
        mediaProjection = projection
        pendingProjectionResultCode = null
        pendingProjectionData = null
    }

    /**
     * 释放 MediaProjection 实例（保留 token）。
     * Service.onDestroy 时调用，避免脏引用导致 audio policy 注册崩溃。
     * token 保留，下次启动时无需再次弹窗授权。
     */
    fun releaseMediaProjectionInstance() {
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "releaseMediaProjectionInstance: stop 异常: ${e.message}")
        }
        mediaProjection = null
        Log.i(LOG_TAG, "releaseMediaProjectionInstance: 已释放实例，保留 token")
    }

    /**
     * 释放 MediaProjection 实例和待处理 token。
     * 仅在切换音频源（从应用内声音切到麦克风）时调用。
     */
    fun releaseMediaProjection() {
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "releaseMediaProjection: stop 异常: ${e.message}")
        }
        mediaProjection = null
        pendingProjectionResultCode = null
        pendingProjectionData = null
        Log.i(LOG_TAG, "releaseMediaProjection: 已清空 MediaProjection 实例和 token")
    }

    /**
     * 检查 MediaProjection 实例是否已就绪。
     */
    fun hasMediaProjection(): Boolean = mediaProjection != null

    /** 重启音频采集（切换音频源时调用） */
    private fun restartAudioProcessing() {
        scope.launch(Dispatchers.IO) {
            audioMutex.withLock {
                Log.i(LOG_TAG, "restartAudioProcessing: 开始重启音频采集")
                isRecording = false
                audioProcessingJob?.cancel()
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                delay(100)
                // 直接在这里启动，复用 startAudioProcessing 的内部逻辑
                // 注意：不能调用 startAudioProcessing() 因为它也会尝试锁 mutex
                startAudioProcessingLocked()
            }
        }
    }

    /**
     * 在已持有 audioMutex 的情况下启动音频采集（供 restartAudioProcessing 内部调用）。
     *
     * 严格唯一引擎策略：
     * - 语言 → 唯一引擎映射，无 fallback（避免模型下载后因 fallback 用错引擎）
     * - 模型文件/init 失败 → 精准提示用户该语言应下载什么模型
     */
    private suspend fun startAudioProcessingLocked() {
        if (isRecording) return
        Log.i(LOG_TAG, "startAudioProcessingLocked: 开始启动音频采集...")

        val sourceLanguage = _subtitleState.value.sourceLanguage
        val engine = currentAsrEngineType

        // === 1. 解析期望的模型路径 / modelId（唯一引擎，单路径） ===
        val expectedModelPath: String
        val expectedModelId: String?
        when (engine) {
            AsrEngineType.VOSK -> {
                expectedModelPath = modelPreparer.asrModelDir(sourceLanguage).absolutePath
                expectedModelId = null
            }
            AsrEngineType.SHERPA_ONNX,
            AsrEngineType.SHERPA_ONNX_BN,
            AsrEngineType.SHERPA_ONNX_NEMOTRON -> {
                val modelId = resolveSherpaModelId(sourceLanguage)
                if (modelId == null) {
                    Log.e(LOG_TAG, "startAudioProcessingLocked: 源语言 $sourceLanguage 无 Sherpa 模型, engine=$engine")
                    _runtimeError.value = "源语言 $sourceLanguage 无对应 ASR 模型，请切换语言"
                    _isOverlayActive.value = false
                    return
                }
                expectedModelId = modelId
                expectedModelPath = modelPreparer.sherpaOnnxModelDir(modelId).absolutePath
            }
        }

        // === 2. 判断是否需要 reload（引擎已就绪且 modelId/path 匹配则复用） ===
        val needReload = when (engine) {
            AsrEngineType.VOSK ->
                !voskAsrEngine.isReady() || voskAsrEngine.loadedModelPath != expectedModelPath
            AsrEngineType.SHERPA_ONNX,
            AsrEngineType.SHERPA_ONNX_BN,
            AsrEngineType.SHERPA_ONNX_NEMOTRON ->
                !sherpaOnnxAsrEngine.isReady() || sherpaOnnxAsrEngine.loadedModelId != expectedModelId
        }

        if (needReload) {
            Log.d(LOG_TAG, "startAudioProcessingLocked: 加载/切换模型, engine=$engine, lang=$sourceLanguage" +
                    ", expectedModelId=$expectedModelId, currentSherpaId=${sherpaOnnxAsrEngine.loadedModelId}" +
                    ", voskLoaded=${voskAsrEngine.loadedModelPath}")
            // Nemotron 需要在 init 前设置 per-stream language，setOption 生效
            if (engine == AsrEngineType.SHERPA_ONNX_NEMOTRON) {
                sherpaOnnxAsrEngine.setLanguage(resolveNemotronLanguage(sourceLanguage))
            }
            // prepare：校验文件完整性 + 引擎 init（SubtitleManager 的引擎实例）
            val asrResult = when (engine) {
                AsrEngineType.VOSK -> modelPreparer.prepareAsr(sourceLanguage)
                AsrEngineType.SHERPA_ONNX,
                AsrEngineType.SHERPA_ONNX_BN,
                AsrEngineType.SHERPA_ONNX_NEMOTRON -> modelPreparer.prepareSherpaOnnxAsr(expectedModelId!!)
            }
            if (asrResult.isFailure) {
                Log.e(LOG_TAG, "startAudioProcessingLocked: ASR prepare 失败, engine=$engine: ${asrResult.exceptionOrNull()?.message}")
                _runtimeError.value = "语音识别模型未下载，请前往模型下载界面下载对应模型"
                _isOverlayActive.value = false
                return
            }
            // ModelPreparer 用的是另一套引擎实例，这里再 init SubtitleManager 自己的实例
            val initResult = when (engine) {
                AsrEngineType.VOSK -> voskAsrEngine.init(expectedModelPath)
                AsrEngineType.SHERPA_ONNX,
                AsrEngineType.SHERPA_ONNX_BN,
                AsrEngineType.SHERPA_ONNX_NEMOTRON -> sherpaOnnxAsrEngine.init(expectedModelPath)
            }
            if (initResult.isFailure) {
                Log.e(LOG_TAG, "startAudioProcessingLocked: ASR engine init 失败, engine=$engine: ${initResult.exceptionOrNull()?.message}")
                _runtimeError.value = buildString {
                    append("语音识别引擎初始化失败，请前往模型下载界面检查对应模型是否损坏")
                    append("（错误：")
                    append(initResult.exceptionOrNull()?.message ?: "未知")
                    append("）")
                }
                _isOverlayActive.value = false
                return
            }
        }
        Log.i(LOG_TAG, "startAudioProcessingLocked: ASR 模型加载完成, engine=$engine, modelId=$expectedModelId")

        // === 3. 音频采集（麦克风 / 应用内声音） ===
        val currentSource = audioSource.value
        Log.d(LOG_TAG, "startAudioProcessingLocked: 音频源=$currentSource (0=麦克风, 1=应用内声音)")

        val record: AudioRecord = if (currentSource == 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mp = mediaProjection
            if (mp == null) {
                // 用户要求：选"应用内声音"必须确认开启 MediaProjection 权限才允许开浮窗，不回退麦克风。
                Log.e(LOG_TAG, "startAudioProcessingLocked: 用户选择应用内声音但无 MediaProjection 授权 → 直接拒绝开启浮窗（不回退麦克风）")
                _runtimeError.value = "请先授权屏幕录制权限，以启用应用内声音捕获（不允许回退麦克风）"
                _isOverlayActive.value = false
                return
            }
            try {
                createPlaybackCaptureAudioRecord(mp)
            } catch (e: UnsupportedOperationException) {
                // ROM 不支持 playback capture 时，也不允许回退麦克风（用户明确选应用内声音就必须用这个）
                Log.e(LOG_TAG, "startAudioProcessingLocked: 应用内声音捕获不支持(${e.message}) → 关闭浮窗(不回退麦克风)", e)
                releaseMediaProjection()
                _runtimeError.value = "当前设备不支持应用内声音捕获，请改选\"麦克风\"或检查系统权限"
                _isOverlayActive.value = false
                return
            } catch (e: Exception) {
                Log.e(LOG_TAG, "startAudioProcessingLocked: 应用内声音 AudioRecord 创建失败", e)
                releaseMediaProjection()
                _runtimeError.value = "应用内声音捕获失败：${e.message ?: "未知"}（不回退麦克风）"
                _isOverlayActive.value = false
                return
            }
        } else {
            // 默认 = 麦克风声音
            createMicrophoneAudioRecord()
        }

        audioRecord = record
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "startAudioProcessingLocked: AudioRecord init failed, state=${record.state}")
            runCatching { record.release() }
            audioRecord = null
            _runtimeError.value = "麦克风初始化失败，请检查权限或硬件"
            return
        }
        record.startRecording()
        isRecording = true
        _vadState.value = VadState.LISTENING
        Log.i(LOG_TAG, "startAudioProcessingLocked: 音频采集启动成功，isRecording=true")

        // === 4. 循环读帧：VAD -> ASR -> 翻译 ===
        val audioBuffer = ShortArray(FRAME_SIZE)
        val accumulateBuffer = ShortArray(FRAME_SIZE * 2)
        var accumulateOffset = 0
        val frameBuffer = ShortArray(FRAME_SIZE)
        var vadSpeechFrames = 0
        var vadSilentFrames = 0
        var debouncedVadState = false
        var asrPaused = false

        while (isRecording) {
            val shortsRead = record.read(audioBuffer, 0, audioBuffer.size)
            if (!isRecording) break
            if (shortsRead > 0) {
                System.arraycopy(audioBuffer, 0, accumulateBuffer, accumulateOffset, shortsRead)
                accumulateOffset += shortsRead

                while (accumulateOffset >= FRAME_SIZE) {
                    System.arraycopy(accumulateBuffer, 0, frameBuffer, 0, FRAME_SIZE)
                    System.arraycopy(
                        accumulateBuffer, FRAME_SIZE,
                        accumulateBuffer, 0,
                        accumulateOffset - FRAME_SIZE
                    )
                    accumulateOffset -= FRAME_SIZE

                    // ===== VAD 检测 =====
                    val vadResult = vadEngine.processAudio(frameBuffer)
                    if (vadResult.hasSpeech) {
                        vadSpeechFrames++
                        vadSilentFrames = 0
                        if (vadSpeechFrames >= VAD_SPEECH_DEBOUNCE_FRAMES && !debouncedVadState) {
                            debouncedVadState = true
                            _vadState.value = VadState.LISTENING
                            if (asrPaused) {
                                Log.i(LOG_TAG, "VAD 语音恢复: reset ASR stream, 恢复送音")
                                currentAsrEngine.resetStream()
                                asrPaused = false
                            }
                        }
                    } else {
                        vadSilentFrames++
                        vadSpeechFrames = 0
                        val isVoskMode = (inputModeEngine ?: currentAsrEngineType) == AsrEngineType.VOSK
                        if (isVoskMode && vadSilentFrames == VAD_SOFT_SILENCE_FRAMES &&
                            currentSourceBuffer.isNotEmpty()) {
                            Log.d(LOG_TAG, "VAD 软静音(480ms): 提交字幕 final, buffer='${currentSourceBuffer}'")
                            handleFinalResult(currentSourceBuffer.toString())
                        }
                        if (vadSilentFrames >= VAD_SILENT_DEBOUNCE_FRAMES && debouncedVadState) {
                            Log.d(LOG_TAG, "VAD SILENT 触发: silentFrames=$vadSilentFrames")
                            debouncedVadState = false
                            _vadState.value = VadState.SILENT
                        }
                        if (vadSilentFrames >= VAD_STOP_ASR_FRAMES && !asrPaused) {
                            Log.i(LOG_TAG, "VAD 长时间静音(2s): 暂停 ASR 送音")
                            asrPaused = true
                        }
                    }

                    // ===== ASR 送音(静音超 60 帧后跳过) =====
                    if (!asrPaused) {
                        currentAsrEngine.feedAudio(frameBuffer)
                        currentAsrEngine.decodeAndGetResult()
                    }

                    // Vosk 模式 VAD 10s 超时清空字幕；Sherpa 模式 VAD 只做人声检测
                    val isVoskMode = (inputModeEngine ?: currentAsrEngineType) == AsrEngineType.VOSK
                    if (isVoskMode && !debouncedVadState && lastFinalTimeMs > 0) {
                        val elapsed = System.currentTimeMillis() - lastFinalTimeMs
                        if (elapsed > SUBTITLE_RETENTION_MS && currentSourceBuffer.isNotEmpty()) {
                            currentSourceBuffer.setLength(0)
                            currentTranslationBuffer.setLength(0)
                            historyLines.clear()
                            lastPartialTranslateLength = 0
                            _subtitleState.value = _subtitleState.value.copy(lines = emptyList())
                            lastFinalTimeMs = 0L
                        }
                    }
                }
            } else if (shortsRead < 0) {
                delay(100)
            }
        }
        Log.i(LOG_TAG, "录音循环退出")
        isRecording = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        _vadState.value = VadState.LISTENING
        Log.i(LOG_TAG, "startAudioProcessingLocked: 资源已释放")
    }

    /**
     * 如果录音正在运行，重启音频采集（MediaProjection 授权后调用）。
     * 公开方法，供 MainActivity 在授权回调中调用。
     */
    fun restartAudioProcessingIfRunning() {
        if (isRecording) {
            restartAudioProcessing()
        }
    }

    /**
     * 启动音频采集和处理主循环（Vosk 多语种真流式版）
     *
     * 架构：
     *   AudioRecord -> VAD(仅UI状态) -> VoskAsrEngine.feedAudio
     *   -> Vosk 内部 getPartialResult/getResult -> Flow 推送 partial/final
     *   -> UI 更新 / 触发翻译
     *
     * 音频源：
     *   0=麦克风(VOICE_RECOGNITION, 带系统 AGC/AEC/NS)
     *   1=应用内声音(AudioPlaybackCapture, Android 10+, 需 MediaProjection 授权)
     *
     * 线程安全：通过 audioMutex 确保 start/stop 原子性，防止并发调用导致多录音协程。
     */
    fun startAudioProcessing() {
        scope.launch(Dispatchers.IO) {
            audioMutex.withLock {
                if (isRecording) {
                    Log.d(LOG_TAG, "startAudioProcessing: 已在录音，跳过")
                    return@withLock
                }
                Log.i(LOG_TAG, "startAudioProcessing: 开始启动音频采集...")
                startAudioProcessingLocked()
            }
        }
    }

    fun stopAudioProcessing() {
        scope.launch(Dispatchers.IO) {
            // 尝试获取锁，如果正在启动中，则设置 isRecording=false 让启动流程自行退出
            if (audioMutex.tryLock()) {
                try {
                    isRecording = false
                    audioProcessingJob?.cancel()
                    // 翻译任务采用版本号管理，停止时递增版本号使在途结果失效
                    translationVersion.incrementAndGet()
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null
                    _vadState.value = VadState.LISTENING
                    // 停止时清空字幕状态
                    currentSourceBuffer.setLength(0)
                    currentTranslationBuffer.setLength(0)
                    historyLines.clear()
                    lastPartialTranslateLength = 0
                    lastFinalTimeMs = 0L
                    _subtitleState.value = _subtitleState.value.copy(lines = emptyList())
                    _isTranslating.value = false
                    Log.i(LOG_TAG, "stopAudioProcessing: 音频采集已停止，字幕已清空")
                } finally {
                    audioMutex.unlock()
                }
            } else {
                // mutex 被持有（启动中），设置标志让启动流程检测到后退出
                isRecording = false
                Log.i(LOG_TAG, "stopAudioProcessing: 启动中，设置 isRecording=false 等待退出")
            }
        }
    }

    /**
     * 创建麦克风 AudioRecord。
     * 使用 VOICE_RECOGNITION 音频源，自带系统 AGC/AEC/NS 优化。
     * 缓冲大小取系统最小值的2倍，减少read调用频率，降低CPU开销。
     */
    private fun createMicrophoneAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, FRAME_SIZE * 2 * 2) // 至少2帧缓冲
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    }

    /**
     * 创建应用内声音捕获 AudioRecord（Android 10+）。
     * 通过 AudioPlaybackCapture API 捕获系统播放的音频。
     * 需要 MediaProjection 授权。
     * 缓冲大小取系统最小值的2倍，减少read调用频率。
     */
    private fun createPlaybackCaptureAudioRecord(projection: MediaProjection): AudioRecord {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection).apply {
                addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                addMatchingUsage(AudioAttributes.USAGE_GAME)
                addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            }.build()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer, FRAME_SIZE * 2 * 2)
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } else {
            createMicrophoneAudioRecord()
        }
    }

    companion object {
        private const val LOG_TAG = "SubtitleManager"

        /** 采样率：16kHz，Vosk 和 WebRTC VAD 通用采样率 */
        private const val SAMPLE_RATE = 16000

        /** 帧大小：480样本 = 30ms（采样率16000）。
         *  Silero VAD 要求 512 samples (32ms), 引擎内部缓冲对齐。
         *  保持 480 帧以匹配 ASR 引擎特征提取需求。 */
        private const val FRAME_SIZE = 480

        /** VAD 语音开始防抖帧数：连续 5 帧检测到语音才判定开始（~160ms）
         *  Silero VAD 每 ~32ms 一窗口, 480 样本帧约每 1.07 帧送一次 VAD,
         *  因此 5 帧 ≈ 5 × 30ms × 1.07 ≈ 160ms, 符合 5~8 帧(160~256ms) 范围 */
        private const val VAD_SPEECH_DEBOUNCE_FRAMES = 5

        /** VAD 软静音帧数：连续 15 帧静音(480ms) 触发软静音,
         *  用于 Vosk 模式下字幕 final 提交(替代 Sherpa endpoint) */
        private const val VAD_SOFT_SILENCE_FRAMES = 15  // 480ms 软静音

        /** VAD 静音结束防抖帧数：连续 28 帧静音才判定结束（~896ms）。
         *  取 subtitle commit 范围(25~32 帧, 800~1024ms) 中间值 */
        private const val VAD_SILENT_DEBOUNCE_FRAMES = 28  // ~896ms 字幕提交

        /** VAD 停止 ASR 帧数：连续 60 帧静音(~2s) 停止送入 ASR, 节省计算。
         *  语音恢复时自动 resetStream 并重新送入 */
        private const val VAD_STOP_ASR_FRAMES = 60  // ~2s 停止 ASR

        /** 翻译防抖延迟（毫秒）：避免短时间内重复触发翻译请求 */
        private const val TRANSLATE_DEBOUNCE_MS = 200L

        /** 轻量润色超时（毫秒）：超时则跳过润色，直接翻译 */
        private const val POLISH_TIMEOUT_MS = 2000L

        /** 翻译超时（毫秒）：智能模式润色+翻译合并调用的总超时 */
        private const val TRANSLATE_TIMEOUT_MS = 5000L

        /** 轻量润色最小长度：少于此字符不润色，省资源 */
        private const val MIN_POLISH_LENGTH = 5

        /** 字幕文本最大留存时间（毫秒），Vosk 模式下超时后自动清空 */
        private const val SUBTITLE_RETENTION_MS = 10_000L

        /** 最大历史字幕行数：保留最近 N 行，自动向上滚动 */
        private const val MAX_HISTORY_LINES = 3

        /** Partial 翻译滑动窗口步长（中文）：每增长 N 字触发一次预览翻译 */
        private const val PARTIAL_TRANSLATE_STEP_ZH = 5

        /** Partial 翻译滑动窗口步长（英文）：每增长 N 字符触发一次预览翻译 */
        private const val PARTIAL_TRANSLATE_STEP_EN = 20

        /** Partial 翻译最小长度：少于此长度不触发预览翻译 */
        private const val MIN_PARTIAL_TRANSLATE_LENGTH = 5
    }
}
