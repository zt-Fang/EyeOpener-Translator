/**
 * 字幕核心管理器：音频采集 → VAD(仅UI) → ASR → 翻译 → 悬浮窗状态。
 * partial 实时显示，final 触发翻译；源语种切换动态加载模型。
 */
package io.github.ztfang.eye.viewmodel

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import io.github.ztfang.eye.BuildConfig
import io.github.ztfang.eye.domain.engine.asr.AsrEngine
import io.github.ztfang.eye.domain.engine.vad.VADEngine
import io.github.ztfang.eye.domain.model.AsrEngineType
import io.github.ztfang.eye.domain.model.AsrRoutingTable
import io.github.ztfang.eye.domain.model.DisplayMode
import io.github.ztfang.eye.domain.model.ModelState
import io.github.ztfang.eye.domain.model.ModelStatus
import io.github.ztfang.eye.domain.model.SherpaOnnxModel
import io.github.ztfang.eye.domain.model.SubtitleLine
import io.github.ztfang.eye.domain.model.SubtitleState
import io.github.ztfang.eye.domain.model.SubtitleType
import io.github.ztfang.eye.domain.model.TranslationEngine
import io.github.ztfang.eye.domain.model.VoskLanguage
import io.github.ztfang.eye.domain.repository.SettingsRepository
import io.github.ztfang.eye.domain.usecase.translation.TranslateUseCase
import io.github.ztfang.eye.engine.ModelPreparer
import io.github.ztfang.eye.engine.TranslationPrepPhase
import io.github.ztfang.eye.engine.asr.SherpaOnnxAsrEngine
import io.github.ztfang.eye.engine.asr.VoskAsrEngine
import io.github.ztfang.eye.engine.asr.VoskLanguageMap
import io.github.ztfang.eye.engine.translation.TranslationPrepException
import io.github.ztfang.eye.engine.translation.TranslationPrepFailureKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubtitleManager
    @Inject
    constructor(
        private val translateUseCase: TranslateUseCase,
        private val settingsRepository: SettingsRepository,
        private val historyRepository: io.github.ztfang.eye.domain.repository.HistoryRepository,
        private val vadEngine: VADEngine,
        private val voskAsrEngine: VoskAsrEngine,
        private val sherpaOnnxAsrEngine: SherpaOnnxAsrEngine,
        private val modelPreparer: ModelPreparer,
        private val llmClient: io.github.ztfang.eye.engine.translation.llm.LLMClient,
        private val modelRepository: io.github.ztfang.eye.domain.repository.ModelRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val _subtitleState = MutableStateFlow(SubtitleState())
        val subtitleState: StateFlow<SubtitleState> = _subtitleState.asStateFlow()

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
        @Volatile
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
                val result =
                    when (actualEngine) {
                        AsrEngineType.VOSK -> modelPreparer.prepareAsr(sourceLanguage)
                        AsrEngineType.SHERPA_ONNX,
                        AsrEngineType.SHERPA_ONNX_BN,
                        AsrEngineType.SHERPA_ONNX_NEMOTRON,
                        -> {
                            if (modelId == null) {
                                Result.failure(
                                    IllegalStateException(
                                        "源语言 $sourceLanguage 无对应 Sherpa-ONNX 模型，请切换语言",
                                    ),
                                )
                            } else {
                                modelPreparer.prepareSherpaOnnxAsr(modelId)
                            }
                        }
                    }
                result
                    .onSuccess {
                        if (!isRecording) {
                            startAudioProcessing()
                        } else {
                            currentAsrEngine.resetStream()
                        }
                    }.onFailure { e ->
                        Log.e(LOG_TAG, "startVoiceInput: 模型准备失败, engine=$actualEngine, lang=$sourceLanguage", e)
                        _runtimeError.value =
                            buildString {
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

        /** 悬浮窗位置与尺寸：从 DataStore 持续同步，支持双向更新 */
        val overlayX: StateFlow<Int> =
            settingsRepository.overlayX
                .stateIn(scope, SharingStarted.Eagerly, 0)
        val overlayY: StateFlow<Int> =
            settingsRepository.overlayY
                .stateIn(scope, SharingStarted.Eagerly, 0)
        val overlayWidth: StateFlow<Int> =
            settingsRepository.overlayWidth
                .stateIn(scope, SharingStarted.Eagerly, 0)
        val overlayHeight: StateFlow<Int> =
            settingsRepository.overlayHeight
                .stateIn(scope, SharingStarted.Eagerly, 0)

        /** 个性化设置：暴露给悬浮窗 Service 实时应用 */
        val accentColorIndex: StateFlow<Int> =
            settingsRepository.accentColorIndex
                .stateIn(scope, SharingStarted.Eagerly, 1)
        val backgroundTransparency: StateFlow<Float> =
            settingsRepository.backgroundTransparency
                .stateIn(scope, SharingStarted.Eagerly, 0.75f)
        val fontSize: StateFlow<Float> =
            settingsRepository.fontSize
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
        private fun checkEngineReadyAndLog(
            engine: AsrEngineType,
            languageCode: String,
        ): Boolean {
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
                    val listStr =
                        runCatching {
                            dir.listFiles()?.joinToString(limit = 5) { f -> f.name }
                        }.getOrDefault("<list_dir_err>")
                    ready = supported && am && conf && graph
                    details =
                        buildString {
                            append("Vosk lang=$languageCode, supported=$supported, dir=$dir, exists=$dirExists")
                            append(", am=$am, conf=$conf, graph=$graph, files(top5)=$listStr")
                        }
                }
                AsrEngineType.SHERPA_ONNX,
                AsrEngineType.SHERPA_ONNX_BN,
                AsrEngineType.SHERPA_ONNX_NEMOTRON,
                -> {
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
                        val tokOfficial =
                            model?.let { m ->
                                val tf = File(dir, m.tokensFile)
                                tf.exists() && tf.length() > 0L
                            } == true
                        val tokAlias =
                            model != null &&
                                runCatching {
                                    dir.listFiles()?.any { f ->
                                        val n = f.name
                                        (n == "${model.tokensFile}.part" || n == "${model.tokensFile}.part.corrupted") && f.length() > 0L
                                    } == true
                                }.getOrDefault(false)
                        val tok = tokOfficial || tokAlias
                        val listStr =
                            runCatching {
                                dir.listFiles()?.joinToString(limit = 5) { f -> "${f.name}(${f.length()})" }
                            }.getOrDefault("<list_dir_err>")
                        val key =
                            when (engine) {
                                AsrEngineType.SHERPA_ONNX -> "SHERPA_ONNX_ASR_${SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelId}"
                                AsrEngineType.SHERPA_ONNX_BN -> "SHERPA_ONNX_ASR_${SherpaOnnxModel.BN_VOSK_2026_02_09.modelId}"
                                AsrEngineType.SHERPA_ONNX_NEMOTRON -> "SHERPA_ONNX_ASR_${SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8.modelId}"
                            }
                        val byRepoPath = modelRepository.getModelPath(key)
                        details =
                            buildString {
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
         * 返回某语言所需模型的展示名与体积（字节），用于弹窗精准提示。
         * 引擎映射复用 [resolveAsrEngine]，保证提示的模型与运行时实际加载的一致。
         */
        private fun requiredModelInfo(languageCode: String): Pair<String, Long> {
            val route = AsrRoutingTable.forLanguage(languageCode)
            val sherpa = route.modelId?.let { SherpaOnnxModel.fromModelId(it) }
            return if (sherpa != null) {
                sherpa.displayName to sherpa.sizeBytes
            } else {
                "Vosk ${VoskLanguageMap.getDisplayName(languageCode)}" to
                    (VoskLanguage.fromCode(languageCode)?.sizeBytes ?: 0L)
            }
        }

        /**
         * 组装「模型未下载」运行时错误文案，带上所需模型名。
         * 文案前缀「语音识别模型未下载」被 [observeModelDownloads] 用于识别该类错误，改动需同步。
         */
        private fun modelMissingMessage(languageCode: String): String {
            val (modelName, _) = requiredModelInfo(languageCode)
            return "语音识别模型未下载：识别该语言需要「$modelName」模型，请前往模型下载界面下载"
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

        /**
         * 上次解析出的「源语言 → 引擎」，用于抑制热路径日志刷屏。
         * resolveAsrEngine 会被音频帧循环每帧调用，若每帧都 Log.i 会通过 logd IPC 产生
         * 数千次/秒的跨进程写日志（实测一次会话刷出 1900+ 行），严重拖慢整机。
         */
        @Volatile
        private var lastResolvedEngine: Pair<String, AsrEngineType>? = null

        private fun resolveAsrEngine(sourceLanguage: String): AsrEngineType {
            val eng = AsrRoutingTable.engineFor(sourceLanguage)
            // 只在解析结果发生变化时打日志（保留诊断能力，去掉热路径刷屏）
            val last = lastResolvedEngine
            if (last == null || last.first != sourceLanguage || last.second != eng) {
                lastResolvedEngine = sourceLanguage to eng
                Log.i(LOG_TAG, "[RESOLVE] lang=$sourceLanguage → force engine=$eng")
            }
            return eng
        }

        /** 解析 Sherpa-ONNX 模型 ID：zh/en→X-ASR, bn→BN Vosk, Nemotron 语种→Nemotron 3.5, 其他→null(Vosk) */
        private fun resolveSherpaModelId(languageCode: String): String? = AsrRoutingTable.modelIdFor(languageCode)

        /**
         * Nemotron per-stream language 代码：sherpa-onnx 接受 ISO 639-1 bare code（'en'/'ja'/'auto'），
         * 内部自动映射 locale，无需转 'en-US'。zh 实际走 X-ASR，传 'zh' 仅为防御。
         */
        private fun resolveNemotronLanguage(languageCode: String): String = languageCode

        /** 当前使用的 ASR 引擎实例（根据翻译模式动态切换；输入模式下可能被覆盖） */
        private val currentAsrEngine: AsrEngine
            get() =
                when (inputModeEngine ?: currentAsrEngineType) {
                    AsrEngineType.VOSK -> voskAsrEngine
                    // 所有 Sherpa-ONNX 变体共用同一引擎实例（通过 modelId 区分加载的模型）
                    AsrEngineType.SHERPA_ONNX,
                    AsrEngineType.SHERPA_ONNX_BN,
                    AsrEngineType.SHERPA_ONNX_NEMOTRON,
                    -> sherpaOnnxAsrEngine
                }

        /**
         * LLM（大模型）配置是否就绪：API Key 和 URL 都不为空。
         * 用于 UI 层判断点击 AI 模式时是否需要弹配置提示。
         */
        val isLlmConfigReady: StateFlow<Boolean> =
            settingsRepository.isLlmConfigReady
                .stateIn(scope, SharingStarted.Eagerly, false)

        /** 音频输入源：0=麦克风，1=应用内声音（AudioPlaybackCapture，Android 10+） */
        val audioSource: StateFlow<Int> =
            MutableStateFlow(0)
                .also { flow ->
                    scope.launch {
                        settingsRepository.audioSource.collect { new ->
                            val old = flow.value
                            flow.value = new
                            // 录音中切换音频源 → 重启音频采集
                            if (old != new && isRecording) {
                                restartAudioProcessing()
                            }
                        }
                    }
                }.asStateFlow()

        private val _isOverlayActive = MutableStateFlow(false)
        val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()

        private val _runtimeError = MutableStateFlow<String?>(null)
        val runtimeError: StateFlow<String?> = _runtimeError.asStateFlow()

        /** 离线翻译模型准备状态：供主屏显示非模态"下载中"状态条 */
        private val _translationModelState = MutableStateFlow(TranslationModelState())
        val translationModelState: StateFlow<TranslationModelState> = _translationModelState.asStateFlow()

        /**
         * 最近一次离线翻译模型失败的**技术细节**，供"设置 → 翻译模型诊断"查看。
         *
         * 失败弹窗只显示一句人话（按 [TranslationPrepFailureKind] 取文案），
         * 域名 / 解析到的 IP / 证书结论 / 原始异常收在这里，避免弹窗塞满用户看不懂的内容，
         * 同时保证排障时信息不丢。null 表示本次运行尚未发生过失败。
         */
        private val _translationModelDiagnostics = MutableStateFlow<String?>(null)
        val translationModelDiagnostics: StateFlow<String?> = _translationModelDiagnostics.asStateFlow()

        /** 离线翻译模型准备阶段 */
        enum class TranslationModelStatus {
            /** 空闲：无需处理（语言未确定、或非本地引擎） */
            IDLE,

            /** 正在查询本地是否已有模型 */
            CHECKING,

            /** 正在下载离线模型（首次约 30-100MB，需 GMS 且网络可达 Google） */
            DOWNLOADING,

            /** 模型就绪，可离线翻译 */
            READY,

            /** 失败，[TranslationModelState.message] 携带原因 */
            FAILED,
        }

        /**
         * 离线翻译模型准备状态。
         * @param status 当前阶段
         * @param sourceLanguage 该状态对应的源语言
         * @param targetLanguage 该状态对应的目标语言
         * @param failureKind 失败分类，UI 据此选一句人话（仅 FAILED 有值）
         * @param message 失败技术细节（仅 FAILED 有值）。**不要直接展示在弹窗正文**，
         *        里面是域名 / IP / 证书结论 / 原始异常，只在"设置 → 翻译模型诊断"里呈现。
         * @param elapsedMs 处于 DOWNLOADING 的累计时长，驱动"已等待 X"与慢速提示。
         *        ML Kit 不提供下载进度回调，只能用时长让用户知道"还在动"而不是卡死。
         */
        data class TranslationModelState(
            val status: TranslationModelStatus = TranslationModelStatus.IDLE,
            val sourceLanguage: String = "",
            val targetLanguage: String = "",
            val failureKind: TranslationPrepFailureKind? = null,
            val message: String = "",
            val elapsedMs: Long = 0L,
        )

        private val _vadState = MutableStateFlow(VadState.LISTENING)
        val vadState: StateFlow<VadState> = _vadState.asStateFlow()

        enum class VadState {
            LISTENING,
            SILENT,
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
                    // 异步准备翻译模型，不阻塞 collect（避免 ML Kit 下载时收不到后续引擎切换）。
                    // 语言取 DataStore 的当前值而非 _subtitleState 快照：init 阶段各 collect 的
                    // 执行顺序不确定，state 可能还是默认 en/zh，会白白下载与用户设置无关的语言对。
                    scope.launch {
                        val src = settingsRepository.sourceLanguage.first()
                        val tgt = settingsRepository.targetLanguage.first()
                        runTranslationPrep(
                            engine = engine,
                            sourceLanguage = src,
                            targetLanguage = tgt,
                            tag = "engine-collect",
                        )
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

        /** 下载计时器 Job：驱动"已等待 X 分 Y 秒"与"网络较慢"提示 */
        private var prepElapsedJob: Job? = null

        /**
         * 启动下载计时。ML Kit 没有进度回调，只能用"已等待时长"让用户确信下载还在进行、
         * 而不是卡死了。已在计时中则不重启（避免并发预热把计时归零）。
         */
        private fun startPrepElapsedTicker() {
            if (prepElapsedJob?.isActive == true) return
            prepElapsedJob =
                scope.launch {
                    val start = System.currentTimeMillis()
                    while (isActive) {
                        delay(PREP_ELAPSED_TICK_MS)
                        val current = _translationModelState.value
                        if (current.status != TranslationModelStatus.DOWNLOADING) break
                        _translationModelState.value =
                            current.copy(elapsedMs = System.currentTimeMillis() - start)
                    }
                }
        }

        /** 停止下载计时（下载结束/失败时调用） */
        private fun stopPrepElapsedTicker() {
            prepElapsedJob?.cancel()
            prepElapsedJob = null
        }

        /**
         * 统一的翻译模型准备入口。
         *
         * 把 [ModelPreparer.prepareTranslation] 的阶段回调映射为 UI 可观察的
         * [translationModelState]，并负责失败时的错误写入与成功后的错误清除。
         *
         * 注意：只有 LOCAL 引擎需要下载离线模型；CLOUD 仅校验 API Key，AI 仅校验 LLM 配置，
         * 因此它们的准备过程是秒回的，不会出现"下载中"状态。
         */
        private suspend fun runTranslationPrep(
            engine: TranslationEngine,
            sourceLanguage: String,
            targetLanguage: String,
            tag: String,
        ) {
            _translationModelState.value =
                TranslationModelState(
                    status = TranslationModelStatus.CHECKING,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                )

            val result =
                modelPreparer.prepareTranslation(
                    engine = engine,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    onPhase = { phase ->
                        val status =
                            when (phase) {
                                TranslationPrepPhase.IDLE -> TranslationModelStatus.IDLE
                                TranslationPrepPhase.CHECKING -> TranslationModelStatus.CHECKING
                                TranslationPrepPhase.DOWNLOADING -> TranslationModelStatus.DOWNLOADING
                                TranslationPrepPhase.READY -> TranslationModelStatus.READY
                            }
                        _translationModelState.value =
                            _translationModelState.value.copy(
                                status = status,
                                sourceLanguage = sourceLanguage,
                                targetLanguage = targetLanguage,
                            )
                        if (status == TranslationModelStatus.DOWNLOADING) {
                            startPrepElapsedTicker()
                        }
                    },
                )

            result
                .onSuccess {
                    stopPrepElapsedTicker()
                    Log.i(
                        LOG_TAG,
                        "[PREP:$tag] 翻译模型准备完成, engine=$engine, $sourceLanguage→$targetLanguage",
                    )
                    _translationModelState.value =
                        _translationModelState.value.copy(
                            status = TranslationModelStatus.READY,
                            failureKind = null,
                            message = "",
                        )
                }
                .onFailure { e ->
                    stopPrepElapsedTicker()
                    Log.e(LOG_TAG, "[PREP:$tag] 翻译模型准备失败: ${e.message}")
                    if (engine == TranslationEngine.LOCAL) {
                        // 离线模型准备失败 → 只走分级弹窗（含"重试"与"稍后"）。
                        // 这里**不再**写 _runtimeError：那条通道在 MainActivity 是 Toast，
                        // 会把"已等待 300s 仍未就绪。无法访问 Google 下载服务器：dl.google.com
                        // 被解析到 x.x.x.x…（原始错误：Timed out…）"整段弹出来，
                        // 既与弹窗重复，又是用户看不懂的技术细节。
                        val prep = e as? TranslationPrepException
                        val detail = prep?.detail ?: (e.message ?: "未知错误")
                        _translationModelDiagnostics.value = detail
                        _translationModelState.value =
                            _translationModelState.value.copy(
                                status = TranslationModelStatus.FAILED,
                                failureKind = prep?.kind ?: TranslationPrepFailureKind.UNKNOWN,
                                message = detail,
                            )
                    } else {
                        // 云端/AI 的"准备失败"本质是配置缺失，点击引擎卡片时已有专门提示；
                        // 这里不再弹"离线翻译模型下载失败"弹窗，避免文案张冠李戴与重复打扰。
                        _translationModelState.value =
                            _translationModelState.value.copy(
                                status = TranslationModelStatus.IDLE,
                                message = "",
                            )
                    }
                }
        }

        /**
         * 清除"翻译模型诊断"记录（设置页手动清空）。
         */
        fun clearTranslationModelDiagnostics() {
            _translationModelDiagnostics.value = null
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
                        Log.i(
                            LOG_TAG,
                            "observeModelDownloads: 模型已下载，清除错误" +
                                ", isRecording=$isRecording",
                        )
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
            if (BuildConfig.DEBUG) Log.d(LOG_TAG, "handlePartialResult: text=\"$text\", isInputMode=$isInputMode")
            if (isInputMode) {
                partialDisplayBuilder.setLength(0)
                if (inputFinalBuffer.isNotEmpty()) {
                    partialDisplayBuilder.append(inputFinalBuffer)
                }
                partialDisplayBuilder.append(text)
                _voiceInputText.value = partialDisplayBuilder.toString()
            } else {
                // 行边界由 app 按句末标点决定（引擎 endpoint 判据是尾静音 0.6s，连读不满足、抖动会断词）
                consumeSegmentText(text, flushTail = false)
            }
        }

        /** 处理 final 识别结果（语音输入模式 / 字幕模式分流） */
        private fun handleFinalResult(text: String) {
            if (BuildConfig.DEBUG) Log.d(LOG_TAG, "handleFinalResult: text=\"$text\", isInputMode=$isInputMode")
            lastFinalTimeMs = System.currentTimeMillis()
            if (isInputMode) {
                inputFinalBuffer.append(text)
                _voiceInputText.value = inputFinalBuffer.toString()
                return
            }

            // 纯标点/空白的 final 当作没发生：既不归档、不上屏，也不翻译、不写历史。
            if (!text.hasMeaningfulContent()) {
                if (BuildConfig.DEBUG) Log.d(LOG_TAG, "handleFinalResult: 纯标点 final，忽略")
                resetSegmentState()
                currentAsrEngine.resetStream()
                return
            }

            // 段结束：段内余下的文本按句末标点逐句收口，收不完的尾巴整句收尾成一行。
            unconfirmedLineSnapshot = ""
            consumeSegmentText(text, flushTail = true)
            resetSegmentState()
            currentAsrEngine.resetStream()
        }

        /** 把段内文本按句末标点收口成行；[segmentConsumedChars] 只增不减，保证不重复上屏。 */
        private fun consumeSegmentText(
            text: String,
            flushTail: Boolean,
        ) {
            // 文本被改写（引擎修订/裁掉头部）：用上一行末尾的词重新定位已收口位置，
            // 锚点由长到短依次尝试，避免对不齐时按旧偏移取子串把中间内容漏掉
            if (lastSegmentText.isNotEmpty() && !text.startsWith(lastSegmentText)) {
                val reanchored = resyncConsumed(text)
                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "段内文本被改写，重新对齐已收口位置 $segmentConsumedChars → $reanchored")
                }
                segmentConsumedChars = reanchored
            }
            lastSegmentText = text

            val segStart = if (segmentConsumedChars in 0..text.length) segmentConsumedChars else text.length
            var segmentRemainder = text.substring(segStart)
            // reset 后重解码会重复带走的半句：先削掉重复前缀
            if (carryOverText.isNotEmpty() && segmentRemainder.isNotEmpty()) {
                when {
                    segmentRemainder.startsWith(carryOverText) ->
                        segmentRemainder = segmentRemainder.substring(carryOverText.length).trimStart()
                    carryOverText.startsWith(segmentRemainder.trim()) -> segmentRemainder = ""
                }
            }
            // 接上上一段没说完的半句，跨段仍按一句处理
            var rest = if (carryOverText.isEmpty()) segmentRemainder else joinText(carryOverText, segmentRemainder)
            var carryLen = rest.length - segmentRemainder.length
            var consumed = segStart

            while (true) {
                val cut = findCutPoint(rest, flushTail) ?: break
                val row = rest.substring(0, cut).trim()
                consumed += maxOf(0, cut - carryLen)
                carryLen = maxOf(0, carryLen - cut)
                segmentConsumedChars = consumed
                rest = rest.substring(cut)
                if (row.isNotEmpty()) emitRow(row)
            }

            if (flushTail) {
                // 段结束仍无句号：半句带到下一段继续成句
                val tail = rest.trim()
                segmentConsumedChars = text.length
                if (tail.isEmpty()) {
                    carryOverText = ""
                    rest = ""
                } else if (tail.length > currentRowLimitChars() * HARD_CAP_RATIO) {
                    // 太长且一直无标点：先出一行，避免草稿无限长
                    carryOverText = ""
                    rest = ""
                    emitRow(tail)
                } else {
                    if (BuildConfig.DEBUG) Log.d(LOG_TAG, "段结束但没等到句号，半句带到下一段: \"$tail\"")
                    carryOverText = tail
                    rest = tail
                }
            } else {
                // 段继续：本段余量下次重新取，只保留上一段残留
                carryOverText = rest.take(minOf(carryLen, rest.length)).trim()
                rest = rest.trimStart()
            }
            updateSourceText(rest, isFinal = false)
        }

        /**
         * 找收口位置（返回切点之后的字符数，null = 这次不切）。
         *
         * 连读（没有句号）时的优先级：先把草稿里的**逗号**用掉 —— 行尾落在子句边界上，
         * 不会出现「一句没说完就断在半句」；只有一整段连逗号都没有、且已经超过两行，
         * 才按空格硬断一行。
         */
        private fun findCutPoint(
            text: String,
            flushTail: Boolean,
        ): Int? {
            val limit = currentRowLimitChars()
            val mark = findSentenceEnd(text)
            if (mark != null) {
                // 标点落在文本末尾时先不切：引擎可能把句号改成逗号
                if (!flushTail && text.substring(mark).isBlank()) return null
                if (mark <= limit) return mark
                // 超容量时在逗号处切；切完只剩标点就整句出
                val cut = foldCut(text, mark)
                if (cut == null || mark - cut <= MIN_TAIL_AFTER_FOLD) return mark
                return cut
            }
            // 段结束但还没等到句号：只在逗号处切，剩下的整句带到下一段，绝不按空格把末词切走
            if (flushTail) return foldCut(text, text.length, allowSpace = false)
            if (text.length <= limit) return null
            // 连读：草稿超一行就用最后一个逗号收口（一行最多让它占到两行）
            val clauseCut = foldCut(text, text.length, allowSpace = false)
            if (clauseCut != null && clauseCut <= limit * MAX_CLAUSE_ROWS) return clauseCut
            if (text.length.toFloat() <= limit * HARD_CAP_RATIO) return null
            return foldCut(text, limit)
        }

        /** 第一个句末标点之后的位置（跳过 3.14 / U.S. / Mr.）。 */
        private fun findSentenceEnd(text: String): Int? {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c in SENTENCE_END_MARKS) {
                    if (c == '.' || c == '!' || c == '?') {
                        val next = text.getOrNull(i + 1)
                        if (next != null && !next.isWhitespace()) {
                            i++
                            continue
                        }
                        if (c == '.' && isAbbreviationBefore(text, i)) {
                            i++
                            continue
                        }
                    }
                    var cut = i + 1
                    while (cut < text.length && text[cut].isWhitespace()) cut++
                    return cut
                }
                i++
            }
            return null
        }

        /**
         * 折行位置：优先逗号；[allowSpace] 为 true 时退到空格。没有合适断点返回 null。
         * 段结束时的收口不允许按空格断 —— 那样会把一句的最后一两个词切到下一条。
         */
        private fun foldCut(
            text: String,
            maxEnd: Int,
            allowSpace: Boolean = true,
        ): Int? {
            val end = minOf(maxEnd, text.length)
            if (end <= MIN_ROW_TAIL_CHARS) return null
            val window = text.substring(0, end)
            val comma = window.lastIndexOfAny(ROW_SOFT_BREAK_CHARS)
            if (comma in MIN_ROW_TAIL_CHARS until text.length - 1) return comma + 1
            if (!allowSpace) return null
            val space = window.lastIndexOf(' ')
            if (space in MIN_ROW_TAIL_CHARS until text.length - 1) return space + 1
            return null
        }

        /** [index] 处的 `.` 前面是不是 Mr / Dr 这类缩写（不能当作句子结尾）。 */
        private fun isAbbreviationBefore(
            text: String,
            index: Int,
        ): Boolean {
            // 识别结果常写成 `Mr .`，先跳过标点前的空白
            var end = index
            while (end > 0 && text[end - 1].isWhitespace()) end--
            var start = end
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            val token = text.substring(start, end)
            if (token.length == 1 && token[0].isUpperCase()) return true
            // U.S 这类全大写缩写
            if (token.length > 1 && token.all { it.isUpperCase() || it == '.' }) return true
            return token.lowercase() in ABBREVIATIONS
        }

        /** 收口一行：进历史 + 立即翻译。 */
        private fun emitRow(rowText: String) {
            if (!rowText.hasMeaningfulContent()) return
            // 去重：同一句可能从草稿收尾与引擎 final 两条路径到达
            val normalized = normalizeForCompare(rowText)
            val duplicated =
                recentRowTexts.contains(rowText) ||
                    (normalized.length >= MIN_DUP_CHECK_CHARS &&
                        recentRowTexts.any { normalizeForCompare(it).contains(normalized) })
            if (duplicated) {
                if (BuildConfig.DEBUG) Log.d(LOG_TAG, "emitRow: 跳过重复行 \"$rowText\"")
                return
            }
            // 引擎把半句补全成整句时，替换掉之前那条半句
            val extended = recentRowTexts.firstOrNull {
                val old = normalizeForCompare(it)
                old.length >= MIN_DUP_CHECK_CHARS && normalized.startsWith(old) && normalized.length > old.length
            }
            if (extended != null) {
                historyLines.removeAll { it.sourceText == extended }
                recentRowTexts.remove(extended)
                if (BuildConfig.DEBUG) Log.d(LOG_TAG, "emitRow: 用补全后的整句替换 \"$extended\"")
            }
            recentRowTexts.addLast(rowText)
            if (recentRowTexts.size > RECENT_ROWS_MEMORY) recentRowTexts.removeFirst()
            historyLines.add(
                SubtitleLine(
                    sourceText = rowText,
                    translatedText = "",
                    subtitleType = SubtitleType.FINAL,
                ),
            )
            // 历史保留到 10 条：收口瞬间屏幕行数不回跳
            if (historyLines.size > MAX_HISTORY_LINES) {
                historyLines.removeAt(0)
            }
            // 清空当前行与其在途翻译
            currentSourceBuffer.setLength(0)
            currentPartialText = ""
            currentTranslationBuffer.setLength(0)
            lastPartialTranslateLength = 0
            unconfirmedLineSnapshot = ""
            partialTranslationVersion.incrementAndGet()
            lastEmittedAnchor = rowText.split(" ").filter { it.isNotBlank() }.takeLast(4).joinToString(" ")
            if (BuildConfig.DEBUG) Log.d(LOG_TAG, "emitRow: \"$rowText\"，历史行数=${historyLines.size}")
            translate(rowText)
        }

        /** 段结束时收尾当前草稿（有句号整句成行，没有就在逗号处切）。 */
        private fun flushPendingDraft() {
            val pending = currentLineText().trim()
            // 引擎即将 reset：账目归零后按「段已结束」处理
            currentSourceBuffer.setLength(0)
            currentPartialText = ""
            currentTranslationBuffer.setLength(0)
            unconfirmedLineSnapshot = ""
            segmentConsumedChars = 0
            lastSegmentText = ""
            if (pending.isEmpty()) {
                updateSourceText("", isFinal = false)
                return
            }
            if (BuildConfig.DEBUG) Log.d(LOG_TAG, "flushPendingDraft: 草稿收尾 \"$pending\"")
            consumeSegmentText(pending, flushTail = true)
            resetSegmentState()
        }

        /** 段结束（引擎 final / resetStream）：段内计数归零，下一段从 0 开始。 */
        private fun resetSegmentState() {
            segmentConsumedChars = 0
            lastSegmentText = ""
        }

        /**
         * 引擎改写文本后重新定位「已收口到哪」：用上一行末尾 4/3/2/1 个词依次当锚点，
         * 取最后一次出现的位置。都对不上就从头再来（重复内容由去重兜底吃掉）。
         */
        private fun resyncConsumed(text: String): Int {
            val words = lastEmittedAnchor.split(" ").filter { it.isNotBlank() }
            for (n in intArrayOf(4, 3, 2, 1)) {
                if (words.size < n) continue
                val anchor = words.takeLast(n).joinToString(" ")
                val idx = text.lastIndexOf(anchor)
                if (idx >= 0) return idx + anchor.length
            }
            if (BuildConfig.DEBUG) Log.d(LOG_TAG, "锚点对不上，按新文本从头收口")
            return 0
        }

        /** 去重比较用：只保留字母与数字，忽略空格和标点差异。 */
        private fun normalizeForCompare(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

        /** 一行最多放多少字符：英文按字母宽度算，中文按字算。 */
        private fun currentRowLimitChars(): Int =
            if (_subtitleState.value.sourceLanguage.startsWith("en", ignoreCase = true)) {
                MAX_ROW_CHARS_EN
            } else {
                MAX_ROW_CHARS_ZH
            }

        /** 当前语种的一行字符容量（浮窗渲染判断「草稿是否已过半行」时用）。 */
        fun rowCapacityChars(): Int = currentRowLimitChars()

        /**
         * 源语言切换时重载 ASR 模型并重启音频采集。
         *
         * 严格唯一引擎：语言 → 引擎 → prepare，失败直接报错，不跨引擎 fallback。
         * 整个流程（释放旧 AudioRecord → 加载新模型 → 重启采集）在新协程内串行执行，
         * 句柄登记到 [audioProcessingJob]，保证停止流程能取消到真正在跑的那个循环。
         */
        private fun reloadAsrModel(languageCode: String) {
            // 先取旧句柄存局部变量，避免新协程读到自己刚被赋的值而自杀
            val previousJob = audioProcessingJob
            audioProcessingJob =
                scope.launch(Dispatchers.IO) {
                    // 同 restartAudioProcessing：必须在加锁【之前】置 false，否则与持锁的录音协程互相等待 → 死锁
                    isRecording = false
                    audioMutex.withLock {
                        Log.i(LOG_TAG, "reloadAsrModel: 切换语种到 $languageCode")
                        previousJob?.cancel()
                        val oldRecord = audioRecord
                        audioRecord = null
                        runCatching { oldRecord?.stop() }
                        runCatching { oldRecord?.release() }
                        delay(100)

                        val engine = currentAsrEngineType
                        val loadResult =
                            when (engine) {
                                AsrEngineType.VOSK -> modelPreparer.prepareAsr(languageCode)
                                AsrEngineType.SHERPA_ONNX,
                                AsrEngineType.SHERPA_ONNX_BN,
                                AsrEngineType.SHERPA_ONNX_NEMOTRON,
                                -> {
                                    val modelId = resolveSherpaModelId(languageCode)
                                    if (modelId == null) {
                                        Result.failure(
                                            IllegalStateException(
                                                "源语言 $languageCode 无对应 Sherpa-ONNX 模型，请切换语言",
                                            ),
                                        )
                                    } else {
                                        if (engine == AsrEngineType.SHERPA_ONNX_NEMOTRON) {
                                            sherpaOnnxAsrEngine.setLanguage(resolveNemotronLanguage(languageCode))
                                        }
                                        modelPreparer.prepareSherpaOnnxAsr(modelId)
                                    }
                                }
                            }
                        loadResult
                            .onSuccess {
                                Log.i(LOG_TAG, "reloadAsrModel: 模型加载成功，引擎=$engine, 重启音频采集")
                                startAudioProcessingLocked()
                            }.onFailure { e ->
                                Log.e(LOG_TAG, "reloadAsrModel: 模型加载失败, engine=$engine, lang=$languageCode", e)
                                _runtimeError.value =
                                    buildString {
                                        append("语音识别模型加载失败，请前往模型下载界面下载对应模型")
                                        append("（错误：")
                                        append(e.message ?: "未知")
                                        append("）")
                                    }
                            }
                    }
                }
        }

        /**
         * 当前句子累积的 final 原文缓冲。
         * 一个句子内多个 final 累积，遇到句末标点或 endpoint 切句时完成一句。
         */
        private val currentSourceBuffer = StringBuilder()

        /**
         * 当前句最近一次 partial 的文本（引擎 resetStream 后即为新句内容）。
         *
         * 与 [currentSourceBuffer] 分开存放的原因：partial 是「尚未确认的预览」，
         * 不能混进已确认缓冲。旧实现让 partial 直接参与 `buffer + text` 的拼接与写回，
         * 导致两个问题：① 新 partial 前面挂着上一句 final；② final 后缓冲未清空，
         * Sherpa 路径下上一句永远进不了历史（被新 partial 整行覆盖）。
         *
         * 线程约束：字幕缓冲只在主线程（ASR Flow 收集器）读写。
         */
        private var currentPartialText: String = ""

        /** 当前引擎段内已收口成行的字符数，只增不减。 */
        private var segmentConsumedChars = 0

        /** 上一帧的段文本，用于判断引擎是否改写了已收口区域。 */
        private var lastSegmentText: String = ""

        /** 上一段没等到句号就结束的半句，带到下一段一起成句。 */
        private var carryOverText: String = ""

        /** 上一条已上屏字幕的最后两个词，用于引擎改写文本时重新对齐。 */
        private var lastEmittedAnchor: String = ""

        /** 最近上屏过的原文，用于去重。 */
        private val recentRowTexts = ArrayDeque<String>()

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
        fun updateSourceText(
            text: String,
            isFinal: Boolean,
        ) {
            val current = _subtitleState.value

            if (isFinal) {
                // final：本句原文已确定，作为当前行内容；未确认的 partial 预览作废
                currentSourceBuffer.setLength(0)
                currentSourceBuffer.append(text)
                currentPartialText = ""
                // 重置 partial 翻译基准
                lastPartialTranslateLength = 0
            } else {
                // partial：只更新预览文本，不写入已确认缓冲
                currentPartialText = text
                val fullText = joinText(currentSourceBuffer.toString(), text)
                val triggerLen =
                    if (current.sourceLanguage.startsWith("en", ignoreCase = true)) {
                        PARTIAL_TRANSLATE_STEP_EN
                    } else {
                        PARTIAL_TRANSLATE_STEP_ZH
                    }
                if (fullText.length - lastPartialTranslateLength >= triggerLen && fullText.length >= MIN_PARTIAL_TRANSLATE_LENGTH) {
                    lastPartialTranslateLength = fullText.length
                    translatePartial(fullText)
                }
            }

            // 组装当前行：已确认 final + 未确认 partial
            val currentLine =
                SubtitleLine(
                    sourceText = joinText(currentSourceBuffer.toString(), currentPartialText),
                    translatedText = currentTranslationBuffer.toString(),
                    subtitleType = if (isFinal) SubtitleType.FINAL else SubtitleType.PARTIAL,
                )

            _subtitleState.value = current.copy(lines = buildDisplayLines(currentLine))
            unconfirmedLineSnapshot = currentPartialText
        }

        /**
         * 组装要渲染的行列表：历史行 + 当前行，最多 [MAX_HISTORY_LINES] 行。
         *
         * 性能：这是 partial 热路径（Vosk 下每帧一次）。旧实现每次都
         * `historyLines.toMutableList()` + `add` + `takeLast`，产生 3 次列表分配；
         * 这里合并为一次 [buildList]，并按上限截断。
         */
        private fun buildDisplayLines(currentLine: SubtitleLine): List<SubtitleLine> {
            // 当前行可能为空（整段都已收口成行），此时只渲染历史行
            val hasCurrent = currentLine.sourceText.isNotBlank()
            val total = historyLines.size + if (hasCurrent) 1 else 0
            val overflow = total - MAX_HISTORY_LINES
            val fromIndex = if (overflow > 0) overflow else 0
            return buildList(total - fromIndex) {
                for (i in fromIndex until historyLines.size) add(historyLines[i])
                if (hasCurrent) add(currentLine)
            }
        }

        /**
         * 拼接「已确认 final」与「未确认 partial」。
         *
         * 两侧都是可见字符时补一个空格：ASR 输出的句子不带行尾空格，
         * 直接相加会出现 `conclusionsIn a moment`、`yet .Everyone` 这类粘连（issue #7）。
         */
        private fun joinText(
            prefix: String,
            suffix: String,
        ): String {
            if (prefix.isEmpty()) return suffix
            if (suffix.isEmpty()) return prefix
            val last = prefix.last()
            val first = suffix.first()
            val boundaryNeedsSpace =
                !last.isWhitespace() &&
                    !first.isWhitespace() &&
                    (last.isLetterOrDigit() || last in PUNCT_TAIL) &&
                    (first.isLetterOrDigit() || first in PUNCT_TAIL)
            return if (boundaryNeedsSpace) "$prefix $suffix" else prefix + suffix
        }

        /** 当前显示行的完整文本（已确认 final + 未确认 partial）。仅可在主线程访问。 */
        private fun currentLineText(): String = joinText(currentSourceBuffer.toString(), currentPartialText)

        /**
         * 「已上屏、但还没等到引擎 final」的未确认文本快照。
         *
         * 由主线程在 [updateSourceText] / 归档时维护，供录音线程在 VAD 暂停恢复点判断
         * 「这次暂停是不是落在句中」——若是，reset 之前必须把这半句补成 final，
         * 否则用户已经看到的那一行会被 resetStream 静默丢掉（表现为字幕回滚、且没有译文）。
         */
        @Volatile
        private var unconfirmedLineSnapshot: String = ""

        /** 清空当前句缓冲与历史行。仅可在主线程访问。 */
        private fun clearSubtitleBuffers() {
            currentSourceBuffer.setLength(0)
            currentPartialText = ""
            currentTranslationBuffer.setLength(0)
            historyLines.clear()
            lastPartialTranslateLength = 0
            unconfirmedLineSnapshot = ""
            // 段内账目与跨段残留一起清掉，避免上一次会话的半句串到下一次
            carryOverText = ""
            segmentConsumedChars = 0
            lastSegmentText = ""
        }

        /**
         * 完成当前句并归档到历史。
         *
         * 行边界现在统一由 [consumeSegmentText] 决定（按句末标点收口），
         * 因此这里不再使用：保留说明是为了避免后续把「一个引擎 final = 一行」的旧模型搬回来。
         */

        /** 滑动窗口 partial 翻译：轻量预览，只更新 UI 不保存历史。
         * 使用版本号管理，避免旧结果覆盖新结果。
         */
        private fun translatePartial(text: String) {
            val version = partialTranslationVersion.incrementAndGet()
            scope.launch {
                val state = _subtitleState.value
                try {
                    // 只做直接翻译，不润色，速度优先
                    translateUseCase
                        .execute(
                            text,
                            state.sourceLanguage,
                            state.targetLanguage,
                            state.engine,
                        ).onSuccess { result ->
                            // 仅当版本匹配、且末行仍是 partial（尚未被 final 取代）时更新预览译文。
                            // 否则 partial 的预览译文会覆盖 final 已经翻好的译文。
                            val current = _subtitleState.value
                            val lines = current.lines
                            if (partialTranslationVersion.get() == version &&
                                currentLineText().isNotEmpty() &&
                                lines.isNotEmpty() &&
                                lines.last().subtitleType == SubtitleType.PARTIAL
                            ) {
                                currentTranslationBuffer.setLength(0)
                                currentTranslationBuffer.append(result.translatedText)
                                // 更新 UI
                                val updated = lines.toMutableList()
                                updated[updated.size - 1] =
                                    updated[updated.size - 1].copy(translatedText = result.translatedText)
                                _subtitleState.value = current.copy(lines = updated)
                            }
                        }
                } catch (_: Exception) {
                    // partial 翻译失败静默处理
                }
            }
        }

        /**
         * 上一句的「原文 → 已上屏译文」，仅作为术语译法一致的参考注入 prompt。
         * 设计取舍：不引入用户维护的术语表，术语识别/自更正交给模型自身；但只保留最近 1 句，
         * 且 prompt 中明确标注「仅供参考、不要翻译/照抄」，以降低错误译法被固化并传染后续句子的风险。
         */
        private var lastLinePair: Pair<String, String>? = null

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
                    translateUseCase
                        .execute(
                            text,
                            state.sourceLanguage,
                            state.targetLanguage,
                            state.engine,
                        ).onSuccess { result ->
                            // 不做版本丢弃：连读时一次会有多行几乎同时收口，版本门会把除最后一行
                            // 之外的译文全部丢掉（表现为「只有最后一句有译文」）。
                            // 写回时按原文定位目标行（见 appendTranslationResult），不会串行。
                            appendTranslationResult(text, result.translatedText, state)
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
         *
         * 超时策略（2026-09-13 修正）：
         * 旧实现用 `withTimeout(POLISH + TRANSLATE)`(7s) 包住整条流 —— 那是**总时长**上限，
         * 慢模型（首 token 3s + 逐字 8s）会在第 7s 被中途掐断，而 catch 分支又把已流出的半截
         * 译文当成功结果写入历史与 lastLinePair，导致「半截译文被当正确结果」并被后续句子学走。
         *
         * 现改为「首 token 超时（TTFT）+ 收尾容错」两段语义，不再对**总时长**设上限：
         *  - [STREAM_FIRST_TOKEN_TIMEOUT_MS]：从发起到第一个 token 的最长等待（15s）。
         *    它只约束「开始」，不约束「长度」—— 一旦开始吐字，长句慢模型不会被误杀。
         *  - 首个 token 到达后若流被外部中断（网络抖动/代理掐断），已有内容才可被采信，
         *    且同时写入"可能不完整"的运行时提示，不再冒充完整译文。
         *  - 首 token 都没来就失败 → 保留原文、不写历史、给出可行动的失败原因。
         * 真实 TCP/TLS 读超时仍由 LLMClient 的 OkHttp 30s 兜底，避免连接卡死时无限等待。
         */
        private suspend fun translateWithPolishAndContext(
            originalText: String,
            state: SubtitleState,
            version: Long,
        ) {
            val streamed = StringBuilder()
            var lastUiUpdateMs = 0L
            // 半截译文是否可被采信：只有「模型正常收尾」或「首 token 已到达后流被外部中断」
            // 才采信；首 token 都没来就失败时坚决不用空串/残片污染历史。
            var firstTokenArrived = false
            try {
                // 提示词唯一来源（engine 层）：带上一句原文+译文，仅供术语译法一致参考
                val systemPrompt =
                    io.github.ztfang.eye.engine.translation.llm.LLMTranslationEngine
                        .buildSystemPrompt(state.sourceLanguage, state.targetLanguage, lastLinePair)

                // 两段式超时：外层等首 token，内层对每次 token 到达重新计时（空闲超时）
                withTimeout(STREAM_FIRST_TOKEN_TIMEOUT_MS) {
                    llmClient
                        .chatStream(
                            listOf(
                                "system" to systemPrompt,
                                "user" to originalText,
                            ),
                        )
                        .collect { token ->
                            // 旧版本（已被新句取代）的结果直接丢弃，不污染当前行
                            if (translationVersion.get() != version) return@collect
                            firstTokenArrived = true
                            streamed.append(token)
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastUiUpdateMs >= STREAM_UI_MIN_INTERVAL_MS) {
                                lastUiUpdateMs = now
                                showStreamingTranslation(originalText, streamed.toString())
                            }
                        }
                }
                val text = streamed.toString().trim()
                if (text.isNotBlank() && translationVersion.get() == version) {
                    appendTranslationResult(originalText, text, state)
                } else {
                    // 空结果或旧版本被丢弃：收尾，避免"实时翻译中"指示器卡住
                    _isTranslating.value = false
                }
            } catch (e: Exception) {
                // 失败处理分两类，不再无脑采用半截文本：
                val partialText = streamed.toString().trim()
                val stillCurrent = translationVersion.get() == version
                when {
                    // ① 首 token 已到、且流被外部中断（网络抖动/代理掐断）→ 已有内容可用，采用之。
                    //    但仍记录"经流式中断产出"，便于排查（不再声称是完整结果）。
                    firstTokenArrived && partialText.isNotBlank() && stillCurrent -> {
                        Log.w(LOG_TAG, "AI 流式中断，采用已输出部分(${partialText.length}字): ${e.message}")
                        _runtimeError.value = "译文可能不完整（流式连接中断）"
                        appendTranslationResult(originalText, partialText, state)
                    }
                    // ② 一个字都没吐出来 → 保留原文，给出可见的失败提示，绝不写历史。
                    else -> {
                        Log.w(LOG_TAG, "AI 流式无输出，保留原文: ${e.javaClass.simpleName}: ${e.message}")
                        _runtimeError.value = buildAiErrorMessage(e)
                        _isTranslating.value = false
                    }
                }
            }
        }

        /**
         * 把流式链路的异常翻译成对用户有信息量的文案。
         *
         * 历史问题：无论什么错都只说「翻译失败，请检查网络或配置」——用户和开发者都无从下手。
         * 这里按异常类型给最小但可行动的区分：超时 / 鉴权 / 端点 / 其他。
         */
        private fun buildAiErrorMessage(e: Throwable): String {
            val raw = (e.message ?: "").trim()
            return when {
                e is kotlinx.coroutines.TimeoutCancellationException ->
                    "AI 响应超时（${STREAM_FIRST_TOKEN_TIMEOUT_MS / 1000}s 内无输出），请检查代理节点或换模型"
                raw.contains("401") || raw.contains("403") ->
                    "API 鉴权失败（$raw），请检查 Key 与服务商是否匹配"
                raw.contains("404") ->
                    "API 地址或模型名不存在（$raw），请检查 Base URL 与模型名称"
                raw.contains("429") ->
                    "API 触发限流（$raw），请稍后重试或更换模型"
                raw.isNotBlank() -> "翻译失败：$raw"
                else -> "翻译失败，请检查网络或配置"
            }
        }

        /**
         * 流式过程中就地更新当前行译文（不归档、不写历史）。
         * 仅当最后一行仍是本次请求对应的句子时才更新，避免覆盖新句。
         */
        private fun showStreamingTranslation(
            originalText: String,
            translatedText: String,
        ) {
            val current = _subtitleState.value
            val lines = current.lines
            if (lines.isEmpty()) return
            val last = lines.last()
            if (last.sourceText != originalText) return
            val updated = lines.toMutableList()
            updated[updated.size - 1] = last.copy(translatedText = translatedText)
            _subtitleState.value = current.copy(lines = updated)
        }

        /**
         * 追加翻译结果并保存历史记录。
         *
         * 目标行按「原文」定位，而不是固定写最后一行：译文回来时，本句可能
         *   · 仍占据当前行（常见），
         *   · 已经因为下一句 partial 到达被归档进历史（[historyLines]），
         * 旧实现无条件写 lines.last()，会把上一句的译文盖到下一句那一行上（译文错行），
         * 而只改显示副本、不改 [historyLines] 时，下一次重排又会换回旧译文（表现为译文回滚）。
         */
        private fun appendTranslationResult(
            sourceText: String,
            translatedText: String,
            state: SubtitleState,
        ) {
            // 当前行才更新当前行译文缓冲（archiveCurrentLine 与 updateSourceText 都读它）
            val isCurrentLine =
                currentSourceBuffer.toString() == sourceText || currentLineText() == sourceText
            if (isCurrentLine) {
                currentTranslationBuffer.setLength(0)
                currentTranslationBuffer.append(translatedText)
            }

            // 已归档进历史的那一行：连历史缓冲一起改，避免下一次重排把译文换回旧值
            val historyIdx = historyLines.indexOfLast { it.sourceText == sourceText }
            if (historyIdx >= 0) {
                historyLines[historyIdx] = historyLines[historyIdx].copy(translatedText = translatedText)
            }

            // 更新显示副本：所有原文匹配的行（正常情况下只有一行）
            val current = _subtitleState.value
            val lines = current.lines.toMutableList()
            var patched = false
            for (i in lines.indices) {
                if (lines[i].sourceText == sourceText) {
                    lines[i] = lines[i].copy(translatedText = translatedText)
                    patched = true
                }
            }
            if (patched) {
                _subtitleState.value = current.copy(lines = lines)
            }

            _isTranslating.value = false

            // 记录本句「原文 → 译文」，供下一句做术语译法一致性参考（仅最近 1 句）
            lastLinePair = sourceText to translatedText

            // 保存到历史记录
            scope.launch(Dispatchers.IO) {
                historyRepository.insertRecord(
                    io.github.ztfang.eye.domain.model.HistoryRecord(
                        sourceText = sourceText,
                        translatedText = translatedText,
                        sourceLanguage = state.sourceLanguage,
                        targetLanguage = state.targetLanguage,
                        timestamp = System.currentTimeMillis(),
                    ),
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

        fun setOverlayX(x: Int) {
            scope.launch { settingsRepository.setOverlayX(x) }
        }

        fun setOverlayY(y: Int) {
            scope.launch { settingsRepository.setOverlayY(y) }
        }

        fun setOverlayWidth(width: Int) {
            scope.launch { settingsRepository.setOverlayWidth(width) }
        }

        fun setOverlayHeight(height: Int) {
            scope.launch { settingsRepository.setOverlayHeight(height) }
        }

        fun setOverlayActive(active: Boolean) {
            _isOverlayActive.value = active
            if (active) {
                _runtimeError.value = null
            } else {
                // 关浮窗时立刻清空字幕：否则下次打开会先看到上一次会话残留的行与译文
                scope.launch {
                    clearSubtitleBuffers()
                    _subtitleState.value = _subtitleState.value.copy(lines = emptyList())
                    _isTranslating.value = false
                    Log.i(LOG_TAG, "字幕已清空：关闭浮窗")
                }
            }
        }

        fun clearRuntimeError() {
            _runtimeError.value = null
        }

        /**
         * 重试翻译模型准备（失败弹窗的"重试"按钮）。
         * 复用当前语言与引擎，重新走一遍 检查 → 下载 → 轮询 流程。
         */
        fun retryTranslationPrep() {
            val s = _subtitleState.value
            Log.i(LOG_TAG, "[PREP] 用户手动重试, engine=${s.engine}, ${s.sourceLanguage}→${s.targetLanguage}")
            scope.launch {
                runTranslationPrep(
                    engine = s.engine,
                    sourceLanguage = s.sourceLanguage,
                    targetLanguage = s.targetLanguage,
                    tag = "manual-retry",
                )
            }
        }

        /**
         * 用户关闭离线翻译模型失败弹窗（不重试，仅收起）。
         * 必须连 [TranslationModelState.failureKind] 一起清掉：只把 status 置回 IDLE 会留下
         * 上一次的分类，任何"只看 failureKind 不看 status"的读取方都会拿到过期值。
         */
        fun dismissTranslationModelError() {
            if (_translationModelState.value.status == TranslationModelStatus.FAILED) {
                _translationModelState.value =
                    _translationModelState.value.copy(
                        status = TranslationModelStatus.IDLE,
                        failureKind = null,
                        message = "",
                    )
            }
        }

        /**
         * 检查 ASR 模型是否已下载，未下载则弹窗提醒。
         * 源语言切换时调用。根据传入的源语言（非 currentAsrEngineType）解析引擎，
         * 避免因 updateSourceLanguage 异步流未传播导致引擎类型用旧语言。
         */
        fun checkAsrModel(
            sourceLanguage: String,
            displayName: String,
        ) {
            scope.launch(Dispatchers.IO) {
                val engine = resolveAsrEngine(sourceLanguage)
                Log.i(LOG_TAG, "checkAsrModel: engine=$engine, lang=$sourceLanguage")
                val ready =
                    when (engine) {
                        AsrEngineType.VOSK -> {
                            val modelDir = modelPreparer.asrModelDir(sourceLanguage)
                            val amDir = java.io.File(modelDir, "am")
                            val confDir = java.io.File(modelDir, "conf")
                            val graphDir = java.io.File(modelDir, "graph")
                            amDir.exists() && confDir.exists() && graphDir.exists()
                        }
                        AsrEngineType.SHERPA_ONNX,
                        AsrEngineType.SHERPA_ONNX_BN,
                        AsrEngineType.SHERPA_ONNX_NEMOTRON,
                        -> {
                            val modelId =
                                resolveSherpaModelId(sourceLanguage)
                                    ?: return@launch // 无对应 Sherpa 模型，跳过检查
                            modelPreparer.isSherpaOnnxAsrReady(modelId)
                        }
                    }
                if (!ready) {
                    val (modelName, modelSize) = requiredModelInfo(sourceLanguage)
                    Log.w(LOG_TAG, "checkAsrModel: 模型未就绪, engine=$engine, lang=$sourceLanguage, 需下载=$modelName")
                    _asrDownloadRequest.value =
                        AsrDownloadRequest(
                            languageCode = sourceLanguage,
                            languageDisplayName = displayName,
                            modelDisplayName = modelName,
                            modelSizeBytes = modelSize,
                        )
                }
            }
        }

        /** ASR 模型下载请求：携带所需模型的展示名与体积，供弹窗精准提示 */
        data class AsrDownloadRequest(
            val languageCode: String,
            val languageDisplayName: String,
            val modelDisplayName: String,
            val modelSizeBytes: Long,
            val requestId: Long = System.currentTimeMillis(),
        )

        private val _asrDownloadRequest = MutableStateFlow<AsrDownloadRequest?>(null)
        val asrDownloadRequest: StateFlow<AsrDownloadRequest?> = _asrDownloadRequest.asStateFlow()

        fun dismissAsrDownload() {
            _asrDownloadRequest.value = null
        }

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
            Log.i(
                LOG_TAG,
                "[ENSURE] ensureModelsLoaded: asrEngine=$asrEngine, transEngine=$engine, source=$lang, target=${s.targetLanguage}",
            )
            scope.launch {
                Log.w(LOG_TAG, "[ENSURE] ===== ASR 模型准备诊断开始, lang=$lang, pickedEngine=$asrEngine =====")
                Log.w(LOG_TAG, "[ENSURE] 候选引擎逐个自检：")
                val route = AsrRoutingTable.forLanguage(lang)
                val candidates =
                    if (route.engine == AsrEngineType.VOSK) {
                        listOf(AsrEngineType.VOSK)
                    } else {
                        listOf(route.engine, AsrEngineType.VOSK)
                    }
                for (c in candidates) {
                    checkEngineReadyAndLog(c, lang)
                }
                Log.w(LOG_TAG, "[ENSURE] ===== ASR 模型准备诊断结束, pickedEngine=$asrEngine =====")

                // 1. 翻译模型准备（失败仅告警，不阻断 ASR）
                runTranslationPrep(
                    engine = engine,
                    sourceLanguage = lang,
                    targetLanguage = s.targetLanguage,
                    tag = "ensureModelsLoaded",
                )
                // 2. ASR 模型准备 —— 严格按映射引擎，无 fallback；但 prepare 前后打印关键信息
                val prepare: Result<Unit> =
                    when (asrEngine) {
                        AsrEngineType.VOSK -> {
                            val dir = modelPreparer.asrModelDir(lang)
                            val listStr =
                                runCatching {
                                    dir.listFiles()?.joinToString(limit = 10) { f -> f.name }
                                }.getOrDefault("<list_dir_err>")
                            Log.i(LOG_TAG, "[ENSURE] 准备 Vosk 模型, lang=$lang, dir=$dir, exists=${dir.exists()}, files(top10)=$listStr")
                            modelPreparer.prepareAsr(lang)
                        }
                        AsrEngineType.SHERPA_ONNX,
                        AsrEngineType.SHERPA_ONNX_BN,
                        AsrEngineType.SHERPA_ONNX_NEMOTRON,
                        -> {
                            val modelId = resolveSherpaModelId(lang)
                            if (modelId == null) {
                                Result.failure(
                                    IllegalStateException(
                                        "源语言 $lang 无对应 Sherpa-ONNX 模型，请切换语言或下载对应模型",
                                    ),
                                )
                            } else {
                                val dir = modelPreparer.sherpaOnnxModelDir(modelId)
                                val listStr =
                                    runCatching {
                                        dir.listFiles()?.joinToString(limit = 10) { f -> f.name }
                                    }.getOrDefault("<list_dir_err>")
                                Log.i(
                                    LOG_TAG,
                                    "[ENSURE] 准备 Sherpa-ONNX 模型, modelId=$modelId, lang=$lang, dir=$dir, exists=${dir.exists()}, files(top10)=$listStr",
                                )
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
                    val dirDetail =
                        runCatching {
                            val resolvedModelId = if (asrEngine == AsrEngineType.VOSK) null else resolveSherpaModelId(lang)
                            val dir =
                                if (resolvedModelId !=
                                    null
                                ) {
                                    modelPreparer.sherpaOnnxModelDir(resolvedModelId)
                                } else {
                                    modelPreparer.asrModelDir(lang)
                                }
                            "dir=${dir.absolutePath}, exists=${dir.exists()}, files=${dir.listFiles()?.map {
                                "${it.name}(${it.length()})"
                            }?.take(
                                10,
                            )}"
                        }.getOrDefault("dirResolveErr")
                    Log.e(
                        LOG_TAG,
                        "[MODEL_NOT_DOWNLOADED] lang=$lang, pickedEngine=$asrEngine, resolvedModelId=${resolveSherpaModelId(
                            lang,
                        )}, $dirDetail",
                    )
                    Log.e(LOG_TAG, "[MODEL_NOT_DOWNLOADED] 真实异常: ${err.javaClass.simpleName}: ${err.message}")
                    _runtimeError.value = modelMissingMessage(lang)
                }
            }
        }

        /** 音频采集实例。录音 IO 协程与主线程停止流程都会读写，故 volatile。 */
        @Volatile
        private var audioRecord: AudioRecord? = null

        /**
         * 录音主循环运行标志。
         *
         * 跨线程读写：录音循环在 Dispatchers.IO 上读，启动/停止/重启流程在其它线程写。
         * 未加 @Volatile 时 JIT 可能把 `while (isRecording)` 提升为寄存器读，导致停止信号
         * 延迟生效甚至不生效 —— 而录音循环整体持有 [audioMutex]，一旦不退出，
         * [restartAudioProcessing] / [reloadAsrModel] 的 withLock 将永久阻塞。
         */
        @Volatile
        private var isRecording = false

        /**
         * 当前录音主循环的协程句柄。
         *
         * 三个入口（首次启动 / 音频源切换重启 / 语种切换重载）都会在这里登记句柄，
         * 保证停止流程能拿到正在运行的那个协程。
         * 注意：录音循环持有 [audioMutex]，因此 [isRecording] = false 才是主要退出信号，
         * 取消句柄只是双保险（用于卡在 [delay] 等挂起点时立即退出）。
         */
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
        fun saveMediaProjectionToken(
            resultCode: Int,
            data: android.content.Intent?,
        ) {
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
                val mpm =
                    context.getSystemService(
                        android.content.Context.MEDIA_PROJECTION_SERVICE,
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
            // 先取旧句柄存局部变量：新协程被调度后就会读 audioProcessingJob，
            // 若直接读字段可能读到自己（刚赋的新值）从而自杀。
            val previousJob = audioProcessingJob
            audioProcessingJob =
                scope.launch(Dispatchers.IO) {
                    // 必须在加锁【之前】置 false：录音协程正持有 audioMutex 在 while(isRecording) 中循环，
                    // 若把 isRecording=false 放进 withLock 内部，本协程永远拿不到锁，而录音协程永远等不到
                    // 退出信号 → 死锁。与 stopAudioProcessing() 的 tryLock 分支同一思路（先置标志再取锁）。
                    isRecording = false
                    audioMutex.withLock {
                        Log.i(LOG_TAG, "restartAudioProcessing: 开始重启音频采集")
                        previousJob?.cancel()
                        // 快照后立即置空：避免与其它线程的释放流程重复 stop/release 同一实例，
                        // 也避免 `audioRecord?.stop()` 的双重读取在字段被置空后抛 NPE。
                        val oldRecord = audioRecord
                        audioRecord = null
                        // stop/release 在未 start 状态下会抛 IllegalStateException，必须容错，
                        // 否则整个重启协程中断，音频再也起不来（与 reloadAsrModel 保持一致）。
                        runCatching { oldRecord?.stop() }
                        runCatching { oldRecord?.release() }
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

            // 每次启动都从空字幕开始：开关关掉再打开时，上一条会话的行/译文/历史不能残留。
            withContext(Dispatchers.Main) {
                clearSubtitleBuffers()
                _subtitleState.value = _subtitleState.value.copy(lines = emptyList())
                _isTranslating.value = false
                Log.i(LOG_TAG, "字幕已清空：启动音频采集")
            }

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
                AsrEngineType.SHERPA_ONNX_NEMOTRON,
                -> {
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
            val needReload =
                when (engine) {
                    AsrEngineType.VOSK ->
                        !voskAsrEngine.isReady() || voskAsrEngine.loadedModelPath != expectedModelPath
                    AsrEngineType.SHERPA_ONNX,
                    AsrEngineType.SHERPA_ONNX_BN,
                    AsrEngineType.SHERPA_ONNX_NEMOTRON,
                    ->
                        !sherpaOnnxAsrEngine.isReady() || sherpaOnnxAsrEngine.loadedModelId != expectedModelId
                }

            if (needReload) {
                Log.d(
                    LOG_TAG,
                    "startAudioProcessingLocked: 加载/切换模型, engine=$engine, lang=$sourceLanguage" +
                        ", expectedModelId=$expectedModelId, currentSherpaId=${sherpaOnnxAsrEngine.loadedModelId}" +
                        ", voskLoaded=${voskAsrEngine.loadedModelPath}",
                )
                // Nemotron 需要在 init 前设置 per-stream language，setOption 生效
                if (engine == AsrEngineType.SHERPA_ONNX_NEMOTRON) {
                    sherpaOnnxAsrEngine.setLanguage(resolveNemotronLanguage(sourceLanguage))
                }
                // prepare：校验文件完整性 + 引擎 init（SubtitleManager 的引擎实例）
                val asrResult =
                    when (engine) {
                        AsrEngineType.VOSK -> modelPreparer.prepareAsr(sourceLanguage)
                        AsrEngineType.SHERPA_ONNX,
                        AsrEngineType.SHERPA_ONNX_BN,
                        AsrEngineType.SHERPA_ONNX_NEMOTRON,
                        -> modelPreparer.prepareSherpaOnnxAsr(expectedModelId!!)
                    }
                if (asrResult.isFailure) {
                    Log.e(LOG_TAG, "startAudioProcessingLocked: ASR prepare 失败, engine=$engine: ${asrResult.exceptionOrNull()?.message}")
                    _runtimeError.value = modelMissingMessage(sourceLanguage)
                    _isOverlayActive.value = false
                    return
                }
                // ModelPreparer 用的是另一套引擎实例，这里再 init SubtitleManager 自己的实例
                val initResult =
                    when (engine) {
                        AsrEngineType.VOSK -> voskAsrEngine.init(expectedModelPath)
                        AsrEngineType.SHERPA_ONNX,
                        AsrEngineType.SHERPA_ONNX_BN,
                        AsrEngineType.SHERPA_ONNX_NEMOTRON,
                        -> sherpaOnnxAsrEngine.init(expectedModelPath)
                    }
                if (initResult.isFailure) {
                    Log.e(
                        LOG_TAG,
                        "startAudioProcessingLocked: ASR engine init 失败, engine=$engine: ${initResult.exceptionOrNull()?.message}",
                    )
                    _runtimeError.value =
                        buildString {
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

            // ASR 加载耗时可达数秒（真机实测约 2.1s）。期间用户若已关闭浮窗，
            // Service.onDestroy 会调用 releaseMediaProjectionInstance() 把实例置 null，
            // 而本协程不会被取消，恢复执行后会误判「无 MediaProjection 授权」并弹出
            // 「请先授权屏幕录制权限」。这里先确认本次启动仍然需要，否则静默退出。
            // 注意：stopAudioProcessing 里的 isRecording=false 信号在启动阶段无效——
            // isRecording 初始为 false，直到 AudioRecord 创建成功才置 true。
            // 必须同时判断 isInputMode：本方法也服务聊天输入栏的语音输入
            //（startVoiceInput() → startAudioProcessing()），那条路径与浮窗无关，
            // 浮窗关闭时本来就应该能正常录音。只加 _isOverlayActive 判断会让语音输入
            // 静默 return，表现为点麦克风无任何反应。
            if (!_isOverlayActive.value && !isInputMode) {
                Log.i(LOG_TAG, "startAudioProcessingLocked: 启动期间浮窗已关闭，放弃本次启动")
                return
            }

            // === 3. 音频采集（麦克风 / 应用内声音） ===
            val currentSource = audioSource.value
            Log.d(LOG_TAG, "startAudioProcessingLocked: 音频源=$currentSource (0=麦克风, 1=应用内声音)")

            val record: AudioRecord =
                if (currentSource == 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

            // 预卷环形缓冲：asrPaused 期间被丢弃的帧按时间顺序留存，VAD 确认语音后整段回填给 ASR。
            // 没有它，句首要等「Silero 窗口判定 + 5 帧防抖」走过去才开始送音，
            // 实测句首稳定丢 0.45~0.6s（"Could we wait a moment" → "In a moment"，issue #7）。
            val preRollFrames = ArrayDeque<ShortArray>(VAD_PREROLL_FRAMES)

            // 热路径缓存：引擎解析含 map 查找（AsrRoutingTable.forLanguage），不能每帧做
            // （30ms/帧 ≈ 33Hz，旧实现每帧要算 2 次 isVoskMode + 取 2 次 currentAsrEngine）。
            // 但语音输入模式会在录音过程中切换 inputModeEngine，故只在它变化时重新解析。
            var lastInputModeEngine = inputModeEngine
            var resolvedEngine: AsrEngine = currentAsrEngine
            var resolvedIsVosk: Boolean = (inputModeEngine ?: currentAsrEngineType) == AsrEngineType.VOSK

            try {
                while (isRecording) {
                    if (inputModeEngine !== lastInputModeEngine) {
                        lastInputModeEngine = inputModeEngine
                        resolvedEngine = currentAsrEngine
                        resolvedIsVosk = (inputModeEngine ?: currentAsrEngineType) == AsrEngineType.VOSK
                    }

                    val shortsRead = record.read(audioBuffer, 0, audioBuffer.size)
                    if (!isRecording) break
                    if (shortsRead > 0) {
                        System.arraycopy(audioBuffer, 0, accumulateBuffer, accumulateOffset, shortsRead)
                        accumulateOffset += shortsRead

                        while (accumulateOffset >= FRAME_SIZE) {
                            System.arraycopy(accumulateBuffer, 0, frameBuffer, 0, FRAME_SIZE)
                            System.arraycopy(
                                accumulateBuffer,
                                FRAME_SIZE,
                                accumulateBuffer,
                                0,
                                accumulateOffset - FRAME_SIZE,
                            )
                            accumulateOffset -= FRAME_SIZE

                            // ===== 预卷留存 =====
                            // ASR 暂停送音期间，每一帧（含语音刚起、VAD 还没确认的那几帧）都按序存下，
                            // 语音确认后一次性回填，句首音节才不会整段丢掉。
                            if (asrPaused) {
                                if (preRollFrames.size >= VAD_PREROLL_FRAMES) preRollFrames.removeFirst()
                                preRollFrames.addLast(frameBuffer.copyOf())
                            }

                            // ===== VAD 检测 =====
                            val vadResult = vadEngine.processAudio(frameBuffer)
                            if (vadResult.hasSpeech) {
                                vadSpeechFrames++
                                vadSilentFrames = 0
                                if (vadSpeechFrames >= VAD_SPEECH_DEBOUNCE_FRAMES && !debouncedVadState) {
                                    debouncedVadState = true
                                    _vadState.value = VadState.LISTENING
                                    if (asrPaused) {
                                        // 预卷最后一帧就是当前帧，留给下面的正常送音逻辑，避免同一帧送两次
                                        preRollFrames.removeLastOrNull()
                                        // 两种情况都要丢掉暂停前的引擎状态：否则下一句的 partial 会把
                                        // 上一句的文本又带出来（重复行）
                                        resolvedEngine.resetStream()
                                        val pendingUnconfirmed = unconfirmedLineSnapshot
                                        if (pendingUnconfirmed.isNotBlank()) {
                                            // 这次暂停落在句中（VAD 把句尾当成了静音）：
                                            // 已经上屏的半句还没等到引擎 final，直接 resetStream 会把它
                                            // 静默丢掉 —— 表现为字幕回滚、且这一句永远没有译文。
                                            // 这里先补一个 final（归档 + 翻译），并【跳过】预卷回填：
                                            // 预卷里那段音频已经被同一句识别过，回填会让句尾重复一遍。
                                            Log.i(
                                                LOG_TAG,
                                                "VAD 语音恢复: 暂停前有未完成句，补 final 后 reset（跳过预卷回填）: " +
                                                    "\"$pendingUnconfirmed\"",
                                            )
                                            scope.launch { flushPendingDraft() }
                                        } else {
                                            Log.i(
                                                LOG_TAG,
                                                "VAD 语音恢复: 回填 ${preRollFrames.size} 帧预卷" +
                                                    "(~${preRollFrames.size * FRAME_MS}ms), 恢复送音",
                                            )
                                            // 干净暂停（停在静音里）：把句首那段音频按序补送，恢复被 VAD 延迟吃掉的音节
                                            for (frame in preRollFrames) resolvedEngine.feedAudio(frame)
                                        }
                                        preRollFrames.clear()
                                        asrPaused = false
                                    }
                                }
                            } else {
                                vadSilentFrames++
                                vadSpeechFrames = 0
                                // Vosk 无 endpoint，靠 VAD 软静音补一个 final。
                                // 必须提交「当前正在显示的那一行」（已确认 final + 未确认 partial）：
                                // 旧实现提交 currentSourceBuffer（只含上一句 final），会把上一句重复上屏，
                                // 而当前句永远等不到提交。
                                // 且必须切回主线程执行：字幕缓冲由主线程的 Flow 收集器维护，
                                // 在录音线程直接读写 StringBuilder 会与 append 竞争。
                                if (resolvedIsVosk && vadSilentFrames == VAD_SOFT_SILENCE_FRAMES) {
                                    scope.launch {
                                        if (BuildConfig.DEBUG) {
                                            Log.d(LOG_TAG, "VAD 软静音: 收尾当前行, line='${currentLineText()}'")
                                        }
                                        flushPendingDraft()
                                        currentAsrEngine.resetStream()
                                    }
                                }
                                if (vadSilentFrames >= VAD_SILENT_DEBOUNCE_FRAMES && debouncedVadState) {
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
                                resolvedEngine.feedAudio(frameBuffer)
                                // Vosk 的 feedAudio 内部已完成 getPartialResult + 发射 partial，
                                // 只有 Sherpa-ONNX 需要显式 decode 才会推进 partial/final。
                                // 旧实现对两者都调 decode，Vosk 下等于每帧多做一次 JNI + 正则解析。
                                if (!resolvedIsVosk) resolvedEngine.decodeAndGetResult()
                            }

                            // Vosk 模式 VAD 超时清空字幕；Sherpa 模式 VAD 只做人声检测。
                            // 缓冲清理同样切回主线程；先在录音线程把 lastFinalTimeMs 归零避免重复触发。
                            if (resolvedIsVosk && !debouncedVadState && lastFinalTimeMs > 0) {
                                if (System.currentTimeMillis() - lastFinalTimeMs > SUBTITLE_RETENTION_MS) {
                                    lastFinalTimeMs = 0L
                                    scope.launch {
                                        clearSubtitleBuffers()
                                        _subtitleState.value = _subtitleState.value.copy(lines = emptyList())
                                    }
                                }
                            }
                        }
                    } else if (shortsRead < 0) {
                        delay(100)
                    }
                }
            } finally {
                // 放在 finally：协程被 cancel（audioProcessingJob.cancel()）时也能可靠释放，
                // 否则会残留未释放的 AudioRecord 与错误的 VAD 状态。
                Log.i(LOG_TAG, "录音循环退出")
                isRecording = false
                val oldRecord = audioRecord
                audioRecord = null
                runCatching { oldRecord?.stop() }
                runCatching { oldRecord?.release() }
                _vadState.value = VadState.LISTENING
                Log.i(LOG_TAG, "startAudioProcessingLocked: 资源已释放")
            }
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
            audioProcessingJob =
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
                        val oldRecord = audioRecord
                        audioRecord = null
                        // 容错：未 start 状态下 stop/release 会抛 IllegalStateException
                        runCatching { oldRecord?.stop() }
                        runCatching { oldRecord?.release() }
                        _vadState.value = VadState.LISTENING
                        lastFinalTimeMs = 0L
                        // 停止时清空字幕状态。字幕缓冲由主线程的 ASR Flow 收集器维护，
                        // 切回主线程清理，避免与在途 append 竞争（否则可能串字或抛越界）。
                        scope.launch {
                            clearSubtitleBuffers()
                            _subtitleState.value = _subtitleState.value.copy(lines = emptyList())
                            _isTranslating.value = false
                        }
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
        @SuppressLint("MissingPermission")
        private fun createMicrophoneAudioRecord(): AudioRecord {
            val minBuffer =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            val bufferSize = maxOf(minBuffer, FRAME_SIZE * 2 * 2) // 至少2帧缓冲
            return AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }

        /**
         * 创建应用内声音捕获 AudioRecord（Android 10+）。
         * 通过 AudioPlaybackCapture API 捕获系统播放的音频。
         * 需要 MediaProjection 授权。
         * 缓冲大小取系统最小值的2倍，减少read调用频率。
         */
        @SuppressLint("MissingPermission")
        private fun createPlaybackCaptureAudioRecord(projection: MediaProjection): AudioRecord =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val config =
                    AudioPlaybackCaptureConfiguration
                        .Builder(projection)
                        .apply {
                            addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            addMatchingUsage(AudioAttributes.USAGE_GAME)
                            addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        }.build()
                val minBuffer =
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                val bufferSize = maxOf(minBuffer, FRAME_SIZE * 2 * 2)
                AudioRecord
                    .Builder()
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    ).setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } else {
                createMicrophoneAudioRecord()
            }

        companion object {
            private const val LOG_TAG = "SubtitleManager"

            /** 下载计时刷新间隔：1s（只更新一个 Long 字段，Compose 重绘成本可忽略） */
            private const val PREP_ELAPSED_TICK_MS = 1_000L

            /**
             * 是否含至少一个「有意义」字符（字母或数字）。
             *
             * CJK 汉字在 [Character.isLetterOrDigit] 下判定为 true，因此中文句子不受影响；
             * 全角/半角标点（"？"、"。"、" ，"）返回 false。
             * 用于过滤 ASR 在噪声/静音段吐出的纯标点 final —— 这类内容会白白触发一次
             * 翻译请求并写进历史记录（实测历史里确实出现过 "？"、"。"、" ，" 三条垃圾行）。
             */
            private fun String.hasMeaningfulContent(): Boolean = any { it.isLetterOrDigit() }

            /** 采样率：16kHz，Vosk 和 WebRTC VAD 通用采样率 */
            private const val SAMPLE_RATE = 16000

            /** 帧大小：480样本 = 30ms（采样率16000）。
             *  Silero VAD 要求 512 samples (32ms), 引擎内部缓冲对齐。
             *  保持 480 帧以匹配 ASR 引擎特征提取需求。 */
            private const val FRAME_SIZE = 480

            /** 单帧时长(ms)：480 / 16000 = 30ms，仅用于日志与预卷时长的可读换算。 */
            private const val FRAME_MS = 30

            /**
             * 预卷帧数：ASR 暂停送音期间保留的音频长度，语音确认后回填。
             *
             * 34 帧 × 30ms ≈ 1.02s，需覆盖三段延迟之和：
             *   Silero 窗口判定（每 ~32ms 一窗，且需概率连续过阈值）
             *   + 语音开始防抖（[VAD_SPEECH_DEBOUNCE_FRAMES]，~150ms）
             *   + 引擎 reset 后冷启动需要的前置上下文。
             * 实测无预卷时句首稳定丢 0.45~0.6s（issue #7），32KB 级别的内存开销可忽略。
             */
            private const val VAD_PREROLL_FRAMES = 34

            /** [joinText] 判定「拼接处需要补空格」时认可的标点尾部字符。 */
            private const val PUNCT_TAIL = ".,!?;:"

            /** VAD 语音开始防抖帧数：连续 5 帧检测到语音才判定开始（~160ms）
             *  Silero VAD 每 ~32ms 一窗口, 480 样本帧约每 1.07 帧送一次 VAD,
             *  因此 5 帧 ≈ 5 × 30ms × 1.07 ≈ 160ms, 符合 5~8 帧(160~256ms) 范围 */
            private const val VAD_SPEECH_DEBOUNCE_FRAMES = 5

            /** VAD 软静音帧数：连续 15 帧静音(480ms) 触发软静音,
             *  用于 Vosk 模式下字幕 final 提交(替代 Sherpa endpoint) */
            private const val VAD_SOFT_SILENCE_FRAMES = 15 // 480ms 软静音

            /** VAD 静音结束防抖帧数：连续 28 帧静音才判定结束（~896ms）。
             *  取 subtitle commit 范围(25~32 帧, 800~1024ms) 中间值 */
            private const val VAD_SILENT_DEBOUNCE_FRAMES = 28 // ~896ms 字幕提交

            /** VAD 停止 ASR 帧数：连续 60 帧静音(~2s) 停止送入 ASR, 节省计算。
             *  语音恢复时自动 resetStream 并重新送入 */
            private const val VAD_STOP_ASR_FRAMES = 60 // ~2s 停止 ASR

            /** 翻译防抖延迟（毫秒）：避免短时间内重复触发翻译请求 */
            private const val TRANSLATE_DEBOUNCE_MS = 200L

            /**
             * 流式翻译首 token 超时（TTFT，毫秒）。
             *
             * 语义：从发起请求到「模型吐出第一个 token」的最长等待。超过则判定链路不通。
             * 15s 覆盖了「代理握手慢 + 上游排队」的最坏情况，同时远短于旧实现让用户干等的时长。
             * 注意：这是**等待开始**的预算，与「整条流总时长」无关 —— 一旦开始吐字就不再受它约束。
             */
            private const val STREAM_FIRST_TOKEN_TIMEOUT_MS = 15_000L

            /**
             * 轻量润色超时（毫秒，保留常量以免调用方失配）。
             * @deprecated 流式改造后已不再作为「超时则跳过润色」的分支条件，
             *   润色与翻译是合并的单次流式调用，见 [translateWithPolishAndContext]。
             */
            private const val POLISH_TIMEOUT_MS = 2000L

            /**
             * 翻译超时（毫秒，保留常量以免调用方失配）。
             * @deprecated 已由 [STREAM_FIRST_TOKEN_TIMEOUT_MS] + 空闲超时替代，不再作总时长上限。
             */
            private const val TRANSLATE_TIMEOUT_MS = 5000L

            /** 轻量润色最小长度：少于此字符不润色，省资源 */
            private const val MIN_POLISH_LENGTH = 5

            /**
             * 流式译文的最小 UI 刷新间隔（毫秒）。
             * 逐 token 直接刷新会产生大量状态变更（悬浮窗虽已节流，但状态分发本身有成本），
             * 因此合并到 100ms 一次，肉眼已是"逐字显示"。
             */
            private const val STREAM_UI_MIN_INTERVAL_MS = 100L

            /** 字幕文本最大留存时间（毫秒），Vosk 模式下超时后自动清空 */
            private const val SUBTITLE_RETENTION_MS = 10_000L

            /** 最大字幕行数：最新 1 条 + 历史 9 条 */
            private const val MAX_HISTORY_LINES = 10

            /** Partial 翻译滑动窗口步长（中文）：每增长 N 字触发一次预览翻译 */
            private const val PARTIAL_TRANSLATE_STEP_ZH = 5

            /** Partial 翻译滑动窗口步长（英文）：每增长 N 字符触发一次预览翻译 */
            private const val PARTIAL_TRANSLATE_STEP_EN = 20

            /** Partial 翻译最小长度：少于此长度不触发预览翻译 */
            private const val MIN_PARTIAL_TRANSLATE_LENGTH = 5

            /** 一行字幕的字符上限（英文）：约两行英文，保证一条字幕 = 一整句 */
            private const val MAX_ROW_CHARS_EN = 90

            /** 一行字幕的字符上限（中文等全角字符） */
            private const val MAX_ROW_CHARS_ZH = 30

            /** 折行切点的最小靠后位置，避免切出碎片 */
            private const val MIN_ROW_TAIL_CHARS = 12

            /** 句子没说完时草稿允许的最大倍数，超过才强行折一行 */
            private const val HARD_CAP_RATIO = 2.5f

            /** 连读时按逗号收口，一行最多占到「行容量 × 这个倍数」 */
            private const val MAX_CLAUSE_ROWS = 2

            /** 折行后剩余字符少于此数时整句一起出 */
            private const val MIN_TAIL_AFTER_FOLD = 3

            /** 记住最近多少条上屏原文用于去重 */
            private const val RECENT_ROWS_MEMORY = 8

            /** 去重时参与包含判断的最短长度 */
            private const val MIN_DUP_CHECK_CHARS = 6

            /** 句末标点：一行的收口位置 */
            private val SENTENCE_END_MARKS = charArrayOf('。', '．', '.', '！', '!', '？', '?', '…')

            /** 折行软断点（含日文顿号） */
            private val ROW_SOFT_BREAK_CHARS = charArrayOf(',', '，', ';', '；', '、')

            /** 不当作句子结尾的缩写 */
            private val ABBREVIATIONS =
                setOf("mr", "mrs", "ms", "dr", "prof", "st", "vs", "etc", "jr", "sr", "no", "inc", "ltd")
        }
    }
